package moe.antimony.hoshi.features.news

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Renders a page in an off-screen WebView and runs `hoshi-news/extract.js` against its DOM.
 *
 * Nothing here touches the reader's WebViews: this instance is created lazily, never attached to
 * a window, has images and media disabled, and is reused across requests behind a mutex. The
 * extraction script is polled after `onPageFinished` until it reports the article (or listing)
 * as ready, because client-rendered sites finish loading long after the navigation completes.
 */
class WebViewNewsExtractor(
    private val context: Context,
    private val pageTimeoutMillis: Long = 25_000,
    private val pollIntervalMillis: Long = 400,
) : NewsArticleExtractor {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var webView: WebView? = null
    private var pageFinished: CancellableContinuation<Unit>? = null
    private val script: String by lazy {
        appContext.assets.open("hoshi-news/extract.js").bufferedReader().use { it.readText() }
    }

    override suspend fun extractListing(source: NewsSource): List<NewsListingItem> {
        val listing = source.listing as? NewsListing.WebPage
            ?: throw NewsExtractionException("${source.name} is not a web listing")
        val result = run(listing.url, mode = "listing", source = source, pattern = listing.articleUrlPattern)
        val items = result["items"]?.jsonArray ?: JsonArray(emptyList())
        if (items.isEmpty()) {
            // Page diagnostics (anchor samples, viewport, notice state) go to the log; the UI only
            // needs to know that the listing was empty.
            Log.w(TAG, "No articles found on ${listing.url}: ${result["debug"] ?: result["error"] ?: "no diagnostics"}")
            throw NewsNoArticlesException("No articles found on ${listing.url}")
        }
        return items.mapNotNull { element ->
            val item = element.jsonObject
            val url = item.string("url") ?: return@mapNotNull null
            NewsListingItem(
                url = url,
                title = item.string("title")?.trim()?.takeIf { it.isNotEmpty() } ?: url,
                publishedAt = item["publishedAt"]?.jsonPrimitive?.longOrNull,
                imageUrl = item.string("imageUrl"),
            )
        }.distinctBy { it.url }
    }

    override suspend fun extractArticle(source: NewsSource, url: String): ExtractedNewsArticle {
        val result = run(url, mode = "article", source = source, pattern = null)
        val text = result.string("text")?.trim().orEmpty()
        val xhtml = result.string("xhtml").orEmpty()
        if (text.length < MIN_ARTICLE_CHARS) throw NewsExtractionException("No article text found at $url")
        return ExtractedNewsArticle(
            url = result.string("url") ?: url,
            title = result.string("title")?.trim()?.takeIf { it.isNotEmpty() } ?: url,
            xhtml = xhtml,
            text = text,
            publishedAt = result["publishedAt"]?.jsonPrimitive?.longOrNull,
            imageUrl = result.string("imageUrl"),
        )
    }

    private suspend fun run(url: String, mode: String, source: NewsSource, pattern: String?): JsonObject = mutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val view = obtainWebView()
            val loaded = withTimeoutOrNull(pageTimeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    pageFinished = continuation
                    view.stopLoading()
                    view.loadUrl(url)
                    continuation.invokeOnCancellation { pageFinished = null }
                }
            }
            if (loaded == null) throw NewsExtractionException("Timed out loading $url")
            val options = buildString {
                append("{\"mode\":").append(Json.encodeToString(kotlinx.serialization.serializer<String>(), mode))
                append(",\"articleUrlPattern\":").append(pattern?.let { Json.encodeToString(kotlinx.serialization.serializer<String>(), it) } ?: "null")
                append(",\"hints\":").append(Json.encodeToString(NewsExtractionHints.serializer(), source.hints))
                append("}")
            }
            var deadline = System.currentTimeMillis() + pageTimeoutMillis
            var gateExtended = false
            var last: JsonObject? = null
            while (System.currentTimeMillis() < deadline) {
                if (webView !== view) throw NewsExtractionException("The page renderer crashed while loading $url")
                val raw = evaluate(view, "$script\n;window.hoshiNewsExtract($options);")
                val parsed = raw?.let { decodeResult(it) }
                if (parsed != null) {
                    last = parsed
                    if (parsed["ready"]?.jsonPrimitive?.booleanOrNull == true) break
                    // Acknowledging an access notice navigates through a sign-in redirect and
                    // reloads the page; give that round trip its own budget once.
                    if (!gateExtended && parsed["gate"]?.jsonPrimitive?.booleanOrNull == true) {
                        gateExtended = true
                        deadline = System.currentTimeMillis() + pageTimeoutMillis
                    }
                }
                delay(pollIntervalMillis)
            }
            view.stopLoading()
            view.loadUrl("about:blank")
            last ?: throw NewsExtractionException("Extraction script produced no result for $url")
        }
    }

    /** `evaluateJavascript` returns a JSON-encoded string; the script itself returns JSON text. */
    private fun decodeResult(raw: String): JsonObject? = runCatching {
        val inner = json.decodeFromString(kotlinx.serialization.serializer<String>(), raw)
        json.parseToJsonElement(inner).jsonObject
    }.getOrNull()

    /** Null when the page produced no value or the renderer never answered. */
    private suspend fun evaluate(view: WebView, code: String): String? = withTimeoutOrNull(EVALUATE_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(code) { result -> if (continuation.isActive) continuation.resume(result) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun obtainWebView(): WebView = webView ?: WebView(appContext).also { view ->
        // The view is never attached to a window, so nothing measures it. Without a viewport a
        // client-rendered page sees a 0x0 window and lazy lists driven by IntersectionObserver or
        // media queries never render; lay it out at a phone-sized viewport explicitly.
        val widthSpec = View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH_PX, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT_PX, View.MeasureSpec.EXACTLY)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, VIEWPORT_WIDTH_PX, VIEWPORT_HEIGHT_PX)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        // NHK ONE acknowledges the overseas notice through an accountless sign-in on a sibling
        // auth host; WebView blocks that host's cookies as third-party by default, unlike Chrome.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.settings.blockNetworkImage = true
        view.settings.loadsImagesAutomatically = false
        view.settings.mediaPlaybackRequiresUserGesture = true
        view.settings.userAgentString = NewsHttp.DEFAULT_USER_AGENT
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) = Unit

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == "about:blank") return
                pageFinished?.takeIf { it.isActive }?.resume(Unit)
                pageFinished = null
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // Untrusted pages render here; a crashed renderer must not take the app down.
                // Drop this instance (a fresh one is created on the next request) and fail the
                // caller instead of the process.
                Log.w(TAG, "News WebView renderer gone (crash=${detail?.didCrash()})")
                pageFinished?.takeIf { it.isActive }?.cancel(NewsExtractionException("The page renderer crashed"))
                pageFinished = null
                if (webView === view) webView = null
                view?.destroy()
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val path = request?.url?.path?.lowercase().orEmpty()
                // Fonts, video and tracking beacons only slow the render down.
                return if (BLOCKED_EXTENSIONS.any { path.endsWith(it) }) {
                    WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                } else null
            }
        }
        webView = view
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private companion object {
        const val TAG = "HoshiNews"
        const val EVALUATE_TIMEOUT_MILLIS = 5_000L
        const val MIN_ARTICLE_CHARS = 40
        const val VIEWPORT_WIDTH_PX = 1080
        const val VIEWPORT_HEIGHT_PX = 2400
        val BLOCKED_EXTENSIONS = listOf(".woff", ".woff2", ".ttf", ".otf", ".mp4", ".m3u8", ".ts", ".mp3", ".gif", ".webp", ".png", ".jpg", ".jpeg", ".svg")
    }
}
