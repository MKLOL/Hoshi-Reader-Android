package moe.antimony.hoshi.features.mangareader

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.antimony.hoshi.features.reader.ReaderNavigationDirection
import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.mokuro.MokuroPage
import moe.antimony.hoshi.mokuro.MokuroTextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class MangaPageZoomInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun openingAndJumpingFromZoomedPageFitsAndAllowsSwipe() = verifyPageNavigation(edgeOcr = false)

    @Test
    fun widePagesWithEdgeOcrFitAndAllowSwipesInBothDirections() = verifyPageNavigation(edgeOcr = true)

    private fun verifyPageNavigation(edgeOcr: Boolean) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "page-zoom-test").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        File(root, "page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val boxes = if (edgeOcr) listOf(
            // OCR can extend beyond the artwork: invisible text must not enlarge the page.
            MokuroTextBox(1550, 100, 50, 30, 40, false, listOf("ページの端にあるテキスト")),
        ) else emptyList()
        val book = MokuroBook("Zoom fixture", listOf(
            MokuroPage(0, "page.png", 400, 600, emptyList()),
            MokuroPage(1, "page.png", 1600, 900, boxes),
            MokuroPage(2, "page.png", 400, 1200, emptyList()),
        ), null)
        val cache = MangaPageRenderCache()
        val ready = AtomicInteger(-1)
        var page by mutableIntStateOf(0)
        var visible by mutableStateOf(true)
        lateinit var webView: WebView
        compose.setContent {
            if (visible) BoxWithConstraints(Modifier.fillMaxSize()) {
                MangaReaderWebView(
                    book = book, bookRoot = root, pageIndex = page,
                    renderConfig = MangaPageRenderConfig("#ffffff", false, false,
                        maxWidth.value.roundToInt(), maxHeight.value.roundToInt(), selectionScript = ""),
                    pageRenderCache = cache,
                    onNavigate = { page += if (it == ReaderNavigationDirection.Forward) 1 else -1 },
                    onTextSelected = { _, _ -> }, onSelectionCleared = {}, onAskAi = { _, _ -> },
                    onPageReady = ready::set, onWebViewReady = { webView = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitUntil(15_000) { ready.get() == 0 }
        assertFitted(webView)
        onMain { webView.zoomBy(2f) }
        compose.waitUntil(5_000) { zoom(webView) > 1.5f }
        onMain { webView.scrollTo(100, 100) }
        compose.runOnIdle { page = 1 }
        compose.waitUntil(15_000) { ready.get() == 1 }
        assertFitted(webView)
        compose.onRoot().performTouchInput { advanceEventTime(500); swipeRight() }
        compose.waitUntil(15_000) { ready.get() == 2 }
        assertFitted(webView)
        compose.onRoot().performTouchInput { advanceEventTime(500); swipeLeft() }
        compose.waitUntil(15_000) { ready.get() == 1 }
        assertFitted(webView)
        // Check both directions from the page containing overflowing OCR.
        compose.onRoot().performTouchInput { advanceEventTime(500); swipeLeft() }
        compose.waitUntil(15_000) { ready.get() == 0 }
        compose.onRoot().performTouchInput { advanceEventTime(500); swipeRight() }
        compose.waitUntil(15_000) { ready.get() == 1 }
        assertFitted(webView)
        compose.onRoot().performTouchInput { advanceEventTime(500); swipeRight() }
        compose.waitUntil(15_000) { ready.get() == 2 }
        onMain { webView.zoomBy(2f) }
        compose.waitUntil(5_000) { zoom(webView) > 1.5f }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        ready.set(-1)
        compose.runOnIdle { visible = true }
        compose.waitUntil(15_000) { ready.get() == 2 }
        assertFitted(webView)
    }

    @Suppress("DEPRECATION")
    private fun zoom(view: WebView): Float {
        var value = 0f
        onMain { value = view.scale / view.resources.displayMetrics.density }
        return value
    }

    private fun assertFitted(view: WebView) {
        assertEquals(1f, zoom(view), 0.01f)
        onMain {
            assertEquals(0, view.scrollX)
            assertEquals(0, view.scrollY)
            assertFalse(view.canScrollHorizontally(1))
            assertFalse(view.canScrollHorizontally(-1))
        }
    }

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
}
