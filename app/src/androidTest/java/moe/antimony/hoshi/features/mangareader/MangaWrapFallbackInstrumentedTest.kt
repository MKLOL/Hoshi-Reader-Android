package moe.antimony.hoshi.features.mangareader

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.mokuro.MokuroPage
import moe.antimony.hoshi.mokuro.MokuroTextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifies that the runtime wrap-fallback in [MangaPageHtml]'s `MANGA_TAP_HANDLER_SCRIPT`
 * actually promotes a known-broken horizontal bubble (mokuro mis-detected a tall narrow
 * box as horizontal) to a multi-row wrap layout, instead of leaving it as a tiny single-
 * row strip.
 *
 * Regression case: Yotsubato Vol 2 HD page 193 block 3, "なんだそりゃ" — 6 chars,
 * `vertical=false`, `font_size=137`, box 85×145. Before the fallback, the OCR plate
 * either rendered as a giant horizontal pill (~10x the box) or — after the parse-time
 * clamp — as a single-line strip too small to read. The fallback should detect that
 * wrapping permits a meaningfully larger font and apply `.wrap` + a bigger font-size.
 *
 * This test loads the generated HTML in a real WebView, invokes the tap handler via
 * `evaluateJavascript` (no actual touch event needed — we just need the JS to run),
 * then reads back the bubble's `class` list and computed `font-size`. Pure JS unit
 * tests can't catch this because the wrap decision depends on real DOM measurement
 * (`scrollWidth`/`scrollHeight`), which only Chromium provides.
 */
@RunWith(AndroidJUnit4::class)
class MangaWrapFallbackInstrumentedTest {
    @Test
    fun wrapFallbackPromotesMisTaggedHorizontalBubble() {
        val brokenBubble = MokuroTextBox(
            left = 527, top = 278,
            width = 85, height = 145,
            fontSize = 14, // matches what the parser's clamp would produce
            vertical = false,
            lines = listOf("なんだそりゃ"),
        )
        val page = MokuroPage(
            index = 0,
            imagePath = "test.png",
            imageWidth = 1950,
            imageHeight = 2800,
            textBoxes = listOf(brokenBubble),
        )
        val html = MangaPageHtml.build(
            page = page,
            backgroundCssColor = "#ffffff",
            selectionScript = "",
            scanNonJapaneseText = false,
            eInkMode = false,
            viewportCssWidth = 411,
            viewportCssHeight = 844,
        )

        val state = runInWebView(html) { webView, done ->
            // Wait for the tap-handler script's IIFE to install `window.hoshiManga`,
            // then call `tryWrapFallback` directly on the only OCR box. This avoids
            // synthesising MotionEvents while exercising the exact code path the user's
            // tap takes (see MANGA_TAP_HANDLER_SCRIPT.handleTap branch for `!revealed`).
            webView.evaluateJavascript(
                """
                (function() {
                  var box = document.querySelector('.ocr-box');
                  if (!box) return JSON.stringify({error: 'no box'});
                  window.hoshiManga.tryWrapFallback(box);
                  var computed = window.getComputedStyle(box);
                  return JSON.stringify({
                    classList: box.className,
                    fontSize: computed.fontSize,
                    initialPx: parseFloat(box.style.fontSize) || null,
                    boxW: box.clientWidth,
                    boxH: box.clientHeight,
                  });
                })();
                """.trimIndent(),
            ) { result ->
                // evaluateJavascript wraps strings in extra quotes + escapes; the inner
                // payload is JSON-encoded so just strip the outer JSON string layer.
                done(result.trim('"').replace("\\\"", "\""))
            }
        }

        val stateText = state ?: error("evaluateJavascript returned null")
        val classList = Regex("\"classList\":\"([^\"]*)\"").find(stateText)?.groupValues?.get(1)
        val fontSizePx = Regex("\"fontSize\":\"([0-9.]+)px\"").find(stateText)?.groupValues?.get(1)?.toFloat()
        val boxW = Regex("\"boxW\":(\\d+)").find(stateText)?.groupValues?.get(1)?.toInt()
        val boxH = Regex("\"boxH\":(\\d+)").find(stateText)?.groupValues?.get(1)?.toInt()

        assertNotNull("classList missing from result: $state", classList)
        assertNotNull("fontSize missing from result: $state", fontSizePx)
        assertNotNull("boxW missing from result: $state", boxW)
        assertNotNull("boxH missing from result: $state", boxH)

        // The box should be small (mokuro's reported 85×145 px in image coords, scaled to
        // the viewport). If the test ever runs in a giant viewport, the assertions below
        // would be wrong — guard.
        assertTrue("box too big — viewport too generous? $boxW x $boxH", boxW!! < 60 && boxH!! < 100)

        // The fallback must have switched the box to wrap mode. Without `.wrap`, the
        // box was either left as a tiny nowrap strip (regression of this fix) or grew
        // past its bounds (regression of the original min-width/min-height issue).
        assertTrue(
            "wrap-fallback did not add .wrap class. classList='$classList'",
            classList!!.contains("wrap"),
        )

        // And the chosen font-size must be larger than the parser-clamp's nowrap floor
        // for this box (mokuro 137 / 6 chars = 22 px nowrap fit pre-clamp; the wrapped
        // 2-chars-per-row layout permits ~9 CSS px on the test viewport — anything ≥4 px
        // beats the nowrap single-row fit which is bounded by box width ≈18 CSS px / 6.
        assertTrue(
            "wrap font-size $fontSizePx px is no larger than nowrap fit (~3 px)",
            fontSizePx!! >= 4f,
        )
    }

    /**
     * Boilerplate to load HTML into a WebView on the main thread and run an arbitrary
     * post-load block (typically `evaluateJavascript`). Mirrors the helper in
     * [MangaScreenshotCaptureInstrumentedTest] but parameterised on the JS payload so
     * a single helper covers multiple wrap-fallback assertions later.
     */
    private fun runInWebView(
        html: String,
        timeoutSeconds: Long = 10,
        block: (WebView, done: (String?) -> Unit) -> Unit,
    ): String? {
        var result: String? = null
        val latch = CountDownLatch(1)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val pageLoaded = CountDownLatch(1)
            lateinit var webView: WebView
            scenario.onActivity { activity ->
                webView = WebView(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(411 * 3, 844 * 3)
                    settings.javaScriptEnabled = true
                    settings.minimumFontSize = 1
                    settings.minimumLogicalFontSize = 1
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            // onPageFinished is enough — postVisualStateCallback adds an
                            // extra layout-completion wait that doesn't fire in this
                            // minimal Activity (no surface attached early enough).
                            pageLoaded.countDown()
                        }
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ): android.webkit.WebResourceResponse? {
                            // The page references an image at https://hoshi.local/...; no
                            // bridge intercepts in this test, so return an empty 200
                            // instead of letting the request hang and delay onPageFinished.
                            if (request.url?.host == "hoshi.local") {
                                return android.webkit.WebResourceResponse(
                                    "image/png", "utf-8",
                                    java.io.ByteArrayInputStream(ByteArray(0)),
                                )
                            }
                            return null
                        }
                    }
                }
                activity.setContentView(webView)
                webView.loadDataWithBaseURL(
                    MangaPageHtml.BASE_URL,
                    html,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
            assertTrue("page never loaded", pageLoaded.await(timeoutSeconds, TimeUnit.SECONDS))
            scenario.onActivity {
                block(webView) { value ->
                    result = value
                    latch.countDown()
                }
            }
            assertTrue("evaluateJavascript callback never fired", latch.await(timeoutSeconds, TimeUnit.SECONDS))
        }
        return result
    }
}
