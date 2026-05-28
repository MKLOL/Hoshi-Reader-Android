package moe.antimony.hoshi.features.mangareader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Base64
import android.view.KeyEvent
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.createBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.ai.AiChatImage
import moe.antimony.hoshi.features.ai.AiChatHistoryView
import moe.antimony.hoshi.features.ai.AiChatPopupView
import moe.antimony.hoshi.features.ai.AiChatSettings
import moe.antimony.hoshi.features.ai.AiChatUiState
import moe.antimony.hoshi.features.ai.OpenAiChatClient
import moe.antimony.hoshi.features.ai.aiChatSettingsRepository
import moe.antimony.hoshi.features.ai.buildAiChatDictionaryLookup
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupOptions
import moe.antimony.hoshi.features.dictionary.LookupPopupAndroidStack
import moe.antimony.hoshi.features.dictionary.createLookupPopupItem
import moe.antimony.hoshi.features.reader.ReaderNavigationDirection
import moe.antimony.hoshi.features.reader.ReaderSelectionCommand
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.findHoshiActivity
import moe.antimony.hoshi.features.reader.readerShouldUseImmersiveSystemBars
import moe.antimony.hoshi.features.reader.readerHardwareKeyActionForKeyEvent
import moe.antimony.hoshi.features.reader.ReaderHardwareKeyAction
import moe.antimony.hoshi.features.reader.usesDarkInterface
import moe.antimony.hoshi.features.reader.ReaderStatisticsTracker
import moe.antimony.hoshi.features.sync.http.rememberHttpSyncReaderHooks
import moe.antimony.hoshi.features.reader.StatisticsAutostartMode
import moe.antimony.hoshi.mokuro.MokuroBook
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val BOOKMARK_SAVE_DEBOUNCE_MS = 400L
private const val MANGA_SCREENSHOT_TRANSLATION_LABEL = "Screenshot translation"
private const val MANGA_SCREENSHOT_IMAGE_MIME_TYPE = "image/png"
private const val MANGA_SCREENSHOT_OUTPUT_MAX_EDGE_PX = 2048
private const val MANGA_SCREENSHOT_OUTPUT_MAX_AREA_PX = 4_000_000

/**
 * The mokuro manga reader once the book is loaded: a page WebView, RTL page navigation,
 * dictionary-lookup popups, reader chrome and debounced bookmark persistence.
 *
 * Position persistence reuses the EPUB [moe.antimony.hoshi.epub.Bookmark] schema unchanged
 * — `chapterIndex` carries the 0-based page index (see [mangaBookmark]). Saves are debounced
 * so flicking through pages does not hammer disk; [onBookmarkSaved] fires after each write.
 */
@Composable
internal fun MangaReaderScreen(
    book: MokuroBook,
    bookRoot: File,
    initialPageIndex: Int,
    repository: BookRepository,
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    dictionarySettings: DictionarySettings,
    onReaderKeyEventHandlerChange: (((KeyEvent) -> Boolean)?) -> Unit,
    onBookmarkSaved: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val backgroundColor = Color(readerSettings.backgroundColor(systemDark))
    val backgroundCssColor = remember(backgroundColor) { backgroundColor.toCssHex() }
    val pageCount = book.pages.size
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle

    var pageIndex by remember(book) {
        mutableIntStateOf(initialPageIndex.coerceIn(0, book.pages.lastIndex.coerceAtLeast(0)))
    }
    val statisticsPageCounterState = remember(book) { mutableIntStateOf(0) }
    var statisticsPageCounter by statisticsPageCounterState
    var webView by remember { mutableStateOf<WebView?>(null) }
    var lookupPopups by remember(book) { mutableStateOf<List<LookupPopupItem>>(emptyList()) }
    var lookupSelectionJob by remember(book) { mutableStateOf<Job?>(null) }
    var lookupSelectionRequest by remember(book) { mutableIntStateOf(0) }
    // A page turn in flight: the snapshot of the page being left, which slides off while the
    // WebView (already reloading to the new page) slides in. Null except during the slide.
    var pageTransition by remember(book) { mutableStateOf<MangaPageTransition?>(null) }
    // The transition the slide is actually driving. It trails `pageTransition` by the one
    // frame in which the LaunchedEffect below snaps the slide back to its start: until they
    // match, the offset modifiers force progress to 0 so the snapshot covers the reloading
    // WebView from the very first frame, instead of briefly flashing it at full size.
    var animatingTransition by remember(book) { mutableStateOf<MangaPageTransition?>(null) }
    // The incoming page must finish its first draw before the slide starts; otherwise the
    // artwork can visibly resize while the page script settles its aspect-correct frame.
    var readyTransition by remember(book) { mutableStateOf<MangaPageTransition?>(null) }
    // Drives the slide 0f (just started) -> 1f (settled); read in the offset modifiers below.
    val transitionProgress = remember { Animatable(0f) }

    // ChatGPT speech-bubble feature. Deliberately self-contained — its own settings repo and
    // per-manga history store (see features/ai) — so it never touches shared/upstream files.
    val aiSettingsRepository = remember { context.applicationContext.aiChatSettingsRepository() }
    val aiSettings by aiSettingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
    val aiHistoryStore = remember { AiChatHistoryStore() }
    // The ChatGPT popup state (null = no popup), the in-flight request, this manga's chat
    // history, and whether the history / settings overlays are open.
    var aiChatState by remember(book) { mutableStateOf<AiChatUiState?>(null) }
    var aiRequestJob by remember(book) { mutableStateOf<Job?>(null) }
    var aiRetryAction by remember(book) { mutableStateOf<(() -> Unit)?>(null) }
    var aiHistory by remember(book) { mutableStateOf<List<AiChatEntry>>(emptyList()) }
    var showAiHistory by remember(book) { mutableStateOf(false) }
    var showStatistics by remember(book) { mutableStateOf(false) }
    var showGoToPageDialog by remember(book) { mutableStateOf(false) }
    var screenshotCropMode by remember(book) { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    // A scope that outlives the reader route, used only to flush a pending bookmark save on
    // exit — rememberCoroutineScope is cancelled on dispose, which would drop the save.
    val persistenceScope = LocalHoshiAppContainer.current.appScope
    var bookmarkSaveJob by remember(book) { mutableStateOf<Job?>(null) }
    // The page index awaiting the debounced bookmark write, or null when nothing is pending.
    val pendingBookmarkPage = remember(book) { mutableStateOf<Int?>(null) }
    val currentOnBookmarkSaved = rememberUpdatedState(onBookmarkSaved)
    // FORK ADDITION: v2 KV HTTP sync auto-push hooks. See features/sync/http/HttpSyncReaderHooks.kt
    // for the every-5 / on-leave / on-chat counter logic — kept out of this file so upstream
    // merges don't have to reason about it.
    val httpSyncHooks = rememberHttpSyncReaderHooks(bookRoot, book.title, persistenceScope)
    val mangaImageResolver = remember(book, bookRoot) { MangaWebResourceBridge(bookRoot, book) }
    val pageRenderCache = remember(book) { MangaPageRenderCache() }

    var persistedStatistics by remember(bookRoot) { mutableStateOf<List<ReadingStatistics>?>(null) }
    LaunchedEffect(bookRoot, readerSettings.enableStatistics) {
        persistedStatistics = null
    }
    LaunchedEffect(bookRoot, repository, readerSettings.enableStatistics) {
        persistedStatistics = if (readerSettings.enableStatistics) {
            repository.loadStatistics(bookRoot)
        } else {
            emptyList()
        }
    }
    val statisticsTracker = remember(
        bookRoot,
        book.title,
        readerSettings.enableStatistics,
        persistedStatistics,
    ) {
        if (!readerSettings.enableStatistics) {
            null
        } else {
            persistedStatistics?.let { statistics ->
                ReaderStatisticsTracker(
                    title = book.title,
                    initialStatistics = statistics,
                    enabled = true,
                )
            }
        }
    }
    var statisticsState by remember(statisticsTracker) { mutableStateOf(statisticsTracker?.state) }
    var resumeStatisticsTrackingOnStart by remember(statisticsTracker) { mutableStateOf(false) }

    fun enableStatisticsFromSheet() {
        persistedStatistics = null
        onReaderSettingsChange(readerSettings.withStatisticsEnabled(true))
    }

    fun syncStatisticsState() {
        statisticsState = statisticsTracker?.state
    }

    fun recordStatisticsAtCounter(counter: Int) {
        statisticsTracker?.update(counter)
        syncStatisticsState()
    }

    fun statisticsForSave(counter: Int, syncState: Boolean = true): List<ReadingStatistics>? {
        statisticsTracker?.update(counter)
        if (syncState) {
            syncStatisticsState()
        }
        return statisticsTracker?.statisticsForPersistenceOrNull()
    }

    fun toggleStatisticsTracking() {
        val tracker = statisticsTracker ?: return
        val currentPosition = statisticsPageCounter
        if (tracker.state.isTracking) {
            tracker.stop(currentPosition)
            syncStatisticsState()
            val statistics = tracker.statisticsForPersistenceOrNull()
            if (statistics != null) {
                persistenceScope.launch {
                    repository.saveStatistics(bookRoot, statistics)
                }
            }
        } else {
            tracker.start(currentPosition)
            syncStatisticsState()
        }
    }

    fun startStatisticsForPageTurnIfNeeded(fromCounter: Int) {
        if (readerSettings.statisticsAutostartMode == StatisticsAutostartMode.PageTurn) {
            statisticsTracker?.startForPageTurnIfNeeded(fromCounter)
            syncStatisticsState()
        }
    }

    val currentStatisticsForDispose = rememberUpdatedState<(Boolean) -> List<ReadingStatistics>?> { syncState ->
        statisticsForSave(counter = statisticsPageCounterState.intValue, syncState = syncState)
    }

    fun scheduleBookmarkSave(index: Int) {
        pendingBookmarkPage.value = index
        bookmarkSaveJob?.cancel()
        bookmarkSaveJob = scope.launch {
            delay(BOOKMARK_SAVE_DEBOUNCE_MS)
            repository.saveBookmark(
                bookRoot,
                mangaBookmark(index, repository.currentAppleReferenceDateSeconds()),
            )
            statisticsForSave(statisticsPageCounter)?.let { statistics ->
                repository.saveStatistics(bookRoot, statistics)
            }
            pendingBookmarkPage.value = null
            currentOnBookmarkSaved.value()
            httpSyncHooks.onPageTurnPersisted()
        }
    }

    fun clearSelectionAndPopups() {
        lookupSelectionJob?.cancel()
        lookupSelectionRequest += 1
        webView?.clearMangaSelection()
        lookupPopups = emptyList()
    }

    fun goToPage(index: Int) {
        val clamped = index.coerceIn(0, book.pages.lastIndex.coerceAtLeast(0))
        if (clamped == pageIndex) return
        val direction = if (clamped > pageIndex) {
            ReaderNavigationDirection.Forward
        } else {
            ReaderNavigationDirection.Backward
        }
        clearSelectionAndPopups()
        // Snapshot the outgoing page so it can slide off over the incoming page. Skipped on
        // e-ink or when the user has disabled page-turn animation, and when the WebView is
        // not laid out yet — either way `pageTransition` stays null and the page simply swaps.
        val snapshot = webView
            ?.takeIf { view ->
                shouldCaptureMangaPageTurnSnapshot(
                    settings = readerSettings,
                    width = view.width,
                    height = view.height,
                )
            }
            ?.let(::captureWebViewBitmap)
        val transition = snapshot?.let { MangaPageTransition(it.asImageBitmap(), direction) }
        pageTransition = transition
        readyTransition = null
        val previousPageIndex = pageIndex
        val previousStatisticsCounter = statisticsPageCounter
        startStatisticsForPageTurnIfNeeded(previousStatisticsCounter)
        statisticsPageCounter = mangaStatisticsCounterAfterPageChange(
            currentCounter = previousStatisticsCounter,
            fromPageIndex = previousPageIndex,
            toPageIndex = clamped,
        )
        pageIndex = clamped
        recordStatisticsAtCounter(statisticsPageCounter)
        scheduleBookmarkSave(clamped)
    }

    fun navigate(direction: ReaderNavigationDirection): Boolean {
        val target = MangaPageNavigation.targetIndex(pageIndex, pageCount, direction) ?: return false
        goToPage(target)
        return true
    }

    fun dismissAiChat() {
        aiRequestJob?.cancel()
        aiRetryAction = null
        aiChatState = null
    }

    /**
     * Sends a tapped speech bubble to ChatGPT, shows the popup, and on success appends the
     * exchange to this manga's history. Dismissing the popup cancels an in-flight request,
     * and the coroutine bails without touching state once cancelled.
     */
    fun askAi(bubbleText: String) {
        aiRetryAction = { askAi(bubbleText) }
        val settings = aiSettings
        if (settings == null) {
            // DataStore's first emission is async; a tap in that brief window would otherwise
            // do nothing at all. Tell the user to retry instead of leaving a dead button.
            aiChatState = AiChatUiState.Failed(
                bubbleText,
                "ChatGPT is still loading — tap again in a moment.",
            )
            return
        }
        if (!settings.isConfigured) {
            aiChatState = AiChatUiState.Failed(
                bubbleText,
                "Set your OpenAI API key first in Settings → ChatGPT.",
            )
            return
        }
        aiRequestJob?.cancel()
        aiChatState = AiChatUiState.Loading(bubbleText)
        aiRequestJob = scope.launch {
            val dictionaryLookup = async(Dispatchers.IO) {
                buildAiChatDictionaryLookup(bubbleText, dictionarySettings)
            }
            val result = runCatching {
                OpenAiChatClient.complete(
                    apiKey = settings.apiKey,
                    model = settings.model,
                    prompt = settings.promptText,
                    bubbleText = bubbleText,
                )
            }
            // Bail without touching state if the popup was dismissed mid-request.
            if (!isActive) return@launch
            result.fold(
                onSuccess = { response ->
                    val entry = AiChatEntry(
                        bubbleText = bubbleText,
                        prompt = settings.promptText,
                        model = settings.model,
                        response = response,
                        timestampSeconds = repository.currentAppleReferenceDateSeconds(),
                        dictionaryLookup = dictionaryLookup.await(),
                    )
                    aiChatState = AiChatUiState.Loaded(entry)
                    // Persist into this manga's history. A disk failure here must not crash
                    // the reader — the reply is already shown — so keep the existing history
                    // on failure, while still letting cancellation propagate normally.
                    val appended = runCatching { aiHistoryStore.append(bookRoot, entry).entries }
                        .getOrElse { error ->
                            if (error is CancellationException) throw error
                            null
                        }
                    if (appended != null) {
                        aiHistory = appended
                        // FORK ADDITION: push this one chat entry to the v2 KV sync server.
                        httpSyncHooks.onChatEntryPersisted(entry)
                    }
                },
                onFailure = { error ->
                    dictionaryLookup.cancel()
                    aiChatState = AiChatUiState.Failed(
                        bubbleText,
                        error.message ?: "ChatGPT request failed.",
                    )
                },
            )
        }
    }

    fun retryScreenshotTranslation(imageBase64: String, prompt: String) {
        val retrySettings = aiSettings
        if (retrySettings == null) {
            aiChatState = AiChatUiState.Failed(
                MANGA_SCREENSHOT_TRANSLATION_LABEL,
                "ChatGPT is still loading — tap again in a moment.",
            )
            return
        }
        if (!retrySettings.isConfigured) {
            aiChatState = AiChatUiState.Failed(
                MANGA_SCREENSHOT_TRANSLATION_LABEL,
                "Set your OpenAI API key first in Settings → ChatGPT.",
            )
            return
        }
        aiRetryAction = { retryScreenshotTranslation(imageBase64, prompt) }
        aiRequestJob?.cancel()
        aiChatState = AiChatUiState.Loading(MANGA_SCREENSHOT_TRANSLATION_LABEL)
        aiRequestJob = scope.launch {
            val result = runCatching {
                OpenAiChatClient.completeImage(
                    apiKey = retrySettings.apiKey,
                    model = retrySettings.model,
                    prompt = prompt,
                    imageBase64 = imageBase64,
                    imageMimeType = MANGA_SCREENSHOT_IMAGE_MIME_TYPE,
                )
            }
            if (!isActive) return@launch
            result.fold(
                onSuccess = { response ->
                    val entry = AiChatEntry(
                        bubbleText = MANGA_SCREENSHOT_TRANSLATION_LABEL,
                        prompt = prompt,
                        model = retrySettings.model,
                        response = response,
                        timestampSeconds = repository.currentAppleReferenceDateSeconds(),
                        screenshotImage = AiChatImage(
                            mimeType = MANGA_SCREENSHOT_IMAGE_MIME_TYPE,
                            base64Data = imageBase64,
                        ),
                    )
                    aiChatState = AiChatUiState.Loaded(entry)
                    val appended = runCatching { aiHistoryStore.append(bookRoot, entry).entries }
                        .getOrElse { error ->
                            if (error is CancellationException) throw error
                            null
                        }
                    if (appended != null) {
                        aiHistory = appended
                        httpSyncHooks.onChatEntryPersisted(entry)
                    }
                },
                onFailure = { error ->
                    aiChatState = AiChatUiState.Failed(
                        MANGA_SCREENSHOT_TRANSLATION_LABEL,
                        error.message ?: "ChatGPT request failed.",
                    )
                },
            )
        }
    }

    fun translateScreenshotCrop(rect: MangaScreenshotCropRect) {
        aiRetryAction = { translateScreenshotCrop(rect) }
        val settings = aiSettings
        if (settings == null) {
            aiChatState = AiChatUiState.Failed(
                MANGA_SCREENSHOT_TRANSLATION_LABEL,
                "ChatGPT is still loading — tap again in a moment.",
            )
            return
        }
        if (!settings.isConfigured) {
            aiChatState = AiChatUiState.Failed(
                MANGA_SCREENSHOT_TRANSLATION_LABEL,
                "Set your OpenAI API key first in Settings → ChatGPT.",
            )
            return
        }
        val currentWebView = webView
        if (currentWebView == null) {
            aiChatState = AiChatUiState.Failed(
                MANGA_SCREENSHOT_TRANSLATION_LABEL,
                "The manga page is not ready yet.",
            )
            return
        }
        if (book.pages.isEmpty()) {
            aiChatState = AiChatUiState.Failed(
                MANGA_SCREENSHOT_TRANSLATION_LABEL,
                "The manga page is not ready yet.",
            )
            return
        }
        aiRequestJob?.cancel()
        val prompt = settings.imagePromptText.trim().ifBlank { AiChatSettings.DEFAULT_IMAGE_PROMPT }
        aiChatState = AiChatUiState.Loading(MANGA_SCREENSHOT_TRANSLATION_LABEL)
        aiRequestJob = scope.launch {
            val imageCrop = currentWebView.evaluateMangaImageCropRect(rect)
            if (!isActive) return@launch
            val imageFile = imageCrop
                ?.let { crop -> book.pages.firstOrNull { it.index == crop.pageIndex } }
                ?.let { page -> mangaImageResolver.resolveDeclaredImageFile(page.imagePath) }
            val imageBase64 = withContext(Dispatchers.Default) {
                if (imageCrop == null || imageFile == null) {
                    null
                } else {
                    cropMangaImageFilePng(
                        imageFile = imageFile,
                        crop = imageCrop,
                        maxOutputWidth = rect.width,
                        maxOutputHeight = rect.height,
                    )
                }?.let { bytes ->
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            }
            if (!isActive) return@launch
            if (imageBase64 == null) {
                aiChatState = AiChatUiState.Failed(
                    MANGA_SCREENSHOT_TRANSLATION_LABEL,
                    "Could not capture the selected area.",
                )
                return@launch
            }
            aiRetryAction = { retryScreenshotTranslation(imageBase64, prompt) }
            aiChatState = AiChatUiState.Loading(MANGA_SCREENSHOT_TRANSLATION_LABEL)
            val result = runCatching {
                OpenAiChatClient.completeImage(
                    apiKey = settings.apiKey,
                    model = settings.model,
                    prompt = prompt,
                    imageBase64 = imageBase64,
                    imageMimeType = MANGA_SCREENSHOT_IMAGE_MIME_TYPE,
                )
            }
            if (!isActive) return@launch
            result.fold(
                onSuccess = { response ->
                    val entry = AiChatEntry(
                        bubbleText = MANGA_SCREENSHOT_TRANSLATION_LABEL,
                        prompt = prompt,
                        model = settings.model,
                        response = response,
                        timestampSeconds = repository.currentAppleReferenceDateSeconds(),
                        screenshotImage = AiChatImage(
                            mimeType = MANGA_SCREENSHOT_IMAGE_MIME_TYPE,
                            base64Data = imageBase64,
                        ),
                    )
                    aiChatState = AiChatUiState.Loaded(entry)
                    val appended = runCatching { aiHistoryStore.append(bookRoot, entry).entries }
                        .getOrElse { error ->
                            if (error is CancellationException) throw error
                            null
                        }
                    if (appended != null) {
                        aiHistory = appended
                        httpSyncHooks.onChatEntryPersisted(entry)
                    }
                },
                onFailure = { error ->
                    aiChatState = AiChatUiState.Failed(
                        MANGA_SCREENSHOT_TRANSLATION_LABEL,
                        error.message ?: "ChatGPT request failed.",
                    )
                },
            )
        }
    }

    val lookupOptions = LookupPopupOptions(
        isVertical = false,
        width = readerSettings.popupWidth,
        height = readerSettings.popupHeight,
        dictionarySettings = dictionarySettings,
        darkMode = readerSettings.usesDarkInterface(systemDark),
        eInkMode = readerSettings.eInkMode,
        documentTitle = book.title,
    )

    fun lookupPopupFor(selection: ReaderSelectionData): Pair<LookupPopupItem, Int>? =
        createLookupPopupItem(selection = selection, options = lookupOptions)

    val handleTextSelected: (ReaderSelectionData, WebView) -> Unit = { selection, sourceWebView ->
        lookupSelectionJob?.cancel()
        lookupSelectionRequest += 1
        val request = lookupSelectionRequest
        lookupPopups = emptyList()
        lookupSelectionJob = scope.launch {
            val lookup = withContext(Dispatchers.IO) {
                lookupPopupFor(selection)
            }
            if (!isActive || request != lookupSelectionRequest || sourceWebView !== webView) return@launch
            if (lookup != null) {
                val (popup, highlightCount) = lookup
                lookupPopups = listOf(popup)
                sourceWebView.evaluateJavascript(
                    ReaderSelectionCommand.HighlightSelection(highlightCount).source,
                    null,
                )
            } else {
                lookupPopups = emptyList()
            }
        }
    }

    // Volume-key / page-key navigation, wired through the host the same way the EPUB reader
    // does (see ReaderHardwareKeyNavigation): a Forward action turns the page forward.
    val currentKeyHandler = rememberUpdatedState<(KeyEvent) -> Boolean> { event ->
        if (screenshotCropMode) {
            true
        } else {
            val action = readerHardwareKeyActionForKeyEvent(
                keyCode = event.keyCode,
                action = event.action,
                repeatCount = event.repeatCount,
                settings = readerSettings,
                sasayakiEnabled = false,
                hasSasayakiAudio = false,
            )
            when (action) {
                is ReaderHardwareKeyAction.ReaderNavigation -> navigate(action.direction)
                else -> false
            }
        }
    }
    DisposableEffect(onReaderKeyEventHandlerChange) {
        onReaderKeyEventHandlerChange { event -> currentKeyHandler.value(event) }
        onDispose { onReaderKeyEventHandlerChange(null) }
    }
    LaunchedEffect(statisticsTracker, readerSettings.statisticsAutostartMode) {
        if (readerSettings.enableStatistics && readerSettings.statisticsAutostartMode == StatisticsAutostartMode.On) {
            statisticsTracker?.start(statisticsPageCounterState.intValue)
            syncStatisticsState()
        }
    }
    LaunchedEffect(statisticsTracker, statisticsState?.isTracking) {
        val tracker = statisticsTracker ?: return@LaunchedEffect
        if (tracker.state.isTracking) {
            while (tracker.state.isTracking) {
                delay(1_000)
                tracker.update(statisticsPageCounterState.intValue)
                syncStatisticsState()
            }
        }
    }
    DisposableEffect(lifecycle, statisticsTracker, bookRoot) {
        val tracker = statisticsTracker
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (tracker != null) {
                        val paused = tracker.pause(statisticsPageCounterState.intValue)
                        if (paused) {
                            resumeStatisticsTrackingOnStart = true
                            syncStatisticsState()
                        }
                        val statistics = tracker.statisticsForPersistenceOrNull()
                        if (statistics != null) {
                            persistenceScope.launch {
                                repository.saveStatistics(bookRoot, statistics)
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_START -> {
                    if (resumeStatisticsTrackingOnStart && tracker != null) {
                        resumeStatisticsTrackingOnStart = false
                        tracker.start(statisticsPageCounterState.intValue)
                        syncStatisticsState()
                    }
                }
                else -> Unit
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
        }
    }
    DisposableEffect(context, view, lifecycle) {
        val activity = context.findHoshiActivity()
        val window = activity?.window
        val controller = window?.let { currentWindow ->
            WindowCompat.getInsetsController(currentWindow, view)
        }
        val previousSystemBarsBehavior = controller?.systemBarsBehavior
        fun applyReaderSystemBars() {
            if (readerShouldUseImmersiveSystemBars(focusMode = false, immersiveReaderContent = true)) {
                controller?.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        applyReaderSystemBars()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                applyReaderSystemBars()
            }
        }
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            if (previousSystemBarsBehavior != null) {
                controller?.systemBarsBehavior = previousSystemBarsBehavior
            }
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Flush a still-pending debounced bookmark save when the reader is left, so closing it
    // within the debounce window doesn't lose the last page turn. rememberCoroutineScope is
    // cancelled on dispose, so the flush runs on the app-lifetime persistence scope.
    DisposableEffect(book, bookRoot) {
        onDispose {
            bookmarkSaveJob?.cancel()
            lookupSelectionJob?.cancel()
            val unsaved = pendingBookmarkPage.value
            val statistics = currentStatisticsForDispose.value(false)
            if (unsaved != null) {
                pendingBookmarkPage.value = null
                persistenceScope.launch {
                    repository.saveBookmark(
                        bookRoot,
                        mangaBookmark(unsaved, repository.currentAppleReferenceDateSeconds()),
                    )
                    if (statistics != null) {
                        repository.saveStatistics(bookRoot, statistics)
                    }
                    // FORK ADDITION: force-push the bookmark to the v2 KV sync server after
                    // the local save, so leaving the reader doesn't lose accumulated turns.
                    httpSyncHooks.onLeave()
                }
            } else {
                if (statistics != null) {
                    persistenceScope.launch {
                        repository.saveStatistics(bookRoot, statistics)
                    }
                }
                // No pending debounced save, but we may still have unpushed turns from
                // earlier saves that fired before the threshold was reached.
                httpSyncHooks.onLeave()
            }
        }
    }
    LaunchedEffect(book, bookRoot) {
        // Record the opened page so "recent" ordering reflects the visit even if the reader
        // is closed before turning a page. Routed through the same debounced + flush-on-exit
        // path as page turns, so the open save and a quick page turn never race to disk.
        scheduleBookmarkSave(pageIndex)
    }
    LaunchedEffect(book, bookRoot) {
        // Load this manga's ChatGPT history so the ⋯ menu can show it.
        aiHistory = aiHistoryStore.load(bookRoot).entries
    }
    // Drive the page-turn slide: snap to the start, adopt the transition (which lets the
    // offset modifiers start reading the live progress), animate to settled, then drop the
    // snapshot. Re-keys on `pageTransition`, so a fast second turn restarts the slide cleanly.
    LaunchedEffect(pageTransition, readyTransition) {
        val transition = pageTransition ?: return@LaunchedEffect
        if (transition !== readyTransition) return@LaunchedEffect
        transitionProgress.snapTo(0f)
        animatingTransition = transition
        transitionProgress.animateTo(1f, tween(durationMillis = MANGA_PAGE_TURN_DURATION_MS))
        pageTransition = null
        animatingTransition = null
        readyTransition = null
    }

    BackHandler {
        // The ChatGPT history / settings overlays own their own back handling (via
        // SettingsDetailScaffold); this handles the ChatGPT popup and lookup popups.
        when {
            screenshotCropMode -> screenshotCropMode = false
            showStatistics -> showStatistics = false
            aiChatState != null -> dismissAiChat()
            lookupPopups.isNotEmpty() -> clearSelectionAndPopups()
            else -> onClose()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        val canTakeScreenshot = webView != null &&
            pageTransition == null &&
            animatingTransition == null

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            val activeTransition = pageTransition
            val animating = animatingTransition
            val containerWidthPx = constraints.maxWidth
            // The WebView's viewport size in CSS pixels — Dp values are 1:1 with CSS px for a
            // WebView at default scale. Passed into the page so `.frame` is sized from a
            // known-good size instead of a possibly-stale `window.innerWidth` during a
            // page-turn reload. If a device keeps a top system bar visible, systemBars
            // reduces this viewport so the page artwork is contained below that bar instead
            // of being clipped behind it; when immersive mode succeeds these insets are
            // empty and manga uses the full screen.
            val viewportCssWidth = maxWidth.value.roundToInt()
            val viewportCssHeight = maxHeight.value.roundToInt()
            val renderConfig = remember(
                backgroundCssColor,
                dictionarySettings.scanNonJapaneseText,
                readerSettings.eInkMode,
                viewportCssWidth,
                viewportCssHeight,
                readerSettings.mangaSingleTapLookup,
                readerSettings.mangaUseNotoSansJp,
            ) {
                MangaPageRenderConfig(
                    backgroundCssColor = backgroundCssColor,
                    scanNonJapaneseText = dictionarySettings.scanNonJapaneseText,
                    eInkMode = readerSettings.eInkMode,
                    viewportCssWidth = viewportCssWidth,
                    viewportCssHeight = viewportCssHeight,
                    singleTapLookup = readerSettings.mangaSingleTapLookup,
                    useNotoSansJpFont = readerSettings.mangaUseNotoSansJp,
                )
            }
            LaunchedEffect(book, pageIndex, renderConfig, mangaImageResolver) {
                pageRenderCache.preloadAdjacentPages(
                    book = book,
                    pageIndexes = mangaAdjacentPreloadIndexes(pageIndex, pageCount),
                    config = renderConfig,
                    imageResolver = mangaImageResolver,
                )
            }
            // Slide direction for a right-to-left manga, modelled as a filmstrip with page 1 at
            // the right: a forward turn slides the outgoing page off to the *right* and pulls the
            // incoming page in from the left; a backward turn does the reverse. The incoming page
            // slides in from the opposite edge, so the two stay edge to edge with no gap.
            // `transitionProgress` is read inside the offset lambdas so each animation frame only
            // re-lays-out, never recomposes.
            val leavingSign =
                if (activeTransition?.direction == ReaderNavigationDirection.Backward) -1 else 1

            MangaReaderWebView(
                book = book,
                bookRoot = bookRoot,
                pageIndex = pageIndex,
                renderConfig = renderConfig,
                pageRenderCache = pageRenderCache,
                onNavigate = { direction -> navigate(direction) },
                onTextSelected = handleTextSelected,
                onSelectionCleared = {
                    lookupSelectionJob?.cancel()
                    lookupSelectionRequest += 1
                    lookupPopups = emptyList()
                },
                onAskAi = { bubbleText -> askAi(bubbleText) },
                onPageReady = { readyPageIndex ->
                    if (pageTransition != null && readyPageIndex == pageIndex) {
                        readyTransition = pageTransition
                    }
                },
                onWebViewReady = { webView = it },
                modifier = Modifier
                    .fillMaxSize()
                    .offset {
                        val transition = activeTransition ?: return@offset IntOffset.Zero
                        // Hold progress at 0 until the LaunchedEffect has adopted this transition,
                        // so the snapshot below covers the reloading WebView from the first frame.
                        val progress =
                            if (transition === animating) transitionProgress.value else 0f
                        val entering = -leavingSign * containerWidthPx * (1f - progress)
                        IntOffset(entering.roundToInt(), 0)
                    },
            )

            if (activeTransition != null) {
                // The outgoing page, drawn on top of the (incoming) WebView and slid off-screen.
                Image(
                    bitmap = activeTransition.snapshot,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset {
                            val progress = if (activeTransition === animating) {
                                transitionProgress.value
                            } else {
                                0f
                            }
                            val leaving = leavingSign * containerWidthPx * progress
                            IntOffset(leaving.roundToInt(), 0)
                        },
                )
            }

            LookupPopupAndroidStack(
                popups = lookupPopups,
                onPopupsChange = { lookupPopups = it },
                lookupChildPopup = ::lookupPopupFor,
                onRootPopupDismissed = {
                    // Clear the in-page selection highlight, then return false so the stack view
                    // still removes the dismissed popup from the list (the manga reader has no
                    // separate root-popup teardown to own the dismissal).
                    webView?.clearMangaSelection()
                    false
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (!screenshotCropMode) {
            MangaReaderCloseButton(
                darkInterface = readerSettings.usesDarkInterface(systemDark),
                onClose = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 4.dp, top = 4.dp)
                    .zIndex(1f),
            )
            // Screenshot translation is the most-used ChatGPT action, so once a key is
            // configured it gets its own button next to the ⋯ menu instead of being buried
            // in it. Without a key the feature is unusable, so it stays inside the menu.
            val startScreenshotCrop = {
                if (canTakeScreenshot) {
                    clearSelectionAndPopups()
                    webView?.clearMangaRevealedBubbles()
                    screenshotCropMode = true
                }
            }
            val aiConfigured = aiSettings?.isConfigured == true
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 4.dp, top = 4.dp)
                    .zIndex(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (aiConfigured) {
                    MangaReaderScreenshotButton(
                        darkInterface = readerSettings.usesDarkInterface(systemDark),
                        enabled = canTakeScreenshot,
                        onClick = startScreenshotCrop,
                    )
                }
                MangaReaderOverflowMenu(
                    darkInterface = readerSettings.usesDarkInterface(systemDark),
                    showTakeScreenshot = !aiConfigured,
                    takeScreenshotEnabled = canTakeScreenshot,
                    onTakeScreenshot = startScreenshotCrop,
                    onShowAiHistory = { showAiHistory = true },
                    onShowStatistics = { showStatistics = true },
                    onShowGoToPage = { showGoToPageDialog = true },
                )
            }

            MangaReaderPageTurnButton(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = "Next page",
                darkInterface = readerSettings.usesDarkInterface(systemDark),
                enabled = pageIndex < pageCount - 1,
                onClick = { navigate(ReaderNavigationDirection.Forward) },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 8.dp, bottom = 4.dp)
                    .zIndex(1f),
            )
            MangaReaderPageIndicator(
                pageIndex = pageIndex,
                pageCount = pageCount,
                darkInterface = readerSettings.usesDarkInterface(systemDark),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 22.dp)
                    .zIndex(1f),
            )
            MangaReaderPageTurnButton(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Previous page",
                darkInterface = readerSettings.usesDarkInterface(systemDark),
                enabled = pageIndex > 0,
                onClick = { navigate(ReaderNavigationDirection.Backward) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 8.dp, bottom = 4.dp)
                    .zIndex(1f),
            )
        }

        if (screenshotCropMode) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .zIndex(2f),
            ) {
                val screenshotContainerWidthPx =
                    webView?.width?.takeIf { it > 0 } ?: constraints.maxWidth
                val screenshotContainerHeightPx =
                    webView?.height?.takeIf { it > 0 } ?: constraints.maxHeight
                MangaScreenshotCropOverlay(
                    containerWidthPx = screenshotContainerWidthPx,
                    containerHeightPx = screenshotContainerHeightPx,
                    darkInterface = readerSettings.usesDarkInterface(systemDark),
                    onCancel = { screenshotCropMode = false },
                    onConfirm = { rect ->
                        screenshotCropMode = false
                        translateScreenshotCrop(rect)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ChatGPT overlays. The response popup sits above the page and the lookup popups;
        // the history screen is full-screen and sits above everything. ChatGPT settings now
        // live in the main Settings tab (Settings → ChatGPT), not in this reader.
        val activeAiChat = aiChatState
        if (activeAiChat != null) {
            AiChatPopupView(
                state = activeAiChat,
                onDismiss = { dismissAiChat() },
                onRetry = { aiRetryAction?.invoke() },
                modifier = Modifier.zIndex(3f),
            )
        }
        if (showAiHistory) {
            AiChatHistoryView(
                entries = aiHistory,
                lookupOptions = lookupOptions,
                onClose = { showAiHistory = false },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f),
            )
        }
        if (showStatistics) {
            MangaStatisticsSheet(
                state = statisticsState,
                statisticsEnabled = readerSettings.enableStatistics,
                pageIndex = pageIndex,
                pageCount = pageCount,
                onEnableStatistics = ::enableStatisticsFromSheet,
                onToggleTracking = ::toggleStatisticsTracking,
                onDismiss = { showStatistics = false },
            )
        }
        if (showGoToPageDialog) {
            MangaGoToPageDialog(
                currentPage = pageIndex + 1,
                pageCount = pageCount,
                onDismiss = { showGoToPageDialog = false },
                onConfirm = { page ->
                    showGoToPageDialog = false
                    goToPage(page - 1)
                },
            )
        }
    }
}

/**
 * Number-entry dialog for the "Go to page…" overflow item. 1-based to match the
 * `X / Y` page indicator at the bottom of the reader. Pre-fills with the current page so
 * the user can edit instead of retyping. Cancel/Backspace/empty-input is a no-op.
 */
@Composable
private fun MangaGoToPageDialog(
    currentPage: Int,
    pageCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var input by remember { mutableStateOf(currentPage.toString()) }
    val parsed = input.toIntOrNull()?.takeIf { it in 1..pageCount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { new -> input = new.filter { it.isDigit() }.take(6) },
                singleLine = true,
                label = { Text("Page (1–$pageCount)") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { parsed?.let(onConfirm) }),
                isError = input.isNotEmpty() && parsed == null,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = parsed != null,
            ) { Text("Go") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Floating top-left close affordance. Kept as an independent node so the transparent top
 * chrome does not create a full-width input layer over the manga page.
 */
@Composable
private fun MangaReaderCloseButton(
    darkInterface: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (darkInterface) Color.White else Color.Black
    IconButton(
        onClick = onClose,
        modifier = modifier.background(mangaFloatingControlBackground(darkInterface), CircleShape),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Close manga reader",
            tint = contentColor,
        )
    }
}

/**
 * Floating top-right ⋯ overflow menu for manga statistics and ChatGPT history. Its layout
 * bounds are just the menu anchor and popup, not a transparent full-width toolbar.
 *
 * "Take screenshot" only appears here when [showTakeScreenshot] is true — i.e. no ChatGPT key
 * is configured yet. Once a key exists the screenshot action is promoted to its own button
 * (see [MangaReaderScreenshotButton]). ChatGPT settings live in the main Settings tab.
 * Single-tap-lookup and Noto-Sans-JP-font are durable preferences and live in
 * Settings → Behavior, not here — both are stable choices, not per-session knobs.
 */
@Composable
private fun MangaReaderOverflowMenu(
    darkInterface: Boolean,
    showTakeScreenshot: Boolean,
    takeScreenshotEnabled: Boolean,
    onTakeScreenshot: () -> Unit,
    onShowAiHistory: () -> Unit,
    onShowStatistics: () -> Unit,
    onShowGoToPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (darkInterface) Color.White else Color.Black
    Box(modifier = modifier) {
        var menuExpanded by remember { mutableStateOf(false) }
        IconButton(
            onClick = { menuExpanded = true },
            modifier = Modifier.background(mangaFloatingControlBackground(darkInterface), CircleShape),
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "More options",
                tint = contentColor,
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            if (showTakeScreenshot) {
                DropdownMenuItem(
                    text = { Text("Take screenshot") },
                    enabled = takeScreenshotEnabled,
                    onClick = {
                        menuExpanded = false
                        onTakeScreenshot()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Go to page…") },
                onClick = {
                    menuExpanded = false
                    onShowGoToPage()
                },
            )
            DropdownMenuItem(
                text = { Text("Statistics") },
                onClick = {
                    menuExpanded = false
                    onShowStatistics()
                },
            )
            DropdownMenuItem(
                text = { Text("ChatGPT history") },
                onClick = {
                    menuExpanded = false
                    onShowAiHistory()
                },
            )
        }
    }
}

/**
 * Floating top-right screenshot-translation button, shown next to the ⋯ menu once a ChatGPT
 * API key is configured. Mirrors the other floating manga controls' circular background.
 */
@Composable
private fun MangaReaderScreenshotButton(
    darkInterface: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (darkInterface) Color.White else Color.Black
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.background(mangaFloatingControlBackground(darkInterface), CircleShape),
    ) {
        Icon(
            imageVector = Icons.Rounded.Screenshot,
            contentDescription = "Take screenshot",
            tint = contentColor.copy(alpha = if (enabled) 1f else 0.38f),
        )
    }
}

/**
 * Floating bottom page-turn button. Kept as an independent node so the transparent bottom
 * chrome does not create a full-width input layer over the manga page.
 *
 * Page turning lives on dedicated buttons (not taps on the page) so tapping a word for
 * dictionary lookup can never move the page. Manga reads right-to-left, so the left-hand
 * button advances to the next page and the right-hand button goes back, matching the swipe
 * direction. Buttons disable at the first / last page.
 */
@Composable
private fun MangaReaderPageTurnButton(
    imageVector: ImageVector,
    contentDescription: String,
    darkInterface: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (darkInterface) Color.White else Color.Black
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(56.dp)
            .background(mangaFloatingControlBackground(darkInterface), CircleShape),
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = contentColor.copy(alpha = if (enabled) 1f else 0.38f),
        )
    }
}

@Composable
private fun MangaReaderPageIndicator(
    pageIndex: Int,
    pageCount: Int,
    darkInterface: Boolean,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (darkInterface) Color.White else Color.Black
    Text(
        text = "${(pageIndex + 1).coerceAtMost(pageCount)} / $pageCount",
        color = contentColor,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .background(mangaFloatingControlBackground(darkInterface), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private fun mangaFloatingControlBackground(darkInterface: Boolean): Color =
    if (darkInterface) {
        Color.Black.copy(alpha = 0.48f)
    } else {
        Color(0xFFF1F3F4).copy(alpha = 0.92f)
    }

internal fun shouldAnimateMangaPageTurns(settings: ReaderSettings): Boolean =
    !settings.eInkMode && !settings.disablePageTurnAnimation

internal fun shouldCaptureMangaPageTurnSnapshot(
    settings: ReaderSettings,
    width: Int,
    height: Int,
): Boolean {
    if (!shouldAnimateMangaPageTurns(settings)) return false
    if (width <= 0 || height <= 0) return false
    return width.toLong() * height.toLong() <= MANGA_PAGE_TURN_SNAPSHOT_MAX_PIXELS
}

private fun Color.toCssHex(): String {
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    return "#%02x%02x%02x".format(r, g, b)
}

/** Duration of the manga page-turn slide. Long enough to read as a page turn, not a jump. */
private const val MANGA_PAGE_TURN_DURATION_MS = 800
private const val MANGA_PAGE_TURN_SNAPSHOT_MAX_PIXELS = 1_500_000L

/**
 * A manga page turn in flight: [snapshot] is the page being left — drawn on top of the
 * WebView (which is already reloading to the new page) and slid off-screen — and [direction]
 * is which way it goes. A forward turn slides it right, a backward turn slides it left.
 */
private data class MangaPageTransition(
    val snapshot: ImageBitmap,
    val direction: ReaderNavigationDirection,
)

/**
 * Snapshots [view]'s current pixels into a bitmap so the page it shows can keep being drawn
 * while the WebView reloads to the next page underneath the slide. Returns null when the
 * WebView is not laid out yet (nothing to capture) or the draw fails.
 */
internal fun captureWebViewBitmap(view: WebView): Bitmap? {
    val width = view.width
    val height = view.height
    if (width <= 0 || height <= 0) return null
    // The snapshot is only the frozen "previous page" during a page-turn slide on opaque
    // manga artwork — RGB_565 (2 bytes/px) halves the per-turn allocation vs the default
    // ARGB_8888 (4 bytes/px). Saves ~5 MB on a 1080×2400 viewport.
    return runCatching {
        val bitmap = createBitmap(width, height, Bitmap.Config.RGB_565)
        view.draw(Canvas(bitmap))
        bitmap
    }.getOrNull()
}

/**
 * Source-image crop in intrinsic manga image pixels. The crop overlay is drawn in host
 * WebView pixels, while pinch zoom and pan live in WebView's visual viewport, so the page
 * script converts the host rectangle into this source-image rectangle before we decode.
 */
internal data class MangaImageCropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val pageIndex: Int = 0,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal suspend fun WebView.evaluateMangaImageCropRect(
    rect: MangaScreenshotCropRect,
): MangaImageCropRect? =
    withContext(Dispatchers.Main.immediate) {
        val hostWidth = width
        val hostHeight = height
        if (hostWidth <= 0 || hostHeight <= 0) return@withContext null
        val script = """
            (function() {
              if (!window.hoshiManga || !window.hoshiManga.imageCropFromHostRect) {
                return null;
              }
              return JSON.stringify(window.hoshiManga.imageCropFromHostRect(
                ${rect.left},
                ${rect.top},
                ${rect.right},
                ${rect.bottom},
                $hostWidth,
                $hostHeight
              ));
            })();
        """.trimIndent()
        val encoded = suspendCancellableCoroutine<String?> { continuation ->
            evaluateJavascript(script) { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }
        parseMangaImageCropRectResult(encoded)
    }

internal fun parseMangaImageCropRectResult(encodedResult: String?): MangaImageCropRect? {
    val encoded = encodedResult ?: return null
    if (encoded == "null") return null
    val jsonText = runCatching { Json.decodeFromString<String>(encoded) }
        .getOrElse { encoded }
    if (jsonText == "null") return null
    val obj = runCatching { Json.parseToJsonElement(jsonText).jsonObject }.getOrNull()
        ?: return null
    val left = obj["left"]?.jsonPrimitive?.intOrNull ?: return null
    val top = obj["top"]?.jsonPrimitive?.intOrNull ?: return null
    val right = obj["right"]?.jsonPrimitive?.intOrNull ?: return null
    val bottom = obj["bottom"]?.jsonPrimitive?.intOrNull ?: return null
    val pageIndex = obj["pageIndex"]?.jsonPrimitive?.intOrNull ?: return null
    val crop = MangaImageCropRect(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        pageIndex = pageIndex,
    )
    return crop.takeIf {
        it.width >= MANGA_SCREENSHOT_CROP_MIN_SIZE_PX &&
            it.height >= MANGA_SCREENSHOT_CROP_MIN_SIZE_PX
    }
}

@Suppress("DEPRECATION")
internal fun cropMangaImageFilePng(
    imageFile: File,
    crop: MangaImageCropRect,
    maxOutputWidth: Int = crop.width,
    maxOutputHeight: Int = crop.height,
): ByteArray? {
    if (!imageFile.isFile) return null
    val decoder = runCatching {
        BitmapRegionDecoder.newInstance(imageFile.absolutePath, false)
    }.getOrNull()
    if (decoder == null) {
        return cropFullDecodedMangaImageFilePng(
            imageFile = imageFile,
            crop = crop,
            maxOutputWidth = maxOutputWidth,
            maxOutputHeight = maxOutputHeight,
        )
    }
    var decodedBitmap: Bitmap? = null
    var outputBitmap: Bitmap? = null
    try {
        val region = Rect(
            crop.left.coerceIn(0, decoder.width),
            crop.top.coerceIn(0, decoder.height),
            crop.right.coerceIn(0, decoder.width),
            crop.bottom.coerceIn(0, decoder.height),
        )
        if (region.width() < MANGA_SCREENSHOT_CROP_MIN_SIZE_PX ||
            region.height() < MANGA_SCREENSHOT_CROP_MIN_SIZE_PX
        ) {
            return null
        }
        val bitmap = decoder.decodeRegion(region, BitmapFactory.Options()) ?: return null
        decodedBitmap = bitmap
        outputBitmap = bitmap.scaledMangaScreenshotCrop(maxOutputWidth, maxOutputHeight)
        val encodedBitmap = outputBitmap ?: bitmap
        return ByteArrayOutputStream().use { output ->
            if (!encodedBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                null
            } else {
                output.toByteArray()
            }
        }
    } finally {
        if (outputBitmap !== decodedBitmap) {
            outputBitmap?.recycle()
        }
        decodedBitmap?.recycle()
        decoder.recycle()
    }
}

private fun cropFullDecodedMangaImageFilePng(
    imageFile: File,
    crop: MangaImageCropRect,
    maxOutputWidth: Int,
    maxOutputHeight: Int,
): ByteArray? {
    var sourceBitmap: Bitmap? = null
    var croppedBitmap: Bitmap? = null
    var outputBitmap: Bitmap? = null
    try {
        val source = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return null
        sourceBitmap = source
        val region = Rect(
            crop.left.coerceIn(0, source.width),
            crop.top.coerceIn(0, source.height),
            crop.right.coerceIn(0, source.width),
            crop.bottom.coerceIn(0, source.height),
        )
        if (region.width() < MANGA_SCREENSHOT_CROP_MIN_SIZE_PX ||
            region.height() < MANGA_SCREENSHOT_CROP_MIN_SIZE_PX
        ) {
            return null
        }
        val cropped = Bitmap.createBitmap(source, region.left, region.top, region.width(), region.height())
        croppedBitmap = cropped
        outputBitmap = cropped.scaledMangaScreenshotCrop(maxOutputWidth, maxOutputHeight)
        val encodedBitmap = outputBitmap ?: cropped
        return ByteArrayOutputStream().use { output ->
            if (!encodedBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                null
            } else {
                output.toByteArray()
            }
        }
    } finally {
        if (outputBitmap !== croppedBitmap) {
            outputBitmap?.recycle()
        }
        croppedBitmap?.recycle()
        sourceBitmap?.recycle()
    }
}

private fun Bitmap.scaledMangaScreenshotCrop(
    maxOutputWidth: Int,
    maxOutputHeight: Int,
): Bitmap? {
    if (width <= 0 || height <= 0) return null
    val widthLimit = maxOutputWidth.takeIf { it > 0 } ?: width
    val heightLimit = maxOutputHeight.takeIf { it > 0 } ?: height
    val area = width.toDouble() * height.toDouble()
    val scale = minOf(
        1.0,
        widthLimit.toDouble() / width.toDouble(),
        heightLimit.toDouble() / height.toDouble(),
        MANGA_SCREENSHOT_OUTPUT_MAX_EDGE_PX.toDouble() / maxOf(width, height).toDouble(),
        sqrt(MANGA_SCREENSHOT_OUTPUT_MAX_AREA_PX.toDouble() / area),
    )
    if (scale >= 0.999) return this
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(MANGA_SCREENSHOT_CROP_MIN_SIZE_PX)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(MANGA_SCREENSHOT_CROP_MIN_SIZE_PX)
    if (targetWidth == width && targetHeight == height) return this
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}

internal fun cropWebViewBitmapPng(bitmap: Bitmap, rect: MangaScreenshotCropRect): ByteArray? {
    var cropped: Bitmap? = null
    try {
        val crop = normalizedMangaScreenshotCropRect(
            startX = rect.left.toFloat(),
            startY = rect.top.toFloat(),
            endX = rect.right.toFloat(),
            endY = rect.bottom.toFloat(),
            containerWidth = bitmap.width,
            containerHeight = bitmap.height,
        ) ?: return null
        val croppedBitmap = runCatching {
            Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
        }.getOrNull() ?: return null
        cropped = croppedBitmap
        return ByteArrayOutputStream().use { output ->
            if (!croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                null
            } else {
                output.toByteArray()
            }
        }
    } finally {
        if (cropped !== bitmap) {
            cropped?.recycle()
        }
        bitmap.recycle()
    }
}
