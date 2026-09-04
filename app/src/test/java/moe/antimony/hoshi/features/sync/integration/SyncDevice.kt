package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.sync.http.HttpSyncAutoPush
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
 * revision/shelf sidecars, and the production engines and push hooks — all talking to the real
 * test server through the production [HttpSyncKvClient].
 */
class SyncDevice(
    val name: String,
    val engine: SyncEngine,
    filesDir: File,
    server: SyncTestServer,
    multipartThresholdBytes: Long = 64L * 1024L * 1024L,
    multipartPartSizeBytes: Long = 64L * 1024L * 1024L,
) {
    val repo = BookRepository(filesDir)
    val history = AiChatHistoryStore()
    val codec = HttpSyncPayloadCodec()
    val settings = HttpSyncSettings(server.baseUrl, server.token, useV3Sync = engine == SyncEngine.V3)
    private var cursor: String? = null

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

    /** One "Sync now", exactly as the settings screen runs it, including cursor persistence for v2. */
    suspend fun sync(): SyncOutcome = when (engine) {
        SyncEngine.V2 -> {
            val result = reconciler.syncOnce(settings.copy(lastSyncedAt = cursor))
            result.newLastSyncedAt?.let { cursor = it }
            SyncOutcome(
                uploadedPayloads = result.uploadedPayloads,
                downloadedPayloads = result.downloadedPayloads,
                uploadedBookmarks = result.uploadedBookmarks,
                downloadedBookmarks = result.downloadedBookmarks,
                downloadedChatEntries = result.downloadedChatEntries,
                errors = result.errors,
            )
        }
        SyncEngine.V3 -> {
            val result = v3.syncOnce(settings)
            SyncOutcome(
                uploadedPayloads = result.pushed.payloads,
                downloadedPayloads = result.applied.payloads,
                uploadedBookmarks = result.pushed.bookmarks,
                downloadedBookmarks = result.applied.bookmarks,
                downloadedChatEntries = result.applied.chatEntries,
                errors = result.errors.map { e -> listOfNotNull(e.syncId, e.action).joinToString(":") + ": " + e.message },
            )
        }
    }

    /** Waits for every fire-and-forget push the hooks launched. */
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
}
