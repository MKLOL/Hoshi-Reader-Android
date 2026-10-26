package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.ui.theme.PlatformReaderUi
import moe.antimony.hoshi.ui.theme.currentLargeScreenUiScale
import moe.antimony.hoshi.ui.theme.currentPopupReadability
import moe.antimony.hoshi.features.usage.ReaderUsageSession
import moe.antimony.hoshi.features.usage.UsageContentType
import moe.antimony.hoshi.features.usage.UsageLookupSource
import moe.antimony.hoshi.features.usage.logLookup
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.webkit.WebView
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.R
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.HighlightColor
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.ReaderHighlight
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.features.audio.AudioRequestHandler
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.audio.WordAudioPlayer
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.followInPopup
import moe.antimony.hoshi.features.ai.AiChatPopupView
import moe.antimony.hoshi.features.ai.AiChatUiState
import moe.antimony.hoshi.features.ai.ChatModelCatalog
import moe.antimony.hoshi.features.ai.CloudChat
import moe.antimony.hoshi.features.ai.EpubTranslationStore
import moe.antimony.hoshi.features.ai.aiChatSettingsRepository
import moe.antimony.hoshi.features.ai.buildAiChatDictionaryLookup
import moe.antimony.hoshi.features.ai.offline.OfflineLlmManager
import moe.antimony.hoshi.features.ai.offline.offlineTranslationSettingsRepository
import moe.antimony.hoshi.features.anki.AnkiViewModel
import moe.antimony.hoshi.features.dictionary.DictionaryImageRequestHandler
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.dictionary.LookupPopupAndroidStack
import moe.antimony.hoshi.features.dictionary.LookupPopupAssets
import moe.antimony.hoshi.features.dictionary.LookupPopupHtml
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupOptions
import moe.antimony.hoshi.features.dictionary.PopupSelectionEInkStyle
import moe.antimony.hoshi.features.dictionary.closeChildPopupsForScrolledParent
import moe.antimony.hoshi.features.dictionary.clearPopupSelectionHighlights
import moe.antimony.hoshi.features.dictionary.createLookupPopupItem
import moe.antimony.hoshi.features.dictionary.dismissPopupAt
import moe.antimony.hoshi.features.dictionary.openPopupExternalLink
import moe.antimony.hoshi.features.dictionary.withLookupPopupVisualOptions
import moe.antimony.hoshi.features.sasayaki.BookSasayakiPlaybackRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiAudioRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRange
import moe.antimony.hoshi.features.sasayaki.SasayakiPlayer
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.sync.http.syncIdForMetadata
import moe.antimony.hoshi.features.sasayaki.SasayakiSheet
import kotlin.math.roundToInt
import moe.antimony.hoshi.features.statistics.statisticsZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderWebView(
    book: EpubBook,
    bookRoot: File? = null,
    initialChapterIndex: Int = 0,
    initialProgress: Double = 0.0,
    readerSettings: ReaderSettings = ReaderSettings(),
    onReaderSettingsChange: (ReaderSettings) -> Unit = {},
    onReaderKeyEventHandlerChange: (((KeyEvent) -> Boolean)?) -> Unit = {},
    onSaveBookmark: (chapterIndex: Int, progress: Double, statistics: List<ReadingStatistics>?) -> Unit = { _, _, _ -> },
    /** Captures a scroll action now and returns its deferred persistence, without refreshing its time. */
    onPrepareBookmarkSave: ((chapterIndex: Int, progress: Double) -> (() -> Unit)?)? = null,
    /** Persists statistics alone, for leaving or backgrounding the reader without a new bookmark. */
    onSaveStatistics: (statistics: List<ReadingStatistics>) -> Unit = {},
    onFlushAutoSyncExport: () -> Unit = {},
    /** Starts the foreground ッツ import with this reader still shown; the route reloads it only on a change. */
    onForegroundAutoSyncImport: () -> Unit = {},
    onPrepareSessionReloadChange: (((() -> Unit)?) -> Unit) = {},
    /** Receives a query for whether this reader's audiobook is playing; `null` when it leaves. */
    onAudiobookPlayingQueryChange: (((() -> Boolean)?) -> Unit) = {},
    onTextSelected: (ReaderSelectionData) -> Int? = { null },
    onClose: () -> Unit,
    /** Opens sentence mode for this book; `null` hides the menu entry. */
    onOpenSentenceMode: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) = PlatformReaderUi {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val scope = rememberCoroutineScope()
    // Dictionary queries share a monitor with rebuilds, so they run off the main thread and a
    // dismissal drops a result that arrives late. Selection and redirect lookups are independent.
    val selectionLookups = remember(scope) { ReaderLookupRunner(scope) }
    val redirectLookups = remember(scope) { ReaderLookupRunner(scope) }
    val fontManager = appContainer.readerFontManager
    val readerImageResourceBridge = remember(book, fontManager) {
        ReaderWebResourceBridge(book, fontManager)
    }
    val dictionaryRepository = appContainer.dictionaryRepository
    val dictionarySettingsRepository = appContainer.dictionarySettingsRepository
    val audioSettingsRepository = appContainer.audioSettingsRepository
    val sasayakiSettingsRepository = appContainer.sasayakiSettingsRepository
    val bookRepository = appContainer.bookRepository
    val aiSettingsRepository = remember { context.applicationContext.aiChatSettingsRepository() }
    val aiSettings by aiSettingsRepository.settings.collectAsState(initial = null)
    val offlineTranslationRepository = remember {
        context.applicationContext.offlineTranslationSettingsRepository()
    }
    val offlineTranslationSettings by offlineTranslationRepository.settings.collectAsState(initial = null)
    var sasayakiSettings by remember { mutableStateOf(SasayakiSettings()) }
    var sasayakiMatchData by remember(bookRoot) { mutableStateOf<SasayakiMatchData?>(null) }
    LaunchedEffect(bookRoot, bookRepository) {
        sasayakiMatchData = bookRoot?.let { bookRepository.loadSasayakiMatch(it) }
    }
    var sentenceTranslationSyncId by remember(bookRoot) { mutableStateOf<String?>(null) }
    var sentenceTranslationPopup by remember(bookRoot) { mutableStateOf<AiChatUiState?>(null) }
    var sentenceTranslationRequest by remember(bookRoot) { mutableStateOf<String?>(null) }
    // Requests outlive the popup (see AiChatRequests); this is the one the popup shows.
    val aiRequests = appContainer.aiChatRequests
    var sentenceTranslationRequestId by remember(bookRoot) { mutableStateOf<Long?>(null) }
    var sentenceTranslationRetry by remember(bookRoot) { mutableStateOf<(() -> Unit)?>(null) }
    val translationSettingsLoading = stringResource(R.string.ai_translation_settings_loading)
    val translationConfigurationMissing = stringResource(R.string.ai_translation_configuration_missing)
    val translationFailed = stringResource(R.string.ai_translation_failed)
    val epubPretranslationDebug = stringResource(R.string.ai_translation_epub_pretranslated_debug)
    LaunchedEffect(bookRoot, book, readerSettings.showSentenceTranslations, bookRepository) {
        sentenceTranslationSyncId = null
        val root = bookRoot ?: return@LaunchedEffect
        if (!readerSettings.showSentenceTranslations) return@LaunchedEffect
        val syncId = withContext(Dispatchers.IO) {
            val metadata = bookRepository.loadMetadata(root) ?: return@withContext null
            val resolved = syncIdForMetadata(metadata) ?: return@withContext null
            resolved.takeIf { EpubTranslationStore.preload(root, resolved, book.spineCount) }
        }
        sentenceTranslationSyncId = syncId
    }
    var highlights by remember(bookRoot) {
        mutableStateOf<List<ReaderHighlight>?>(if (bookRoot == null) emptyList() else null)
    }
    LaunchedEffect(bookRoot, bookRepository) {
        highlights = if (bookRoot != null) {
            bookRepository.loadHighlights(bookRoot)
        } else {
            emptyList()
        }
    }
    var sasayakiPlaybackData by remember(bookRoot) { mutableStateOf<SasayakiPlaybackData?>(null) }
    var isSasayakiPlaybackLoaded by remember(bookRoot) { mutableStateOf(bookRoot == null) }
    LaunchedEffect(bookRoot, bookRepository) {
        isSasayakiPlaybackLoaded = bookRoot == null
        sasayakiPlaybackData = bookRoot?.let { bookRepository.loadSasayakiPlayback(it) }
        isSasayakiPlaybackLoaded = true
    }
    val sasayakiAudioRepository = remember(bookRoot) { bookRoot?.let(::SasayakiAudioRepository) }
    val sasayakiCoverFile = remember(bookRoot, book.coverHref) {
        resolveBookCoverFile(bookRoot, book.coverHref)
    }
    var sasayakiPlayer by remember { mutableStateOf<SasayakiPlayer?>(null) }
    var pendingSasayakiCue by remember(book) { mutableStateOf<PendingSasayakiCue?>(null) }
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val systemDarkTheme = isSystemInDarkTheme()
    val clampedInitialIndex = initialChapterIndex.coerceIn(0, book.chapters.lastIndex)
    val stateHolder = remember(book) {
        ReaderWebViewStateHolder(
            initialSettings = readerSettings,
            initialPosition = ReaderChapterPosition(
                index = clampedInitialIndex,
                progress = initialProgress.coerceIn(0.0, 1.0),
            ),
        )
    }
    LaunchedEffect(readerSettings) {
        stateHolder.syncSettings(readerSettings)
    }
    var dictionarySettings by remember { mutableStateOf(DictionarySettings()) }
    var audioSettings by remember { mutableStateOf(AudioSettings()) }
    var dictionaryStyles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(dictionarySettingsRepository) {
        dictionarySettingsRepository.settings.collect { settings ->
            dictionarySettings = settings
        }
    }
    LaunchedEffect(dictionaryRepository) {
        dictionaryStyles = withContext(Dispatchers.IO) {
            runCatching {
                dictionaryRepository.ensureLookupQueryReady()
                dictionaryRepository.dictionaryStyles()
            }.getOrDefault(emptyMap())
        }
    }
    LaunchedEffect(audioSettingsRepository) {
        audioSettingsRepository.settings.collect { settings ->
            audioSettings = settings
        }
    }
    LaunchedEffect(sasayakiSettingsRepository) {
        sasayakiSettingsRepository.settings.collect { settings ->
            sasayakiSettings = settings
        }
    }
    /** The popup stops showing its request, which carries on in the background. */
    fun detachSentenceTranslationRequest() {
        sentenceTranslationRequestId?.let(aiRequests::hide)
        sentenceTranslationRequestId = null
    }
    fun askLiveSentenceTranslation(sentence: String) {
        sentenceTranslationRequest = sentence
        sentenceTranslationRetry = { askLiveSentenceTranslation(sentence) }
        val settings = aiSettings
        val offlineSettings = offlineTranslationSettings
        if (settings == null || offlineSettings == null) {
            detachSentenceTranslationRequest()
            sentenceTranslationPopup = AiChatUiState.Failed(sentence, translationSettingsLoading)
            return
        }
        val useOnDevice = offlineSettings.useOnDeviceTranslation
        if (!useOnDevice && !settings.isConfigured) {
            detachSentenceTranslationRequest()
            sentenceTranslationPopup = AiChatUiState.Failed(sentence, translationConfigurationMissing)
            return
        }
        sentenceTranslationPopup = AiChatUiState.Loading(sentence, onDevice = useOnDevice)
        val appContext = context.applicationContext
        val lookupSettings = dictionarySettings
        // Closing the popup leaves the request running; its reply still reaches the history.
        val requestId = aiRequests.start(
            bookRoot = bookRoot,
            key = "${if (useOnDevice) "on-device" else settings.model}\n$sentence",
            bubbleText = sentence,
            onDevice = useOnDevice,
            failureMessage = { translationFailed },
        ) {
            val settings = aiSettingsRepository.settings.first()
            coroutineScope {
                val dictionaryLookup = async(Dispatchers.IO) {
                    buildAiChatDictionaryLookup(sentence, lookupSettings)
                }
                val response: String
                val model: String
                val debugInfo: String?
                if (useOnDevice) {
                    val value = OfflineLlmManager.translate(
                        appContext = appContext,
                        instruction = settings.promptText,
                        japaneseText = sentence,
                    )
                    response = value.text
                    model = value.modelId
                    debugInfo = value.debugLine()
                } else {
                    response = CloudChat.complete(
                        provider = ChatModelCatalog.providerForModelId(settings.model),
                        apiKey = settings.apiKey,
                        model = settings.model,
                        prompt = settings.promptText,
                        bubbleText = sentence,
                    )
                    model = settings.model
                    debugInfo = null
                }
                AiChatEntry(
                    bubbleText = sentence,
                    prompt = settings.promptText,
                    model = model,
                    response = response,
                    timestampSeconds = bookRepository.currentAppleReferenceDateSeconds(),
                    dictionaryLookup = dictionaryLookup.await(),
                    debugInfo = debugInfo,
                )
            }
        }
        if (sentenceTranslationRequestId != requestId) detachSentenceTranslationRequest()
        sentenceTranslationRequestId = requestId
    }
    // The popup follows the request it shows, and takes its reply or error when it ends.
    LaunchedEffect(sentenceTranslationRequestId) {
        val id = sentenceTranslationRequestId ?: return@LaunchedEffect
        aiRequests.followInPopup(id, isShown = { sentenceTranslationRequestId == id }) { state, ended ->
            if (ended) sentenceTranslationRequestId = null
            if (state != null) {
                sentenceTranslationPopup = state
            } else if (sentenceTranslationPopup is AiChatUiState.Loading) {
                sentenceTranslationPopup = null
            }
        }
    }
    // Leaving the reader with the popup open sends its request to the background too.
    DisposableEffect(bookRoot) {
        onDispose { sentenceTranslationRequestId?.let(aiRequests::hide) }
    }
    val effectiveSettings = stateHolder.effectiveSettings
    val readerPosition = stateHolder.readerPosition
    val lookupPopups = stateHolder.lookupPopups
    val readerIframePopupSupported = remember { ReaderLookupPopupWebBridge.isSupported() }
    val lookupTouchTarget = remember { ReaderLookupTouchTarget() }
    val lookupTouchDensity = LocalDensity.current.density
    var readerPopupHistories by remember { mutableStateOf<Map<String, ReaderPopupHistoryCounts>>(emptyMap()) }
    var rootSelectionHighlight by remember { mutableStateOf<ReaderRootSelectionHighlight?>(null) }
    var fullscreenImage by remember { mutableStateOf<ReaderFullscreenImage?>(null) }
    val ankiViewModel: AnkiViewModel = viewModel(
        factory = remember(appContainer) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AnkiViewModel(appContainer.ankiRepository) as T
            }
        },
    )
    val ankiUiState by ankiViewModel.uiState.collectAsState()
    val readerUiScale = currentLargeScreenUiScale()
    val popupReadability = currentPopupReadability()
    val cardStyle = effectiveSettings.dictionaryCardStyle(popupReadability)
    val popupAssets = remember(context) { LookupPopupAssets.load(context) }
    val readerPopupBridgeHolder = remember { ReaderLookupPopupBridgeCallbackHolder() }
    val popupDarkMode = effectiveSettings.usesDarkInterface(systemDarkTheme)
    val readerPopupIframeDocument = remember(
        readerUiScale, cardStyle,
        dictionaryStyles,
        dictionarySettings,
        effectiveSettings.popupSwipeToDismiss,
        effectiveSettings.popupSwipeThreshold,
        effectiveSettings.popupReducedMotionScrolling,
        effectiveSettings.popupReducedMotionScrollPercent,
        effectiveSettings.popupReducedMotionSwipeThreshold,
        popupDarkMode,
        effectiveSettings.eInkMode,
        audioSettings,
        ankiUiState.popupSettings,
        fontManager,
        effectiveSettings.popupScale,
        effectiveSettings.dictionaryCard,
    ) {
        LookupPopupHtml.renderIframeDocument(
            assets = null,
            dictionaryStyles = dictionaryStyles,
            settings = dictionarySettings,
            swipeToDismiss = effectiveSettings.popupSwipeToDismiss,
            swipeThreshold = effectiveSettings.popupSwipeThreshold,
            reducedMotionScrolling = effectiveSettings.popupReducedMotionScrolling,
            reducedMotionScrollPercent = effectiveSettings.popupReducedMotionScrollPercent,
            reducedMotionSwipeThreshold = effectiveSettings.popupReducedMotionSwipeThreshold,
            darkMode = popupDarkMode,
            eInkMode = effectiveSettings.eInkMode,
            audioSettings = audioSettings,
            ankiSettings = ankiUiState.popupSettings,
            fontFaceCss = fontManager.popupFontFaceCss(),
            popupScale = cardStyle.scale,
            fontScale = cardStyle.fontScale,
            uiScale = readerUiScale,
        )
    }
    val currentReaderPopupIframeDocument = rememberUpdatedState(readerPopupIframeDocument)
    val readerPopupIframeUrl = remember(readerPopupIframeDocument) {
        readerLookupPopupIframeUrl(readerPopupIframeDocument.hashCode())
    }
    LaunchedEffect(webView, readerPopupIframeUrl, readerIframePopupSupported) {
        if (!readerIframePopupSupported) return@LaunchedEffect
        webView?.evaluateJavascript(
            """
                (function() {
                  var iframeUrl = ${readerJavaScriptStringLiteral(readerPopupIframeUrl)};
                  window.__hoshiReaderPopupIframeUrl = iframeUrl;
                  if (window.hoshiReaderPopupHost) {
                    window.hoshiReaderPopupHost.preloadIdleRootFrame(iframeUrl);
                  }
                })();
            """.trimIndent(),
            null,
        )
    }
    val readerPopupResourceHandler = remember(context, popupAssets, fontManager) {
        ReaderLookupPopupResourceHandler(
            context = context.applicationContext,
            assets = popupAssets,
            fontManager = fontManager,
            audioRequestHandler = AudioRequestHandler(LocalAudioRepository.fromContext(context.applicationContext)),
            imageRequestHandler = DictionaryImageRequestHandler(),
            iframeDocument = { currentReaderPopupIframeDocument.value },
        )
    }
    val themedLookupPopups = remember(
        lookupPopups,
        popupDarkMode,
        effectiveSettings.eInkMode,
        audioSettings,
        effectiveSettings.popupScale,
        effectiveSettings.dictionaryCard,
    ) {
        lookupPopups.withLookupPopupVisualOptions(
            darkMode = popupDarkMode,
            eInkMode = effectiveSettings.eInkMode,
            audioSettings = audioSettings,
            popupScale = effectiveSettings.popupScale,
            cardStyle = effectiveSettings.dictionaryCard,
        )
    }
    val showReaderMenu = stateHolder.showReaderMenu
    val showAppearance = stateHolder.showAppearance
    val showChapters = stateHolder.showChapters
    val showHighlights = stateHolder.showHighlights
    val showSasayaki = stateHolder.showSasayaki
    val showStatistics = stateHolder.showStatistics
    val focusMode = stateHolder.focusMode
    val sasayakiWasPausedByLookup = stateHolder.sasayakiWasPausedByLookup
    var persistedStatistics by remember(bookRoot) {
        mutableStateOf<List<ReadingStatistics>?>(if (bookRoot == null) emptyList() else null)
    }
    // The book's metadata id for the usage log, loaded with the statistics so the usage
    // session exists before the tracker can start its first reading span.
    var usageBookId by remember(bookRoot) { mutableStateOf<String?>(null) }
    var usageBookResolved by remember(bookRoot) { mutableStateOf(bookRoot == null) }
    LaunchedEffect(bookRoot, bookRepository) {
        persistedStatistics = if (bookRoot != null) {
            // Start from the previous instance's final save when this reader replaced it within
            // that write (see BookRepository.trackStatisticsSave).
            bookRepository.awaitPendingStatisticsSaves(bookRoot)
            usageBookId = bookRepository.loadMetadata(bookRoot)?.id ?: bookRoot.name
            usageBookResolved = true
            bookRepository.loadStatistics(bookRoot)
        } else {
            emptyList()
        }
    }
    // This opening's usage log: reading spans, page turns and lookups under one session id.
    val usageSession = remember(bookRoot, usageBookResolved, usageBookId) {
        if (usageBookResolved) {
            ReaderUsageSession(appContainer.usageLog, usageBookId, book.title, UsageContentType.Epub)
        } else {
            null
        }
    }
    val currentUsageSession = rememberUpdatedState(usageSession)
    val currentResetHour = rememberUpdatedState(readerSettings.statisticsDayResetHour)
    val currentStatisticsZone = rememberUpdatedState(readerSettings.statisticsZone())
    val statisticsTracker = remember(bookRoot, book.title, persistedStatistics) {
        persistedStatistics?.let { statistics ->
            ReaderStatisticsTracker(
                title = book.title,
                initialStatistics = statistics,
                enabled = true,
                device = appContainer.deviceIdentity,
                onTrackingChanged = { reading -> currentUsageSession.value?.readingChanged(reading) },
                resetHour = { currentResetHour.value },
                statisticsZone = { currentStatisticsZone.value },
            )
        }
    }
    DisposableEffect(usageSession) {
        usageSession?.opened(page = null)
        onDispose { usageSession?.closed(page = null) }
    }
    var statisticsState by remember(statisticsTracker) { mutableStateOf(statisticsTracker?.state) }
    var resumeStatisticsTrackingOnStart by remember(statisticsTracker) { mutableStateOf(false) }
    var sessionRetired by remember { mutableStateOf(false) }
    fun currentDisplayedCharacter(): Int =
        book.characterCountAt(
            stateHolder.readerPosition.displayedPosition.index,
            stateHolder.readerPosition.displayedPosition.progress,
        )
    fun currentChapterEndCharacter(): Int {
        val index = stateHolder.readerPosition.displayedPosition.index
        return if (index < book.chapters.lastIndex) {
            book.characterCountAt(index + 1, 0.0)
        } else {
            book.bookInfo.characterCount
        }
    }
    fun syncStatisticsState() {
        statisticsState = statisticsTracker?.state
    }
    fun recordStatisticsAtDisplayedPosition() {
        if (sessionRetired) return
        statisticsTracker?.update(currentDisplayedCharacter())
        syncStatisticsState()
    }
    fun resetStatisticsBaseline() {
        statisticsTracker?.resetBaseline(currentDisplayedCharacter())
        syncStatisticsState()
    }
    fun statisticsForSave(): List<ReadingStatistics>? {
        if (sessionRetired) return null
        recordStatisticsAtDisplayedPosition()
        return statisticsTracker?.statisticsForPersistenceOrNull()
    }
    val pendingContinuousSave = remember(bookRoot, book) { java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null) }
    DisposableEffect(pendingContinuousSave) {
        onDispose { pendingContinuousSave.getAndSet(null)?.invoke() }
    }
    fun saveReaderPosition(position: ReaderChapterPosition, statistics: List<ReadingStatistics>? = statisticsForSave()) {
        if (sessionRetired) return
        pendingContinuousSave.getAndSet(null)?.invoke()
        onSaveBookmark(position.index, position.progress, statistics)
    }
    fun saveCurrentDisplayedPosition() {
        saveReaderPosition(stateHolder.readerPosition.displayedPosition)
    }
    fun jumpToPositionWithHistory(position: ReaderChapterPosition, fragment: String? = null) {
        val statistics = statisticsForSave()
        val savedPosition = stateHolder.jumpToWithHistory(position, fragment)
        resetStatisticsBaseline()
        saveReaderPosition(savedPosition, statistics)
    }
    fun navigateJumpBack() {
        val statistics = statisticsForSave()
        val savedPosition = stateHolder.navigateBackInJumpHistory() ?: return
        resetStatisticsBaseline()
        saveReaderPosition(savedPosition, statistics)
    }
    fun navigateJumpForward() {
        val statistics = statisticsForSave()
        val savedPosition = stateHolder.navigateForwardInJumpHistory() ?: return
        resetStatisticsBaseline()
        saveReaderPosition(savedPosition, statistics)
    }
    fun currentLoadChapter(): moe.antimony.hoshi.epub.EpubChapter =
        book.chapters[stateHolder.readerPosition.loadPosition.index.coerceIn(0, book.chapters.lastIndex)]
    fun persistHighlights(nextHighlights: List<ReaderHighlight>) {
        highlights = nextHighlights
        val root = bookRoot ?: return
        scope.launch {
            bookRepository.saveHighlights(root, nextHighlights)
        }
    }
    fun addHighlight(color: HighlightColor, id: String, creation: ReaderHighlightCreationResult) {
        val chapter = currentLoadChapter()
        val info = book.bookInfo.chapterInfo[chapter.href] ?: return
        val highlight = ReaderHighlight(
            id = id,
            character = info.currentTotal + creation.start,
            offset = creation.offset,
            text = creation.text,
            color = color,
            createdAt = bookRepository.currentAppleReferenceDateSeconds(),
        )
        persistHighlights(highlights.orEmpty() + highlight)
    }
    fun removeHighlight(highlight: ReaderHighlight) {
        persistHighlights(highlights.orEmpty().filterNot { it.id == highlight.id })
        if (ReaderHighlights.chapterContains(highlight, book.bookInfo, currentLoadChapter())) {
            webView?.evaluateJavascript(ReaderHighlightCommand.Remove(highlight.id).source, null)
        }
    }
    fun toggleStatisticsTracking() {
        val tracker = statisticsTracker ?: return
        if (tracker.state.isTracking) {
            tracker.stop(currentDisplayedCharacter())
            syncStatisticsState()
            saveCurrentDisplayedPosition()
        } else {
            tracker.start(currentDisplayedCharacter())
            syncStatisticsState()
        }
    }
    fun pauseStatisticsForLifecycleStop(): Boolean {
        val tracker = statisticsTracker ?: return false
        val paused = tracker.pause(currentDisplayedCharacter())
        if (paused) {
            syncStatisticsState()
        }
        return paused
    }
    /**
     * Writes the session's time to `statistics.json` without a bookmark. Page turns save both
     * together, but the time spent on the last page before leaving or backgrounding would
     * otherwise stay in memory only, and the Statistics screen would lag behind this sheet.
     */
    fun saveStatisticsWithoutBookmark() {
        if (sessionRetired) return
        statisticsTracker?.statisticsForPersistenceOrNull()?.let(onSaveStatistics)
    }
    fun resumeStatisticsForLifecycleStartIfNeeded() {
        if (sessionRetired) return
        if (!resumeStatisticsTrackingOnStart) return
        resumeStatisticsTrackingOnStart = false
        statisticsTracker?.start(currentDisplayedCharacter())
        syncStatisticsState()
    }
    LaunchedEffect(statisticsTracker) {
        if (sessionRetired) return@LaunchedEffect
        // A delayed parse/import can finish after Home was pressed. Start only in the
        // foreground; otherwise the next resume establishes the reading baseline.
        resumeStatisticsTrackingOnStart = statisticsTracker?.startWhenForeground(
            currentDisplayedCharacter(), lifecycle.currentState,
        ) ?: false
        syncStatisticsState()
    }
    LaunchedEffect(statisticsTracker, statisticsState?.isTracking) {
        val tracker = statisticsTracker ?: return@LaunchedEffect
        if (tracker.state.isTracking) {
            while (tracker.state.isTracking) {
                delay(1_000)
                tracker.update(currentDisplayedCharacter())
                syncStatisticsState()
            }
        }
    }
    fun sasayakiCueForSelection(selection: ReaderSelectionData): SasayakiMatch? {
        val player = sasayakiPlayer ?: return null
        val offset = selection.normalizedOffset ?: return null
        if (!sasayakiSettings.enabled || !player.hasAudio) return null
        return player.findCue(chapterIndex = stateHolder.readerPosition.displayedPosition.index, offset = offset)
    }
    fun rootPopupOptions(): LookupPopupOptions = LookupPopupOptions(
        isVertical = effectiveSettings.verticalWriting,
        isFullWidth = effectiveSettings.popupFullWidth,
        width = effectiveSettings.popupWidth,
        height = effectiveSettings.popupHeight,
        swipeToDismiss = effectiveSettings.popupSwipeToDismiss,
        swipeThreshold = effectiveSettings.popupSwipeThreshold,
        reducedMotionScrolling = effectiveSettings.popupReducedMotionScrolling,
        reducedMotionScrollPercent = effectiveSettings.popupReducedMotionScrollPercent,
        reducedMotionSwipeThreshold = effectiveSettings.popupReducedMotionSwipeThreshold,
        popupScale = effectiveSettings.popupScale,
        cardStyle = effectiveSettings.dictionaryCard,
        popupActionBar = effectiveSettings.popupActionBar,
        dictionarySettings = dictionarySettings,
        darkMode = popupDarkMode,
        eInkMode = effectiveSettings.eInkMode,
        audioSettings = audioSettings,
        documentTitle = book.title,
        coverPath = sasayakiCoverFile?.absolutePath,
    )
    fun childPopupOptions(): LookupPopupOptions = rootPopupOptions().copy(isVertical = false, isFullWidth = false)
    /**
     * Runs the dictionary query off the main thread and delivers the popup, with its Sasayaki
     * cue attached, back on the main thread. Everything read from composition state happens
     * here, before the query is handed to the background dispatcher.
     */
    fun launchPopupLookup(
        selection: ReaderSelectionData,
        options: LookupPopupOptions,
        onResult: (Pair<LookupPopupItem, Int>?) -> Unit,
    ) {
        val styles = dictionaryStyles
        selectionLookups.launch(
            lookup = { createLookupPopupItem(selection, options, styles, dictionaryRepository::lookup) },
            onResult = { lookup ->
                onResult(
                    lookup?.let { (popup, highlightCount) ->
                        popup.copy(sasayakiCue = sasayakiCueForSelection(selection)) to highlightCount
                    },
                )
            },
        )
    }
    fun cancelPendingLookups() {
        selectionLookups.cancel()
        redirectLookups.cancel()
    }
    /**
     * Clears the popups for a new root selection without resuming Sasayaki. The synchronous
     * code resumed and re-paused playback within one main-thread turn; with the lookup off the
     * main thread that gap would be audible. The result opens a popup and keeps the pause, an
     * empty result or a dismissal resumes playback through [setLookupPopups].
     */
    fun clearLookupPopupsForPendingLookup() {
        val keepPaused = stateHolder.sasayakiWasPausedByLookup
        readerPopupHistories = emptyMap()
        stateHolder.setLookupPopups(emptyList())
        if (keepPaused) stateHolder.markSasayakiPausedByLookup()
    }
    /** Child lookup for the overlay fallback, which runs it on its own background dispatcher. */
    fun lookupChildPopup(selection: ReaderSelectionData): Pair<LookupPopupItem, Int>? =
        createLookupPopupItem(selection, childPopupOptions(), dictionaryStyles, dictionaryRepository::lookup)
            ?.let { (popup, highlightCount) -> popup.copy(sasayakiCue = sasayakiCueForSelection(selection)) to highlightCount }
            .also { lookup -> usageSession?.logLookup(selection, lookup?.first, UsageLookupSource.Popup) }

    fun closeReader() {
        cancelPendingLookups()
        val plan = readerLifecycleAutoSyncPlan(ReaderLifecycleAutoSyncEvent.Dispose)
        if (plan.flushPendingProgressSave) {
            webView?.flushPendingProgressSave()
        }
        if (plan.saveCurrentDisplayedPosition) {
            saveCurrentDisplayedPosition()
        } else {
            pauseStatisticsForLifecycleStop()
            saveStatisticsWithoutBookmark()
        }
        if (plan.flushAutoSyncExport) {
            onFlushAutoSyncExport()
        }
        onClose()
    }
    fun clearReaderSelection(onCleared: () -> Unit = {}) {
        val currentWebView = webView
        if (currentWebView == null) {
            onCleared()
        } else {
            currentWebView.evaluateJavascript(ReaderSelectionCommand.ClearSelection.source) {
                onCleared()
            }
        }
    }
    fun resumeSasayakiAfterLookupIfNeeded() {
        val player = sasayakiPlayer
        if (player != null && !player.isPlaying) {
            player.togglePlayback()
        }
    }
    fun setLookupPopups(nextPopups: List<LookupPopupItem>) {
        val activeIds = nextPopups.mapTo(mutableSetOf()) { it.id }
        readerPopupHistories = readerPopupHistories.filterKeys(activeIds::contains)
        rootSelectionHighlight = rootSelectionHighlight?.takeIf { highlight ->
            highlight.popupId == null || highlight.popupId in activeIds
        }
        stateHolder.setLookupPopups(nextPopups, ::resumeSasayakiAfterLookupIfNeeded)
    }
    fun dismissRootLookupPopup() {
        cancelPendingLookups()
        rootSelectionHighlight = null
        setLookupPopups(clearPopupSelectionHighlights(stateHolder.lookupPopups))
        clearReaderSelection()
        setLookupPopups(emptyList())
    }
    fun closeLookupPopupsAndSelection() {
        // Only a shown or pending lookup owns the page selection. Continuous scrolling calls this
        // on every progress sample, and must not drop a native selection made for a highlight.
        val lookupActive = stateHolder.lookupPopups.isNotEmpty() || selectionLookups.isPending ||
            rootSelectionHighlight != null
        cancelPendingLookups()
        rootSelectionHighlight = null
        if (lookupActive) {
            setLookupPopups(clearPopupSelectionHighlights(stateHolder.lookupPopups))
            clearReaderSelection()
        }
        setLookupPopups(emptyList())
    }
    fun openFullscreenImage(sourceUrl: String) {
        val resource = readerImageResourceBridge.imageResourceForUrl(sourceUrl) ?: return
        closeLookupPopupsAndSelection()
        fullscreenImage = ReaderFullscreenImage(sourceUrl, resource)
    }
    fun updateSasayakiSettings(settings: SasayakiSettings) {
        sasayakiSettings = settings
        scope.launch {
            sasayakiSettingsRepository.update { settings }
        }
        sasayakiPlayer?.autoScroll = settings.autoScroll
        sasayakiPlayer?.readerSkipButtonAction = settings.readerSkipButtonAction
    }
    fun goToNextChapter(): Boolean {
        if (!stateHolder.canAcceptReaderNavigationInput()) return false
        val next = stateHolder.goToNextChapter(book.chapters.lastIndex)
        if (next != null) {
            stateHolder.clearForwardHistoryAfterManualMovement()
            recordStatisticsAtDisplayedPosition()
            saveReaderPosition(next)
            return true
        }
        return false
    }
    fun goToPreviousChapter(): Boolean {
        if (!stateHolder.canAcceptReaderNavigationInput()) return false
        val previous = stateHolder.goToPreviousChapter()
        if (previous != null) {
            stateHolder.clearForwardHistoryAfterManualMovement()
            recordStatisticsAtDisplayedPosition()
            saveReaderPosition(previous)
            return true
        }
        return false
    }
    /**
     * A page turn that ran off the chapter's edge into the next or previous chapter: a page
     * turn like any other for the usage log, which displayPagedTurnProgress never sees.
     */
    fun logChapterPageTurn(moved: Boolean): Boolean {
        if (moved) {
            usageSession?.epubPageTurned(
                chapter = stateHolder.readerPosition.displayedPosition.index + 1,
                character = currentDisplayedCharacter(),
            )
        }
        return moved
    }
    fun turnToNextChapter(): Boolean = logChapterPageTurn(goToNextChapter())
    fun turnToPreviousChapter(): Boolean = logChapterPageTurn(goToPreviousChapter())
    fun saveDisplayedProgress(progress: Double) {
        stateHolder.enterFocusModeForReaderInteraction()
        val savedPosition = stateHolder.recordDisplayedProgress(progress)
        stateHolder.clearForwardHistoryAfterManualMovement()
        recordStatisticsAtDisplayedPosition()
        saveReaderPosition(savedPosition)
    }
    fun displayPagedTurnProgress(progress: Double) {
        stateHolder.enterFocusModeForReaderInteraction()
        val position = stateHolder.recordDisplayedProgress(progress)
        stateHolder.clearForwardHistoryAfterManualMovement()
        recordStatisticsAtDisplayedPosition()
        // Capture navigation at display time. The later statistics/idle callback must not
        // turn a disposal flush into a newer bookmark after a remote reader reload.
        saveReaderPosition(position, statistics = null)
        usageSession?.epubPageTurned(
            chapter = stateHolder.readerPosition.displayedPosition.index + 1,
            character = currentDisplayedCharacter(),
        )
    }
    fun displayContinuousScrollProgress(progress: Double, restoreEpoch: Int) {
        val position = stateHolder.recordContinuousScrollDisplayProgress(progress, restoreEpoch) ?: return
        stateHolder.clearForwardHistoryAfterManualMovement()
        recordStatisticsAtDisplayedPosition()
        // Coalesce scroll samples in memory; only the newest captured action is persisted at
        // idle/disposal. Preparing it now freezes intent/time before any remote reader reload.
        onPrepareBookmarkSave?.invoke(position.index, position.progress)?.let(pendingContinuousSave::set)
    }
    fun saveContinuousScrollProgress(progress: Double, restoreEpoch: Int) {
        val savedPosition = stateHolder.recordContinuousScrollProgress(progress, restoreEpoch) ?: return
        stateHolder.clearForwardHistoryAfterManualMovement()
        recordStatisticsAtDisplayedPosition()
        saveReaderPosition(savedPosition)
    }
    fun navigateReaderPage(direction: ReaderNavigationDirection): Boolean {
        val currentWebView = webView ?: return false
        if (!stateHolder.beginReaderNavigationInput()) return false
        closeLookupPopupsAndSelection()
        val onLimit = when (direction) {
            ReaderNavigationDirection.Forward -> ::turnToNextChapter
            ReaderNavigationDirection.Backward -> ::turnToPreviousChapter
        }
        currentWebView.navigatePage(direction, onLimit, ::displayPagedTurnProgress, ::saveDisplayedProgress)
        return true
    }
    fun pauseSasayakiForLookupIfNeeded() {
        val player = sasayakiPlayer
        if (stateHolder.shouldPauseSasayakiForLookup(
                enabled = sasayakiSettings.enabled,
                autoPause = sasayakiSettings.autoPause,
                isPlaying = player?.isPlaying == true,
            )
        ) {
            player?.pausePlayback()
        }
    }
    fun replyReaderPopupMessage(popupId: String, messageId: String, bodyJson: String) {
        webView?.evaluateJavascript(
            """
                window.hoshiReaderPopupHost &&
                window.hoshiReaderPopupHost.resolveMessage(${readerJavaScriptStringLiteral(popupId)}, ${readerJavaScriptStringLiteral(messageId)}, $bodyJson)
            """.trimIndent(),
            null,
        )
    }
    fun highlightReaderPopupSelection(popupId: String, highlightCount: Int) {
        webView?.evaluateJavascript(
            """
                window.hoshiReaderPopupHost &&
                window.hoshiReaderPopupHost.highlightSelection(${readerJavaScriptStringLiteral(popupId)}, $highlightCount)
            """.trimIndent(),
            null,
        )
    }
    fun popupIndex(popupId: String): Int =
        stateHolder.lookupPopups.indexOfFirst { it.id == popupId }

    fun popupById(popupId: String): LookupPopupItem? =
        stateHolder.lookupPopups.firstOrNull { it.id == popupId }

    fun handleReaderPopupBridgeMessage(message: ReaderLookupPopupBridgeMessage) {
        when (message) {
            is ReaderLookupPopupBridgeMessage.OpenLink -> context.openPopupExternalLink(message.url)
            is ReaderLookupPopupBridgeMessage.TapOutside -> {
                val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                selectionLookups.cancel()
                setLookupPopups(stateHolder.lookupPopups.take(index + 1))
            }
            is ReaderLookupPopupBridgeMessage.SwipeDismiss -> {
                val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                selectionLookups.cancel()
                if (index == 0) {
                    dismissRootLookupPopup()
                } else {
                    setLookupPopups(dismissPopupAt(stateHolder.lookupPopups, index))
                }
            }
            is ReaderLookupPopupBridgeMessage.TextSelected -> {
                if (popupIndex(message.popupId) < 0) return
                launchPopupLookup(message.selection, childPopupOptions()) { lookup ->
                    usageSession?.logLookup(message.selection, lookup?.first, UsageLookupSource.Popup)
                    // The parent may have been closed while the query ran.
                    val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return@launchPopupLookup
                    if (lookup == null) {
                        highlightReaderPopupSelection(message.popupId, 0)
                        return@launchPopupLookup
                    }
                    val (childPopup, highlightCount) = lookup
                    setLookupPopups(stateHolder.lookupPopups.take(index + 1) + childPopup)
                    highlightReaderPopupSelection(message.popupId, highlightCount)
                }
            }
            is ReaderLookupPopupBridgeMessage.PlayWordAudio -> {
                WordAudioPlayer.get(context).play(message.url, message.mode)
            }
            is ReaderLookupPopupBridgeMessage.MineEntry -> {
                val popup = popupById(message.popupId) ?: return
                val messageId = message.messageId ?: return
                val cue = popup.sasayakiCue?.takeIf { ankiUiState.popupSettings.needsSasayakiAudio }
                val player = sasayakiPlayer
                scope.launch {
                    val ankiContext = try {
                        cue?.let {
                            popup.state.ankiContext.copy(
                                sasayakiAudioPath = player?.exportCueAudio(it, popup.state.selection.sentence)?.absolutePath,
                            )
                        } ?: popup.state.ankiContext
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        replyReaderPopupMessage(message.popupId, messageId, "false")
                        return@launch
                    }
                    ankiViewModel.mineEntryAsync(message.payloadJson, ankiContext) { mined ->
                        replyReaderPopupMessage(message.popupId, messageId, mined.toString())
                    }
                }
            }
            is ReaderLookupPopupBridgeMessage.DuplicateCheck -> {
                ankiViewModel.duplicateCheckAsync(message.expression) { isDuplicate ->
                    replyReaderPopupMessage(message.popupId, message.messageId ?: return@duplicateCheckAsync, isDuplicate.toString())
                }
            }
            is ReaderLookupPopupBridgeMessage.LookupRedirect -> {
                val popup = popupById(message.popupId) ?: return
                val settings = popup.state.dictionarySettings
                redirectLookups.launch(
                    lookup = {
                        runCatching { dictionaryRepository.lookup(message.query, settings.maxResults, settings.scanLength) }
                            .getOrDefault(emptyList())
                    },
                    // A redirect superseded by another link click, or dropped by a dismissal,
                    // still resolves its promise so the popup's click handler does not hang.
                    onDropped = { message.messageId?.let { replyReaderPopupMessage(message.popupId, it, "0") } },
                    onResult = { results ->
                        // A popup closed while the query ran has nothing left awaiting the reply.
                        if (popupById(message.popupId) == null) return@launch
                        if (results.isNotEmpty()) {
                            setLookupPopups(
                                stateHolder.lookupPopups.map { existing ->
                                    if (existing.id == message.popupId) {
                                        existing.copy(state = existing.state.copy(results = results))
                                    } else {
                                        existing
                                    }
                                },
                            )
                            val current = readerPopupHistories[message.popupId] ?: ReaderPopupHistoryCounts()
                            readerPopupHistories = readerPopupHistories + (
                                message.popupId to current.copy(
                                    backCount = current.backCount + 1,
                                    forwardCount = 0,
                                )
                                )
                        }
                        message.messageId?.let { replyReaderPopupMessage(message.popupId, it, results.size.toString()) }
                    },
                )
            }
            is ReaderLookupPopupBridgeMessage.GetEntry -> {
                val entry = popupById(message.popupId)?.state?.results?.getOrNull(message.index)
                val body = entry?.let(LookupPopupHtml::entryJsonString) ?: "null"
                replyReaderPopupMessage(message.popupId, message.messageId ?: return, body)
            }
            is ReaderLookupPopupBridgeMessage.PopupScrolled -> {
                val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                selectionLookups.cancel()
                setLookupPopups(closeChildPopupsForScrolledParent(stateHolder.lookupPopups, index))
            }
            is ReaderLookupPopupBridgeMessage.NavigateBack -> {
                val current = readerPopupHistories[message.popupId] ?: return
                if (current.backCount > 0) {
                    readerPopupHistories = readerPopupHistories + (
                        message.popupId to current.copy(
                            backCount = current.backCount - 1,
                            forwardCount = current.forwardCount + 1,
                        )
                        )
                }
            }
            is ReaderLookupPopupBridgeMessage.NavigateForward -> {
                val current = readerPopupHistories[message.popupId] ?: return
                if (current.forwardCount > 0) {
                    readerPopupHistories = readerPopupHistories + (
                        message.popupId to current.copy(
                            backCount = current.backCount + 1,
                            forwardCount = current.forwardCount - 1,
                        )
                        )
                }
            }
            is ReaderLookupPopupBridgeMessage.ContentReady -> Unit
            is ReaderLookupPopupBridgeMessage.SasayakiReplayCue -> {
                popupById(message.popupId)?.sasayakiCue?.let { cue ->
                    WordAudioPlayer.get(context).stop()
                    sasayakiPlayer?.playCue(cue, stop = true)
                }
            }
            is ReaderLookupPopupBridgeMessage.SasayakiTogglePlayback -> {
                WordAudioPlayer.get(context).stop()
                if (sasayakiWasPausedByLookup) {
                    stateHolder.clearSasayakiPauseState()
                } else {
                    sasayakiPlayer?.togglePlayback()
                }
            }
            is ReaderLookupPopupBridgeMessage.SasayakiPlayForward -> {
                popupById(message.popupId)?.sasayakiCue?.let { cue ->
                    WordAudioPlayer.get(context).stop()
                    sasayakiPlayer?.playCue(cue, stop = false)
                    closeLookupPopupsAndSelection()
                }
            }
        }
    }
    readerPopupBridgeHolder.callbacks = ReaderLookupPopupBridgeCallbacks(::handleReaderPopupBridgeMessage)
    fun applyRootLookup(
        selection: ReaderSelectionData,
        selectionRects: (Int, (List<ReaderSelectionRect>) -> Unit) -> Unit,
        lookup: Pair<LookupPopupItem, Int>?,
    ) {
        if (lookup != null) {
            val (popup, highlightCount) = lookup
            pauseSasayakiForLookupIfNeeded()
            val selectionCount = onTextSelected(selection) ?: highlightCount
            rootSelectionHighlight = ReaderRootSelectionHighlight(
                popupId = popup.id,
                rects = null,
            )
            setLookupPopups(listOf(popup))
            if (readerIframePopupSupported) {
                selectionRects(selectionCount) { rects ->
                    if (stateHolder.lookupPopups.none { it.id == popup.id }) return@selectionRects
                    val displayRects = rects.ifEmpty { listOf(popup.state.selection.rect) }
                    val anchor = displayRects.firstOrNull()
                    if (anchor != null) {
                        setLookupPopups(
                            stateHolder.lookupPopups.map { existing ->
                                if (existing.id == popup.id) {
                                    existing.copy(
                                        state = existing.state.copy(
                                            selection = existing.state.selection.copy(rect = anchor),
                                        ),
                                    )
                                } else {
                                    existing
                                }
                            },
                        )
                    }
                    rootSelectionHighlight = ReaderRootSelectionHighlight(
                        popupId = popup.id,
                        rects = displayRects,
                    )
                }
            } else {
                selectionRects(selectionCount) { rects ->
                    if (stateHolder.lookupPopups.none { it.id == popup.id }) return@selectionRects
                    val displayRects = rects.ifEmpty { listOf(popup.state.selection.rect) }
                    val anchor = displayRects.firstOrNull() ?: return@selectionRects
                    setLookupPopups(
                        stateHolder.lookupPopups.map { existing ->
                            if (existing.id == popup.id) {
                                existing.copy(
                                    state = existing.state.copy(
                                        selection = existing.state.selection.copy(rect = anchor),
                                    ),
                                )
                            } else {
                                existing
                            }
                        },
                    )
                    rootSelectionHighlight = ReaderRootSelectionHighlight(
                        popupId = popup.id,
                        rects = displayRects,
                    )
                }
            }
        } else {
            // No popup opens, so playback paused for the previous popup may resume now.
            setLookupPopups(emptyList())
            onTextSelected(selection)?.let { count ->
                if (readerIframePopupSupported) {
                    selectionRects(count) { rects ->
                        rootSelectionHighlight = ReaderRootSelectionHighlight(
                            popupId = null,
                            rects = rects,
                        )
                    }
                } else {
                    selectionRects(count) {}
                }
            }
        }
    }
    val handleTextSelected: (ReaderSelectionData, (Int, (List<ReaderSelectionRect>) -> Unit) -> Unit) -> Unit = { selection, selectionRects ->
        stateHolder.enterFocusModeForReaderInteraction()
        rootSelectionHighlight = null
        clearLookupPopupsForPendingLookup()
        launchPopupLookup(selection, rootPopupOptions()) { lookup ->
            usageSession?.logLookup(selection, lookup?.first, UsageLookupSource.Page)
            applyRootLookup(selection, selectionRects, lookup)
        }
    }
    fun handleReaderTapOutside() {
        // A lookup whose popup has not opened yet counts as visible: the tap dismisses it
        // instead of toggling focus mode and then letting the late popup appear.
        val hasVisiblePopups = stateHolder.lookupPopups.isNotEmpty() || selectionLookups.isPending
        if (!stateHolder.toggleFocusModeFromReaderTap(hasVisiblePopups = hasVisiblePopups)) {
            closeLookupPopupsAndSelection()
        }
    }
    val chromeState = remember(
        book,
        readerPosition.displayedPosition,
        stateHolder.backTargetPosition,
        stateHolder.forwardTargetPosition,
        statisticsState,
    ) {
        ReaderChromeState(
            title = book.title,
            currentCharacter = book.characterCountAt(readerPosition.displayedPosition.index, readerPosition.displayedPosition.progress),
            totalCharacters = book.bookInfo.characterCount,
            backTargetCharacter = stateHolder.backTargetPosition?.let { book.characterCountAt(it.index, it.progress) },
            forwardTargetCharacter = stateHolder.forwardTargetPosition?.let { book.characterCountAt(it.index, it.progress) },
            statistics = statisticsState?.session?.let {
                ReaderStatisticsChromeState(
                    readingSpeed = it.lastReadingSpeed,
                    readingTimeSeconds = it.readingTime,
                )
            },
        )
    }
    fun dispatchSasayakiCueToReader(cue: SasayakiMatch, reveal: Boolean) {
        val targetWebView = webView
        if (targetWebView == null || stateHolder.isWebViewRestoring) {
            pendingSasayakiCue = PendingSasayakiCue(cue = cue, reveal = reveal)
            return
        }
        pendingSasayakiCue = null
        targetWebView.evaluateJavascript(
            ReaderPaginationScripts.highlightSasayakiCueInvocation(cue.toCueRange(), reveal),
        ) { progressResult ->
            ReaderPaginationScripts.doubleResult(progressResult)?.let { progress ->
                val savedPosition = stateHolder.recordDisplayedProgress(progress)
                recordStatisticsAtDisplayedPosition()
                saveReaderPosition(savedPosition)
            }
        }
    }
    LaunchedEffect(
        webView,
        stateHolder.isWebViewRestoring,
        readerPosition.displayedPosition.index,
        pendingSasayakiCue,
    ) {
        val pending = pendingSasayakiCue ?: return@LaunchedEffect
        if (stateHolder.isWebViewRestoring || webView == null) return@LaunchedEffect
        if (pending.cue.chapterIndex != readerPosition.displayedPosition.index) {
            pendingSasayakiCue = null
            return@LaunchedEffect
        }
        dispatchSasayakiCueToReader(pending.cue, pending.reveal)
    }
    LaunchedEffect(bookRoot, sasayakiMatchData, isSasayakiPlaybackLoaded, sasayakiPlaybackData) {
        sasayakiPlayer?.release()
        sasayakiPlayer = if (bookRoot != null && sasayakiMatchData != null && isSasayakiPlaybackLoaded) {
            SasayakiPlayer(
                context = context,
                bookRoot = bookRoot,
                playbackRepository = BookSasayakiPlaybackRepository(bookRoot, bookRepository),
                bookTitle = book.title,
                bookCoverFile = sasayakiCoverFile,
                matchData = sasayakiMatchData,
                initialPlayback = sasayakiPlaybackData,
                persistenceScope = scope,
                getCurrentChapterIndex = { stateHolder.readerPosition.displayedPosition.index },
                onCue = { cue, reveal ->
                    dispatchSasayakiCueToReader(cue, reveal)
                },
                onClearCue = {
                    pendingSasayakiCue = null
                    webView?.evaluateJavascript(ReaderPaginationScripts.clearSasayakiCueInvocation(), null)
                },
                onLoadChapter = { chapterIndex ->
                    statisticsForSave()
                    val target = ReaderChapterPosition(index = chapterIndex, progress = 0.0)
                    val savedPosition = stateHolder.jumpTo(target)
                    resetStatisticsBaseline()
                    saveReaderPosition(savedPosition, statisticsTracker?.statisticsForPersistenceOrNull())
                },
            )
        } else {
            null
        }
    }
    DisposableEffect(Unit) {
        onDispose { sasayakiPlayer?.release() }
    }
    sasayakiPlayer?.autoScroll = sasayakiSettings.autoScroll
    sasayakiPlayer?.readerSkipButtonAction = sasayakiSettings.readerSkipButtonAction
    val currentReaderKeyHandler = rememberUpdatedState<(KeyEvent) -> Boolean> { event ->
        val action = readerHardwareKeyActionForKeyEvent(
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount,
            settings = effectiveSettings,
            sasayakiEnabled = sasayakiSettings.enabled,
            hasSasayakiAudio = sasayakiPlayer?.hasAudio == true,
        ) ?: return@rememberUpdatedState false
        when (action) {
            is ReaderHardwareKeyAction.ReaderNavigation -> navigateReaderPage(action.direction)
            ReaderHardwareKeyAction.SasayakiSeekBackward -> {
                sasayakiPlayer?.previousCue()
                true
            }
            ReaderHardwareKeyAction.SasayakiSeekForward -> {
                sasayakiPlayer?.nextCue()
                true
            }
        }
    }
    DisposableEffect(onReaderKeyEventHandlerChange) {
        onReaderKeyEventHandlerChange { event -> currentReaderKeyHandler.value(event) }
        onDispose { onReaderKeyEventHandlerChange(null) }
    }
    val keepScreenOn = ReaderScreenAwake.shouldKeepScreenOn(
        keepScreenOnWhileReading = effectiveSettings.keepScreenOnWhileReading,
        sasayakiIsPlaying = sasayakiPlayer?.isPlaying == true,
        sasayakiAutoScroll = sasayakiSettings.autoScroll,
    )
    DisposableEffect(context, keepScreenOn) {
        val window = context.findActivity()?.window
        if (keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var lastInactiveAtMillis by remember { mutableStateOf<Long?>(null) }
    val currentLifecycleResume = rememberUpdatedState {
        val inactiveAt = lastInactiveAtMillis
        lastInactiveAtMillis = null
        val plan = readerLifecycleAutoSyncPlan(
            event = ReaderLifecycleAutoSyncEvent.Resume,
            inactiveElapsedMillis = inactiveAt?.let { SystemClock.elapsedRealtime() - it },
        )
        // As on iOS the import runs while this reader goes on counting; the route reloads it only
        // when the import changed the book. That reload would release the audiobook player, so
        // audio that kept playing in the background skips the import; it drives the position anyway.
        if (plan.importOnForeground && sasayakiPlayer?.isPlaying != true) onForegroundAutoSyncImport()
        resumeStatisticsForLifecycleStartIfNeeded()
    }
    val currentLifecyclePause = rememberUpdatedState {
        if (sessionRetired) return@rememberUpdatedState
        lastInactiveAtMillis = SystemClock.elapsedRealtime()
        val plan = readerLifecycleAutoSyncPlan(ReaderLifecycleAutoSyncEvent.Pause)
        if (plan.flushPendingProgressSave) {
            webView?.flushPendingProgressSave()
        }
        resumeStatisticsTrackingOnStart = pauseStatisticsForLifecycleStop()
        if (plan.saveCurrentDisplayedPosition) {
            saveCurrentDisplayedPosition()
        } else {
            saveStatisticsWithoutBookmark()
        }
        if (plan.flushAutoSyncExport) {
            onFlushAutoSyncExport()
        }
    }
    val currentLifecycleDispose = rememberUpdatedState {
        if (sessionRetired) return@rememberUpdatedState
        val plan = readerLifecycleAutoSyncPlan(ReaderLifecycleAutoSyncEvent.Dispose)
        if (plan.flushPendingProgressSave) {
            webView?.flushPendingProgressSave()
        }
        if (plan.saveCurrentDisplayedPosition) {
            saveCurrentDisplayedPosition()
        } else {
            pauseStatisticsForLifecycleStop()
            saveStatisticsWithoutBookmark()
        }
        if (plan.flushAutoSyncExport) {
            onFlushAutoSyncExport()
        }
    }
    val currentPrepareSessionReload = rememberUpdatedState {
        if (!sessionRetired) {
            // Capture pending navigation before closing the old position session. Its saved
            // action time stays the one from the original scroll, never the import time.
            webView?.flushPendingProgressSave()
            pendingContinuousSave.getAndSet(null)?.invoke()
            pauseStatisticsForLifecycleStop()
            saveStatisticsWithoutBookmark()
            sessionRetired = true
        }
    }
    DisposableEffect(onAudiobookPlayingQueryChange) {
        // Read at event time, so a remote reload can never slip in before a state update lands.
        onAudiobookPlayingQueryChange { sasayakiPlayer?.isPlaying == true }
        onDispose { onAudiobookPlayingQueryChange(null) }
    }
    DisposableEffect(onPrepareSessionReloadChange) {
        onPrepareSessionReloadChange { currentPrepareSessionReload.value() }
        onDispose { onPrepareSessionReloadChange(null) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> currentLifecycleResume.value()
                Lifecycle.Event.ON_PAUSE -> currentLifecyclePause.value()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            currentLifecycleDispose.value()
            lifecycle.removeObserver(observer)
        }
    }

    BackHandler {
        if (stateHolder.handleBackNavigation()) {
            closeReader()
        }
    }
    val useDarkSystemBarIcons = effectiveSettings.usesDarkSystemBarIcons(systemDarkTheme)
    DisposableEffect(context, view, useDarkSystemBarIcons) {
        val activity = context.findActivity()
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        }
        controller?.isAppearanceLightStatusBars = useDarkSystemBarIcons
        controller?.isAppearanceLightNavigationBars = useDarkSystemBarIcons
        onDispose {
            controller?.isAppearanceLightStatusBars = useDarkSystemBarIcons
            controller?.isAppearanceLightNavigationBars = useDarkSystemBarIcons
        }
    }
    DisposableEffect(context, view) {
        val activity = context.findActivity()
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        }
        val previousBehavior = controller?.systemBarsBehavior
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            if (previousBehavior != null) {
                controller.systemBarsBehavior = previousBehavior
            }
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(context, view, focusMode) {
        val activity = context.findActivity()
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        } ?: return@LaunchedEffect
        val visibility = readerSystemBarVisibility(focusMode)
        if (visibility.showStatusBar) {
            controller.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
        if (visibility.showNavigationBar) {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    val bottomChromeMetrics = readerBottomChromeMetrics()
    val currentStatusBarPadding = rememberCurrentStatusBarPadding()
    val stableStatusBarPadding = rememberStableStatusBarPadding()
    val stableNavigationBarPadding = rememberStableNavigationBarPadding()
    val currentStatusBarPaddingDp = currentStatusBarPadding.value.roundToInt().coerceAtLeast(0)
    val stableStatusBarPaddingDp = stableStatusBarPadding.value.roundToInt().coerceAtLeast(0)
    val sasayakiBottomPlaybackControls = readerSasayakiBottomPlaybackControls(
        settings = sasayakiSettings,
        hasAudio = sasayakiPlayer?.hasAudio == true,
        metrics = bottomChromeMetrics,
    )
    val sasayakiBottomSkipButtonActions = readerSasayakiBottomSkipButtonActions(
        verticalWriting = effectiveSettings.verticalWriting,
        reverseVerticalReaderSkipButtons = sasayakiSettings.reverseVerticalReaderSkipButtons,
    )
    fun performSasayakiBottomSkipAction(action: ReaderSasayakiBottomSkipButtonAction) {
        when (action) {
            ReaderSasayakiBottomSkipButtonAction.Backward -> sasayakiPlayer?.previousCue()
            ReaderSasayakiBottomSkipButtonAction.Forward -> sasayakiPlayer?.nextCue()
        }
    }
    val showSasayakiTopToggle = sasayakiSettings.enabled &&
        sasayakiSettings.showReaderToggle &&
        sasayakiMatchData != null &&
        (sasayakiPlayer?.hasAudio == true || sasayakiPlaybackData.hasStoredAudioSource())
    val reserveSasayakiTopToggle = remember(bookRoot, sasayakiSettings) {
        readerShouldReserveSasayakiTopToggle(bookRoot, sasayakiSettings)
    }
    val contentChromeInsets = readerContentChromeInsets(
        state = chromeState,
        settings = effectiveSettings,
        showSasayakiToggle = reserveSasayakiTopToggle || showSasayakiTopToggle,
        showStatisticsToggle = effectiveSettings.showStatisticsToggle,
        focusMode = focusMode,
        topSystemInsetDp = stableStatusBarPadding.value.roundToInt().coerceAtLeast(0),
    )
    val topInfoPadding = readerTopInfoOverlayPaddingDp(
        topSystemInsetDp = currentStatusBarPaddingDp,
        focusMode = focusMode,
    ).dp
    val onSasayakiTopToggle = sasayakiPlayer
        ?.takeIf { showSasayakiTopToggle && it.hasAudio }
        ?.let { player ->
            ({
                if (sasayakiWasPausedByLookup) {
                    stateHolder.clearSasayakiPauseState()
                } else {
                    player.togglePlayback()
                }
            })
        }
    val chromeLayout = readerChromeLayout(
        chromeState,
        effectiveSettings,
        showSasayakiToggle = reserveSasayakiTopToggle || showSasayakiTopToggle,
        showStatisticsToggle = effectiveSettings.showStatisticsToggle,
        focusMode = focusMode,
    )
    val chromeVisibility = readerChromeVisibility(
        focusMode = focusMode,
        hasStatisticsToggle = effectiveSettings.showStatisticsToggle,
        hasSasayakiToggle = onSasayakiTopToggle != null,
        hasBackJump = stateHolder.backTargetPosition != null,
        hasForwardJump = stateHolder.forwardTargetPosition != null,
    )
    val topInfoVisibility = chromeVisibility.copy(
        showTitleAndProgress = chromeVisibility.showTitleAndProgress &&
            readerShouldShowTitleAndProgress(
                focusMode = focusMode,
                currentStatusBarInsetDp = currentStatusBarPaddingDp,
                stableStatusBarInsetDp = stableStatusBarPaddingDp,
            ),
    )
    val currentDismissLookup = rememberUpdatedState(::closeLookupPopupsAndSelection)
    val currentHasActiveLookup = rememberUpdatedState {
        stateHolder.lookupPopups.isNotEmpty() || selectionLookups.isPending
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { lookupTouchTarget.rootCoordinates = it }
            .dismissReaderLookupOnOutsideTouch(
                hasActiveLookup = { currentHasActiveLookup.value() },
                hitPopup = { lookupTouchTarget.containsRootPoint(it, lookupTouchDensity) },
                dismissLookup = { currentDismissLookup.value() },
            )
            .background(Color(effectiveSettings.backgroundColor(systemDarkTheme))),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = contentChromeInsets.topDp.dp,
                    bottom = contentChromeInsets.bottomDp.dp,
                ),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val viewportHorizontalPadding = maxWidth * effectiveSettings.continuousViewportHorizontalPaddingRatio.toFloat()
                val viewportVerticalPadding = maxHeight * effectiveSettings.continuousViewportVerticalPaddingRatio.toFloat()
                val readerLookupPopupViewport = ReaderLookupPopupViewport(
                    uiScale = readerUiScale,
                    popupFrameScale = popupReadability.frameScale,
                    width = (maxWidth.value - viewportHorizontalPadding.value * 2f).coerceAtLeast(0f).toDouble(),
                    height = (maxHeight.value - viewportVerticalPadding.value * 2f).coerceAtLeast(0f).toDouble(),
                )
                val readerRootSelectionHighlightPayload = remember(
                    rootSelectionHighlight,
                    popupDarkMode,
                    effectiveSettings.eInkMode,
                    effectiveSettings.verticalWriting,
                ) {
                    rootSelectionHighlight?.let { highlight ->
                        ReaderLookupPopupRootHighlightPayload.fromReaderRects(
                            popupId = highlight.popupId,
                            rects = highlight.rects,
                            darkMode = popupDarkMode,
                            eInkMode = effectiveSettings.eInkMode,
                            verticalWriting = effectiveSettings.verticalWriting,
                        )
                    }
                }
                val readerLookupPopupPayloads = remember(
                    themedLookupPopups,
                    readerPopupHistories,
                    readerLookupPopupViewport,
                    sasayakiWasPausedByLookup,
                    sasayakiPlayer?.isPlaying == true,
                    readerIframePopupSupported,
                    readerPopupIframeUrl,
                    rootSelectionHighlight,
                ) {
                    if (!readerIframePopupSupported) {
                        emptyList()
                    } else {
                        readerLookupPopupFramePayloads(
                            popups = themedLookupPopups,
                            histories = readerPopupHistories,
                            viewport = readerLookupPopupViewport,
                            sasayakiWasPaused = sasayakiWasPausedByLookup,
                            sasayakiIsPlaying = sasayakiPlayer?.isPlaying == true,
                            iframeUrl = readerPopupIframeUrl,
                            rootSelectionHighlight = rootSelectionHighlight,
                        )
                    }
                }
                if (readerIframePopupSupported) {
                    lookupTouchTarget.popupRects = readerLookupPopupPayloads.map { popup ->
                        ReaderSelectionRect(popup.frame.left, popup.frame.top, popup.frame.width, popup.frame.height)
                    }
                }
                if (highlights != null) {
                    val loadChapter = currentLoadChapter()
                    val chapterSentenceAnchorsJson = remember(
                        bookRoot,
                        sentenceTranslationSyncId,
                        loadChapter,
                        effectiveSettings.showSentenceTranslations,
                    ) {
                        val root = bookRoot
                        val syncId = sentenceTranslationSyncId
                        if (root == null || syncId == null || !effectiveSettings.showSentenceTranslations) {
                            null
                        } else {
                            val spine = loadChapter.spineIndex ?: readerPosition.loadPosition.index
                            Json.encodeToString(
                                EpubTranslationStore.anchors(root, syncId, book.spineCount, spine),
                            )
                        }
                    }
                    ChapterWebView(
                        book = book,
                        chapterPosition = readerPosition.loadPosition,
                        chapterFragment = readerPosition.loadFragment,
                        webViewViewportSize = stateHolder.webViewViewportSize,
                        onReaderViewportSizeChanged = stateHolder::updateViewportSize,
                        onWebViewReady = { webView = it },
                        isWebViewRestoring = stateHolder.isWebViewRestoring,
                        webViewRestoreEpoch = stateHolder.webViewRestoreEpoch,
                        onRestoreStarted = stateHolder::markWebViewRestoring,
                        onRestoreCompleted = stateHolder::markWebViewRestored,
                        onNextChapter = {
                            turnToNextChapter()
                        },
                        onPreviousChapter = {
                            turnToPreviousChapter()
                        },
                        onSaveBookmark = { progress ->
                            saveDisplayedProgress(progress)
                        },
                        onDisplayProgress = { progress ->
                            displayPagedTurnProgress(progress)
                        },
                        onContinuousScrollDisplayProgress = { progress, restoreEpoch ->
                            displayContinuousScrollProgress(progress, restoreEpoch)
                        },
                        onContinuousScrollProgress = { progress, restoreEpoch ->
                            saveContinuousScrollProgress(progress, restoreEpoch)
                        },
                        onInternalLink = { target ->
                            closeLookupPopupsAndSelection()
                            jumpToPositionWithHistory(target.position, target.fragment)
                        },
                        scanNonJapaneseText = dictionarySettings.scanNonJapaneseText,
                        readerSettings = effectiveSettings,
                        chapterHighlightsJson = ReaderHighlights.chapterHighlightsJson(
                            highlights = highlights.orEmpty(),
                            bookInfo = book.bookInfo,
                            chapter = loadChapter,
                        ),
                        chapterSasayakiCuesJson = ReaderSasayakiCues.chapterCuesJson(
                            matchData = sasayakiMatchData,
                            chapterIndex = readerPosition.loadPosition.index,
                        ),
                        chapterSentenceAnchorsJson = chapterSentenceAnchorsJson,
                        sasayakiTextColor = sasayakiSettings.textColor(effectiveSettings.usesDarkInterface(systemDarkTheme)),
                        sasayakiBackgroundColor = sasayakiSettings.backgroundColor(effectiveSettings.usesDarkInterface(systemDarkTheme)),
                        onTextSelected = handleTextSelected,
                        onClearLookupPopup = ::closeLookupPopupsAndSelection,
                        onReaderTapOutside = ::handleReaderTapOutside,
                        onReaderInteraction = stateHolder::enterFocusModeForReaderInteraction,
                        onImageTapped = ::openFullscreenImage,
                        onHighlightCreated = ::addHighlight,
                        onSentenceTranslation = { renderedId ->
                            val root = bookRoot
                            val syncId = sentenceTranslationSyncId
                            val cached = if (root != null && syncId != null) {
                                EpubTranslationStore.lookup(renderedId, root, syncId, book.spineCount)
                            } else {
                                null
                            }
                            if (cached != null) {
                                closeLookupPopupsAndSelection()
                                detachSentenceTranslationRequest()
                                sentenceTranslationRequest = cached.sentenceText
                                sentenceTranslationRetry = null
                                sentenceTranslationPopup = AiChatUiState.Loaded(
                                    entry = AiChatEntry(
                                        bubbleText = cached.sentenceText,
                                        prompt = "",
                                        model = cached.model,
                                        response = cached.markdownResponse,
                                        timestampSeconds = bookRepository.currentAppleReferenceDateSeconds(),
                                        debugInfo = epubPretranslationDebug,
                                    ),
                                    pretranslated = true,
                                )
                            }
                        },
                        readerPopupBridgeHolder = readerPopupBridgeHolder,
                        readerPopupResourceHandler = readerPopupResourceHandler,
                        readerIframePopupSupported = readerIframePopupSupported,
                        readerPopupFrames = readerLookupPopupPayloads,
                        fontManager = fontManager,
                        systemDark = systemDarkTheme,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                horizontal = viewportHorizontalPadding,
                                vertical = viewportVerticalPadding,
                            )
                            .onGloballyPositioned {
                                if (readerIframePopupSupported) lookupTouchTarget.popupCoordinates = it
                            },
                    )
                }
                if (readerIframePopupSupported) {
                    ReaderLookupPopupIframeSync(
                        webView = webView,
                        payloads = readerLookupPopupPayloads,
                        rootHighlight = readerRootSelectionHighlightPayload,
                    )
                } else {
                    LookupPopupAndroidStack(
                        popups = themedLookupPopups,
                        onPopupsChange = ::setLookupPopups,
                        lookupChildPopup = ::lookupChildPopup,
                        onRootPopupDismissed = {
                            dismissRootLookupPopup()
                            true
                        },
                        rootHighlightRects = rootSelectionHighlight?.rects.orEmpty(),
                        rootHighlightDarkMode = popupDarkMode,
                        rootHighlightEInkMode = effectiveSettings.eInkMode,
                        rootHighlightVerticalWriting = effectiveSettings.verticalWriting,
                        rootHighlightEInkStyle = PopupSelectionEInkStyle.Box,
                        onPopupBoundsChanged = { lookupTouchTarget.popupRects = it },
                        modifier = Modifier.fillMaxSize().onGloballyPositioned {
                            lookupTouchTarget.popupCoordinates = it
                        },
                    )
                }
            }
        }
        ReaderTopInfo(
            state = chromeState,
            settings = effectiveSettings,
            colors = readerChromeColors(effectiveSettings, systemDarkTheme),
            onStatisticsToggle = if (effectiveSettings.showStatisticsToggle) {
                ::toggleStatisticsTracking
            } else {
                null
            },
            statisticsTracking = statisticsState?.isTracking == true,
            onJumpBack = stateHolder.backTargetPosition?.let {
                {
                    closeLookupPopupsAndSelection()
                    navigateJumpBack()
                }
            },
            onJumpForward = stateHolder.forwardTargetPosition?.let {
                {
                    closeLookupPopupsAndSelection()
                    navigateJumpForward()
                }
            },
            onSasayakiToggle = onSasayakiTopToggle,
            sasayakiPlaying = sasayakiPlayer?.isPlaying == true || sasayakiWasPausedByLookup,
            visibility = topInfoVisibility,
            metrics = bottomChromeMetrics,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topInfoPadding)
                .padding(horizontal = 15.dp),
        )
        ReaderFocusModeToggleArea(
            metrics = bottomChromeMetrics,
            focusMode = focusMode,
            onToggleFocusMode = ::handleReaderTapOutside,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        ReaderBottomSafeProgress(
            state = chromeState,
            settings = effectiveSettings,
            colors = readerChromeColors(effectiveSettings, systemDarkTheme),
            metrics = bottomChromeMetrics,
            focusMode = focusMode,
            sasayakiPlaybackControls = sasayakiBottomPlaybackControls,
            sasayakiPlaying = sasayakiPlayer?.isPlaying == true,
            onSasayakiSkipBackward = { performSasayakiBottomSkipAction(sasayakiBottomSkipButtonActions.left) },
            onSasayakiTogglePlayback = { sasayakiPlayer?.togglePlayback() },
            onSasayakiSkipForward = { performSasayakiBottomSkipAction(sasayakiBottomSkipButtonActions.right) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (chromeVisibility.showBottomChrome) ReaderBottomChrome(
            state = chromeState,
            settings = effectiveSettings,
            layout = chromeLayout,
            colors = readerChromeColors(effectiveSettings, systemDarkTheme),
            onClose = ::closeReader,
            onMenu = stateHolder::toggleReaderMenu,
            menuExpanded = showReaderMenu,
            onDismissMenu = stateHolder::dismissReaderMenu,
            onChapters = stateHolder::openChaptersFromMenu,
            onHighlights = stateHolder::openHighlightsFromMenu,
            onAppearance = stateHolder::openAppearanceFromMenu,
            onStatistics = stateHolder::openStatisticsFromMenu,
            onSasayaki = if (sasayakiSettings.enabled && sasayakiMatchData != null) {
                stateHolder::openSasayakiFromMenu
            } else {
                null
            },
            onSentenceMode = onOpenSentenceMode?.let { open ->
                {
                    stateHolder.dismissReaderMenu()
                    // The new reader waits for tracked writes before loading its statistics.
                    // Register this handoff now, before navigation/transition disposal can race
                    // that load and let a new session start from an older daily entry.
                    webView?.flushPendingProgressSave()
                    resumeStatisticsTrackingOnStart = pauseStatisticsForLifecycleStop()
                    saveStatisticsWithoutBookmark()
                    open()
                }
            },
            metrics = bottomChromeMetrics,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (showAppearance) {
            ReaderAppearanceSheet(
                settings = effectiveSettings,
                onSettingsChange = {
                    stateHolder.applySettings(it)
                    onReaderSettingsChange(it)
                },
                sasayakiSettings = sasayakiSettings,
                onSasayakiSettingsChange = ::updateSasayakiSettings,
                fontManager = fontManager,
                onDismiss = stateHolder::dismissAppearance,
            )
        }
        if (showChapters) {
            ReaderChapterSheet(
                book = book,
                currentPosition = readerPosition.displayedPosition,
                onJump = { target, fragment ->
                    closeLookupPopupsAndSelection()
                    jumpToPositionWithHistory(target, fragment)
                    stateHolder.dismissChapters()
                },
                onDismiss = stateHolder::dismissChapters,
            )
        }
        if (showHighlights && highlights != null) {
            ReaderHighlightSheet(
                book = book,
                highlights = highlights.orEmpty(),
                onJump = { highlight ->
                    closeLookupPopupsAndSelection()
                    val target = ReaderHighlights.positionForCharacter(book.bookInfo, highlight.character)
                    jumpToPositionWithHistory(target)
                    stateHolder.dismissHighlights()
                },
                onDelete = ::removeHighlight,
                onDismiss = stateHolder::dismissHighlights,
            )
        }
        if (showSasayaki && sasayakiPlayer != null && sasayakiAudioRepository != null) {
            SasayakiSheet(
                player = requireNotNull(sasayakiPlayer),
                audioRepository = sasayakiAudioRepository,
                settings = sasayakiSettings,
                onSettingsChange = ::updateSasayakiSettings,
                onDismiss = stateHolder::dismissSasayaki,
            )
        }
        if (showStatistics && statisticsState != null) {
            ReaderStatisticsSheet(
                state = requireNotNull(statisticsState),
                currentCharacter = currentDisplayedCharacter(),
                currentChapterEndCharacter = currentChapterEndCharacter(),
                totalCharacters = book.bookInfo.characterCount,
                onToggleTracking = ::toggleStatisticsTracking,
                onDismiss = stateHolder::dismissStatistics,
            )
        }
        fullscreenImage?.let { image ->
            ReaderFullscreenImageOverlay(
                image = image,
                resourceBridge = readerImageResourceBridge,
                backgroundColor = Color(effectiveSettings.backgroundColor(systemDarkTheme)),
                topSafeAreaPadding = stableStatusBarPadding,
                bottomSafeAreaPadding = stableNavigationBarPadding,
                onDismiss = { fullscreenImage = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        sentenceTranslationPopup?.let { popup ->
            AiChatPopupView(
                cardStyle = effectiveSettings.translationCard,
                state = popup,
                onDismiss = {
                    detachSentenceTranslationRequest()
                    sentenceTranslationPopup = null
                },
                onRetry = { sentenceTranslationRetry?.invoke() },
                onAskLive = if (popup is AiChatUiState.Loaded && popup.pretranslated) {
                    { sentenceTranslationRequest?.let(::askLiveSentenceTranslation) }
                } else {
                    null
                },
                modifier = Modifier.zIndex(5f),
            )
        }
        webView?.let { _ -> Unit }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun resolveBookCoverFile(bookRoot: File?, coverHref: String?): File? {
    val root = bookRoot?.canonicalFile ?: return null
    val cover = coverHref?.takeIf { it.isNotBlank() } ?: return null
    val file = root.resolve(cover).canonicalFile
    if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) return null
    return file.takeIf { it.isFile }
}

private fun SasayakiMatch.toCueRange(): SasayakiCueRange =
    SasayakiCueRange(id = id, start = start, length = length)

private data class PendingSasayakiCue(
    val cue: SasayakiMatch,
    val reveal: Boolean,
)

private fun SasayakiPlaybackData?.hasStoredAudioSource(): Boolean =
    this?.audioUri?.isNotBlank() == true || this?.audioFileName?.isNotBlank() == true
