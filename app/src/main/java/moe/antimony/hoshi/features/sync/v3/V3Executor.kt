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
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.sync.http.HttpSyncBookLocks
import moe.antimony.hoshi.features.sync.http.HttpSyncBookmarkBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncChatEntryBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookStateStore
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.HttpSyncShelfPlacementRecord
import moe.antimony.hoshi.features.sync.http.resolveSyncImportedCoverPath
import moe.antimony.hoshi.features.sync.http.HttpSyncShelfStateStore
import moe.antimony.hoshi.features.sync.http.appleSecondsToRfc3339
import moe.antimony.hoshi.features.sync.http.compareRfc3339
import moe.antimony.hoshi.features.sync.http.rfc3339ToAppleSeconds
import java.io.File
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

    suspend fun run(
        plan: V3Plan,
        transport: HttpSyncKvTransport,
        onProgress: suspend (V3Progress) -> Unit,
    ): V3SyncResult {
        var appliedBookmarks = 0
        var appliedChatEntries = 0
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
                                bookRepository.deleteBook(targetRoot)
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
                            bookRepository.deleteBook(targetRoot)
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
                            val currentLocal = runCatching { bookRepository.loadBookmark(targetRoot) }
                                .getOrNull()
                            val localStamp = currentLocal?.lastModified?.let(::appleSecondsToRfc3339)
                            // compareRfc3339 returns positive when remote is strictly newer.
                            val cmp = compareRfc3339(action.blob.lastModified, localStamp)
                            if (cmp > 0) {
                                bookRepository.saveBookmark(
                                    targetRoot,
                                    Bookmark(
                                        chapterIndex = action.blob.chapterIndex,
                                        progress = action.blob.progress,
                                        characterCount = action.blob.characterCount,
                                        lastModified = rfc3339ToAppleSeconds(action.blob.lastModified),
                                    ),
                                )
                                true
                            } else {
                                // Local is newer (or equal) than the remote blob this plan
                                // was computed against — drop the apply. The next sync's
                                // push phase will reconcile via pushBookmarkConditional.
                                false
                            }
                        }
                        if (applied) appliedBookmarks += 1
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
                        pushOps.pushMetadata(transport, action.syncId, action.blob)
                        pushedMetadata += 1
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
                                bookRoot = action.root,
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
        val targetRoot = bookRepository.createBookDirectoryForImportedTitle(syncId)
        val manifest = try {
            withByteProgress(
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
                payloadCodec.downloadAndUnpack(transport, syncId, targetRoot, onByteProgress)
            }
        } catch (e: Exception) {
            // Clean up the half-imported directory.
            targetRoot.deleteRecursively()
            throw e
        }
        // Mirror the user-side import path: parse the freshly-unzipped book and resolve a
        // cover path so the bookshelf can render a thumbnail before the user opens it. Without
        // this, `metadata.cover` stayed null until the first open triggered the parser via
        // `BookshelfRepository.openBook`, and the bookshelf showed a blank cover slot.
        val coverPath = resolveSyncImportedCoverPath(bookRepository, targetRoot)
        bookRepository.saveMetadata(
            targetRoot,
            BookMetadata(
                id = UUID.randomUUID().toString(),
                title = manifest.originalName,
                cover = coverPath,
                folder = targetRoot.name,
                lastAccess = 0.0,
                // Stamp the import the same way the user-side import path does. Lets the
                // planner's next pass compare local `importedAt` against any remote tombstone
                // and overwrite the tombstone when the local re-import is strictly newer.
                importedAt = java.time.Instant.now().toString(),
            ),
        )
        return targetRoot
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
        is V3Action.ApplyRemoteBookmark,
        is V3Action.ImportChat,
        is V3Action.ApplyAiSettings -> V3Phase.ApplyingRemoteState
        is V3Action.PushBookmark,
        is V3Action.PushChat,
        is V3Action.PushPayload,
        is V3Action.PushMetadata -> V3Phase.PushingLocalState
        is V3Action.PushAiSettings -> V3Phase.SyncingAppSettings
    }
}
