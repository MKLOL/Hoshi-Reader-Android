package moe.antimony.hoshi.features.sync.v3

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.ai.AiChatSettingsRepository
import moe.antimony.hoshi.features.sync.http.HttpSyncBookLocks
import moe.antimony.hoshi.features.sync.http.HttpSyncKvClient
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings

/**
 * Public entry point for the v3 sync algorithm.
 *
 * Design contract: see `docs/SYNC_V3_SPEC.md`. **Not hooked up.** The
 * production sync still runs through [moe.antimony.hoshi.features.sync.http.HttpSyncReconciler].
 * v3 ships as parallel code with its own tests until the user gives the
 * go-ahead to swap.
 *
 * Algorithm (four steps):
 *   1. snapshot local state via [V3LocalState].
 *   2. snapshot remote state via [V3RemoteState].
 *   3. compute a deterministic plan via [V3Planner].
 *   4. execute the plan via [V3Executor], collecting per-action results + errors.
 */
class V3SyncEngine(
    private val bookRepository: BookRepository,
    aiHistoryStore: AiChatHistoryStore = AiChatHistoryStore(),
    aiSettingsRepository: AiChatSettingsRepository? = null,
    private val payloadCodec: HttpSyncPayloadCodec = HttpSyncPayloadCodec(),
    bookLocks: HttpSyncBookLocks = HttpSyncBookLocks(),
    private val transportFactory: (HttpSyncSettings) -> HttpSyncKvTransport = { settings ->
        HttpSyncKvClient(settings.baseUrl, settings.bearerToken)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val localState = V3LocalState(
        bookRepository = bookRepository,
        aiHistoryStore = aiHistoryStore,
        aiSettingsRepository = aiSettingsRepository,
    )
    private val remoteState = V3RemoteState()
    private val planner = V3Planner()
    private val pushOps = V3PushOps(
        bookRepository = bookRepository,
        aiHistoryStore = aiHistoryStore,
        aiSettingsRepository = aiSettingsRepository,
        payloadCodec = payloadCodec,
        bookLocks = bookLocks,
    )
    private val executor = V3Executor(
        bookRepository = bookRepository,
        aiHistoryStore = aiHistoryStore,
        payloadCodec = payloadCodec,
        pushOps = pushOps,
    )

    /**
     * Run one sync pass. See spec for ordering + error semantics.
     */
    suspend fun syncOnce(
        settings: HttpSyncSettings,
        onProgress: suspend (V3Progress) -> Unit = {},
    ): V3SyncResult = withContext(ioDispatcher) {
        require(settings.isConfigured) { "HTTP sync is not configured." }
        val transport = transportFactory(settings)

        onProgress(V3Progress(V3Phase.ReadingLocal, "Reading local books"))
        val local = localState.read()

        onProgress(V3Progress(V3Phase.ListingRemote, "Listing remote state"))
        val remoteResult = remoteState.read(transport) { progress -> onProgress(progress) }

        onProgress(V3Progress(V3Phase.Planning, "Computing plan"))
        val plan = planner.compute(local, remoteResult.snapshot)

        // Per-key remote-listing errors (decoding failures, etc) accumulate alongside
        // any per-action executor errors so the UI surfaces them in one place.
        val executed = executor.run(plan, transport, onProgress)
        executed.copy(errors = remoteResult.errors + executed.errors)
    }
}
