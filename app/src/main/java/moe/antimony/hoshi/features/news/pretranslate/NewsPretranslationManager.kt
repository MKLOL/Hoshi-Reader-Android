package moe.antimony.hoshi.features.news.pretranslate

import android.content.Context
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.ai.AiChatSettingsRepository
import moe.antimony.hoshi.features.ai.ChatModelCatalog
import moe.antimony.hoshi.features.ai.aiChatSettingsRepository
import moe.antimony.hoshi.features.ai.offline.OfflineTranslationSettingsRepository
import moe.antimony.hoshi.features.ai.offline.offlineTranslationSettingsRepository
import moe.antimony.hoshi.features.news.NewsFeedStore
import moe.antimony.hoshi.features.news.NewsTranslationRecord
import moe.antimony.hoshi.features.sync.http.HttpSyncKvClient
import moe.antimony.hoshi.features.sync.http.HttpSyncSettingsRepository
import moe.antimony.hoshi.features.sync.http.httpSyncSettingsRepository
import moe.antimony.hoshi.features.sync.http.syncIdForMetadata
import moe.antimony.hoshi.ui.UiText

/** A pre-translation the user asked for from the News tab. */
data class PretranslationRequest(
    val bookId: String,
    val articleId: String,
    val title: String,
    val config: PretranslationConfig,
)

/**
 * What a job needs from the app. Built directly from the application context rather than through
 * [moe.antimony.hoshi.HoshiAppContainer], whose construction starts sync pollers and other
 * long-lived machinery that a background job must not duplicate.
 */
internal class PretranslationDependencies(
    val bookRepository: BookRepository,
    val aiSettings: AiChatSettingsRepository,
    val offlineSettings: OfflineTranslationSettingsRepository,
    val syncSettings: HttpSyncSettingsRepository,
    val newsStore: NewsFeedStore,
) {
    companion object {
        fun from(context: Context): PretranslationDependencies {
            val appContext = context.applicationContext
            return PretranslationDependencies(
                bookRepository = BookRepository(filesDir = appContext.filesDir),
                aiSettings = appContext.aiChatSettingsRepository(),
                offlineSettings = appContext.offlineTranslationSettingsRepository(),
                syncSettings = appContext.httpSyncSettingsRepository(),
                newsStore = NewsFeedStore(appContext.filesDir),
            )
        }
    }
}

/**
 * Process-wide queue of pre-translation jobs and their states.
 *
 * Jobs are executed by [NewsPretranslationService] one at a time (the cloud clients have no rate
 * limiting and the on-device model is strictly serial). State survives the News tab being closed
 * and reopened because the object outlives any screen; finished states stay until the tab reads
 * them and the store records the outcome.
 */
object NewsPretranslationManager {
    private val _jobs = MutableStateFlow<Map<String, PretranslationJobState>>(emptyMap())
    val jobs: StateFlow<Map<String, PretranslationJobState>> = _jobs

    private val drainMutex = Mutex()
    private val queue = ArrayDeque<PretranslationRequest>()
    private var current: Pair<PretranslationRequest, Job>? = null
    private var draining = false

    /** Replaceable for tests. */
    internal var dependenciesFactory: (Context) -> PretranslationDependencies = PretranslationDependencies::from

    fun enqueue(context: Context, request: PretranslationRequest) {
        val added = synchronized(queue) {
            if (queue.any { it.bookId == request.bookId } || current?.first?.bookId == request.bookId) return@synchronized false
            queue.addLast(request)
            true
        }
        if (!added) return
        _jobs.update { it + (request.bookId to PretranslationJobState.Queued(request.bookId)) }
        NewsPretranslationService.start(context)
    }

    fun cancel(bookId: String) {
        val removed = synchronized(queue) { queue.removeIf { it.bookId == bookId } }
        if (removed) _jobs.update { it + (bookId to PretranslationJobState.Cancelled(bookId)) }
        val running = synchronized(queue) { current?.takeIf { it.first.bookId == bookId } } ?: return
        // The in-flight request cannot be interrupted; tell the UI the stop is on its way.
        _jobs.update { jobs ->
            val state = jobs[bookId] as? PretranslationJobState.Running ?: return@update jobs
            jobs + (bookId to state.copy(cancelling = true))
        }
        running.second.cancel()
    }

    /** Forgets a terminal state so the tab stops showing it. */
    fun dismiss(bookId: String) {
        _jobs.update { jobs -> if (jobs[bookId]?.isActive == false) jobs - bookId else jobs }
    }

    val hasPendingWork: Boolean
        get() = synchronized(queue) { queue.isNotEmpty() || current != null }

    /**
     * Runs queued jobs until the queue is empty. Only one caller drains at a time; a second call
     * returns immediately. [onState] is invoked for every state change of the running job.
     */
    suspend fun drain(context: Context, onState: (PretranslationJobState) -> Unit) {
        drainMutex.withLock {
            if (draining) return
            draining = true
        }
        try {
            while (true) {
                val request = synchronized(queue) { queue.pollFirst() } ?: break
                coroutineScope {
                    val job = launch(Dispatchers.IO) {
                        execute(context, request) { state ->
                            _jobs.update { jobs ->
                                // A cancel request already marked the running state; keep that flag.
                                val cancelling = (jobs[request.bookId] as? PretranslationJobState.Running)?.cancelling == true
                                val next = if (state is PretranslationJobState.Running && cancelling) state.copy(cancelling = true) else state
                                jobs + (request.bookId to next)
                            }
                            onState(state)
                        }
                    }
                    synchronized(queue) { current = request to job }
                    try {
                        job.join()
                    } finally {
                        synchronized(queue) { current = null }
                    }
                }
            }
        } finally {
            drainMutex.withLock { draining = false }
        }
    }

    private suspend fun execute(
        context: Context,
        request: PretranslationRequest,
        publish: (PretranslationJobState) -> Unit,
    ) {
        val appContext = context.applicationContext
        val bookId = request.bookId
        val deps = dependenciesFactory(appContext)
        val sink = LinkedHashMap<String, SentenceTranslation>()
        var plan: PretranslationPlan? = null
        var root: File? = null
        var existingCount = 0
        val label = request.config.engine.label
        val promptId = SentenceBatchPrompt.promptId(request.config.includeExplanations)

        /**
         * Keeps what was already paid for when a job stops early: writes (and, when sync is
         * configured, uploads) the entries in [sink]. A complete sidecar produced with another
         * model or prompt is never replaced by a partial one. Returns how many new entries were saved.
         */
        suspend fun persistPartial(): Int {
            val currentPlan = plan ?: return 0
            val bookRoot = root ?: return 0
            if (sink.size <= existingCount) return 0
            if (SentenceTranslationsWriter.hasSidecarForOtherConfiguration(bookRoot, currentPlan.syncId, label, promptId)) return 0
            // execute() already runs on the IO dispatcher; NonCancellable alone keeps this on the
            // same thread so a cancelled outer job cannot interrupt the write.
            return withContext(NonCancellable) {
                runCatching {
                    val blob = SentenceTranslationsWriter.buildBlob(currentPlan, sink, label, promptId)
                    if (SentenceTranslationsWriter.validationError(blob) != null) return@runCatching 0
                    val bytes = SentenceTranslationsWriter.encode(blob)
                    SentenceTranslationsWriter.write(bookRoot, bytes)
                    val sync = deps.syncSettings.settings.first()
                    if (sync.isConfigured) {
                        runCatching { SentenceTranslationsUploader(HttpSyncKvClient(sync.baseUrl, sync.bearerToken)).upload(currentPlan.syncId, bytes) }
                    }
                    sink.size - existingCount
                }.getOrDefault(0)
            }
        }

        try {
            val entry = deps.bookRepository.loadBookEntry(bookId)
                ?: throw JobFailure(UiText.Resource(R.string.news_pretranslate_error_book_missing))
            root = entry.root
            val syncId = syncIdForMetadata(entry.metadata)
                ?: throw JobFailure(UiText.Resource(R.string.news_pretranslate_error_no_sync_id))
            val config = request.config.withEffectiveBatchSize()
            val runnerPromptId = SentenceBatchPrompt.promptId(config.includeExplanations)
            check(runnerPromptId == promptId)
            val translator: SentenceTranslator = when (val engine = config.engine) {
                is PretranslationEngine.Cloud -> {
                    val provider = ChatModelCatalog.providerForModelId(engine.modelId)
                    val apiKey = deps.aiSettings.apiKey(provider)
                    if (apiKey.isBlank()) {
                        throw JobFailure(UiText.Resource(R.string.news_pretranslate_api_key_missing_format, provider.displayName))
                    }
                    CloudSentenceTranslator(provider, apiKey, engine.modelId)
                }
                is PretranslationEngine.OnDevice -> {
                    // The offline manager always runs the model selected in settings.
                    val active = deps.offlineSettings.settings.first().activeModelId
                    if (active != engine.modelId) {
                        throw JobFailure(UiText.Resource(R.string.news_pretranslate_error_on_device_model_format, engine.modelId))
                    }
                    OnDeviceSentenceTranslator(appContext)
                }
            }
            val builtPlan = withContext(Dispatchers.IO) { PretranslationPlanner.plan(bookId, syncId, entry.root, config) }
            plan = builtPlan
            if (builtPlan.sentences.isEmpty()) throw JobFailure(UiText.Resource(R.string.news_pretranslate_error_no_sentences))
            val existing = SentenceTranslationsWriter.existingTranslations(entry.root, builtPlan, label, promptId)
            existingCount = existing.size
            publish(PretranslationJobState.Running(bookId, existing.size, builtPlan.sentences.size, label))
            val translations = PretranslationRunner(translator, config).run(builtPlan.sentences, existing, sink) { done, total ->
                publish(PretranslationJobState.Running(bookId, done, total, label))
            }
            val blob = SentenceTranslationsWriter.buildBlob(builtPlan, translations, label, promptId)
            SentenceTranslationsWriter.validationError(blob)?.let {
                throw JobFailure(UiText.Resource(R.string.news_pretranslate_error_blob_format, it))
            }
            val bytes = SentenceTranslationsWriter.encode(blob)
            SentenceTranslationsWriter.write(entry.root, bytes)

            var uploaded = false
            val sync = deps.syncSettings.settings.first()
            if (sync.isConfigured) {
                publish(PretranslationJobState.Uploading(bookId))
                runCatching {
                    SentenceTranslationsUploader(HttpSyncKvClient(sync.baseUrl, sync.bearerToken)).upload(syncId, bytes)
                }.onSuccess { uploaded = true }
            }
            deps.newsStore.updateSaved(request.articleId) { saved ->
                saved.copy(
                    translation = NewsTranslationRecord(
                        model = label,
                        sentenceCount = builtPlan.sentences.size,
                        translatedCount = translations.size,
                        completedAt = System.currentTimeMillis(),
                        uploaded = uploaded,
                    ),
                )
            }
            publish(PretranslationJobState.Finished(bookId, translations.size, builtPlan.sentences.size, uploaded, label))
        } catch (cancelled: CancellationException) {
            // The state must reach the UI even if persisting throws under a cancelled job.
            val saved = runCatching { persistPartial() }.getOrDefault(0)
            publish(PretranslationJobState.Cancelled(bookId, savedTranslations = saved))
            throw cancelled
        } catch (failure: JobFailure) {
            publish(PretranslationJobState.Failed(bookId, failure.uiMessage, savedTranslations = runCatching { persistPartial() }.getOrDefault(0)))
        } catch (error: PretranslationRunner.TooManyFailuresException) {
            val detail = error.cause?.message ?: error.message ?: error.javaClass.simpleName
            val saved = runCatching { persistPartial() }.getOrDefault(0)
            publish(PretranslationJobState.Failed(bookId, UiText.Resource(R.string.news_pretranslate_error_requests_failed_format, detail), savedTranslations = saved))
        } catch (error: Exception) {
            val saved = runCatching { persistPartial() }.getOrDefault(0)
            publish(PretranslationJobState.Failed(bookId, UiText.Literal(error.message ?: error.javaClass.simpleName), savedTranslations = saved))
        }
    }

    private class JobFailure(val uiMessage: UiText) : Exception()
}
