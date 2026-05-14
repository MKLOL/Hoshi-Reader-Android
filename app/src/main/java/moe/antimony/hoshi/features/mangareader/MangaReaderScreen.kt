package moe.antimony.hoshi.features.mangareader

import android.view.KeyEvent
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.antimony.hoshi.epub.BookRepository
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
        mutableIntStateOf(initialPageIndex.coerceIn(0, book.pages.lastIndex))
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var lookupPopups by remember(book) { mutableStateOf<List<LookupPopupItem>>(emptyList()) }

    val scope = rememberCoroutineScope()
    var bookmarkSaveJob by remember(book) { mutableStateOf<Job?>(null) }
    val currentOnBookmarkSaved = rememberUpdatedState(onBookmarkSaved)

    fun scheduleBookmarkSave(index: Int) {
        bookmarkSaveJob?.cancel()
        bookmarkSaveJob = scope.launch {
            delay(BOOKMARK_SAVE_DEBOUNCE_MS)
            repository.saveBookmark(
                bookRoot,
                mangaBookmark(index, repository.currentAppleReferenceDateSeconds()),
            )
            currentOnBookmarkSaved.value()
        }
    }

    fun clearSelectionAndPopups() {
        webView?.clearMangaSelection()
        lookupPopups = emptyList()
    }

    fun goToPage(index: Int) {
        val clamped = index.coerceIn(0, book.pages.lastIndex)
        if (clamped == pageIndex) return
        clearSelectionAndPopups()
        pageIndex = clamped
        scheduleBookmarkSave(clamped)
    }

    fun navigate(direction: ReaderNavigationDirection): Boolean {
        val target = MangaPageNavigation.targetIndex(pageIndex, pageCount, direction) ?: return false
        goToPage(target)
        return true
    }

    val lookupOptions = LookupPopupOptions(
        isVertical = false,
        width = readerSettings.popupWidth,
        height = readerSettings.popupHeight,
        dictionarySettings = dictionarySettings,
        darkMode = readerSettings.usesDarkInterface(systemDark),
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

    // Persist the resume position when the reader is left, in case the debounce is pending.
    DisposableEffect(book, bookRoot) {
        onDispose {
            bookmarkSaveJob?.cancel()
        }
    }
    LaunchedEffect(book, bookRoot, initialPageIndex) {
        // Record the opened page so "recent" ordering reflects the visit even if the reader
        // is closed before turning a page.
        repository.saveBookmark(
            bookRoot,
            mangaBookmark(pageIndex, repository.currentAppleReferenceDateSeconds()),
        )
        currentOnBookmarkSaved.value()
    }

    BackHandler {
        if (lookupPopups.isNotEmpty()) {
            clearSelectionAndPopups()
        } else {
            onClose()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        MangaReaderWebView(
            book = book,
            bookRoot = bookRoot,
            pageIndex = pageIndex,
            backgroundCssColor = backgroundCssColor,
            scanNonJapaneseText = dictionarySettings.scanNonJapaneseText,
            onNavigate = { direction -> navigate(direction) },
            onTextSelected = handleTextSelected,
            onSelectionCleared = { lookupPopups = emptyList() },
            onWebViewReady = { webView = it },
            modifier = Modifier.fillMaxSize(),
        )

        LookupPopupStackView(
            popups = lookupPopups,
            onPopupsChange = { lookupPopups = it },
            lookupChildPopup = ::lookupPopupFor,
            onRootPopupDismissed = { webView?.clearMangaSelection() },
            modifier = Modifier.fillMaxSize(),
        )

        MangaReaderChrome(
            title = book.title,
            pageIndex = pageIndex,
            pageCount = pageCount,
            darkInterface = readerSettings.usesDarkInterface(systemDark),
            onClose = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(1f),
        )
    }
}

/** Minimal reader chrome for v1: a close affordance, the book title and a page indicator. */
@Composable
private fun MangaReaderChrome(
    title: String,
    pageIndex: Int,
    pageCount: Int,
    darkInterface: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (darkInterface) Color.White else Color.Black
    val scrim = if (darkInterface) {
        Color.Black.copy(alpha = 0.45f)
    } else {
        Color.White.copy(alpha = 0.55f)
    }
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
            Text(
                text = "${(pageIndex + 1).coerceAtMost(pageCount)} / $pageCount",
                color = contentColor,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
            )
        }
    }
}

private fun Color.toCssHex(): String {
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    return "#%02x%02x%02x".format(r, g, b)
}
