package moe.antimony.hoshi.features.mangareader

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import moe.antimony.hoshi.features.reader.ReaderNavigationDirection
import moe.antimony.hoshi.features.reader.ReaderSelectionBridge
import moe.antimony.hoshi.features.reader.ReaderSelectionCommand
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionResult
import moe.antimony.hoshi.features.reader.ReaderSelectionScripts
import moe.antimony.hoshi.features.reader.SwipePageTouchListener
import moe.antimony.hoshi.features.reader.androidPixelsToCssPixels
import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults
import java.io.File

private const val MANGA_MAX_SELECTION_LENGTH = 16

/**
 * The WebView that renders one mokuro manga page at a time.
 *
 * Mirrors the EPUB reader's `ChapterWebView` one-content-load-per-page pattern: each page
 * is a self-contained HTML document ([MangaPageHtml]) loaded via `loadDataWithBaseURL` with
 * the `https://hoshi.local/manga/` base URL, so the page image URL is intercepted by
 * [MangaWebResourceBridge]. Navigating pages just reloads the WebView with the next page's
 * HTML.
 *
 * Text selection -> dictionary lookup reuses the shared EPUB mechanism verbatim: the
 * injected [ReaderSelectionScripts] source plus a [ReaderSelectionBridge] bound to the
 * `HoshiTextSelection` JavaScript interface. A tap selects the word under the finger and
 * the caller turns that [ReaderSelectionData] into a lookup popup.
 *
 * Right-to-left navigation: a left swipe / left-edge tap moves *forward* in reading order,
 * a right swipe / right-edge tap moves *backward* — see [MangaPageNavigation].
 */
@Composable
internal fun MangaReaderWebView(
    book: MokuroBook,
    bookRoot: File,
    pageIndex: Int,
    backgroundCssColor: String,
    scanNonJapaneseText: Boolean,
    onNavigate: (ReaderNavigationDirection) -> Unit,
    onTextSelected: (ReaderSelectionData) -> Int?,
    onSelectionCleared: () -> Unit,
    onWebViewReady: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnNavigate = rememberUpdatedState(onNavigate)
    val currentOnTextSelected = rememberUpdatedState(onTextSelected)
    val currentOnSelectionCleared = rememberUpdatedState(onSelectionCleared)

    val resourceBridge = remember(book, bookRoot) { MangaWebResourceBridge(bookRoot, book) }

    val page = book.pages[pageIndex.coerceIn(0, book.pages.lastIndex)]
    val html = remember(page, backgroundCssColor, scanNonJapaneseText) {
        MangaPageHtml.build(
            page = page,
            backgroundCssColor = backgroundCssColor,
            selectionScript = ReaderSelectionScripts.source(),
            scanNonJapaneseText = scanNonJapaneseText,
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                applyHoshiWebViewSecurityDefaults()
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                // Deliberately NOT setting useWideViewPort / loadWithOverviewMode: those make
                // the WebView size its layout viewport from a <meta viewport> tag (for zooming
                // desktop pages to fit) and leave CSS vh / % heights resolving to 0 for our
                // own generated document. Left at defaults, the layout viewport is the
                // WebView's own size, so 100vh / height:100% work.
                addJavascriptInterface(
                    ReaderSelectionBridge(this) { selection ->
                        currentOnTextSelected.value(selection)
                    },
                    "HoshiTextSelection",
                )
                webViewClient = MangaWebViewClient(resourceBridge)
                attachMangaTouchListener(currentOnNavigate, currentOnSelectionCleared)
                onWebViewReady(this)
            }
        },
        update = { webView ->
            // Re-attach in case the bridge instance changed (book / root recomposition).
            if (webView.webViewClient !is MangaWebViewClient) {
                webView.webViewClient = MangaWebViewClient(resourceBridge)
            }
            webView.attachMangaTouchListener(currentOnNavigate, currentOnSelectionCleared)
            val loadKey = "${page.index}#${html.hashCode()}"
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.loadDataWithBaseURL(
                    MangaPageHtml.BASE_URL,
                    html,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
    )
}

@SuppressLint("ClickableViewAccessibility")
private fun WebView.attachMangaTouchListener(
    onNavigate: androidx.compose.runtime.State<(ReaderNavigationDirection) -> Unit>,
    onSelectionCleared: androidx.compose.runtime.State<() -> Unit>,
) {
    val webView = this
    setOnTouchListener(
        object : SwipePageTouchListener() {
            override fun onTap(x: Float, y: Float) {
                // OCR text boxes can sit anywhere on the page, including the edge zones, so
                // always try to select the word under the tap first. Only a tap that hits no
                // OCR text falls through to edge-zone page turning (or clearing the popup).
                val viewWidth = webView.width.toFloat().coerceAtLeast(1f)
                webView.selectAt(x, y) {
                    val tapDirection = MangaPageNavigation.directionForTap(x / viewWidth)
                    if (tapDirection != null) {
                        onNavigate.value(tapDirection)
                    } else {
                        onSelectionCleared.value()
                    }
                }
            }

            override fun onLeftSwipe() {
                onNavigate.value(MangaPageNavigation.directionForSwipe(MangaSwipeDirection.Left))
            }

            override fun onRightSwipe() {
                onNavigate.value(MangaPageNavigation.directionForSwipe(MangaSwipeDirection.Right))
            }
        },
    )
}

/**
 * Asks the in-page selection script to select the word at ([x], [y]) (Android pixels). When
 * a word is selected the shared selection bridge delivers it for dictionary lookup; when the
 * tap hits no OCR text, [onSelectedNothing] runs so the caller can fall back to navigation.
 */
private fun WebView.selectAt(x: Float, y: Float, onSelectedNothing: () -> Unit) {
    val density = resources.displayMetrics.density
    evaluateJavascript(
        ReaderSelectionCommand.SelectText(
            x = androidPixelsToCssPixels(x, density),
            y = androidPixelsToCssPixels(y, density),
            maxLength = MANGA_MAX_SELECTION_LENGTH,
        ).source,
    ) { result ->
        if (ReaderSelectionResult.fromWebViewResult(result).selectedNothing) {
            onSelectedNothing()
        }
    }
}

/** Clears any active in-page text selection (mirrors the EPUB reader's clear command). */
internal fun WebView.clearMangaSelection() {
    evaluateJavascript(ReaderSelectionCommand.ClearSelection.source, null)
}

private class MangaWebViewClient(
    private val resourceBridge: MangaWebResourceBridge,
) : WebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        return resourceBridge.resourceForUrl(url)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // Never override the page's own document load: loadDataWithBaseURL uses the
        // hoshi.local base URL, and returning true for that load cancels it and blanks the
        // WebView. Only a genuine outbound navigation (a different host) is blocked.
        return request.url?.host != MangaWebResourceBridge.HOST
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        view.destroy()
        return true
    }
}
