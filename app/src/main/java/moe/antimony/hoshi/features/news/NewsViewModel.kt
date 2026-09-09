package moe.antimony.hoshi.features.news

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.ai.AiChatSettingsRepository
import moe.antimony.hoshi.features.ai.ChatModelCatalog
import moe.antimony.hoshi.features.ai.ChatModelOption
import moe.antimony.hoshi.features.ai.offline.LlmModelCatalog
import moe.antimony.hoshi.features.ai.offline.OfflineLlmManager
import moe.antimony.hoshi.features.ai.offline.OfflineTranslationSettingsRepository
import moe.antimony.hoshi.features.news.pretranslate.NewsPretranslationManager
import moe.antimony.hoshi.features.news.pretranslate.PretranslationConfig
import moe.antimony.hoshi.features.news.pretranslate.PretranslationEngine
import moe.antimony.hoshi.features.news.pretranslate.PretranslationEstimate
import moe.antimony.hoshi.features.news.pretranslate.PretranslationJobState
import moe.antimony.hoshi.features.news.pretranslate.PretranslationPlan
import moe.antimony.hoshi.features.news.pretranslate.PretranslationPlanner
import moe.antimony.hoshi.features.news.pretranslate.PretranslationRequest
import moe.antimony.hoshi.features.sync.http.HttpSyncSettingsRepository
import moe.antimony.hoshi.features.sync.http.syncIdForMetadata
import moe.antimony.hoshi.ui.UiText

/** A selectable engine in the pre-translate dialog. */
data class PretranslateModelChoice(
    val engine: PretranslationEngine,
    val label: String,
    val providerName: String?,
    val note: String? = null,
)

data class PretranslateDialogState(
    val article: NewsArticle,
    val saved: SavedNewsArticle? = null,
    val plan: PretranslationPlan? = null,
    val config: PretranslationConfig,
    val estimate: PretranslationEstimate? = null,
    val choices: List<PretranslateModelChoice> = emptyList(),
    val apiKeyMissingFor: String? = null,
    val syncConfigured: Boolean = true,
    val preparing: Boolean = true,
    val error: UiText? = null,
)

data class NewsUiState(
    val feed: NewsFeedState = NewsFeedState(),
    val settings: NewsSettings = NewsSettings(),
    val selectedSourceId: String? = null,
    val jobs: Map<String, PretranslationJobState> = emptyMap(),
    val dialog: PretranslateDialogState? = null,
    val sourcesDialogOpen: Boolean = false,
    val message: UiText? = null,
    /** Book to open once, set after a shared link finished saving. */
    val openBookId: String? = null,
) {
    val visibleArticles: List<NewsArticle>
        get() = feed.articles.filter { selectedSourceId == null || it.sourceId == selectedSourceId }

    fun sourceName(sourceId: String): String = settings.sources.firstOrNull { it.id == sourceId }?.name ?: sourceId

    /** The job for an article's book, if any. */
    fun jobFor(article: NewsArticle): PretranslationJobState? = feed.saved[article.id]?.let { jobs[it.bookId] }
}

internal class NewsViewModel(
    private val appContext: Context,
    private val repository: NewsRepository,
    private val settingsRepository: NewsSettingsRepository,
    private val aiSettings: AiChatSettingsRepository,
    private val offlineSettings: OfflineTranslationSettingsRepository,
    private val syncSettings: HttpSyncSettingsRepository,
    private val bookRepository: moe.antimony.hoshi.epub.BookRepository,
) : ViewModel() {
    private data class Local(
        val selectedSourceId: String? = null,
        val dialog: PretranslateDialogState? = null,
        val sourcesDialogOpen: Boolean = false,
        val message: UiText? = null,
        val openBookId: String? = null,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<NewsUiState> = combine(
        repository.state,
        settingsRepository.settings,
        NewsPretranslationManager.jobs,
        local,
    ) { feed, settings, jobs, local ->
        NewsUiState(
            feed = feed,
            settings = settings,
            selectedSourceId = local.selectedSourceId?.takeIf { id -> settings.enabledSources.any { it.id == id } },
            jobs = jobs,
            dialog = local.dialog,
            sourcesDialogOpen = local.sourcesDialogOpen,
            message = local.message,
            openBookId = local.openBookId,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NewsUiState())

    init {
        viewModelScope.launch {
            repository.load()
            runCatching { repository.reconcileSavedBooks() }
            repository.refreshAll(force = false)
        }
        viewModelScope.launch {
            // A finished job wrote the translation record from the service process context; reload.
            var previous: Map<String, PretranslationJobState> = emptyMap()
            NewsPretranslationManager.jobs.collect { jobs ->
                val newlyFinished = jobs.values.any { it is PretranslationJobState.Finished && previous[it.bookId] != it }
                previous = jobs
                if (newlyFinished) repository.load()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { repository.refreshAll(force = true) }
    }

    fun selectSource(sourceId: String?) = local.update { it.copy(selectedSourceId = sourceId) }

    fun dismissMessage() = local.update { it.copy(message = null) }

    fun save(article: NewsArticle) {
        viewModelScope.launch {
            try {
                val saved = repository.saveArticle(article)
                local.update { it.copy(message = UiText.Resource(R.string.news_saved_to_shelf_format, saved.title)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                local.update { it.copy(message = UiText.Resource(R.string.news_save_failed_format, error.message ?: error.javaClass.simpleName)) }
            }
        }
    }

    /** "Save as article" share target: saves the link as a book and opens it. */
    fun importSharedUrl(url: String) {
        viewModelScope.launch {
            local.update { it.copy(message = UiText.Resource(R.string.news_import_started_format, url)) }
            try {
                val saved = repository.saveSharedUrl(url)
                local.update { it.copy(message = UiText.Resource(R.string.news_saved_to_shelf_format, saved.title), openBookId = saved.bookId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                local.update { it.copy(message = UiText.Resource(R.string.news_save_failed_format, error.message ?: error.javaClass.simpleName)) }
            }
        }
    }

    fun consumeOpenBook() = local.update { it.copy(openBookId = null) }

    fun openSources() = local.update { it.copy(sourcesDialogOpen = true) }
    fun closeSources() = local.update { it.copy(sourcesDialogOpen = false) }

    fun setSourceEnabled(source: NewsSource, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSourceEnabled(source.id, enabled)
            if (enabled) repository.refreshSource(source) else repository.refreshAll(force = false)
        }
    }

    fun addRss(name: String, url: String) {
        viewModelScope.launch {
            val source = settingsRepository.addCustomRss(name, url)
            repository.refreshSource(source)
        }
    }

    fun removeSource(source: NewsSource) {
        viewModelScope.launch {
            settingsRepository.removeCustomSource(source.id)
            repository.refreshAll(force = false)
        }
    }

    /** Opens the pre-translate dialog: saves the article first if needed, then plans and prices it. */
    fun preparePretranslate(article: NewsArticle) {
        viewModelScope.launch {
            val engine = defaultEngine()
            val defaultConfig = PretranslationConfig(engine = engine, includeExplanations = engine !is PretranslationEngine.OnDevice)
                .withEffectiveBatchSize()
            local.update { it.copy(dialog = PretranslateDialogState(article = article, config = defaultConfig)) }
            try {
                val saved = repository.saveArticle(article)
                val entry = bookRepository.loadBookEntry(saved.bookId) ?: error("Book not found")
                val syncId = syncIdForMetadata(entry.metadata) ?: error("No sync id")
                val plan = withContext(Dispatchers.IO) {
                    PretranslationPlanner.plan(saved.bookId, syncId, entry.root, defaultConfig)
                }
                val choices = modelChoices()
                val syncConfigured = syncSettings.settings.first().isConfigured
                local.update { state ->
                    val dialog = state.dialog?.takeIf { it.article.id == article.id } ?: return@update state
                    state.copy(
                        dialog = dialog.copy(
                            saved = saved,
                            plan = plan,
                            estimate = plan.estimate,
                            choices = choices,
                            apiKeyMissingFor = apiKeyMissingFor(defaultConfig.engine),
                            syncConfigured = syncConfigured,
                            preparing = false,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                local.update { state ->
                    val dialog = state.dialog?.takeIf { it.article.id == article.id } ?: return@update state
                    state.copy(dialog = dialog.copy(preparing = false, error = UiText.Resource(R.string.news_save_failed_format, error.message ?: error.javaClass.simpleName)))
                }
            }
        }
    }

    fun updatePretranslateConfig(transform: (PretranslationConfig) -> PretranslationConfig) {
        // Apply the change and its estimate atomically against the latest dialog, then fill in the
        // key check (which suspends) only if the engine is still the one it was computed for.
        var engine: PretranslationEngine? = null
        local.update { state ->
            val current = state.dialog ?: return@update state
            // Reset to the default batch size so the engine's own effective size applies below.
            var config = transform(current.config.copy(sentencesPerRequest = PretranslationConfig.DEFAULT_SENTENCES_PER_REQUEST)).withEffectiveBatchSize()
            // On-device models are only ever asked for a translation.
            if (config.engine is PretranslationEngine.OnDevice) config = config.copy(includeExplanations = false)
            engine = config.engine
            val estimate = current.plan?.let { PretranslationPlanner.estimate(it.sentences, config) }
            state.copy(dialog = current.copy(config = config, estimate = estimate))
        }
        val checked = engine ?: return
        viewModelScope.launch {
            val missing = apiKeyMissingFor(checked)
            local.update { state ->
                val current = state.dialog?.takeIf { it.config.engine == checked } ?: return@update state
                state.copy(dialog = current.copy(apiKeyMissingFor = missing))
            }
        }
    }

    fun dismissPretranslate() = local.update { it.copy(dialog = null) }

    fun startPretranslate() {
        val dialog = local.value.dialog ?: return
        val saved = dialog.saved ?: return
        NewsPretranslationManager.enqueue(
            appContext,
            PretranslationRequest(bookId = saved.bookId, articleId = saved.articleId, title = saved.title, config = dialog.config),
        )
        local.update { it.copy(dialog = null) }
    }

    fun cancelPretranslate(bookId: String) = NewsPretranslationManager.cancel(bookId)

    fun dismissJob(bookId: String) = NewsPretranslationManager.dismiss(bookId)

    private suspend fun defaultEngine(): PretranslationEngine {
        val offline = offlineSettings.settings.first()
        if (offline.useOnDeviceTranslation && isOnDeviceAvailable(offline.activeModelId)) {
            return PretranslationEngine.OnDevice(offline.activeModelId)
        }
        return PretranslationEngine.Cloud(aiSettings.settings.first().model)
    }

    private suspend fun modelChoices(): List<PretranslateModelChoice> {
        val configured = aiSettings.settings.first().model
        val catalog = ChatModelCatalog.models.map { option -> option.toChoice() }
        val custom = if (ChatModelCatalog.models.none { it.id == configured }) {
            listOf(
                PretranslateModelChoice(
                    engine = PretranslationEngine.Cloud(configured),
                    label = configured,
                    providerName = ChatModelCatalog.providerForModelId(configured).displayName,
                    note = appContext.getString(R.string.news_pretranslate_custom_model),
                ),
            )
        } else emptyList()
        // The offline manager always runs the model selected in settings, so only that one is offered.
        val activeId = offlineSettings.settings.first().activeModelId
        val onDevice = LlmModelCatalog.byId(activeId)
            ?.takeIf { OfflineLlmManager.isDownloaded(appContext, it) }
            ?.let { model ->
                PretranslateModelChoice(
                    engine = PretranslationEngine.OnDevice(model.id),
                    label = appContext.getString(R.string.news_pretranslate_on_device_format, appContext.getString(model.displayNameRes)),
                    providerName = null,
                )
            }
        return custom + catalog + listOfNotNull(onDevice)
    }

    private fun ChatModelOption.toChoice() = PretranslateModelChoice(
        engine = PretranslationEngine.Cloud(id),
        label = displayName,
        providerName = provider.displayName,
        note = note,
    )

    private fun isOnDeviceAvailable(modelId: String): Boolean =
        LlmModelCatalog.byId(modelId)?.let { OfflineLlmManager.isDownloaded(appContext, it) } == true

    private suspend fun apiKeyMissingFor(engine: PretranslationEngine): String? = when (engine) {
        is PretranslationEngine.Cloud -> {
            val provider = ChatModelCatalog.providerForModelId(engine.modelId)
            provider.displayName.takeIf { aiSettings.apiKey(provider).isBlank() }
        }
        is PretranslationEngine.OnDevice -> null
    }
}
