package moe.antimony.hoshi.features.sync.v3

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.PretranslationStore
import moe.antimony.hoshi.features.ai.PRETRANSLATIONS_FILENAME
import moe.antimony.hoshi.features.ai.EPUB_TRANSLATIONS_FILENAME
import moe.antimony.hoshi.features.ai.EpubTranslationStore
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.sync.http.HttpSyncActiveBooks
import moe.antimony.hoshi.features.sync.http.HttpSyncBookLocks
import moe.antimony.hoshi.features.sync.http.HttpSyncBookmarkBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncChatEntryBlob
import moe.antimony.hoshi.features.sync.http.PretranslationsBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookStateStore
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.MAX_EPUB_SENTENCES_BLOB_BYTES
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.bookContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncRevisionStore
import moe.antimony.hoshi.features.sync.http.HttpSyncShelfPlacementRecord
import moe.antimony.hoshi.features.sync.http.resolveSyncImportedCoverPath
import moe.antimony.hoshi.features.sync.http.createSyncImportStagingDirectory
import moe.antimony.hoshi.features.sync.http.publishSyncImportDirectory
import moe.antimony.hoshi.features.sync.http.HttpSyncShelfStateStore
import moe.antimony.hoshi.features.sync.http.SyncComparison
import moe.antimony.hoshi.features.sync.http.appleSecondsToRfc3339
import moe.antimony.hoshi.features.sync.http.bookmarkKey
import moe.antimony.hoshi.features.sync.http.compareRevisioned
import moe.antimony.hoshi.features.sync.http.rfc3339ToAppleSeconds
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Step 4 of the v3 algorithm. Applies a [V3Plan] against the transport.
 *
 * - Runs actions one at a time in the order produced by the planner.
 * - Catches per-action exceptions and converts them to [V3Error] entries.
 * - Counts applied/pushed actions into [V3SyncResult.applied] / [V3SyncResult.pushed].
 * - Reports phase + counter progress.
 *
 * Never calls `transport.put` / `transport.delete` directly — all writes go through
 * [V3PushOps]. Reads (chat bodies, etc.) are fine to do directly.
 */
class V3Executor(
    private val bookRepository: BookRepository,
    private val aiHistoryStore: AiChatHistoryStore,
    private val payloadCodec: HttpSyncPayloadCodec,
    private val pushOps: V3PushOps,
    private val bookLocks: HttpSyncBookLocks = HttpSyncBookLocks(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val shelfStateStore = HttpSyncShelfStateStore(json)
    private val deletedBookStateStore = HttpSyncDeletedBookStateStore(json)
    private val revisionStore = HttpSyncRevisionStore(json)

    suspend fun run(
        plan: V3Plan,
        transport: HttpSyncKvTransport,
        onProgress: suspend (V3Progress) -> Unit,
    ): V3SyncResult {
        var appliedBookmarks = 0
        var appliedChatEntries = 0
        var appliedPretranslations = 0
        var appliedSentenceTranslations = 0
        var appliedPayloads = 0
        var appliedMetadataDeletes = 0
        var appliedShelfPlacements = 0
        var appliedAiSettings = 0

        var pushedBookmarks = 0
        var pushedChatEntries = 0
        var pushedMetadata = 0
        var pushedPayloads = 0
        var pushedTombstones = 0
        var pushedAiSettings = 0

        val errors = mutableListOf<V3Error>()
        val rootBySyncId = mutableMapOf<String, File>()
        // Tracks any shelf-state mutations so we save the sidecar exactly once.
        val existingShelfState = shelfStateStore.load(bookRepository.booksDirectory).toMutableMap()
        var shelfStateDirty = false
        // Bug 3: the deleted-book sidecar must NOT be mutated through a
        // snapshot + dirty-flag + final-save pattern. The same file is also
        // written by `BookshelfRepository.recordHttpSyncTombstone` on
        // user-initiated deletes, which can happen during sync. If we held a
        // snapshot here and wrote it back at the end of sync, we'd clobber any
        // concurrent user delete recorded mid-sync. Instead, mutate via the
        // atomic per-key helpers (`recordDeletedBook` / `removeDeletedBook`),
        // which re-read the latest disk state under a process-wide lock.

        val actions = plan.actions
        for ((index, action) in actions.withIndex()) {
            val phase = phaseFor(action)
            onProgress(
                V3Progress(
                    phase = phase,
                    message = phase.name,
                    detail = action::class.simpleName + (action.syncId?.let { " ($it)" } ?: ""),
                    completed = index,
                    total = actions.size,
                ),
            )
            try {
                when (action) {
                    is V3Action.PushTombstone -> {
                        pushOps.pushTombstone(transport, action.syncId, action.record)
                        pushedTombstones += 1
                        // Atomic per-key clear: re-reads disk under the store's
                        // lock so a concurrent recordDeletedBook for a different
                        // key (Bug 3) survives.
                        deletedBookStateStore.removeDeletedBook(
                            bookRepository.booksDirectory,
                            action.syncId,
                        )
                        existingShelfState.remove(action.syncId)
                        shelfStateDirty = true
                    }
                    is V3Action.ApplyRemoteMetadata -> {
                        val targetRoot = action.root ?: rootBySyncId[action.syncId]
                        if (action.blob.deletedAt != null) {
                            if (targetRoot != null) {
                                val removed = HttpSyncActiveBooks.runIfInactive(action.syncId) {
                                    bookRepository.deleteBook(targetRoot)
                                }
                                if (!removed) {
                                    throw IllegalStateException("Deletion deferred while this book is open.")
                                }
                                rootBySyncId.remove(action.syncId)
                            }
                            existingShelfState.remove(action.syncId)
                            shelfStateDirty = true
                            appliedMetadataDeletes += 1
                        } else if (targetRoot != null) {
                            val applied = applyShelfPlacement(targetRoot, action.blob.shelfName)
                            if (applied) {
                                existingShelfState[action.syncId] = HttpSyncShelfPlacementRecord(
                                    shelfName = action.blob.shelfName,
                                    updatedAt = action.blob.shelfUpdatedAt ?: java.time.Instant.now().toString(),
                                )
                                shelfStateDirty = true
                                appliedShelfPlacements += 1
                            }
                        }
                    }
                    is V3Action.DeleteLocalBook -> {
                        val targetRoot = if (action.root.exists()) action.root else rootBySyncId[action.syncId]
                        if (targetRoot != null && targetRoot.exists()) {
                            val removed = HttpSyncActiveBooks.runIfInactive(action.syncId) {
                                bookRepository.deleteBook(targetRoot)
                            }
                            if (!removed) {
                                throw IllegalStateException("Deletion deferred while this book is open.")
                            }
                            rootBySyncId.remove(action.syncId)
                            existingShelfState.remove(action.syncId)
                            shelfStateDirty = true
                            appliedMetadataDeletes += 1
                        }
                    }
                    is V3Action.ImportRemoteBook -> {
                        val newRoot = importRemoteBook(transport, action.syncId, action, onProgress)
                        if (newRoot != null) {
                            rootBySyncId[action.syncId] = newRoot
                            appliedPayloads += 1
                            // Bug 2: apply shelf placement carried inline on the import
                            // action. We can't rely on a separate ApplyRemoteMetadata
                            // because bucket ordering puts metadata-apply BEFORE import,
                            // so the executor would have no root for this syncId yet.
                            if (action.shelfName != null || action.shelfUpdatedAt != null) {
                                val applied = applyShelfPlacement(newRoot, action.shelfName)
                                if (applied) {
                                    existingShelfState[action.syncId] = HttpSyncShelfPlacementRecord(
                                        shelfName = action.shelfName,
                                        updatedAt = action.shelfUpdatedAt ?: java.time.Instant.now().toString(),
                                    )
                                    shelfStateDirty = true
                                    appliedShelfPlacements += 1
                                }
                            }
                        }
                    }
                    is V3Action.ReplaceRemotePayload -> {
                        if (replaceRemotePayload(transport, action, onProgress)) {
                            appliedPayloads += 1
                        }
                    }
                    is V3Action.ApplyRemoteBookmark -> {
                        val targetRoot = resolveRoot(action.root, action.syncId, rootBySyncId)
                            ?: continue
                        // Hold the per-book lock and re-read local before overwriting.
                        // The planner's snapshot was taken at the start of sync; if the
                        // user (or the reader-hook pusher) advanced the bookmark in the
                        // meantime, blindly applying the remote blob from the plan would
                        // clobber the newer local with an older remote. Mirrors the
                        // recheck-under-lock pattern in V3PushOps.pushBookmarkConditional.
                        val applied = bookLocks.withBookLock(targetRoot) {
                            val key = bookmarkKey(action.syncId)
                            val booksRoot = bookRepository.booksDirectory
                            val currentLocal = runCatching { bookRepository.loadBookmark(targetRoot) }
                                .getOrNull()
                            val localStamp = currentLocal?.lastModified?.let(::appleSecondsToRfc3339)
                            // Event time first (re-read under the lock so a reader-hook push
                            // cannot be lost); revision only breaks exact timestamp ties.
                            val localRev = revisionStore.current(booksRoot, key).localRev
                            if (compareRevisioned(
                                    localRev = localRev,
                                    remoteRev = action.blob.rev,
                                    localStamp = localStamp,
                                    remoteStamp = action.blob.lastModified,
                                ) == SyncComparison.REMOTE_WINS
                            ) {
                                bookRepository.saveBookmark(
                                    targetRoot,
                                    Bookmark(
                                        chapterIndex = action.blob.chapterIndex,
                                        progress = action.blob.progress,
                                        characterCount = action.blob.characterCount,
                                        lastModified = rfc3339ToAppleSeconds(action.blob.lastModified),
                                    ),
                                )
                                revisionStore.noteRemote(booksRoot, key, action.blob.rev, appliedLocally = true)
                                true
                            } else {
                                // Local out-revisions (or ties) the remote blob this plan
                                // was computed against — drop the apply. The next sync's
                                // push phase will reconcile via pushBookmarkConditional.
                                revisionStore.noteRemote(booksRoot, key, action.blob.rev, appliedLocally = false)
                                false
                            }
                        }
                        if (applied) appliedBookmarks += 1
                    }
                    is V3Action.ImportPretranslations -> {
                        val targetRoot = resolveRoot(action.root, action.syncId, rootBySyncId)
                            ?: continue
                        val fetched = transport.get(action.key)
                            ?: throw IllegalStateException("Listed offline translations are missing at ${action.key}.")
                        val body = fetched.body.toString(Charsets.UTF_8)
                        val blob = json.decodeFromString(
                            PretranslationsBlob.serializer(),
                            body,
                        )
                        // Every field decodes leniently, so "valid JSON" alone is too weak a
                        // check: an empty object or a proxy error page would wipe a good cache.
                        if (blob.version > PretranslationStore.SUPPORTED_BLOB_VERSION ||
                            blob.entries.isEmpty()
                        ) {
                            continue
                        }
                        // Temp + rename: a truncated write would silently lose every offline
                        // translation for this book.
                        val target = File(targetRoot, PRETRANSLATIONS_FILENAME)
                        val temp = File(targetRoot, "$PRETRANSLATIONS_FILENAME.tmp")
                        temp.writeText(body, Charsets.UTF_8)
                        replaceFile(temp, target)
                        PretranslationStore.invalidate(targetRoot)
                        appliedPretranslations += 1
                    }
                    is V3Action.ImportSentences -> {
                        val targetRoot = resolveRoot(action.root, action.syncId, rootBySyncId)
                            ?: continue
                        val fetched = transport.getBounded(action.key, MAX_EPUB_SENTENCES_BLOB_BYTES)
                            ?: throw IllegalStateException("Listed sentence translations are missing at ${action.key}.")
                        val target = File(targetRoot, EPUB_TRANSLATIONS_FILENAME)
                        val alreadyInstalled = target.isFile &&
                            target.length() == fetched.body.size.toLong() &&
                            target.length() <= MAX_EPUB_SENTENCES_BLOB_BYTES &&
                            target.readBytes().contentEquals(fetched.body)
                        val body = fetched.body.toString(Charsets.UTF_8)
                        val spineCount = EpubBookParser().parse(
                            root = targetRoot,
                            cachedBookInfo = bookRepository.loadBookInfo(targetRoot),
                        ).spineCount
                        EpubTranslationStore.decodeAndValidate(body, action.syncId, spineCount)
                        if (alreadyInstalled) continue
                        val temp = File(targetRoot, "$EPUB_TRANSLATIONS_FILENAME.tmp")
                        temp.writeText(body, Charsets.UTF_8)
                        replaceFile(temp, target)
                        EpubTranslationStore.invalidate(targetRoot)
                        appliedSentenceTranslations += 1
                    }
                    is V3Action.ImportChat -> {
                        val targetRoot = resolveRoot(action.root, action.syncId, rootBySyncId)
                            ?: continue
                        val fetched = transport.get(action.key) ?: continue
                        val blob = json.decodeFromString(
                            HttpSyncChatEntryBlob.serializer(),
                            fetched.body.toString(Charsets.UTF_8),
                        )
                        val incoming = AiChatEntry(
                            bubbleText = blob.bubbleText,
                            prompt = blob.prompt,
                            model = blob.model,
                            response = blob.response,
                            timestampSeconds = blob.timestampSeconds,
                            screenshotImage = blob.screenshotImage,
                            dictionaryLookup = blob.dictionaryLookup,
                        )
                        val existing = runCatching { aiHistoryStore.load(targetRoot).entries }
                            .getOrDefault(emptyList())
                        val collides = existing.any {
                            it.bubbleText == incoming.bubbleText &&
                                it.timestampSeconds == incoming.timestampSeconds &&
                                it.response == incoming.response
                        }
                        if (!collides) {
                            aiHistoryStore.append(targetRoot, incoming)
                            appliedChatEntries += 1
                        }
                    }
                    is V3Action.PushBookmark -> {
                        when (pushOps.pushBookmarkConditional(
                            transport = transport,
                            bookRoot = action.root,
                            syncId = action.syncId,
                            localBookmark = action.bookmark,
                        )) {
                            PushBookmarkOutcome.Pushed -> pushedBookmarks += 1
                            PushBookmarkOutcome.AppliedRemote -> appliedBookmarks += 1
                            PushBookmarkOutcome.NoOp -> Unit
                        }
                    }
                    is V3Action.PushMetadata -> {
                        // SkippedIdentical = content matched the server's copy ignoring rev
                        // (revision store still advanced); SkippedStale = a deliberate local
                        // edit / deeper remote write superseded the plan-time blob mid-sync.
                        val outcome = pushOps.pushMetadata(
                            transport, action.syncId, action.blob, action.expectedRemote,
                        )
                        if (outcome is PushMetadataOutcome.Pushed) pushedMetadata += 1
                        if (action.blob.shelfUpdatedAt != null) {
                            existingShelfState[action.syncId] = HttpSyncShelfPlacementRecord(
                                shelfName = action.blob.shelfName,
                                updatedAt = action.blob.shelfUpdatedAt,
                            )
                            shelfStateDirty = true
                        }
                    }
                    is V3Action.PushChat -> {
                        pushOps.pushChat(transport, action.root, action.syncId, action.entry, action.key)
                        pushedChatEntries += 1
                    }
                    is V3Action.PushPayload -> {
                        val targetRoot = resolveRoot(action.root, action.syncId, rootBySyncId)
                            ?: continue
                        val uploaded = withByteProgress(
                            onProgress = onProgress,
                            makeProgress = { transferred, total ->
                                byteProgress(
                                    phase = V3Phase.PushingLocalState,
                                    message = "Uploading",
                                    title = action.title,
                                    transferred = transferred,
                                    total = total,
                                )
                            },
                        ) { onByteProgress ->
                            pushOps.pushPayload(
                                transport = transport,
                                bookRoot = targetRoot,
                                syncId = action.syncId,
                                title = action.title,
                                format = action.format,
                                onByteProgress = onByteProgress,
                            )
                        }
                        if (uploaded) pushedPayloads += 1
                    }
                    is V3Action.PushAiSettings -> {
                        pushOps.pushAiSettings(transport, action.local)
                        pushedAiSettings += 1
                    }
                    is V3Action.ApplyAiSettings -> {
                        if (pushOps.applyAiSettings(action.remote)) {
                            appliedAiSettings += 1
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Never swallow cancellation: structured concurrency requires the
                // CancellationException to flow back to the caller so the parent job
                // sees the sync as cancelled rather than as a normal `Done`.
                throw e
            } catch (e: Exception) {
                errors += V3Error(
                    syncId = action.syncId,
                    action = action::class.simpleName ?: "Unknown",
                    message = e.message ?: e.javaClass.simpleName,
                )
            }
        }

        if (shelfStateDirty) {
            runCatching {
                shelfStateStore.save(bookRepository.booksDirectory, existingShelfState)
            }
        }
        // Bug 3: no final `save(pendingDeletions)` here — every mutation has
        // already gone through the atomic per-key store helpers. A trailing
        // full-map write would clobber any tombstone recorded concurrently by
        // the user's delete path (BookshelfRepository.recordHttpSyncTombstone).

        onProgress(V3Progress(V3Phase.Done, "Sync complete"))
        return V3SyncResult(
            applied = V3AppliedCounts(
                bookmarks = appliedBookmarks,
                chatEntries = appliedChatEntries,
                payloads = appliedPayloads,
                sentenceTranslations = appliedSentenceTranslations,
                metadataDeletes = appliedMetadataDeletes,
                shelfPlacements = appliedShelfPlacements,
                aiSettings = appliedAiSettings,
            ),
            pushed = V3PushedCounts(
                bookmarks = pushedBookmarks,
                chatEntries = pushedChatEntries,
                metadata = pushedMetadata,
                payloads = pushedPayloads,
                tombstones = pushedTombstones,
                aiSettings = pushedAiSettings,
            ),
            remoteOnlyBooks = plan.pendingRemoteOnlyBooks.size,
            errors = errors,
        )
    }

    /**
     * Imports a remote-only book: creates a fresh directory, downloads + unpacks the
     * payload, writes a minimal metadata sidecar.
     */
    private suspend fun importRemoteBook(
        transport: HttpSyncKvTransport,
        syncId: String,
        action: V3Action.ImportRemoteBook,
        onProgress: suspend (V3Progress) -> Unit = {},
    ): File? {
        val stagingRoot = createSyncImportStagingDirectory(bookRepository.booksDirectory)
        var publishedRoot: File? = null
        return try {
            val manifest = withByteProgress(
                onProgress = onProgress,
                makeProgress = { transferred, total ->
                    byteProgress(
                        phase = V3Phase.ImportingPayloads,
                        message = "Downloading",
                        title = syncId,
                        transferred = transferred,
                        total = total,
                    )
                },
            ) { onByteProgress ->
                payloadCodec.downloadAndUnpack(
                    transport = transport,
                    syncId = syncId,
                    targetDir = stagingRoot,
                    onByteProgress = onByteProgress,
                    keys = action.payloadKeys,
                    expectedFormat = action.manifest.format,
                )
            }
            val actualContentType = bookContentType(stagingRoot)
            if (actualContentType != manifest.format.toLocal()) {
                throw IllegalArgumentException(
                    "Payload for $syncId declares ${manifest.format} but unpacked as $actualContentType.",
                )
            }
            val parsedEpub = moe.antimony.hoshi.features.sync.http.validateSyncImportedBook(stagingRoot)
            val targetRoot = publishSyncImportDirectory(stagingRoot, bookRepository.booksDirectory)
            publishedRoot = targetRoot
            // Parse/cover resolution is deliberately inside the cleanup boundary: malformed
            // remote bytes must never leave a ghost shelf directory behind.
            val coverPath = if (parsedEpub != null) {
                bookRepository.metadataCoverPath(targetRoot, parsedEpub.coverHref)
            } else {
                resolveSyncImportedCoverPath(bookRepository, targetRoot)
            }
            parsedEpub?.let { bookRepository.saveBookInfo(targetRoot, it.bookInfo) }
            bookRepository.saveMetadata(
                targetRoot,
                BookMetadata(
                    id = UUID.randomUUID().toString(),
                    title = manifest.originalName,
                    cover = coverPath,
                    folder = targetRoot.name,
                    lastAccess = 0.0,
                    syncId = action.syncId,
                    importedAt = java.time.Instant.now().toString(),
                ),
            )
            targetRoot
        } catch (e: Exception) {
            (publishedRoot ?: stagingRoot).deleteRecursively()
            throw e
        }
    }

    private suspend fun replaceRemotePayload(
        transport: HttpSyncKvTransport,
        action: V3Action.ReplaceRemotePayload,
        onProgress: suspend (V3Progress) -> Unit,
    ): Boolean {
        if (moe.antimony.hoshi.features.sync.http.HttpSyncActiveBooks.contains(action.syncId)) {
            return false
        }
        if (payloadCodec.hasPayloadContentDirty(action.root)) {
            return payloadCodec.uploadIfChanged(
                transport = transport,
                syncId = action.syncId,
                bookRoot = action.root,
                originalName = action.manifest.originalName,
                format = action.manifest.format,
            )
        }
        val localSha = payloadCodec.cachedPayloadSha(action.root)
            ?: payloadCodec.ensurePayloadContentSha(action.root)
        if (action.manifest.contentSha256 != null &&
            localSha == action.manifest.contentSha256
        ) return false
        val stagingRoot = createSyncImportStagingDirectory(bookRepository.booksDirectory)
        try {
            val manifest = withByteProgress(
                onProgress = onProgress,
                makeProgress = { transferred, total ->
                    byteProgress(
                        phase = V3Phase.ImportingPayloads,
                        message = "Updating",
                        title = action.syncId,
                        transferred = transferred,
                        total = total,
                    )
                },
            ) { onByteProgress ->
                payloadCodec.downloadAndUnpack(
                    transport = transport,
                    syncId = action.syncId,
                    targetDir = stagingRoot,
                    onByteProgress = onByteProgress,
                    keys = action.payloadKeys,
                    expectedFormat = action.manifest.format,
                )
            }
            val actualContentType = bookContentType(stagingRoot)
            require(actualContentType == manifest.format.toLocal()) {
                "Payload for ${action.syncId} declares ${manifest.format} but unpacked as $actualContentType."
            }
            val parsedEpub = moe.antimony.hoshi.features.sync.http.validateSyncImportedBook(stagingRoot)
            val verifiedRemoteSha = requireNotNull(manifest.contentSha256)
            if (action.manifest.contentSha256 == null) {
                payloadCodec.publishVerifiedContentSha(transport, action.payloadKeys, manifest)
            }
            if (localSha == verifiedRemoteSha) return false
            return bookLocks.withBookLock(action.root) {
                // Re-check after download and under the shared import/sync lock. A local import
                // that won this race marks the generation dirty before releasing this lock.
                if (payloadCodec.hasPayloadContentDirty(action.root)) return@withBookLock false
                HttpSyncActiveBooks.runIfInactive(action.syncId) {
                    payloadCodec.installReplacement(
                        action.root,
                        stagingRoot,
                        verifiedRemoteSha,
                    )
                    parsedEpub?.let { bookRepository.saveBookInfo(action.root, it.bookInfo) }
                }
            }
        } finally {
            if (stagingRoot.exists()) stagingRoot.deleteRecursively()
        }
    }

    /**
     * Resolves a per-action `root`: if it points at a sentinel `/v3-pending/...` path
     * (from the planner), look the real one up in `rootBySyncId`. Otherwise return as-is.
     */
    private fun resolveRoot(
        root: File,
        syncId: String,
        rootBySyncId: Map<String, File>,
    ): File? {
        if (root.path.startsWith("/v3-pending/")) {
            return rootBySyncId[syncId]
        }
        return root.takeIf { it.exists() } ?: rootBySyncId[syncId] ?: root
    }

    /**
     * Apply a remote shelf placement to local shelves. Returns true if shelves changed.
     */
    private suspend fun applyShelfPlacement(bookRoot: File, shelfName: String?): Boolean {
        val bookId = bookRepository.loadMetadata(bookRoot)?.id ?: return false
        val normalized = shelfName?.trim()?.takeIf { it.isNotEmpty() }
        val shelves = bookRepository.loadShelves()
        var foundTarget = false
        val updated = shelves.map { shelf ->
            val withoutBook = shelf.bookIds.filterNot { it == bookId }
            if (normalized != null && shelf.name == normalized) {
                foundTarget = true
                shelf.copy(bookIds = (withoutBook + bookId).distinct())
            } else {
                shelf.copy(bookIds = withoutBook)
            }
        }
        val finalShelves = if (normalized != null && !foundTarget) {
            updated + BookShelf(normalized, listOf(bookId))
        } else {
            updated
        }
        if (finalShelves != shelves) {
            bookRepository.saveShelves(finalShelves)
            return true
        }
        return false
    }

    /**
     * Runs [block] while bridging its non-suspend byte-progress callback to the suspend
     * [onProgress] channel. A conflated [Channel] decouples the two: the payload upload /
     * download loop only ever does a non-blocking `trySend`, and a collector coroutine
     * drains the latest value and emits a [V3Progress] with byte-level `completed`/`total`.
     * Mirrors the v2 reconciler's bridge so v3 shows the same per-file progress bar.
     */
    private suspend fun <T> withByteProgress(
        onProgress: suspend (V3Progress) -> Unit,
        makeProgress: (bytesTransferred: Long, totalBytes: Long) -> V3Progress,
        block: suspend (onByteProgress: (Long, Long) -> Unit) -> T,
    ): T = coroutineScope {
        val channel = Channel<Pair<Long, Long>>(Channel.CONFLATED)
        val collector = launch {
            for ((transferred, total) in channel) {
                onProgress(makeProgress(transferred, total))
            }
        }
        try {
            block { transferred, total -> channel.trySend(transferred to total) }
        } finally {
            channel.close()
            collector.join()
        }
    }

    /**
     * Bytes-to-[V3Progress] mapper for the payload upload / download paths. Uses byte counts
     * as `completed`/`total` so the progress bar tracks the file transfer; a non-positive
     * `total` (chunked transfer, unknown length) leaves them null. Byte counts are coerced
     * into `Int` defensively — a 100 MB payload fits, but a hypothetical >2 GB one would not.
     */
    private fun byteProgress(
        phase: V3Phase,
        message: String,
        title: String,
        transferred: Long,
        total: Long,
    ): V3Progress {
        val haveTotal = total > 0L
        return V3Progress(
            phase = phase,
            message = "$message $title",
            detail = if (haveTotal) {
                "${megabytes(transferred)} MB / ${megabytes(total)} MB"
            } else {
                "${megabytes(transferred)} MB"
            },
            completed = if (haveTotal) {
                transferred.coerceAtMost(total).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else {
                null
            },
            total = if (haveTotal) total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null,
        )
    }

    private fun megabytes(bytes: Long): String =
        "%.1f".format(bytes.coerceAtLeast(0L) / (1024.0 * 1024.0))

    private fun phaseFor(action: V3Action): V3Phase = when (action) {
        is V3Action.PushTombstone -> V3Phase.PushingTombstones
        is V3Action.ApplyRemoteMetadata -> V3Phase.ApplyingMetadata
        is V3Action.DeleteLocalBook -> V3Phase.DeletingBooks
        is V3Action.ImportRemoteBook -> V3Phase.ImportingPayloads
        is V3Action.ReplaceRemotePayload -> V3Phase.ImportingPayloads
        is V3Action.ApplyRemoteBookmark,
        is V3Action.ImportChat,
        is V3Action.ImportPretranslations,
        is V3Action.ImportSentences,
        is V3Action.ApplyAiSettings -> V3Phase.ApplyingRemoteState
        is V3Action.PushBookmark,
        is V3Action.PushChat,
        is V3Action.PushPayload,
        is V3Action.PushMetadata -> V3Phase.PushingLocalState
        is V3Action.PushAiSettings -> V3Phase.SyncingAppSettings
    }

    private fun replaceFile(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
