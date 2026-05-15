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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import moe.antimony.hoshi.features.sync.http.rememberHttpSyncReaderHooks
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
    // The incoming page must finish its first draw before the slide starts; otherwise the
    // artwork can visibly resize while the page script settles its aspect-correct frame.
    var readyTransition by remember(book) { mutableStateOf<MangaPageTransition?>(null) }
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
    // FORK ADDITION: v2 KV HTTP sync auto-push hooks. See features/sync/http/HttpSyncReaderHooks.kt
    // for the every-5 / on-leave / on-chat counter logic — kept out of this file so upstream
    // merges don't have to reason about it.
    val httpSyncHooks = rememberHttpSyncReaderHooks(bookRoot, book.title, persistenceScope)

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
            httpSyncHooks.onPageTurnPersisted()
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
        // e-ink or when the user has disabled page-turn animation, and when the WebView is
        // not laid out yet — either way `pageTransition` stays null and the page simply swaps.
        val snapshot = if (!shouldAnimateMangaPageTurns(readerSettings)) {
            null
        } else {
            webView?.let(::captureWebViewBitmap)
        }
        val transition = snapshot?.let { MangaPageTransition(it.asImageBitmap(), direction) }
        pageTransition = transition
        readyTransition = null
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
                    // FORK ADDITION: force-push the bookmark to the v2 KV sync server after
                    // the local save, so leaving the reader doesn't lose accumulated turns.
                    httpSyncHooks.onLeave()
                }
            } else {
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
        // The WebView's viewport size in CSS pixels — Dp values are 1:1 with CSS px for a
        // WebView at default scale. Passed into the page so `.frame` is sized from a
        // known-good size instead of a possibly-stale `window.innerWidth` during a
        // page-turn reload, which would otherwise resize the incoming artwork mid-slide.
        val viewportCssWidth = maxWidth.value.roundToInt()
        val viewportCssHeight = maxHeight.value.roundToInt()
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
            viewportCssWidth = viewportCssWidth,
            viewportCssHeight = viewportCssHeight,
            onNavigate = { direction -> navigate(direction) },
            onTextSelected = handleTextSelected,
            onSelectionCleared = { lookupPopups = emptyList() },
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

        MangaReaderCloseButton(
            darkInterface = readerSettings.usesDarkInterface(systemDark),
            onClose = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 4.dp, top = 4.dp)
                .zIndex(1f),
        )
        MangaReaderOverflowMenu(
            darkInterface = readerSettings.usesDarkInterface(systemDark),
            onShowAiHistory = { showAiHistory = true },
            onShowAiSettings = { showAiSettings = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 4.dp, top = 4.dp)
                .zIndex(1f),
        )

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
 * Floating top-right overflow menu for ChatGPT history / settings. Its layout bounds are
 * just the menu anchor and popup, not a transparent full-width toolbar.
 */
@Composable
private fun MangaReaderOverflowMenu(
    darkInterface: Boolean,
    onShowAiHistory: () -> Unit,
    onShowAiSettings: () -> Unit,
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
