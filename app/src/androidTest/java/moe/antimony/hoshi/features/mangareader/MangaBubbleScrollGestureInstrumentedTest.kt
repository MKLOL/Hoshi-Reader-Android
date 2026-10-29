package moe.antimony.hoshi.features.mangareader

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import moe.antimony.hoshi.features.reader.ReaderNavigationDirection
import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.mokuro.MokuroPage
import moe.antimony.hoshi.mokuro.MokuroTextBox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the production native touch listener and JS bridge, rather than JS-only scrolls. */
@RunWith(AndroidJUnit4::class)
class MangaBubbleScrollGestureInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun scrollableBubbleConsumesItsSwipeWhileArtworkAndHiddenBubbleStillTurnPages() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "bubble-scroll-gesture").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        File(root, "page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val text = "学校".repeat(500)
        val page = MokuroPage(0, "page.png", 1000, 1000, listOf(
            // Keep the original glyph size large enough that this text overflows even
            // on tall phone viewports; the fixture must exercise actual child scrolling.
            MokuroTextBox(20, 20, 150, 150, 100, true, listOf(text), originalFontSize = 80),
        ))
        val book = MokuroBook("Scrollable bubble", listOf(page), null)
        val cache = MangaPageRenderCache(
            imageLoader = MangaImageLoaderBackend(root, book),
            furiganaGenerator = { source -> (source.indices step 2).map { MangaFuriganaSpan(it, 2, "がっこう") } },
        )
        val ready = AtomicInteger(-1)
        val navigations = mutableListOf<ReaderNavigationDirection>()
        lateinit var webView: WebView
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                MangaReaderWebView(
                    book = book, bookRoot = root, pageIndex = 0,
                    renderConfig = MangaPageRenderConfig("#ffffff", false, false,
                        maxWidth.value.roundToInt(), maxHeight.value.roundToInt(), enlargeSmallText = false),
                    pageRenderCache = cache,
                    onNavigate = { navigations.add(it) },
                    onTextSelected = { _, _ -> }, onSelectionCleared = {}, onAskAi = { _, _ -> },
                    onPageReady = ready::set, onWebViewReady = { webView = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitUntil(15_000) { ready.get() == 0 }
        val state = evaluate(webView, """
            var box = document.querySelector('.ocr-box');
            var range = document.createRange();
            range.setStart(box.querySelector('ruby').firstChild, 0); range.setEnd(box.querySelector('ruby').firstChild, 1);
            var r = range.getBoundingClientRect();
            window.hoshiManga.handleTap(r.left+r.width/2, r.top+r.height/2, 16);
            var p = box.querySelector('p'), rect = window.hoshiManga.hostRectFromViewportRect(p.getBoundingClientRect());
            return {x:rect.x, y:rect.y, width:rect.width, height:rect.height,
              scrollable:box.classList.contains('furigana-scroll'), scrollWidth:p.scrollWidth, clientWidth:p.clientWidth};
        """)
        assertTrue("long vertical text must scroll: $state", state.getBoolean("scrollable"))
        assertTrue(state.getDouble("scrollWidth") > state.getDouble("clientWidth"))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val density = webView.resources.displayMetrics.density
        val startX = ((state.getDouble("x") + state.getDouble("width") * 0.25) * density).toFloat()
        // End beyond the paragraph: the gesture must remain suppressed after leaving it.
        val endX = webView.width - density
        val y = ((state.getDouble("y") + state.getDouble("height") * 0.5) * density).toFloat()
        swipe(webView, startX, endX, y)
        compose.waitUntil(5_000) { evaluate(webView, "return {left:document.querySelector('.ocr-box p').scrollLeft};").getDouble("left") < -5 }
        compose.runOnIdle { assertTrue("scrolling text must not turn the page", navigations.isEmpty()) }

        // Start below the expanded paragraph: ordinary artwork navigation stays available.
        swipe(webView, webView.width * 0.25f, webView.width * 0.75f, webView.height - 4f * density)
        compose.runOnIdle { assertEquals(listOf(ReaderNavigationDirection.Forward), navigations) }

        evaluate(webView, "window.hoshiManga.clearRevealed(); return {};")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        swipe(webView, startX, endX, y)
        compose.runOnIdle { assertEquals(listOf(ReaderNavigationDirection.Forward, ReaderNavigationDirection.Forward), navigations) }
    }

    private fun swipe(view: WebView, startX: Float, endX: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        for (step in 0..8) {
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                8 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                startX + (endX - startX) * step / 8f, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            onMain { view.dispatchTouchEvent(event) }
            event.recycle()
            SystemClock.sleep(16)
        }
    }

    private fun evaluate(view: WebView, source: String): JSONObject {
        val latch = CountDownLatch(1)
        var result: String? = null
        onMain { view.evaluateJavascript("JSON.stringify((function(){$source})())") { result = it; latch.countDown() } }
        assertTrue("JavaScript evaluation timed out", latch.await(10, TimeUnit.SECONDS))
        return JSONObject(org.json.JSONTokener(requireNotNull(result)).nextValue() as String)
    }

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}
