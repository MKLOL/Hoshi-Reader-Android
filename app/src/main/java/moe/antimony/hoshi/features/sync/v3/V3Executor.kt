package moe.antimony.hoshi.features.sync.v3

import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.sync.http.withHttpSyncTransferProgress
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
import moe.antimony.hoshi.features.sync.http.HttpSyncStatisticsSync
import moe.antimony.hoshi.features.sync.http.StatisticsRemoteListing
import moe.antimony.hoshi.features.sync.http.HttpSyncActiveBooks
import moe.antimony.hoshi.features.sync.http.HttpSyncBookLocks
import moe.antimony.hoshi.features.sync.http.HttpSyncBookmarkBlob
import moe.antimony.hoshi.features.sync.http.FailedCloudRequest
import moe.antimony.hoshi.features.sync.http.HttpSyncCloudBookListing
import moe.antimony.hoshi.features.sync.http.HttpSyncCloudBooks
import moe.antimony.hoshi.features.sync.http.fetchRemoteMangaCover
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
import moe.antimony.hoshi.storage.writeSidecarAtomically
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
    /** Receives [V3Plan.cloudBooks], their covers and the tapped downloads' progress. */
    private val cloudBooks: HttpSyncCloudBooks? = null,
    /** Brings downloaded cloud manga up to date (reading position) before the shelf shows them. */
    private val beforeCloudBooksShown: suspend (List<File>) -> Unit = {},
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val shelfStateStore = HttpSyncShelfStateStore(json)
    private val deletedBookStateStore = HttpSyncDeletedBookStateStore(json)
    private val revisionStore = HttpSyncRevisionStore(json)
    private val statisticsSync = HttpSyncStatisticsSync(bookRepository, bookLocks)

    suspend fun run(
        plan: V3Plan,
        transport: HttpSyncKvTransport,
        onProgress: suspend (V3Progress) -> Unit,
    ): V3SyncResult {
        // Shelf-state mutations are tracked so the sidecar is saved exactly once.
        val state = RunState(shelfStateStore.load(bookRepository.booksDirectory).toMutableMap())
        // Bug 3: the deleted-book sidecar must NOT be mutated through a
        // snapshot + dirty-flag + final-save pattern. The same file is also
        // written by `BookshelfRepository.recordHttpSyncTombstone` on
        // user-initiated deletes, which can happen during sync. If we held a
        // snapshot here and wrote it back at the end of sync, we'd clobber any
        // concurrent user delete recorded mid-sync. Instead, mutate via the
        // atomic per-key helpers (`recordDeletedBook` / `removeDeletedBook`),
        // which re-read the latest disk state under a process-wide lock.

        // Cloud manga are listed first, so the shelf shows them while any longer transfer below
        // is still running. Their covers come after the actions, so a tapped download never
        // waits for a new library's covers.
        val cloudListed = plan.cloudBooks?.takeIf { cloudBooks != null }
        var newCloudBooks = 0
        val cloudRequests = cloudListed.orEmpty()
            .mapNotNull { book -> book.requestedAt?.let { book.syncId to it } }
            .toMap()
        state.cloudRequests = cloudRequests
        val failedCloudRequests = mutableMapOf<String, FailedCloudRequest>()
        if (cloudBooks != null && cloudListed != null) {
            newCloudBooks = cloudBooks.recordListed(cloudListed.map { it.toListing() })
        }

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
                runAction(action, state, transport, onProgress)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Never swallow cancellation: structured concurrency requires the
                // CancellationException to flow back to the caller so the parent job
                // sees the sync as cancelled rather than as a normal `Done`.
                cloudRequests.keys.forEach { cloudBooks?.clearProgress(it) }
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                state.errors += V3Error(
                    syncId = action.syncId,
                    action = action::class.simpleName ?: "Unknown",
                    message = message,
                    title = (action as? V3Action.ImportRemoteBook)?.manifest?.originalName?.takeIf { it.isNotBlank() },
                )
                if (action is V3Action.ImportRemoteBook) {
                    cloudRequests[action.syncId]?.let { requestedAt ->
                        failedCloudRequests[action.syncId] = FailedCloudRequest(
                            requestedAt,
                            message.ifBlank { e.javaClass.simpleName },
                        )
                    }
                }
            }
        }

        val cloudStore = cloudBooks
        if (cloudStore != null && cloudListed != null) {
            // A downloaded manga leaves the catalogue (and appears on the shelf) only now, with its
            // chats, translations and statistics applied, and its position from the maps.
            val installed = cloudRequests.keys.mapNotNull { syncId -> state.rootBySyncId[syncId]?.let { syncId to it } }
            if (installed.isNotEmpty()) {
                try {
                    beforeCloudBooksShown(installed.map { it.second })
                } catch (e: kotlinx.coroutines.CancellationException) {
                    installed.forEach { (syncId, _) -> cloudStore.clearProgress(syncId) }
                    throw e
                } catch (e: Exception) {
                    // The end-of-sync map publication applies the position instead.
                }
                for ((syncId, _) in installed) cloudStore.markDownloaded(syncId)
            }
            fetchCloudCovers(transport, cloudStore, cloudListed, onProgress)
            // A book whose manifest could not be read this time is still on the server: keep
            // its entry, cover and request for the next pass.
            val stillRemote = cloudListed.mapTo(mutableSetOf()) { it.syncId } + plan.pendingRemoteOnlyBooks
            runCatching { cloudStore.finishPass(stillRemote, failedCloudRequests) }
        }

        if (state.shelfStateDirty) {
            runCatching {
                shelfStateStore.save(bookRepository.booksDirectory, state.existingShelfState)
            }
        }
        // Bug 3: no final `save(pendingDeletions)` here — every mutation has
        // already gone through the atomic per-key store helpers. A trailing
        // full-map write would clobber any tombstone recorded concurrently by
        // the user's delete path (BookshelfRepository.recordHttpSyncTombstone).

        onProgress(V3Progress(V3Phase.Done, "Sync complete"))
        return V3SyncResult(
            applied = V3AppliedCounts(
                bookmarks = state.appliedBookmarks,
                chatEntries = state.appliedChatEntries,
                payloads = state.appliedPayloads,
                sentenceTranslations = state.appliedSentenceTranslations,
                statistics = state.appliedStatistics,
                metadataDeletes = state.appliedMetadataDeletes,
                shelfPlacements = state.appliedShelfPlacements,
                aiSettings = state.appliedAiSettings,
            ),
            pushed = V3PushedCounts(
                bookmarks = state.pushedBookmarks,
                chatEntries = state.pushedChatEntries,
                statistics = state.pushedStatistics,
                metadata = state.pushedMetadata,
                payloads = state.pushedPayloads,
                tombstones = state.pushedTombstones,
                aiSettings = state.pushedAiSettings,
            ),
            remoteOnlyBooks = plan.pendingRemoteOnlyBooks.size,
            errors = state.errors,
            newCloudBooks = newCloudBooks,
        )
    }

    /** Runs one planned action, counting what it did in [state]; a failure is the caller's. */
    private suspend fun runAction(
        action: V3Action,
        state: RunState,
        transport: HttpSyncKvTransport,
        onProgress: suspend (V3Progress) -> Unit,
    ) {
        when (action) {
            is V3Action.PushTombstone -> {
                pushOps.pushTombstone(transport, action.syncId, action.record)
                state.pushedTombstones += 1
                // Atomic per-key clear: re-reads disk under the store's
                // lock so a concurrent recordDeletedBook for a different
                // key (Bug 3) survives.
                deletedBookStateStore.removeDeletedBook(
                    bookRepository.booksDirectory,
                    action.syncId,
                )
                state.existingShelfState.remove(action.syncId)
                state.shelfStateDirty = true
            }
            is V3Action.ApplyRemoteMetadata -> {
                val targetRoot = action.root ?: state.rootBySyncId[action.syncId]
                if (action.blob.deletedAt != null) {
                    if (targetRoot != null) {
                        val removed = HttpSyncActiveBooks.runIfInactive(action.syncId) {
                            bookRepository.deleteBook(targetRoot)
                        }
                        if (!removed) {
                            throw IllegalStateException("Deletion deferred while this book is open.")
                        }
                        state.rootBySyncId.remove(action.syncId)
                    }
                    state.existingShelfState.remove(action.syncId)
                    state.shelfStateDirty = true
                    state.appliedMetadataDeletes += 1
                } else if (targetRoot != null) {
                    val applied = applyShelfPlacement(targetRoot, action.blob.shelfName)
                    if (applied) {
                        state.existingShelfState[action.syncId] = HttpSyncShelfPlacementRecord(
                            shelfName = action.blob.shelfName,
                            updatedAt = action.blob.shelfUpdatedAt ?: java.time.Instant.now().toString(),
                        )
                        state.shelfStateDirty = true
                        state.appliedShelfPlacements += 1
                    }
                }
            }
            is V3Action.DeleteLocalBook -> {
                val targetRoot = if (action.root.exists()) action.root else state.rootBySyncId[action.syncId]
                if (targetRoot != null && targetRoot.exists()) {
                    val removed = HttpSyncActiveBooks.runIfInactive(action.syncId) {
                        bookRepository.deleteBook(targetRoot)
                    }
                    if (!removed) {
                        throw IllegalStateException("Deletion deferred while this book is open.")
                    }
                    state.rootBySyncId.remove(action.syncId)
                    state.existingShelfState.remove(action.syncId)
                    state.shelfStateDirty = true
                    state.appliedMetadataDeletes += 1
                }
            }
            is V3Action.ImportRemoteBook -> {
                val cloudDownload = cloudBooks?.takeIf { action.syncId in state.cloudRequests }
                val newRoot = try {
                    importRemoteBook(transport, action.syncId, action, onProgress, cloudDownload)
                } catch (e: Throwable) {
                    cloudDownload?.clearProgress(action.syncId)
                    throw e
                }
                if (newRoot != null) {
                    state.rootBySyncId[action.syncId] = newRoot
                    state.appliedPayloads += 1
                    // Bug 2: apply shelf placement carried inline on the import
                    // action. We can't rely on a separate ApplyRemoteMetadata
                    // because bucket ordering puts metadata-apply BEFORE import,
                    // so the executor would have no root for this syncId yet.
                    if (action.shelfName != null || action.shelfUpdatedAt != null) {
                        val applied = applyShelfPlacement(newRoot, action.shelfName)
                        if (applied) {
                            state.existingShelfState[action.syncId] = HttpSyncShelfPlacementRecord(
                                shelfName = action.shelfName,
                                updatedAt = action.shelfUpdatedAt ?: java.time.Instant.now().toString(),
                            )
                            state.shelfStateDirty = true
                            state.appliedShelfPlacements += 1
                        }
                    }
                    // The shelf shows it once everything below has been applied to it.
                    cloudDownload?.reportProgress(action.syncId, 1f)
                }
            }
            is V3Action.ReplaceRemotePayload -> {
                if (replaceRemotePayload(transport, action, onProgress)) {
                    state.appliedPayloads += 1
                }
            }
            is V3Action.ApplyRemoteBookmark -> {
                val targetRoot = resolveRoot(action.root, action.syncId, state.rootBySyncId)
                    ?: return
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
                if (applied) state.appliedBookmarks += 1
            }
            is V3Action.ImportPretranslations -> {
                val targetRoot = resolveRoot(action.root, action.syncId, state.rootBySyncId)
                    ?: return
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
                    return
                }
                // Temp + rename: a truncated write would silently lose every offline
                // translation for this book.
                val target = File(targetRoot, PRETRANSLATIONS_FILENAME)
                writeSidecarAtomically(target, body)
                PretranslationStore.invalidate(targetRoot)
                state.appliedPretranslations += 1
            }
            is V3Action.ImportSentences -> {
                val targetRoot = resolveRoot(action.root, action.syncId, state.rootBySyncId)
                    ?: return
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
                if (alreadyInstalled) return
                writeSidecarAtomically(target, body)
                EpubTranslationStore.invalidate(targetRoot)
                state.appliedSentenceTranslations += 1
            }
            is V3Action.SyncStatistics -> {
                val targetRoot = resolveRoot(action.root, action.syncId, state.rootBySyncId)
                    ?: return
                val listing = when {
                    action.remoteKey == null -> StatisticsRemoteListing.Absent
                    action.remoteSize == null -> StatisticsRemoteListing.Unknown
                    else -> StatisticsRemoteListing.Listed(action.remoteSize, action.remoteLastModified, action.remoteEtag)
                }
                // A newer version's body or a key gone since the listing: skipped, not an error.
                val outcome = try {
                    statisticsSync.sync(transport, targetRoot, action.syncId, action.kind, listing)
                } catch (_: moe.antimony.hoshi.features.sync.http.StatisticsSkippedException) {
                    moe.antimony.hoshi.features.sync.http.StatisticsSyncOutcome.NONE
                }
                if (outcome.downloaded) state.appliedStatistics += 1
                if (outcome.uploaded) state.pushedStatistics += 1
            }
            is V3Action.ImportChat -> {
                val targetRoot = resolveRoot(action.root, action.syncId, state.rootBySyncId)
                    ?: return
                val fetched = transport.get(action.key) ?: return
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
                    state.appliedChatEntries += 1
                }
            }
            is V3Action.PushBookmark -> {
                when (pushOps.pushBookmarkConditional(
                    transport = transport,
                    bookRoot = action.root,
                    syncId = action.syncId,
                    localBookmark = action.bookmark,
                )) {
                    PushBookmarkOutcome.Pushed -> state.pushedBookmarks += 1
                    PushBookmarkOutcome.AppliedRemote -> state.appliedBookmarks += 1
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
                if (outcome is PushMetadataOutcome.Pushed) state.pushedMetadata += 1
                if (action.blob.shelfUpdatedAt != null) {
                    state.existingShelfState[action.syncId] = HttpSyncShelfPlacementRecord(
                        shelfName = action.blob.shelfName,
                        updatedAt = action.blob.shelfUpdatedAt,
                    )
                    state.shelfStateDirty = true
                }
            }
            is V3Action.PushChat -> {
                pushOps.pushChat(transport, action.entry, action.key)
                state.pushedChatEntries += 1
            }
            is V3Action.PushPayload -> {
                val targetRoot = resolveRoot(action.root, action.syncId, state.rootBySyncId)
                    ?: return
                val uploaded = withHttpSyncTransferProgress(
                    onProgress = onProgress,
                    makeProgress = { transfer ->
                        V3Progress(
                            phase = V3Phase.PushingLocalState,
                            message = "Uploading ${action.title}",
                            transfer = transfer,
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
                if (uploaded) state.pushedPayloads += 1
            }
            is V3Action.PushAiSettings -> {
                pushOps.pushAiSettings(transport, action.local)
                state.pushedAiSettings += 1
            }
            is V3Action.ApplyAiSettings -> {
                if (pushOps.applyAiSettings(action.remote)) {
                    state.appliedAiSettings += 1
                }
            }
        }
    }

    /**
     * Reads each cloud manga's cover from its remote archive once per archive version. Covers are
     * best effort: a server without byte ranges or an unusual archive just leaves the
     * placeholder. A network failure ends the covers for this pass (an unreachable server would
     * otherwise time out once per manga) and the next sync retries them; neither is a sync error.
     */
    private suspend fun fetchCloudCovers(
        transport: HttpSyncKvTransport,
        store: HttpSyncCloudBooks,
        listed: List<V3CloudBook>,
        onProgress: suspend (V3Progress) -> Unit,
    ) {
        // A tapped manga is about to be downloaded with its real cover.
        val missing = listed.filter { book ->
            book.requestedAt == null && store.needsCover(book.syncId, book.manifest.sha256)
        }
        for ((index, book) in missing.withIndex()) {
            onProgress(
                V3Progress(
                    phase = V3Phase.ListingRemote,
                    message = "Fetching covers",
                    messageResource = moe.antimony.hoshi.R.string.http_sync_fetching_covers,
                    detail = book.title,
                    completed = index,
                    total = missing.size,
                ),
            )
            try {
                val image = fetchRemoteMangaCover(transport, book.payloadKeys.zip, book.manifest.sizeBytes)
                store.saveCover(book.syncId, book.manifest.sha256, image)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
        }
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
        /** Set for a tapped cloud manga, whose cover shows the download's progress. */
        cloudDownload: HttpSyncCloudBooks? = null,
    ): File? {
        cloudDownload?.reportProgress(syncId, null)
        val stagingRoot = createSyncImportStagingDirectory(bookRepository.booksDirectory)
        var publishedRoot: File? = null
        return try {
            val manifest = withHttpSyncTransferProgress(
                onProgress = onProgress,
                makeProgress = { transfer ->
                    V3Progress(
                        phase = V3Phase.ImportingPayloads,
                        message = "Downloading ${action.manifest.originalName.ifBlank { syncId }}",
                        transfer = transfer,
                    )
                },
            ) { onByteProgress ->
                payloadCodec.downloadAndUnpack(
                    transport = transport,
                    syncId = syncId,
                    targetDir = stagingRoot,
                    onByteProgress = if (cloudDownload == null) {
                        onByteProgress
                    } else {
                        { transferred, total ->
                            onByteProgress(transferred, total)
                            if (total > 0) cloudDownload.reportProgress(syncId, transferred.toFloat() / total)
                        }
                    },
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
                bookRepository.syncedCoverPath(targetRoot, parsedEpub.coverHref)
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
        val cachedSha = payloadCodec.cachedPayloadSha(action.root)
            ?: payloadCodec.ensurePayloadContentSha(action.root)
        val remoteSha = action.manifest.contentSha256
        if (remoteSha != null && cachedSha == remoteSha) return false
        // The server still points at the exact archive this install uploaded or installed, so
        // the content-hash disagreement is a derivation difference between clients, never new
        // content. Chasing it with a download would have two clients that hash differently
        // trade the same archive back and forth forever. (A manifest with no content hash at
        // all is still downloaded once so the verified hash gets published.)
        if (remoteSha != null && action.manifest.sha256 == payloadCodec.cachedZipSha(action.root)) return false
        // The cached baseline disagrees with the server. Re-derive it from the bytes on disk
        // before paying for a download: a stale or mis-derived sidecar is far cheaper to fix
        // locally than a multi-hundred-MB replacement that installs identical content.
        val localSha = payloadCodec.refreshPayloadContentSha(action.root)
        if (remoteSha != null && localSha == remoteSha) {
            // The bytes on disk are the archive the manifest describes, so a book that predates
            // the archive sidecar gains the baseline the guard above needs.
            payloadCodec.rememberZipSha(action.root, action.manifest.sha256)
            return false
        }
        if (remoteSha != null &&
            moe.antimony.hoshi.features.sync.http.migrateLegacyGeneratedCoverAndRepoint(
                payloadCodec, bookRepository, action.root, remoteSha,
            )
        ) return false
        val stagingRoot = createSyncImportStagingDirectory(bookRepository.booksDirectory)
        try {
            val manifest = withHttpSyncTransferProgress(
                onProgress = onProgress,
                makeProgress = { transfer ->
                    V3Progress(
                        phase = V3Phase.ImportingPayloads,
                        message = "Updating ${action.syncId}",
                        transfer = transfer,
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
            // downloadAndUnpack already republished the manifest if its declared hash was
            // missing or wrong, so the remote note now matches this verified value.
            val verifiedRemoteSha = requireNotNull(manifest.contentSha256)
            if (localSha == verifiedRemoteSha) {
                // Staging is discarded below; the real book root holds this exact archive, so
                // remember it there or the next foreign hash costs this download again.
                payloadCodec.rememberZipSha(action.root, manifest.sha256)
                return false
            }
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
        is V3Action.SyncStatistics,
        is V3Action.ApplyAiSettings -> V3Phase.ApplyingRemoteState
        is V3Action.PushBookmark,
        is V3Action.PushChat,
        is V3Action.PushPayload,
        is V3Action.PushMetadata -> V3Phase.PushingLocalState
        is V3Action.PushAiSettings -> V3Phase.SyncingAppSettings
    }
}

private fun V3CloudBook.toListing() = HttpSyncCloudBookListing(
    syncId = syncId,
    title = title,
    format = manifest.format,
    sizeBytes = manifest.sizeBytes,
    archiveSha256 = manifest.sha256,
    contentSha256 = manifest.contentSha256,
    shelfName = shelfName,
)

/**
 * What one [V3Executor.run] accumulates. Each action runs in its own method
 * ([V3Executor.runAction]): a single method holding every action grew past the 65,535 DEX code
 * units Android's runtime executes reliably, which crashed or mis-routed exceptions on a device.
 */
private class RunState(val existingShelfState: MutableMap<String, HttpSyncShelfPlacementRecord>) {
    var appliedBookmarks = 0
    var appliedChatEntries = 0
    var appliedPretranslations = 0
    var appliedSentenceTranslations = 0
    var appliedStatistics = 0
    var pushedStatistics = 0
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
    var shelfStateDirty = false

    /** The tapped cloud manga this pass downloads, with their request stamps. */
    var cloudRequests: Map<String, String> = emptyMap()
}
