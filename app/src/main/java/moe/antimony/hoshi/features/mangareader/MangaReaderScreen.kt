package moe.antimony.hoshi.features.mangareader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.ai.AiChatHistoryView
import moe.antimony.hoshi.features.ai.AiChatPopupView
import moe.antimony.hoshi.features.ai.AiChatSettingsScreen
import moe.antimony.hoshi.features.ai.AiChatUiState
import moe.antimony.hoshi.features.ai.OpenAiChatClient
import moe.antimony.hoshi.features.ai.aiChatSettingsRepository
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupOptions
import moe.antimony.hoshi.features.dictionary.LookupPopupStackView
import moe.antimony.hoshi.features.dictionary.createLookupPopupItem
import moe.antimony.hoshi.features.reader.ReaderNavigationDirection
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.readerHardwareKeyActionForKeyEvent
import moe.antimony.hoshi.features.reader.ReaderHardwareKeyAction
import moe.antimony.hoshi.features.reader.usesDarkInterface
import moe.antimony.hoshi.mokuro.MokuroBook
import java.io.File
import kotlin.math.roundToInt

private const val BOOKMARK_SAVE_DEBOUNCE_MS = 400L

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

    var pageIndex by remember(book) {
        mutableIntStateOf(initialPageIndex.coerceIn(0, book.pages.lastIndex.coerceAtLeast(0)))
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var lookupPopups by remember(book) { mutableStateOf<List<LookupPopupItem>>(emptyList()) }
    // A page turn in flight: the snapshot of the page being left, which slides off while the
    // WebView (already reloading to the new page) slides in. Null except during the slide.
    var pageTransition by remember(book) { mutableStateOf<MangaPageTransition?>(null) }
    // The transition the slide is actually driving. It trails `pageTransition` by the one
    // frame in which the LaunchedEffect below snaps the slide back to its start: until they
    // match, the offset modifiers force progress to 0 so the snapshot covers the reloading
    // WebView from the very first frame, instead of briefly flashing it at full size.
    var animatingTransition by remember(book) { mutableStateOf<MangaPageTransition?>(null) }
    // Drives the slide 0f (just started) -> 1f (settled); read in the offset modifiers below.
    val transitionProgress = remember { Animatable(0f) }

    // ChatGPT speech-bubble feature. Deliberately self-contained — its own settings repo and
    // per-manga history store (see features/ai) — so it never touches shared/upstream files.
    val context = LocalContext.current
    val aiSettingsRepository = remember { context.applicationContext.aiChatSettingsRepository() }
    val aiSettings by aiSettingsRepository.settings.collectAsState(initial = null)
    val aiHistoryStore = remember { AiChatHistoryStore() }
    // The ChatGPT popup state (null = no popup), the in-flight request, this manga's chat
    // history, and whether the history / settings overlays are open.
    var aiChatState by remember(book) { mutableStateOf<AiChatUiState?>(null) }
    var aiRequestJob by remember(book) { mutableStateOf<Job?>(null) }
    var aiHistory by remember(book) { mutableStateOf<List<AiChatEntry>>(emptyList()) }
    var showAiHistory by remember(book) { mutableStateOf(false) }
    var showAiSettings by remember(book) { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    // A scope that outlives the reader route, used only to flush a pending bookmark save on
    // exit — rememberCoroutineScope is cancelled on dispose, which would drop the save.
    val persistenceScope = LocalHoshiAppContainer.current.appScope
    var bookmarkSaveJob by remember(book) { mutableStateOf<Job?>(null) }
    // The page index awaiting the debounced bookmark write, or null when nothing is pending.
    val pendingBookmarkPage = remember(book) { mutableStateOf<Int?>(null) }
    val currentOnBookmarkSaved = rememberUpdatedState(onBookmarkSaved)

    fun scheduleBookmarkSave(index: Int) {
        pendingBookmarkPage.value = index
        bookmarkSaveJob?.cancel()
        bookmarkSaveJob = scope.launch {
            delay(BOOKMARK_SAVE_DEBOUNCE_MS)
            repository.saveBookmark(
                bookRoot,
                mangaBookmark(index, repository.currentAppleReferenceDateSeconds()),
            )
            pendingBookmarkPage.value = null
            currentOnBookmarkSaved.value()
        }
    }

    fun clearSelectionAndPopups() {
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
        // e-ink (a slide just ghosts on a slow panel) and when the WebView is not laid out
        // yet — either way `pageTransition` stays null and the page simply swaps.
        val snapshot = if (readerSettings.eInkMode) {
            null
        } else {
            webView?.let(::captureWebViewBitmap)
        }
        pageTransition = snapshot?.let { MangaPageTransition(it.asImageBitmap(), direction) }
        pageIndex = clamped
        scheduleBookmarkSave(clamped)
    }

    fun navigate(direction: ReaderNavigationDirection): Boolean {
        val target = MangaPageNavigation.targetIndex(pageIndex, pageCount, direction) ?: return false
        goToPage(target)
        return true
    }

    fun dismissAiChat() {
        aiRequestJob?.cancel()
        aiChatState = null
    }

    /**
     * Sends a tapped speech bubble to ChatGPT, shows the popup, and on success appends the
     * exchange to this manga's history. Dismissing the popup cancels an in-flight request,
     * and the coroutine bails without touching state once cancelled.
     */
    fun askAi(bubbleText: String) {
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
                "Set your OpenAI API key first: open the ⋯ menu → ChatGPT settings.",
            )
            return
        }
        aiRequestJob?.cancel()
        aiChatState = AiChatUiState.Loading(bubbleText)
        aiRequestJob = scope.launch {
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
                    )
                    aiChatState = AiChatUiState.Loaded(entry)
                    // Persist into this manga's history. A disk failure here must not crash
                    // the reader — the reply is already shown — so keep the existing history
                    // on failure, while still letting cancellation propagate normally.
                    aiHistory = runCatching { aiHistoryStore.append(bookRoot, entry).entries }
                        .getOrElse { error ->
                            if (error is CancellationException) throw error
                            aiHistory
                        }
                },
                onFailure = { error ->
                    aiChatState = AiChatUiState.Failed(
                        bubbleText,
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

    val handleTextSelected: (ReaderSelectionData) -> Int? = { selection ->
        val lookup = lookupPopupFor(selection)
        if (lookup != null) {
            val (popup, highlightCount) = lookup
            lookupPopups = listOf(popup)
            highlightCount
        } else {
            lookupPopups = emptyList()
            null
        }
    }

    // Volume-key / page-key navigation, wired through the host the same way the EPUB reader
    // does (see ReaderHardwareKeyNavigation): a Forward action turns the page forward.
    val currentKeyHandler = rememberUpdatedState<(KeyEvent) -> Boolean> { event ->
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
    DisposableEffect(onReaderKeyEventHandlerChange) {
        onReaderKeyEventHandlerChange { event -> currentKeyHandler.value(event) }
        onDispose { onReaderKeyEventHandlerChange(null) }
    }

    // Flush a still-pending debounced bookmark save when the reader is left, so closing it
    // within the debounce window doesn't lose the last page turn. rememberCoroutineScope is
    // cancelled on dispose, so the flush runs on the app-lifetime persistence scope.
    DisposableEffect(book, bookRoot) {
        onDispose {
            bookmarkSaveJob?.cancel()
            val unsaved = pendingBookmarkPage.value
            if (unsaved != null) {
                pendingBookmarkPage.value = null
                persistenceScope.launch {
                    repository.saveBookmark(
                        bookRoot,
                        mangaBookmark(unsaved, repository.currentAppleReferenceDateSeconds()),
                    )
                }
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
    LaunchedEffect(pageTransition) {
        val transition = pageTransition ?: return@LaunchedEffect
        transitionProgress.snapTo(0f)
        animatingTransition = transition
        transitionProgress.animateTo(1f, tween(durationMillis = MANGA_PAGE_TURN_DURATION_MS))
        pageTransition = null
        animatingTransition = null
    }

    BackHandler {
        // The ChatGPT history / settings overlays own their own back handling (via
        // SettingsDetailScaffold); this handles the ChatGPT popup and lookup popups.
        when {
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
        val activeTransition = pageTransition
        val animating = animatingTransition
        val containerWidthPx = constraints.maxWidth
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
            backgroundCssColor = backgroundCssColor,
            scanNonJapaneseText = dictionarySettings.scanNonJapaneseText,
            eInkMode = readerSettings.eInkMode,
            onNavigate = { direction -> navigate(direction) },
            onTextSelected = handleTextSelected,
            onSelectionCleared = { lookupPopups = emptyList() },
            onAskAi = { bubbleText -> askAi(bubbleText) },
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

        LookupPopupStackView(
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

        MangaReaderChrome(
            title = book.title,
            darkInterface = readerSettings.usesDarkInterface(systemDark),
            eInkMode = readerSettings.eInkMode,
            onClose = onClose,
            onShowAiHistory = { showAiHistory = true },
            onShowAiSettings = { showAiSettings = true },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .zIndex(1f),
        )

        MangaReaderBottomBar(
            pageIndex = pageIndex,
            pageCount = pageCount,
            darkInterface = readerSettings.usesDarkInterface(systemDark),
            eInkMode = readerSettings.eInkMode,
            onForward = { navigate(ReaderNavigationDirection.Forward) },
            onBackward = { navigate(ReaderNavigationDirection.Backward) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .zIndex(1f),
        )

        // ChatGPT overlays. The response popup sits above the page and the lookup popups;
        // the history / settings screens are full-screen and sit above everything.
        val activeAiChat = aiChatState
        if (activeAiChat != null) {
            AiChatPopupView(
                state = activeAiChat,
                onDismiss = { dismissAiChat() },
                onRetry = { askAi(activeAiChat.bubbleText) },
                modifier = Modifier.zIndex(3f),
            )
        }
        if (showAiHistory) {
            AiChatHistoryView(
                entries = aiHistory,
                onClose = { showAiHistory = false },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f),
            )
        }
        if (showAiSettings) {
            AiChatSettingsScreen(
                onClose = { showAiSettings = false },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f),
            )
        }
    }
}

/**
 * Top reader chrome: a close affordance, the book title, and a ⋯ overflow menu for the
 * ChatGPT history / settings (a fork addition, kept off the shared Settings navigation).
 */
@Composable
private fun MangaReaderChrome(
    title: String,
    darkInterface: Boolean,
    eInkMode: Boolean,
    onClose: () -> Unit,
    onShowAiHistory: () -> Unit,
    onShowAiSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (darkInterface) Color.White else Color.Black
    val scrim = mangaChromeScrim(darkInterface, eInkMode)
    Surface(
        modifier = modifier,
        color = scrim,
        contentColor = contentColor,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Close manga reader",
                    tint = contentColor,
                )
            }
            Text(
                text = title,
                color = contentColor,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 56.dp),
                maxLines = 1,
            )
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                var menuExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { menuExpanded = true }) {
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
                    DropdownMenuItem(
                        text = { Text("ChatGPT history") },
                        onClick = {
                            menuExpanded = false
                            onShowAiHistory()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("ChatGPT settings") },
                        onClick = {
                            menuExpanded = false
                            onShowAiSettings()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Bottom reader chrome: explicit previous / next page buttons plus the page indicator.
 *
 * Page turning lives on dedicated buttons (not taps on the page) so tapping a word for
 * dictionary lookup can never move the page. Manga reads right-to-left, so the left-hand
 * button advances to the next page and the right-hand button goes back, matching the swipe
 * direction. Buttons disable at the first / last page.
 */
@Composable
private fun MangaReaderBottomBar(
    pageIndex: Int,
    pageCount: Int,
    darkInterface: Boolean,
    eInkMode: Boolean,
    onForward: () -> Unit,
    onBackward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (darkInterface) Color.White else Color.Black
    val scrim = mangaChromeScrim(darkInterface, eInkMode)
    val canGoForward = pageIndex < pageCount - 1
    val canGoBackward = pageIndex > 0
    fun tint(enabled: Boolean) = contentColor.copy(alpha = if (enabled) 1f else 0.38f)
    Surface(
        modifier = modifier,
        color = scrim,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onForward,
                enabled = canGoForward,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowLeft,
                    contentDescription = "Next page",
                    tint = tint(canGoForward),
                )
            }
            Text(
                text = "${(pageIndex + 1).coerceAtMost(pageCount)} / $pageCount",
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onBackward,
                enabled = canGoBackward,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowRight,
                    contentDescription = "Previous page",
                    tint = tint(canGoBackward),
                )
            }
        }
    }
}

/**
 * Background for the reader chrome bars. On e-ink the bar is solid black/white — a
 * translucent scrim dithers to a muddy grey over the artwork — while a colour display keeps
 * the translucent scrim so the page edge still shows through.
 */
private fun mangaChromeScrim(darkInterface: Boolean, eInkMode: Boolean): Color = when {
    eInkMode -> if (darkInterface) Color.Black else Color.White
    darkInterface -> Color.Black.copy(alpha = 0.45f)
    else -> Color.White.copy(alpha = 0.55f)
}

private fun Color.toCssHex(): String {
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    return "#%02x%02x%02x".format(r, g, b)
}

/** Duration of the manga page-turn slide. Long enough to read as a page turn, not a jump. */
private const val MANGA_PAGE_TURN_DURATION_MS = 800

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
private fun captureWebViewBitmap(view: WebView): Bitmap? {
    val width = view.width
    val height = view.height
    if (width <= 0 || height <= 0) return null
    return runCatching {
        val bitmap = createBitmap(width, height)
        view.draw(Canvas(bitmap))
        bitmap
    }.getOrNull()
}
