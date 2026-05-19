package moe.antimony.hoshi.features.mangareader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.view.View
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
import moe.antimony.hoshi.features.reader.ReaderSelectionScripts
import moe.antimony.hoshi.features.reader.ReaderSwipeGestureTracker
import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults
import java.io.File

private const val MANGA_SWIPE_MIN_DISTANCE = 72f
private const val MANGA_NAVIGATION_MAX_ZOOM = 1.01f

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
    renderConfig: MangaPageRenderConfig,
    pageRenderCache: MangaPageRenderCache,
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
    // Re-keyed on the viewport size so a real viewport change (rotation) reloads the page
    // with fresh dimensions baked in — see MangaPageHtml's frame-sizing script.
    val html = remember(
        page,
        renderConfig,
        pageRenderCache,
    ) {
        pageRenderCache.htmlFor(page, renderConfig)
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            MangaWebView(context).apply {
                applyHoshiWebViewSecurityDefaults()
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                // OCR font sizes are derived from mokuro's image coordinates; Android's
                // text zoom would resize only the DOM text, not the artwork it must track.
                settings.textZoom = 100
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
                addJavascriptInterface(
                    MangaTapBridge { currentOnSelectionCleared.value() },
                    "HoshiMangaTap",
                )
                webViewClient = MangaWebViewClient(resourceBridge) { readyPageIndex ->
                    currentOnPageReady.value(readyPageIndex)
                }
                attachMangaTouchListener(currentOnNavigate)
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
                webView.post { webView.syncMangaHostScale() }
            }
        },
    )
}

private class MangaWebView(context: Context) : WebView(context) {
    private var baselineScale: Float? = null

    fun adoptMangaScaleBaseline(scale: Float) {
        if (baselineScale == null && scale.isFinite() && scale > 0f) {
            baselineScale = scale
        }
    }

    fun mangaZoomScale(): Float {
        val current = rawMangaScale()
        val baseline = baselineScale?.takeIf { it.isFinite() && it > 0f }
            ?: current.takeIf { it.isFinite() && it > 0f }?.also { baselineScale = it }
            ?: 1f
        return current / baseline
    }
}

private data class MangaPageLoadToken(
    val pageIndex: Int,
    val htmlHash: Int,
)

@SuppressLint("ClickableViewAccessibility")
private fun WebView.attachMangaTouchListener(
    onNavigate: androidx.compose.runtime.State<(ReaderNavigationDirection) -> Unit>,
) {
    val webView = this
    setOnTouchListener(
        object : View.OnTouchListener {
            private val tracker = ReaderSwipeGestureTracker(minDistance = MANGA_SWIPE_MIN_DISTANCE)
            private val navigationGate = MangaTouchNavigationGate()

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                val decision = navigationGate.onTouch(
                    action = event.toMangaTouchAction(),
                    pointerCount = event.pointerCount,
                )
                if (decision == MangaTouchNavigationDecision.CancelTracking) {
                    tracker.onCancel()
                    return false
                }

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> tracker.onDown(event.x, event.y, event.eventTime)
                    MotionEvent.ACTION_MOVE -> dispatch(tracker.onMove(event.x, event.y, event.eventTime))
                    MotionEvent.ACTION_UP -> dispatch(tracker.onUp(event.x, event.y))
                    MotionEvent.ACTION_CANCEL -> tracker.onCancel()
                }
                return false
            }

            private fun dispatch(result: ReaderSwipeGestureTracker.Result) {
                when (result) {
                    ReaderSwipeGestureTracker.Result.LeftSwipe -> {
                        dispatchSwipe(MangaSwipeDirection.Left)
                    }
                    ReaderSwipeGestureTracker.Result.RightSwipe -> {
                        dispatchSwipe(MangaSwipeDirection.Right)
                    }
                    is ReaderSwipeGestureTracker.Result.Tap -> Unit
                    ReaderSwipeGestureTracker.Result.None -> Unit
                }
            }

            private fun dispatchSwipe(direction: MangaSwipeDirection) {
                if (!webView.shouldDispatchMangaSwipe()) {
                    tracker.onCancel()
                    return
                }
                onNavigate.value(MangaPageNavigation.directionForSwipe(direction))
            }
        },
    )
}

@Suppress("DEPRECATION")
private fun WebView.rawMangaScale(): Float = scale

private fun WebView.mangaZoomScale(): Float =
    (this as? MangaWebView)?.mangaZoomScale()
        ?: 1f

private fun WebView.shouldDispatchMangaSwipe(): Boolean =
    shouldDispatchMangaSwipe(
        canScrollLeft = canScrollHorizontally(-1),
        canScrollRight = canScrollHorizontally(1),
        zoomScale = mangaZoomScale(),
    )

internal fun shouldDispatchMangaSwipe(
    canScrollLeft: Boolean,
    canScrollRight: Boolean,
    zoomScale: Float,
): Boolean =
    !canScrollLeft &&
        !canScrollRight &&
        (zoomScale.isNaN() || zoomScale <= MANGA_NAVIGATION_MAX_ZOOM)

private fun WebView.syncMangaHostScale() {
    val scale = mangaZoomScale()
        .takeIf { it.isFinite() && it > 0f }
        ?: 1f
    evaluateJavascript(
        "window.hoshiManga && window.hoshiManga.setHostScale($scale)",
        null,
    )
}

private fun MotionEvent.toMangaTouchAction(): MangaTouchAction =
    when (actionMasked) {
        MotionEvent.ACTION_DOWN -> MangaTouchAction.Down
        MotionEvent.ACTION_MOVE -> MangaTouchAction.Move
        MotionEvent.ACTION_UP -> MangaTouchAction.Up
        MotionEvent.ACTION_POINTER_DOWN -> MangaTouchAction.PointerDown
        MotionEvent.ACTION_POINTER_UP -> MangaTouchAction.PointerUp
        MotionEvent.ACTION_CANCEL -> MangaTouchAction.Cancel
        else -> MangaTouchAction.Other
    }

/** Clears any active in-page text selection (mirrors the EPUB reader's clear command). */
internal fun WebView.clearMangaSelection() {
    evaluateJavascript(ReaderSelectionCommand.ClearSelection.source, null)
}

/** Hides any revealed OCR bubble plates/action buttons before taking a raw page crop. */
internal fun WebView.clearMangaRevealedBubbles() {
    evaluateJavascript(
        "window.hoshiManga && window.hoshiManga.clearRevealed && window.hoshiManga.clearRevealed()",
        null,
    )
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
        view.syncMangaHostScale()
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

    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
        super.onScaleChanged(view, oldScale, newScale)
        (view as? MangaWebView)?.adoptMangaScaleBaseline(oldScale)
        view.syncMangaHostScale()
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
