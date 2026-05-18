package moe.antimony.hoshi.features.mangareader

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class MangaScreenshotCaptureInstrumentedTest {
    @Test
    fun cropWebViewBitmapPngPreservesSelectedPixels() {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                source.setPixel(x, y, Color.rgb(x, y, 40))
            }
        }

        val png = cropWebViewBitmapPng(
            bitmap = source,
            rect = MangaScreenshotCropRect(left = 10, top = 12, right = 50, bottom = 52),
        )

        assertNotNull(png)
        val decoded = BitmapFactory.decodeByteArray(png, 0, png!!.size)
        assertEquals(40, decoded.width)
        assertEquals(40, decoded.height)
        assertEquals(Color.rgb(10, 12, 40), decoded.getPixel(0, 0))
        assertEquals(Color.rgb(49, 51, 40), decoded.getPixel(39, 39))
        decoded.recycle()
    }

    @Test
    fun captureWebViewBitmapPreservesVisibleZoomedViewport() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val pageLoaded = CountDownLatch(1)
            lateinit var webView: WebView

            scenario.onActivity { activity ->
                webView = WebView(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(200, 200)
                    settings.javaScriptEnabled = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.postVisualStateCallback(
                                1L,
                                object : WebView.VisualStateCallback() {
                                    override fun onComplete(requestId: Long) {
                                        pageLoaded.countDown()
                                    }
                                },
                            )
                        }
                    }
                }
                activity.setContentView(webView)
                webView.loadDataWithBaseURL(
                    "https://hoshi.local/zoom-test/",
                    ZOOM_TEST_HTML,
                    "text/html",
                    "utf-8",
                    null,
                )
            }

            assertTrue("WebView test page loaded", pageLoaded.await(10, TimeUnit.SECONDS))

            scenario.onActivity {
                webView.zoomBy(2.0f)
                webView.scrollTo(0, 0)
            }
            waitForWebViewScale(scenario, webView, minimumScale = 1.5f)

            var captured: Bitmap? = null
            scenario.onActivity {
                webView.scrollTo(0, 0)
                captured = captureWebViewBitmap(webView)
            }
            val bitmap = captured
            assertNotNull(bitmap)
            bitmap ?: return
            try {
                // At 1x this point is in the green top-right quadrant. After zooming into
                // the top-left visual viewport it should be red, proving the captured bitmap
                // is the visible zoomed view rather than the unscaled document.
                assertColorNear(Color.RED, bitmap.getPixel(150, 50))
            } finally {
                bitmap.recycle()
            }
        }
    }

    @SuppressLint("WebViewApiAvailability")
    @Suppress("DEPRECATION")
    private fun waitForWebViewScale(
        scenario: ActivityScenario<ComponentActivity>,
        webView: WebView,
        minimumScale: Float,
    ) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var scale = 1f
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity {
                scale = webView.scale
            }
            if (scale >= minimumScale) return
            SystemClock.sleep(100L)
        }
        assertTrue("expected WebView scale >= $minimumScale, was $scale", scale >= minimumScale)
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        val tolerance = 8
        assertTrue(
            "expected ${Integer.toHexString(expected)}, was ${Integer.toHexString(actual)}",
            abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
                abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
                abs(Color.blue(expected) - Color.blue(actual)) <= tolerance,
        )
    }

    private companion object {
        val ZOOM_TEST_HTML: String = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5, user-scalable=yes">
              <style>
                html, body { margin: 0; padding: 0; width: 200px; height: 200px; overflow: hidden; }
                .root { position: relative; width: 200px; height: 200px; }
                .quad { position: absolute; width: 100px; height: 100px; }
                .red { left: 0; top: 0; background: #ff0000; }
                .green { left: 100px; top: 0; background: #00ff00; }
                .blue { left: 0; top: 100px; background: #0000ff; }
                .yellow { left: 100px; top: 100px; background: #ffff00; }
              </style>
            </head>
            <body>
              <div class="root">
                <div class="quad red"></div>
                <div class="quad green"></div>
                <div class="quad blue"></div>
                <div class="quad yellow"></div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }
}
