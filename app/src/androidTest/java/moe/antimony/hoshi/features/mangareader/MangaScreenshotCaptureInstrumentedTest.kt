package moe.antimony.hoshi.features.mangareader

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.mokuro.MokuroPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
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
                    setInitialScale(100)
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

    @Test
    fun cropMangaImageFilePngPreservesSourceRegionPixels() {
        val cacheDir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        val sourceFile = File.createTempFile("manga-crop-source", ".png", cacheDir)
        val source = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        for (y in 0 until 200) {
            for (x in 0 until 200) {
                source.setPixel(x, y, Color.rgb(x, y, 80))
            }
        }
        try {
            sourceFile.outputStream().use { output ->
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
            }

            val png = cropMangaImageFilePng(
                imageFile = sourceFile,
                crop = MangaImageCropRect(left = 40, top = 50, right = 120, bottom = 130),
            )

            assertNotNull(png)
            val decoded = BitmapFactory.decodeByteArray(png, 0, png!!.size)
            try {
                assertEquals(80, decoded.width)
                assertEquals(80, decoded.height)
                assertEquals(Color.rgb(40, 50, 80), decoded.getPixel(0, 0))
                assertEquals(Color.rgb(119, 129, 80), decoded.getPixel(79, 79))
            } finally {
                decoded.recycle()
            }
        } finally {
            source.recycle()
            sourceFile.delete()
        }
    }

    @Test
    fun cropMangaImageFilePngDownsamplesToVisibleSelectionBounds() {
        val cacheDir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        val sourceFile = File.createTempFile("manga-crop-large-source", ".png", cacheDir)
        val source = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        try {
            sourceFile.outputStream().use { output ->
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
            }

            val png = cropMangaImageFilePng(
                imageFile = sourceFile,
                crop = MangaImageCropRect(left = 20, top = 20, right = 180, bottom = 180),
                maxOutputWidth = 80,
                maxOutputHeight = 60,
            )

            assertNotNull(png)
            val decoded = BitmapFactory.decodeByteArray(png, 0, png!!.size)
            try {
                assertEquals(60, decoded.width)
                assertEquals(60, decoded.height)
            } finally {
                decoded.recycle()
            }
        } finally {
            source.recycle()
            sourceFile.delete()
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun evaluateMangaImageCropRectUsesZoomedAndPannedViewport() {
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
                                2L,
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
                    MangaPageHtml.BASE_URL,
                    MangaPageHtml.build(
                        page = MokuroPage(
                            index = 0,
                            imagePath = "missing.png",
                            imageWidth = 200,
                            imageHeight = 200,
                            textBoxes = emptyList(),
                        ),
                        backgroundCssColor = "#ffffff",
                        selectionScript = "",
                        scanNonJapaneseText = false,
                        eInkMode = false,
                        viewportCssWidth = 400,
                        viewportCssHeight = 400,
                    ),
                    "text/html",
                    "utf-8",
                    null,
                )
            }

            assertTrue("WebView test page loaded", pageLoaded.await(10, TimeUnit.SECONDS))
            enableScrollableTestPage(scenario, webView)

            var baselineScale = 1f
            scenario.onActivity {
                baselineScale = webView.scale.takeIf { it.isFinite() && it > 0f } ?: 1f
            }
            scenario.onActivity {
                webView.zoomBy(2.0f)
            }
            waitForWebViewScale(scenario, webView, minimumScale = 1.5f)
            syncHostScaleForTest(scenario, webView, baselineScale)
            scrollFrameIntoView(scenario, webView)

            var hostWidth = 0
            var hostHeight = 0
            scenario.onActivity {
                hostWidth = webView.width
                hostHeight = webView.height
            }
            val fullViewport = MangaScreenshotCropRect(left = 0, top = 0, right = hostWidth, bottom = hostHeight)
            val beforePan = runBlocking {
                webView.evaluateMangaImageCropRect(fullViewport)
            }
            assertNotNull(evaluateMangaCropDebug(scenario, webView, fullViewport), beforePan)
            beforePan ?: return
            assertEquals(0, beforePan.pageIndex)
            assertTrue("zoomed crop should start inside source image: $beforePan", beforePan.left >= 0)
            assertTrue("zoomed crop should start inside source image: $beforePan", beforePan.top >= 0)
            assertTrue(
                "zoom should crop at least one source axis: $beforePan",
                beforePan.width < 200 || beforePan.height < 200,
            )

            dragWebView(scenario, webView, fromX = 140f, fromY = 140f, toX = 80f, toY = 80f)
            translateTestPageLikePan(scenario, webView, left = -80, top = 0)

            val afterPan = runBlocking {
                webView.evaluateMangaImageCropRect(fullViewport)
            }
            assertNotNull(evaluateMangaCropDebug(scenario, webView, fullViewport), afterPan)
            afterPan ?: return
            assertTrue(
                "pan should move the source crop: before=$beforePan after=$afterPan",
                afterPan.left != beforePan.left || afterPan.top != beforePan.top,
            )
            assertTrue("crop should stay inside source image: $afterPan", afterPan.right <= 200)
            assertTrue("crop should stay inside source image: $afterPan", afterPan.bottom <= 200)
            assertTrue("pan should keep the zoomed crop narrow: $afterPan", afterPan.width <= beforePan.width + 8)
            assertTrue("pan should keep the zoomed crop short: $afterPan", afterPan.height <= beforePan.height + 8)
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

    @Suppress("DEPRECATION")
    private fun syncHostScaleForTest(
        scenario: ActivityScenario<ComponentActivity>,
        webView: WebView,
        baselineScale: Float = 1f,
    ) {
        val synced = CountDownLatch(1)
        scenario.onActivity {
            val baseline = baselineScale.takeIf { it.isFinite() && it > 0f } ?: 1f
            val hostScale = webView.scale / baseline
            webView.evaluateJavascript(
                "window.hoshiManga && window.hoshiManga.setHostScale($hostScale)",
            ) {
                synced.countDown()
            }
        }
        assertTrue("host scale synced", synced.await(5, TimeUnit.SECONDS))
    }

    private fun enableScrollableTestPage(
        scenario: ActivityScenario<ComponentActivity>,
        webView: WebView,
    ) {
        val applied = CountDownLatch(1)
        scenario.onActivity {
            webView.evaluateJavascript(
                """
                document.documentElement.style.width = '400px';
                document.documentElement.style.height = '400px';
                document.documentElement.style.overflow = 'scroll';
                document.body.style.width = '400px';
                document.body.style.height = '400px';
                document.body.style.overflow = 'visible';
                document.querySelector('.page').style.position = 'relative';
                true;
                """.trimIndent(),
            ) {
                applied.countDown()
            }
        }
        assertTrue("test page made scrollable", applied.await(5, TimeUnit.SECONDS))
    }

    private fun translateTestPageLikePan(
        scenario: ActivityScenario<ComponentActivity>,
        webView: WebView,
        left: Int,
        top: Int,
    ) {
        val translated = CountDownLatch(1)
        scenario.onActivity {
            webView.evaluateJavascript(
                """
                var page = document.querySelector('.page');
                page.style.transformOrigin = '0 0';
                page.style.transform = 'translate(${left}px, ${top}px)';
                true;
                """.trimIndent(),
            ) {
                translated.countDown()
            }
        }
        assertTrue("test page translated", translated.await(5, TimeUnit.SECONDS))
        SystemClock.sleep(300L)
    }

    private fun scrollFrameIntoView(
        scenario: ActivityScenario<ComponentActivity>,
        webView: WebView,
    ) {
        val scrolled = CountDownLatch(1)
        scenario.onActivity {
            webView.evaluateJavascript(
                "document.querySelector('.frame').scrollIntoView(); true;",
            ) {
                scrolled.countDown()
            }
        }
        assertTrue("test frame scrolled into view", scrolled.await(5, TimeUnit.SECONDS))
        SystemClock.sleep(300L)
    }

    private fun evaluateMangaCropDebug(
        scenario: ActivityScenario<ComponentActivity>,
        webView: WebView,
        rect: MangaScreenshotCropRect,
    ): String {
        val done = CountDownLatch(1)
        var result = ""
        scenario.onActivity {
            webView.evaluateJavascript(
                """
                (function() {
                  var frame = document.querySelector('.frame');
                  var viewport = window.visualViewport;
                  var r = frame && frame.getBoundingClientRect();
                  return JSON.stringify({
                    crop: window.hoshiManga && window.hoshiManga.imageCropFromHostRect(${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}, ${webView.width}, ${webView.height}),
                    innerWidth: window.innerWidth,
                    innerHeight: window.innerHeight,
                    scale: window.hoshiManga && window.hoshiManga.hostScale && window.hoshiManga.hostScale(),
                    viewportWidth: viewport && viewport.width,
                    viewportHeight: viewport && viewport.height,
                    viewportOffsetLeft: viewport && viewport.offsetLeft,
                    viewportOffsetTop: viewport && viewport.offsetTop,
                    frameLeft: r && r.left,
                    frameTop: r && r.top,
                    frameRight: r && r.right,
                    frameBottom: r && r.bottom,
                    frameWidth: r && r.width,
                    frameHeight: r && r.height,
                    hostWidth: ${webView.width},
                    hostHeight: ${webView.height}
                  });
                })();
                """.trimIndent(),
            ) { value ->
                result = value.orEmpty()
                done.countDown()
            }
        }
        assertTrue("crop debug evaluated", done.await(5, TimeUnit.SECONDS))
        return result
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

    private fun dragWebView(
        scenario: ActivityScenario<ComponentActivity>,
        webView: WebView,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
    ) {
        val location = IntArray(2)
        scenario.onActivity {
            webView.getLocationOnScreen(location)
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float, timeOffset: Long) {
            val event = MotionEvent.obtain(
                downTime,
                downTime + timeOffset,
                action,
                location[0] + x,
                location[1] + y,
                0,
            )
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, fromX, fromY, 0L)
        val steps = 12
        for (step in 1..steps) {
            val progress = step.toFloat() / steps.toFloat()
            val x = fromX + (toX - fromX) * progress
            val y = fromY + (toY - fromY) * progress
            send(MotionEvent.ACTION_MOVE, x, y, step * 24L)
        }
        send(MotionEvent.ACTION_UP, toX, toY, (steps + 1) * 24L)
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
