package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.bookContentType
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.sync.http.HttpSyncAutoPush
import moe.antimony.hoshi.features.sync.http.HttpSyncBatchState
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookRecord
import moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookStateStore
import moe.antimony.hoshi.features.sync.http.HttpSyncEngineDispatcher
import moe.antimony.hoshi.features.sync.http.HttpSyncFastSync
import moe.antimony.hoshi.features.sync.http.HttpSyncFullCycleRunner
import moe.antimony.hoshi.features.sync.http.HttpSyncKvClient
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.HttpSyncPusher
import moe.antimony.hoshi.features.sync.http.HttpSyncReconciler
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import moe.antimony.hoshi.features.sync.http.PAYLOAD_EXCLUDED_DIRS
import moe.antimony.hoshi.features.sync.http.PAYLOAD_EXCLUDED_FILES
import moe.antimony.hoshi.features.sync.v3.V3SyncEngine
import java.io.File
import java.time.Instant
import java.util.UUID

/** Which "Sync now" engine a simulated install runs. Production defaults to [V3]; [V2] is the rollback path. */
enum class SyncEngine { V2, V3 }

/** Engine-neutral view of one sync pass, so a scenario reads the same for either engine. */
data class SyncOutcome(
    val uploadedPayloads: Int,
    val downloadedPayloads: Int,
    val uploadedBookmarks: Int,
    val downloadedBookmarks: Int,
    val downloadedChatEntries: Int,
    val errors: List<String>,
) {
    val transferredPayloads: Int get() = uploadedPayloads + downloadedPayloads
}

/**
 * One simulated install of the app: its own files directory, [BookRepository], chat history,
 * revision/shelf sidecars, and the production sync surface — the map-layer "Sync now"
 * ([HttpSyncFastSync] + [HttpSyncEngineDispatcher]), the reader's page-turn outbox
 * ([HttpSyncBatchState]), and the bookshelf hooks ([HttpSyncAutoPush]) — all talking to the
 * real test server through the production [HttpSyncKvClient].
 *
 * [close] cancels the hooks' push scope so a late push can never land on a server another
 * test has already reset.
 */
class SyncDevice(
    val name: String,
    val engine: SyncEngine,
    filesDir: File,
    server: SyncTestServer,
    multipartThresholdBytes: Long = 64L * 1024L * 1024L,
    multipartPartSizeBytes: Long = 64L * 1024L * 1024L,
    installationId: String = UUID.randomUUID().toString(),
) : AutoCloseable {
    val repo = BookRepository(filesDir)
    val history = AiChatHistoryStore()
    val codec = HttpSyncPayloadCodec()
    val settings = HttpSyncSettings(server.baseUrl, server.token, useV3Sync = engine == SyncEngine.V3)
    private var cursor: String? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val deletedBooks = HttpSyncDeletedBookStateStore(json)

    private val transportFactory: (HttpSyncSettings) -> HttpSyncKvTransport = { s ->
        HttpSyncKvClient(
            baseUrl = s.baseUrl,
            bearerToken = s.bearerToken,
            ioDispatcher = Dispatchers.IO,
            multipartPartSizeBytes = multipartPartSizeBytes,
            multipartThresholdBytes = multipartThresholdBytes,
        )
    }

    val reconciler = HttpSyncReconciler(
        bookRepository = repo,
        aiHistoryStore = history,
        transportFactory = transportFactory,
        ioDispatcher = Dispatchers.IO,
    )
    val v3 = V3SyncEngine(
        bookRepository = repo,
        aiHistoryStore = history,
        payloadCodec = codec,
        transportFactory = transportFactory,
        ioDispatcher = Dispatchers.IO,
    )
    /** The reader's fire-and-forget push path (page turns, chat replies). */
    val pusher = HttpSyncPusher(bookRepository = repo, transportFactory = transportFactory, ioDispatcher = Dispatchers.IO)
    private val pushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The import/delete/shelf hooks the app fires outside the reader. */
    val autoPush = HttpSyncAutoPush(
        bookRepository = repo,
        pusher = pusher,
        reconciler = reconciler,
        currentSettings = { settings },
        scope = pushScope,
        pushDispatcher = Dispatchers.IO,
    )

    /** The bookmark-map layer every production sync goes through (five-second exchange + maps). */
    val batchState = HttpSyncBatchState(
        bookRepository = repo,
        installationId = installationId,
    )
    private val fastSync = HttpSyncFastSync(
        state = batchState,
        fullCycleRunner = HttpSyncFullCycleRunner(pushScope),
        transportFactory = transportFactory,
    )

    /**
     * One "Sync now", exactly as the settings screen runs it: the map preflight, then the
     * engine selected by [SyncEngine] through [HttpSyncEngineDispatcher], then map publication —
     * including cursor persistence for v2.
     */
    suspend fun sync(): SyncOutcome {
        val result = fastSync.syncNow(settings.copy(lastSyncedAt = cursor)) { reconcileSettings, transport ->
            HttpSyncEngineDispatcher.syncOnce(
                reconciler = reconciler,
                v3Engine = v3,
                settings = reconcileSettings,
                transport = transport,
            )
        }
        result.newLastSyncedAt?.let { cursor = it }
        return SyncOutcome(
            uploadedPayloads = result.uploadedPayloads,
            downloadedPayloads = result.downloadedPayloads,
            uploadedBookmarks = result.uploadedBookmarks,
            downloadedBookmarks = result.downloadedBookmarks,
            downloadedChatEntries = result.downloadedChatEntries,
            errors = result.errors,
        )
    }

    /** A user import: the corpus manga on disk, then the hook the bookshelf fires afterwards. */
    suspend fun importManga(
        title: String = SyncCorpus.MANGA_TITLE,
        pageCount: Int = 3,
        extraBytesPerPage: Int = 0,
        shipCover: Boolean = true,
    ): File = registerImport(
        SyncCorpus.importManga(repo, title, pageCount, extraBytesPerPage, shipCover),
        title,
        ContentType.Mokuro,
    )

    /** A user import: the corpus novel on disk, then the hook the bookshelf fires afterwards. */
    suspend fun importNovel(
        title: String = SyncCorpus.NOVEL_TITLE,
        chapterText: String = SyncCorpus.NOVEL_SENTENCE,
    ): File = registerImport(SyncCorpus.importNovel(repo, title, chapterText), title, ContentType.Epub)

    private suspend fun registerImport(root: File, title: String, contentType: ContentType): File {
        val metadata = repo.loadMetadata(root)
        autoPush.onBookImported(root, title, contentType, metadata?.importedAt, metadata?.syncId)
        awaitPushes()
        return root
    }

    /**
     * Deleting from the shelf, as `BookshelfRepository.deleteBook` does it: stage the tombstone
     * sidecar (the retry path and the re-import guard), remove the directory, push immediately.
     */
    suspend fun deleteBook(entry: BookEntry) {
        val syncId = requireNotNull(entry.metadata.syncId) { "${entry.root} has no sync id" }
        val title = entry.metadata.title ?: syncId
        val contentType = HttpSyncContentType.fromLocal(bookContentType(entry.root))
        val deletedAt = Instant.now().toString()
        deletedBooks.recordDeletedBook(repo.booksDirectory, syncId, HttpSyncDeletedBookRecord(title, contentType, deletedAt))
        repo.deleteBook(entry.root)
        autoPush.onBookDeleted(syncId, title, contentType, deletedAt)
        awaitPushes()
    }

    /** Moving a book onto a shelf (`null` = unshelved), as `BookshelfRepository.moveBooks` does it. */
    suspend fun placeOnShelf(entry: BookEntry, shelfName: String?) {
        val id = entry.metadata.id
        val cleared = repo.loadShelves().map { shelf -> shelf.copy(bookIds = shelf.bookIds.filterNot { it == id }) }
        val shelves = when {
            shelfName == null -> cleared
            cleared.any { it.name == shelfName } ->
                cleared.map { if (it.name == shelfName) it.copy(bookIds = (it.bookIds + id).distinct()) else it }
            else -> cleared + BookShelf(shelfName, listOf(id))
        }
        repo.saveShelves(shelves)
        autoPush.onShelfPlacementChanged(entry.metadata.title, bookContentType(entry.root), entry.metadata.importedAt, shelfName, entry.metadata.syncId)
        awaitPushes()
    }

    /** The shelf this install keeps the book with [syncId] on, or `null` when unshelved. */
    suspend fun shelfNameOf(syncId: String): String? {
        val id = book(syncId).metadata.id
        return repo.loadShelves().firstOrNull { id in it.bookIds }?.name
    }

    /** A ChatGPT reply saved in the manga reader: appended locally, then pushed as the reader does. */
    suspend fun authorChat(entry: BookEntry, chat: AiChatEntry) {
        history.append(entry.root, chat)
        pusher.pushChatEntry(entry.metadata.title ?: requireNotNull(entry.metadata.syncId), chat, settings)
    }

    /**
     * A page turn as the reader persists it: the position is queued into the durable outbox and
     * the next five-second map exchange uploads this install's shard.
     */
    suspend fun turnPage(root: File, title: String, bookmark: Bookmark) {
        repo.saveBookmark(root, bookmark)
        batchState.queueBookmark(root, title, repo.loadMetadata(root)?.syncId)
        batchState.syncMaps(transportFactory(settings))
    }

    /** The reader-open gate: one cheap map exchange before a book is displayed. */
    suspend fun beforeOpen() {
        batchState.syncMaps(transportFactory(settings))
    }

    /** Waits for every fire-and-forget push the hooks have launched so far. */
    suspend fun awaitPushes() {
        pushScope.coroutineContext.job.children.forEach { it.join() }
    }

    suspend fun books(): List<BookEntry> = repo.loadBookEntries()

    suspend fun book(syncId: String): BookEntry =
        books().single { it.metadata.syncId == syncId }

    suspend fun bookOrNull(syncId: String): BookEntry? =
        books().singleOrNull { it.metadata.syncId == syncId }

    /** Every file that travels in the payload, keyed by forward-slash relative path. */
    fun payloadFiles(root: File): Map<String, ByteArray> = root.walkTopDown()
        .filter { it.isFile && it.name !in PAYLOAD_EXCLUDED_FILES }
        .filter { file -> file.relativeTo(root).invariantSeparatorsPath.split('/').dropLast(1).none { it in PAYLOAD_EXCLUDED_DIRS } }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }

    override fun close() {
        pushScope.cancel()
    }
}
