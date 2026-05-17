package moe.antimony.hoshi.features.sync.v3

import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.appleSecondsToRfc3339
import moe.antimony.hoshi.features.sync.http.chatEntryKeySuffix
import moe.antimony.hoshi.features.sync.http.chatKey
import moe.antimony.hoshi.features.sync.http.compareRfc3339

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
        val applyRemoteBookmarks = mutableListOf<V3Action.ApplyRemoteBookmark>()
        val importChats = mutableListOf<V3Action.ImportChat>()
        val applyAiSettings = mutableListOf<V3Action.ApplyAiSettings>()
        val pushBookmarks = mutableListOf<V3Action.PushBookmark>()
        val pushChats = mutableListOf<V3Action.PushChat>()
        val pushPayloads = mutableListOf<V3Action.PushPayload>()
        val pushMetadatas = mutableListOf<V3Action.PushMetadata>()
        val pushAiSettings = mutableListOf<V3Action.PushAiSettings>()

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

            // Server-side tombstone short-circuits everything else.
            val remoteDeletedAt = r?.metadata?.deletedAt
            if (r != null && remoteDeletedAt != null) {
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
                    importRemoteBooks += V3Action.ImportRemoteBook(
                        syncId = syncId,
                        manifest = r.manifest,
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
                    if (r.metadata != null) {
                        applyRemoteMetadata += V3Action.ApplyRemoteMetadata(
                            root = null, // unknown until after ImportRemoteBook materializes the root
                            syncId = syncId,
                            blob = r.metadata,
                        )
                    }
                    for (chatKey in r.chatKeys) {
                        importChats += V3Action.ImportChat(
                            root = sentinelRoot(syncId),
                            syncId = syncId,
                            key = chatKey,
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
                // ── Bookmark LWW ──
                if (r?.bookmark != null) {
                    val localBookmark = l.bookmark
                    val localStampRfc = localBookmark?.lastModified?.let(::appleSecondsToRfc3339)
                    val cmp = compareRfc3339(r.bookmark.lastModified, localStampRfc)
                    when {
                        cmp > 0 -> applyRemoteBookmarks += V3Action.ApplyRemoteBookmark(
                            root = l.root,
                            syncId = syncId,
                            blob = r.bookmark,
                        )
                        cmp < 0 && localBookmark != null -> pushBookmarks += V3Action.PushBookmark(
                            root = l.root,
                            syncId = syncId,
                            bookmark = localBookmark,
                            expectedRemote = r.bookmark,
                        )
                        else -> Unit // tie or both null
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
                            localShelfUpdatedAt = l.shelfUpdatedAt,
                        ) && r.metadata.shelfName != l.shelfName
                    ) {
                        applyRemoteMetadata += V3Action.ApplyRemoteMetadata(
                            root = l.root,
                            syncId = syncId,
                            blob = r.metadata,
                        )
                    }
                }

                // ── Always push our own metadata if local exists. Even if the shelf merge
                //    chose to apply remote first, we re-push so importedAt/title stay
                //    consistent with the live local state on the server.
                if (l.bookId.isNotEmpty()) {
                    val uploadShelfName = if (r?.metadata != null && shouldApplyRemoteShelfPlacement(
                            remoteShelfUpdatedAt = r.metadata.shelfUpdatedAt,
                            localShelfUpdatedAt = l.shelfUpdatedAt,
                        )
                    ) r.metadata.shelfName else l.shelfName
                    val uploadShelfUpdatedAt = if (r?.metadata != null && shouldApplyRemoteShelfPlacement(
                            remoteShelfUpdatedAt = r.metadata.shelfUpdatedAt,
                            localShelfUpdatedAt = l.shelfUpdatedAt,
                        )
                    ) (r.metadata.shelfUpdatedAt ?: r.metadataLastModified) else l.shelfUpdatedAt
                    pushMetadatas += V3Action.PushMetadata(
                        syncId = syncId,
                        title = l.title,
                        blob = moe.antimony.hoshi.features.sync.http.HttpSyncMetadataBlob(
                            title = l.title,
                            contentType = HttpSyncContentType.fromLocal(l.contentType),
                            shelfName = uploadShelfName,
                            shelfUpdatedAt = uploadShelfUpdatedAt,
                            importedAt = r?.metadata?.importedAt,
                            deletedAt = null,
                        ),
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

                // ── Payload push (widened gate: Mokuro OR EPUB) ──
                if (l.bookId.isNotEmpty() && r?.manifest == null) {
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
        val localAi = local.aiSettings
        val remoteAi = remote.aiSettings
        val localAiStamp = localAi?.lastEditedAt
        val remoteAiStamp = remoteAi?.lastModified
        when {
            remoteAi != null && localAi == null -> applyAiSettings += V3Action.ApplyAiSettings(remoteAi)
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

        // Build the final action list in the spec's order; sort each bucket by syncId.
        val actions = buildList<V3Action> {
            addAll(pushTombstones.sortedBy { it.syncId })
            addAll(applyRemoteMetadata.sortedBy { it.syncId })
            addAll(deleteLocalBooks.sortedBy { it.syncId })
            addAll(importRemoteBooks.sortedBy { it.syncId })
            // Apply-remote bucket: bookmarks, chats (sorted by key for stable ordering),
            // ai settings at the end of the apply group.
            addAll(applyRemoteBookmarks.sortedBy { it.syncId })
            addAll(importChats.sortedWith(compareBy({ it.syncId }, { it.key })))
            addAll(applyAiSettings)
            // Push bucket: bookmark, chat, payload, metadata, ai settings.
            addAll(pushBookmarks.sortedBy { it.syncId })
            addAll(pushChats.sortedWith(compareBy({ it.syncId }, { it.key })))
            addAll(pushPayloads.sortedBy { it.syncId })
            addAll(pushMetadatas.sortedBy { it.syncId })
            addAll(pushAiSettings)
        }

        return V3Plan(actions = actions, pendingRemoteOnlyBooks = pendingRemoteOnly)
    }

    /**
     * Mirrors v2's `HttpSyncReconciler.shouldApplyRemoteShelfPlacement`. Both sides
     * track an RFC-3339 `shelfUpdatedAt`; the newer one wins, with the same tie-break
     * (remote >= local) v2 uses to converge.
     */
    private fun shouldApplyRemoteShelfPlacement(
        remoteShelfUpdatedAt: String?,
        localShelfUpdatedAt: String?,
    ): Boolean {
        if (localShelfUpdatedAt == null) return true
        if (remoteShelfUpdatedAt == null) return false
        return compareRfc3339(remoteShelfUpdatedAt, localShelfUpdatedAt) >= 0
    }

    /**
     * For actions that reference a not-yet-imported book, we need *some* `File` to put
     * in the action. The executor swaps this for the real post-import root via its
     * `rootBySyncId` map.
     */
    private fun sentinelRoot(syncId: String): java.io.File =
        java.io.File("/v3-pending/$syncId")
}
