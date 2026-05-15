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
 * Text selection -> dictionary lookup reuses the shared EPUB mechanism: the injected
 * [ReaderSelectionScripts] source plus a [ReaderSelectionBridge] bound to the
 * `HoshiTextSelection` JavaScript interface. OCR text is invisible until tapped — a tap
 * goes through `window.hoshiManga.handleTap` ([MangaPageHtml]): the first tap on a bubble
 * just reveals it, a second tap on that revealed bubble looks the tapped word up, a tap on
 * the copy button copies the bubble via the `HoshiMangaClipboard` interface
 * ([MangaClipboardBridge]), and a tap on empty artwork hides every revealed bubble.
 *
 * Right-to-left navigation: a right swipe moves *forward* in reading order and a left swipe
 * moves *backward* — see [MangaPageNavigation]. A tap never turns the page; it is reserved
 * for the OCR interactions above, so page turning is driven by swipes, the chrome buttons,
 * and the hardware page/volume keys.
 */
@Composable
internal fun MangaReaderWebView(
    book: MokuroBook,
    bookRoot: File,
    pageIndex: Int,
    backgroundCssColor: String,
    scanNonJapaneseText: Boolean,
    eInkMode: Boolean,
    onNavigate: (ReaderNavigationDirection) -> Unit,
    onTextSelected: (ReaderSelectionData) -> Int?,
    onSelectionCleared: () -> Unit,
    onAskAi: (String) -> Unit,
    onPageReady: (Int) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnNavigate = rememberUpdatedState(onNavigate)
    val currentOnTextSelected = rememberUpdatedState(onTextSelected)
    val currentOnSelectionCleared = rememberUpdatedState(onSelectionCleared)
    val currentOnAskAi = rememberUpdatedState(onAskAi)
    val currentOnPageReady = rememberUpdatedState(onPageReady)

    val resourceBridge = remember(book, bookRoot) { MangaWebResourceBridge(bookRoot, book) }

    val page = book.pages[pageIndex.coerceIn(0, book.pages.lastIndex)]
    val html = remember(page, backgroundCssColor, scanNonJapaneseText, eInkMode) {
        MangaPageHtml.build(
            page = page,
            backgroundCssColor = backgroundCssColor,
            selectionScript = ReaderSelectionScripts.source(),
            scanNonJapaneseText = scanNonJapaneseText,
            eInkMode = eInkMode,
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
                    // The shared selection bridge now hands back a `selectionRects` callback
                    // for the EPUB reader's Compose highlight overlay; the manga reader does
                    // not use that — it highlights the matched word with the in-page CSS
                    // Custom Highlight (see MangaPageHtml's `::highlight(hoshi-selection)`),
                    // so it ignores `selectionRects` and applies the highlight here.
                    ReaderSelectionBridge(this) { selection, _ ->
                        val highlightCount = currentOnTextSelected.value(selection)
                        if (highlightCount != null) {
                            evaluateJavascript(
                                ReaderSelectionCommand.HighlightSelection(highlightCount).source,
                                null,
                            )
                        }
                    },
                    "HoshiTextSelection",
                )
                addJavascriptInterface(
                    // Lets a revealed bubble's copy button copy the whole bubble's OCR text.
                    MangaClipboardBridge(context),
                    "HoshiMangaClipboard",
                )
                addJavascriptInterface(
                    // Lets a revealed bubble's ChatGPT button send the bubble text for a
                    // lookup; MangaReaderScreen turns it into the ChatGPT popup.
                    MangaAiBridge { bubbleText -> currentOnAskAi.value(bubbleText) },
                    "HoshiMangaAi",
                )
                webViewClient = MangaWebViewClient(resourceBridge) { readyPageIndex ->
                    currentOnPageReady.value(readyPageIndex)
                }
                attachMangaTouchListener(currentOnNavigate, currentOnSelectionCleared)
                onWebViewReady(this)
            }
        },
        onRelease = { webView ->
            // Tear the WebView down when the reader leaves composition: it holds a
            // JavaScript interface and, via the Activity context, can otherwise leak
            // until GC. destroy() also stops any in-flight load.
            webView.destroy()
        },
        update = { webView ->
            // The WebViewClient and touch listener are attached once in factory(); both
            // read rememberUpdatedState values, so they stay current without being
            // re-allocated on every recomposition. update() only swaps the page content.
            val loadToken = MangaPageLoadToken(
                pageIndex = page.index,
                htmlHash = html.hashCode(),
            )
            if (webView.tag != loadToken) {
                webView.tag = loadToken
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

private data class MangaPageLoadToken(
    val pageIndex: Int,
    val htmlHash: Int,
)

@SuppressLint("ClickableViewAccessibility")
private fun WebView.attachMangaTouchListener(
    onNavigate: androidx.compose.runtime.State<(ReaderNavigationDirection) -> Unit>,
    onSelectionCleared: androidx.compose.runtime.State<() -> Unit>,
) {
    val webView = this
    setOnTouchListener(
        object : SwipePageTouchListener() {
            override fun onTap(x: Float, y: Float) {
                // A tap never turns the page — it reveals/looks up/copies OCR text, or hides
                // revealed bubbles (see selectAt). Page turning is the chrome buttons,
                // swipes and the hardware page/volume keys, so a tap can't move the page.
                webView.selectAt(x, y) { onSelectionCleared.value() }
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
 * Routes a tap at ([x], [y]) (Android pixels) through the in-page manga tap handler
 * (`window.hoshiManga.handleTap`): the first tap on a bubble reveals it, a second tap on a
 * revealed bubble looks the word up, a copy-button tap copies the bubble, and a tap on empty
 * artwork hides every revealed bubble.
 *
 * [onSelectedNothing] runs when the tap selects no word — empty artwork, or a revealed bubble
 * with no character under the finger — so the caller can clear the lookup popup. A first-tap
 * reveal and a copy/ChatGPT-button hit report neither a selection nor "nothing", so they
 * leave any open popup untouched.
 */
private fun WebView.selectAt(x: Float, y: Float, onSelectedNothing: () -> Unit) {
    val density = resources.displayMetrics.density
    val cssX = androidPixelsToCssPixels(x, density)
    val cssY = androidPixelsToCssPixels(y, density)
    evaluateJavascript(
        "window.hoshiManga && window.hoshiManga.handleTap($cssX, $cssY, $MANGA_MAX_SELECTION_LENGTH)",
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
    private val onPageReady: (Int) -> Unit,
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

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        if (requestUrlHost(url) != MangaWebResourceBridge.HOST) return
        val loadToken = view.tag as? MangaPageLoadToken ?: return
        val requestId = nextMangaPageReadyRequestId()
        view.postVisualStateCallback(
            requestId,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (view.tag == loadToken) {
                        onPageReady(loadToken.pageIndex)
                    }
                }
            },
        )
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        view.destroy()
        return true
    }

    private fun requestUrlHost(url: String?): String? =
        url?.let(android.net.Uri::parse)?.host
}

private var mangaPageReadyRequestId = 0L

private fun nextMangaPageReadyRequestId(): Long {
    mangaPageReadyRequestId += 1L
    return mangaPageReadyRequestId
}
