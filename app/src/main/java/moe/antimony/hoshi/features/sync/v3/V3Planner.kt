package moe.antimony.hoshi.features.sync.v3

import moe.antimony.hoshi.features.ai.PRETRANSLATIONS_FILENAME
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadKeys
import moe.antimony.hoshi.features.sync.http.MAX_EPUB_SENTENCES_BLOB_BYTES
import moe.antimony.hoshi.features.sync.http.SyncComparison
import moe.antimony.hoshi.features.sync.http.appleSecondsToRfc3339
import moe.antimony.hoshi.features.sync.http.chatEntryKeySuffix
import moe.antimony.hoshi.features.sync.http.chatKey
import moe.antimony.hoshi.features.sync.http.compareRevisioned
import moe.antimony.hoshi.features.sync.http.compareRfc3339
import moe.antimony.hoshi.features.sync.http.localImportedAtOverridesRemoteDeletion
import moe.antimony.hoshi.features.sync.http.maxRfc
import moe.antimony.hoshi.features.sync.http.shouldApplyRemoteShelfPlacement
import java.io.File

/**
 * Step 3 of the v3 algorithm — **PURE**. Given a snapshot of local and remote
 * state, produces a deterministic [V3Plan]. No I/O, no transport, no side effects.
 *
 * See `docs/SYNC_V3_SPEC.md` § Step 3 for the full rule set.
 *
 * Ordering of [V3Plan.actions] (within each bucket, sorted by syncId for byte-for-byte
 * reproducibility):
 *  1. PushTombstone — server learns about deletes first.
 *  2. ApplyRemoteMetadata — apply remote deletions / shelf moves.
 *  3. DeleteLocalBook — local deletions of server-side tombstones.
 *  4. ImportRemoteBook — payload + sidecar import for new remote-only books.
 *  5. ApplyRemoteBookmark / ImportChat / ApplyAiSettings.
 *  6. PushBookmark / PushChat / PushPayload / PushMetadata / PushAiSettings.
 */
class V3Planner {
    fun compute(local: V3LocalSnapshot, remote: V3RemoteSnapshot): V3Plan {
        // Bucketed actions; each bucket emits in syncId-sorted order.
        val pushTombstones = mutableListOf<V3Action.PushTombstone>()
        val applyRemoteMetadata = mutableListOf<V3Action.ApplyRemoteMetadata>()
        val deleteLocalBooks = mutableListOf<V3Action.DeleteLocalBook>()
        val importRemoteBooks = mutableListOf<V3Action.ImportRemoteBook>()
        val replaceRemotePayloads = mutableListOf<V3Action.ReplaceRemotePayload>()
        val applyRemoteBookmarks = mutableListOf<V3Action.ApplyRemoteBookmark>()
        val importChats = mutableListOf<V3Action.ImportChat>()
        val importPretranslations = mutableListOf<V3Action.ImportPretranslations>()
        val importSentences = mutableListOf<V3Action.ImportSentences>()
        val applyAiSettings = mutableListOf<V3Action.ApplyAiSettings>()
        val pushBookmarks = mutableListOf<V3Action.PushBookmark>()
        val pushChats = mutableListOf<V3Action.PushChat>()
        val pushPayloads = mutableListOf<V3Action.PushPayload>()
        val pushMetadatas = mutableListOf<V3Action.PushMetadata>()
        val pushAiSettings = mutableListOf<V3Action.PushAiSettings>()
        val plannerErrors = mutableListOf<V3Error>()

        val pendingRemoteOnly = mutableSetOf<String>()

        val localBySyncId: Map<String, V3LocalBook> = local.books.associateBy { it.syncId }
        val allSyncIds = (localBySyncId.keys + remote.books.keys).toSortedSet()

        for (syncId in allSyncIds) {
            val l = localBySyncId[syncId]
            val r = remote.books[syncId]

            // Local pending-deletion wins over everything else for this syncId.
            val pending = l?.pendingDeletion
            if (pending != null) {
                pushTombstones += V3Action.PushTombstone(syncId = syncId, record = pending)
                // Also delete the live local copy if any exists alongside the staged tombstone.
                if (l.bookId.isNotEmpty() && l.root.exists()) {
                    deleteLocalBooks += V3Action.DeleteLocalBook(root = l.root, syncId = syncId)
                }
                continue
            }

            // Server-side tombstone short-circuits everything else — UNLESS the local copy
            // was imported strictly after the tombstone was published. That's the re-import-
            // after-delete path: the user re-imported the same title after deleting it on
            // another device, the tombstone is stale, and we publish fresh `deletedAt = null`
            // metadata that overrides it. Falls through to the normal local-and-remote
            // branch below so the metadata push emits with `deletedAt = null` and the local
            // import stamp.
            val remoteDeletedAt = r?.metadata?.deletedAt
            val tombstoneOverridden = remoteDeletedAt != null &&
                localImportedAtOverridesRemoteDeletion(l?.importedAt, remoteDeletedAt)
            if (r != null && remoteDeletedAt != null && !tombstoneOverridden) {
                // Always apply the remote metadata (so we have the tombstone locally indexed).
                applyRemoteMetadata += V3Action.ApplyRemoteMetadata(
                    root = l?.root,
                    syncId = syncId,
                    blob = r.metadata,
                )
                if (l != null) {
                    deleteLocalBooks += V3Action.DeleteLocalBook(root = l.root, syncId = syncId)
                }
                continue
            }

            // Remote-only: needs an import (if a manifest is there) or marks the book as
            // pending-remote-only when no manifest is available.
            if (l == null && r != null) {
                if (r.manifest != null) {
                    // Bug 2: thread shelf placement (when remote metadata has one) into the
                    // import action itself so the executor can apply it in the same step
                    // the directory is created. Emitting a separate ApplyRemoteMetadata
                    // here doesn't work — bucket ordering puts ApplyRemoteMetadata BEFORE
                    // ImportRemoteBook, so the executor would see `rootBySyncId[syncId] ==
                    // null` and silently skip the shelf branch. Remote tombstones are
                    // handled earlier (above), so any metadata reaching here is guaranteed
                    // to have `deletedAt == null`.
                    importRemoteBooks += V3Action.ImportRemoteBook(
                        syncId = syncId,
                        manifest = r.manifest,
                        payloadKeys = r.payloadKeys
                            ?: moe.antimony.hoshi.features.sync.http.HttpSyncPayloadKeys.forFormat(r.manifest.format, syncId),
                        shelfName = r.metadata?.shelfName,
                        shelfUpdatedAt = r.metadata?.shelfUpdatedAt
                            ?: r.metadata?.let { r.metadataLastModified },
                    )
                    // After import, apply any remote bookmark / chat the server has too.
                    if (r.bookmark != null) {
                        // Use the post-import root via a placeholder; the executor patches it.
                        // We can't synthesize a File here, so the executor materializes it via
                        // its rootBySyncId map. We still emit the action with a sentinel root.
                        applyRemoteBookmarks += V3Action.ApplyRemoteBookmark(
                            root = sentinelRoot(syncId),
                            syncId = syncId,
                            blob = r.bookmark,
                        )
                    }
                    for (chatKey in r.chatKeys) {
                        importChats += V3Action.ImportChat(
                            root = sentinelRoot(syncId),
                            syncId = syncId,
                            key = chatKey,
                        )
                    }
                    r.pretranslationsKey?.takeIf { r.manifest.format == HttpSyncContentType.Mokuro }?.let { key ->
                        importPretranslations += V3Action.ImportPretranslations(
                            root = sentinelRoot(syncId),
                            syncId = syncId,
                            key = key,
                        )
                    }
                    r.sentencesKey?.takeIf { r.manifest.format == HttpSyncContentType.Epub }?.let { key ->
                        if ((r.sentencesSize ?: 0) > MAX_EPUB_SENTENCES_BLOB_BYTES) {
                            plannerErrors += V3Error(
                                syncId,
                                "ImportSentences",
                                "remote sentence translations exceed the $MAX_EPUB_SENTENCES_BLOB_BYTES-byte limit",
                            )
                        } else {
                            importSentences += V3Action.ImportSentences(
                                root = sentinelRoot(syncId),
                                syncId = syncId,
                                key = key,
                            )
                        }
                    }
                    if (
                        r.manifest.format == HttpSyncContentType.Epub &&
                        r.payloadKeys == HttpSyncPayloadKeys.legacy(syncId)
                    ) {
                        pushPayloads += V3Action.PushPayload(
                            root = sentinelRoot(syncId),
                            syncId = syncId,
                            title = r.metadata?.title ?: r.manifest.originalName,
                            format = HttpSyncContentType.Epub,
                        )
                    }
                } else {
                    // Manifest missing → can't import the book yet.
                    pendingRemoteOnly += syncId
                }
                continue
            }

            // Both sides exist (or local-only).
            if (l != null) {
                // ── Content-type collision guard (Bug 6) ──
                // If the server has a payload manifest whose format disagrees with our
                // local content type, pushing EPUB-typed metadata against a Mokuro payload
                // (or vice versa) would corrupt every other client. The manifest is the
                // authoritative pointer at the bytes already on the server — we leave it
                // alone, skip ALL pushes for this syncId, and surface a structured error.
                // Apply-side actions (e.g. tombstone, remote shelf placement) are
                // already short-circuited above where appropriate; here we just block
                // anything that would write our wrong-typed state up to the server.
                val remoteManifestFormat = r?.manifest?.format
                if (remoteManifestFormat != null &&
                    remoteManifestFormat != HttpSyncContentType.fromLocal(l.contentType)
                ) {
                    plannerErrors += V3Error(
                        syncId = syncId,
                        action = "ContentTypeCollision",
                        message = "local content type ${l.contentType} does not match remote payload format $remoteManifestFormat for syncId '$syncId'; skipping metadata/bookmark/chat/payload push to avoid corrupting remote-pointed-at payload",
                    )
                    continue
                }

                val remoteContentSha = r?.manifest?.contentSha256
                if (!l.payloadDirty && r?.manifest != null &&
                    (remoteContentSha == null || l.payloadSha == null || l.payloadSha != remoteContentSha)
                ) {
                    replaceRemotePayloads += V3Action.ReplaceRemotePayload(
                        root = l.root,
                        syncId = syncId,
                        manifest = r.manifest,
                        payloadKeys = r.payloadKeys
                            ?: HttpSyncPayloadKeys.forFormat(r.manifest.format, syncId),
                    )
                }

                // ── Bookmark LWW ──
                // Bug 5: if the remote bookmark blob was present-but-malformed, refuse
                // to push local over it — that would destroy the only copy of the
                // corrupt remote bytes the user might still want to recover. The decode
                // error is already in V3RemoteSnapshotResult.errors; surface a planner-
                // level marker too so the cause is clear at this layer.
                if (r?.bookmarkMalformed == true) {
                    plannerErrors += V3Error(
                        syncId = syncId,
                        action = "MalformedRemoteBookmark",
                        message = "remote bookmark for syncId '$syncId' is malformed; skipping bookmark push to avoid overwriting the only copy of corrupt remote data",
                    )
                } else if (r?.bookmark != null) {
                    val localBookmark = l.bookmark
                    val localStampRfc = localBookmark?.lastModified?.let(::appleSecondsToRfc3339)
                    // Event timestamp first; revision only breaks exact ties. This keeps legacy
                    // clients without revision sidecars from being rolled backward.
                    when (compareRevisioned(
                        localRev = l.bookmarkLocalRev,
                        remoteRev = r.bookmark.rev,
                        localStamp = localStampRfc,
                        remoteStamp = r.bookmark.lastModified,
                    )) {
                        SyncComparison.REMOTE_WINS -> applyRemoteBookmarks += V3Action.ApplyRemoteBookmark(
                            root = l.root,
                            syncId = syncId,
                            blob = r.bookmark,
                        )
                        SyncComparison.LOCAL_WINS -> if (localBookmark != null) {
                            pushBookmarks += V3Action.PushBookmark(
                                root = l.root,
                                syncId = syncId,
                                bookmark = localBookmark,
                                expectedRemote = r.bookmark,
                            )
                        }
                        SyncComparison.TIE -> Unit // tie or both null
                    }
                } else if (l.bookmark != null) {
                    pushBookmarks += V3Action.PushBookmark(
                        root = l.root,
                        syncId = syncId,
                        bookmark = l.bookmark,
                        expectedRemote = null,
                    )
                }

                // ── Metadata / shelf merge ──
                if (r?.metadata != null) {
                    if (shouldApplyRemoteShelfPlacement(
                            remoteShelfUpdatedAt = r.metadata.shelfUpdatedAt,
                            localShelvesUpdatedAt = l.shelfUpdatedAt,
                        ) && r.metadata.shelfName != l.shelfName
                    ) {
                        // When the local importedAt overrode a stale remote tombstone, scrub
                        // `deletedAt` out of the blob we hand to the executor. Otherwise the
                        // executor's ApplyRemoteMetadata path would still see deletedAt != null
                        // and re-delete the local book we just decided to keep.
                        val blobForApply = if (tombstoneOverridden) {
                            r.metadata.copy(deletedAt = null)
                        } else {
                            r.metadata
                        }
                        applyRemoteMetadata += V3Action.ApplyRemoteMetadata(
                            root = l.root,
                            syncId = syncId,
                            blob = blobForApply,
                        )
                    }
                }

                // ── Always push our own metadata if local exists. Even if the shelf merge
                //    chose to apply remote first, we re-push so importedAt/title stay
                //    consistent with the live local state on the server.
                // Bug 5: but NEVER push over a present-but-malformed remote metadata blob —
                // that would silently destroy the only copy of corrupt data alongside an
                // error message saying we couldn't decode it. Skip the push and surface
                // the cause at the planner layer.
                if (r?.metadataMalformed == true) {
                    plannerErrors += V3Error(
                        syncId = syncId,
                        action = "MalformedRemoteMetadata",
                        message = "remote metadata for syncId '$syncId' is malformed; skipping metadata push to avoid overwriting the only copy of corrupt remote data",
                    )
                } else if (l.bookId.isNotEmpty()) {
                    val uploadShelfName = if (r?.metadata != null && shouldApplyRemoteShelfPlacement(
                            remoteShelfUpdatedAt = r.metadata.shelfUpdatedAt,
                            localShelvesUpdatedAt = l.shelfUpdatedAt,
                        )
                    ) r.metadata.shelfName else l.shelfName
                    val uploadShelfUpdatedAt = if (r?.metadata != null && shouldApplyRemoteShelfPlacement(
                            remoteShelfUpdatedAt = r.metadata.shelfUpdatedAt,
                            localShelvesUpdatedAt = l.shelfUpdatedAt,
                        )
                    ) {
                        // An unshelved book with no placement stamp has never been moved.
                        // A metadata upload is not a shelf edit: manufacturing its timestamp
                        // here changes identical metadata on the next sync. Legacy named
                        // shelves still use the metadata timestamp to date their placement.
                        r.metadata.shelfUpdatedAt
                            ?: r.metadataLastModified.takeIf { r.metadata.shelfName != null }
                    } else l.shelfUpdatedAt
                    // importedAt write-out:
                    //  - If we just overrode a server tombstone, publish the LOCAL import stamp
                    //    verbatim so peers can in turn compare it against any older `deletedAt`
                    //    they might still observe.
                    //  - Otherwise keep whatever timestamp is newer between local and remote.
                    //    This preserves the prior behavior of propagating an existing remote
                    //    stamp while still ensuring the field is non-null on first push from
                    //    a device that imported locally.
                    val uploadImportedAt = if (tombstoneOverridden) {
                        l.importedAt
                    } else {
                        maxRfc(r?.metadata?.importedAt, l.importedAt)
                    }
                    // A sync pushes merged state, not a new edit: carry the max of both revs
                    // forward without bumping (only the deliberate-edit hooks bump). Same
                    // rule as the v2 reconciler's outbound metadata pass.
                    val uploadRev = maxOf(l.metadataLocalRev, r?.metadata?.rev ?: 0)
                    pushMetadatas += V3Action.PushMetadata(
                        syncId = syncId,
                        title = l.title,
                        blob = moe.antimony.hoshi.features.sync.http.HttpSyncMetadataBlob(
                            title = l.title,
                            contentType = HttpSyncContentType.fromLocal(l.contentType),
                            shelfName = uploadShelfName,
                            shelfUpdatedAt = uploadShelfUpdatedAt,
                            importedAt = uploadImportedAt,
                            deletedAt = null,
                            rev = uploadRev,
                        ),
                        expectedRemote = r?.metadata,
                    )
                }

                // ── Chat set-union ──
                if (l.contentType == moe.antimony.hoshi.epub.ContentType.Mokuro) {
                    // Local chat keys (content-addressable).
                    val localChatByKey: Map<String, moe.antimony.hoshi.features.ai.AiChatEntry> =
                        l.chatEntries.associateBy { entry ->
                            chatKey(
                                syncId,
                                chatEntryKeySuffix(entry.timestampSeconds, entry.bubbleText, entry.response),
                            )
                        }
                    val remoteChatKeys = r?.chatKeys ?: emptySet()
                    // Remote-only chat → ImportChat.
                    for (key in remoteChatKeys.toSortedSet()) {
                        if (key !in localChatByKey) {
                            importChats += V3Action.ImportChat(
                                root = l.root,
                                syncId = syncId,
                                key = key,
                            )
                        }
                    }
                    // Offline translations are download-only, so the only question is whether the
                    // local copy is already the same bytes the server lists.
                    val pretranslationsKey = r?.pretranslationsKey
                    if (pretranslationsKey != null) {
                        val local = File(l.root, PRETRANSLATIONS_FILENAME)
                        val remoteSize = r.pretranslationsSize
                        val unchanged = remoteSize != null &&
                            local.isFile &&
                            local.length() == remoteSize.toLong()
                        if (!unchanged) {
                            importPretranslations += V3Action.ImportPretranslations(
                                root = l.root,
                                syncId = syncId,
                                key = pretranslationsKey,
                            )
                        }
                    }
                    // Local-only chat → PushChat.
                    for ((key, entry) in localChatByKey.toSortedMap()) {
                        if (key !in remoteChatKeys) {
                            pushChats += V3Action.PushChat(
                                root = l.root,
                                syncId = syncId,
                                entry = entry,
                                key = key,
                            )
                        }
                    }
                }
                if (l.contentType == moe.antimony.hoshi.epub.ContentType.Epub) {
                    val sentencesKey = r?.sentencesKey
                    if (sentencesKey != null) {
                        if ((r.sentencesSize ?: 0) > MAX_EPUB_SENTENCES_BLOB_BYTES) {
                            plannerErrors += V3Error(
                                syncId,
                                "ImportSentences",
                                "remote sentence translations exceed the $MAX_EPUB_SENTENCES_BLOB_BYTES-byte limit",
                            )
                        } else {
                            importSentences += V3Action.ImportSentences(
                                root = l.root,
                                syncId = syncId,
                                key = sentencesKey,
                            )
                        }
                    }
                }

                // ── Payload push (widened gate: Mokuro OR EPUB) ──
                // Bug 5: if the remote manifest blob is present-but-malformed, treat the
                // payload slot as occupied — re-uploading would overwrite the manifest
                // (the change-detector) with our local sha and hide the corruption. The
                // decode error is already surfaced; flag the cause at the planner layer
                // for visibility.
                if (r?.manifestMalformed == true) {
                    plannerErrors += V3Error(
                        syncId = syncId,
                        action = "MalformedRemoteManifest",
                        message = "remote manifest for syncId '$syncId' is malformed; skipping payload push to avoid overwriting the only copy of corrupt remote data",
                    )
                } else if (
                    l.bookId.isNotEmpty() &&
                    (
                        l.payloadDirty ||
                            r?.manifest == null ||
                            (
                                l.contentType == moe.antimony.hoshi.epub.ContentType.Epub &&
                                    r.payloadKeys == HttpSyncPayloadKeys.legacy(syncId)
                                )
                        )
                ) {
                    pushPayloads += V3Action.PushPayload(
                        root = l.root,
                        syncId = syncId,
                        title = l.title,
                        format = HttpSyncContentType.fromLocal(l.contentType),
                    )
                }
            }
        }

        // ── App settings LWW (single global key) ──
        // Bug 5: if the remote `app/ai_chat_settings` blob is present-but-malformed,
        // skip the push so we don't destroy the only copy of corrupt remote data, and
        // surface the cause as a planner-level error alongside the existing decode
        // error from the read stage.
        if (remote.aiSettingsMalformed) {
            plannerErrors += V3Error(
                syncId = null,
                action = "MalformedRemoteAiSettings",
                message = "remote app/ai_chat_settings is malformed; skipping AI settings push to avoid overwriting the only copy of corrupt remote data",
            )
        } else {
            val localAi = local.aiSettings
            val remoteAi = remote.aiSettings
            val localAiStamp = localAi?.lastEditedAt
            val remoteAiStamp = remoteAi?.lastModified
            when {
                // Pull remote when local is either truly absent OR a fresh-install default
                // (no `lastEditedAt` stamp yet). The repository emits a non-null default
                // AiChatSettings on first run, so the bare `localAi == null` check is dead
                // in practice — Bug 7 fix broadens this to also cover `localAiStamp == null`.
                remoteAi != null && (localAi == null || localAiStamp == null) ->
                    applyAiSettings += V3Action.ApplyAiSettings(remoteAi)
                remoteAi == null && localAi != null && localAiStamp != null ->
                    pushAiSettings += V3Action.PushAiSettings(localAi)
                remoteAi != null && localAi != null && localAiStamp != null -> {
                    val cmp = compareRfc3339(localAiStamp, remoteAiStamp)
                    when {
                        cmp > 0 -> pushAiSettings += V3Action.PushAiSettings(localAi)
                        cmp < 0 -> applyAiSettings += V3Action.ApplyAiSettings(remoteAi)
                        else -> Unit // tie
                    }
                }
                // remote=null, local=null OR localAiStamp=null → no action.
                else -> Unit
            }
        }

        // Build the final action list in the spec's order; sort each bucket by syncId.
        val actions = buildList<V3Action> {
            addAll(pushTombstones.sortedBy { it.syncId })
            addAll(applyRemoteMetadata.sortedBy { it.syncId })
            addAll(deleteLocalBooks.sortedBy { it.syncId })
            addAll(importRemoteBooks.sortedBy { it.syncId })
            addAll(replaceRemotePayloads.sortedBy { it.syncId })
            // Apply-remote bucket: bookmarks, chats (sorted by key for stable ordering),
            // ai settings at the end of the apply group.
            addAll(applyRemoteBookmarks.sortedBy { it.syncId })
            addAll(importChats.sortedWith(compareBy({ it.syncId }, { it.key })))
            addAll(importPretranslations.sortedBy { it.syncId })
            addAll(importSentences.sortedBy { it.syncId })
            addAll(applyAiSettings)
            // Push bucket: bookmark, chat, payload, metadata, ai settings.
            addAll(pushBookmarks.sortedBy { it.syncId })
            addAll(pushChats.sortedWith(compareBy({ it.syncId }, { it.key })))
            addAll(pushPayloads.sortedBy { it.syncId })
            addAll(pushMetadatas.sortedBy { it.syncId })
            addAll(pushAiSettings)
        }

        return V3Plan(
            actions = actions,
            pendingRemoteOnlyBooks = pendingRemoteOnly,
            errors = plannerErrors.toList(),
        )
    }

    // shouldApplyRemoteShelfPlacement lives in HttpSyncBlobs.kt — one shared spec function
    // for v2, v3, and the fire-and-forget metadata push (mirrors iOS SyncCore).

    /**
     * For actions that reference a not-yet-imported book, we need *some* `File` to put
     * in the action. The executor swaps this for the real post-import root via its
     * `rootBySyncId` map.
     */
    private fun sentinelRoot(syncId: String): java.io.File =
        java.io.File("/v3-pending/$syncId")
}
