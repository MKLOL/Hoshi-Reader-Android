package moe.antimony.hoshi.features.sync.v3

import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.sync.http.HttpSyncBookmarkBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncChatEntryBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookStateStore
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.HttpSyncShelfPlacementRecord
import moe.antimony.hoshi.features.sync.http.HttpSyncShelfStateStore
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
        val pendingDeletions = deletedBookStateStore.load(bookRepository.booksDirectory).toMutableMap()
        var deletedStateDirty = false

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
                        pendingDeletions.remove(action.syncId)
                        deletedStateDirty = true
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
                        val newRoot = importRemoteBook(transport, action.syncId, action)
                        if (newRoot != null) {
                            rootBySyncId[action.syncId] = newRoot
                            appliedPayloads += 1
                        }
                    }
                    is V3Action.ApplyRemoteBookmark -> {
                        val targetRoot = resolveRoot(action.root, action.syncId, rootBySyncId)
                            ?: continue
                        bookRepository.saveBookmark(
                            targetRoot,
                            Bookmark(
                                chapterIndex = action.blob.chapterIndex,
                                progress = action.blob.progress,
                                characterCount = action.blob.characterCount,
                                lastModified = rfc3339ToAppleSeconds(action.blob.lastModified),
                            ),
                        )
                        appliedBookmarks += 1
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
                        val uploaded = pushOps.pushPayload(
                            transport = transport,
                            bookRoot = action.root,
                            syncId = action.syncId,
                            title = action.title,
                            format = action.format,
                        )
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
        if (deletedStateDirty) {
            runCatching {
                deletedBookStateStore.save(bookRepository.booksDirectory, pendingDeletions)
            }
        }

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
    ): File? {
        val targetRoot = bookRepository.createBookDirectoryForImportedTitle(syncId)
        val manifest = try {
            payloadCodec.downloadAndUnpack(transport, syncId, targetRoot)
        } catch (e: Exception) {
            // Clean up the half-imported directory.
            targetRoot.deleteRecursively()
            throw e
        }
        bookRepository.saveMetadata(
            targetRoot,
            BookMetadata(
                id = UUID.randomUUID().toString(),
                title = manifest.originalName,
                cover = null,
                folder = targetRoot.name,
                lastAccess = 0.0,
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
