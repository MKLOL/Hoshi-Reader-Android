package moe.antimony.hoshi.features.mangareader

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.HoshiApplication
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.mokuro.MokuroPage
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class MangaReaderFullscreenInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun firstPageFillsWindowWhileSystemBarsStayVisible() {
        val opened = mountReader(immersive = false)
        val decor = compose.activity.window.decorView
        val bars = WindowInsetsCompat.Type.systemBars()
        compose.runOnUiThread { WindowCompat.getInsetsController(compose.activity.window, decor).show(bars) }
        compose.waitUntil(5_000) {
            compose.runOnUiThread {
                ViewCompat.getRootWindowInsets(decor)?.let { it.getInsets(bars) != Insets.NONE } == true
            }
        }
        compose.runOnIdle { opened.value = true }
        compose.onNodeWithTag("manga-reader-surface").assertExists()
        val reader = compose.runOnUiThread { requireNotNull(findWebView(decor)) }
        val first = assertFullWindowPage(reader)
        compose.runOnUiThread {
            assertTrue("the system bars must still be visible while the page draws",
                requireNotNull(ViewCompat.getRootWindowInsets(decor)).getInsets(bars) != Insets.NONE)
            WindowCompat.getInsetsController(compose.activity.window, decor).hide(bars)
        }
        waitForHiddenBars(decor)
        compose.waitForIdle()
        assertEquals("hiding system bars must keep the same page document", first.getLong(9), assertFullWindowPage(reader).getLong(9))
    }

    @Test
    fun focusedReaderRehidesBarsShownWithoutWindowFocusChange() {
        val opened = mountReader()
        val activity = compose.activity
        val decor = activity.window.decorView
        val bars = WindowInsetsCompat.Type.systemBars()
        compose.runOnIdle { opened.value = true }
        compose.onNodeWithTag("manga-reader-surface").assertExists()
        val reader = compose.runOnUiThread { requireNotNull(findWebView(decor)) }
        waitForHiddenBars(decor)
        val first = assertFullWindowPage(reader)

        val visibleInsets = CountDownLatch(1)
        val observer = compose.runOnUiThread {
            View(activity).also { view ->
                ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
                    if (insets.getInsets(bars) != Insets.NONE) visibleInsets.countDown()
                    insets
                }
                activity.findViewById<ViewGroup>(android.R.id.content)
                    .addView(view, 0, FrameLayout.LayoutParams(1, 1))
                WindowCompat.getInsetsController(activity.window, decor).show(bars)
            }
        }
        try {
            assertTrue("the system must dispatch the late visible insets", visibleInsets.await(5, TimeUnit.SECONDS))
            waitForHiddenBars(decor)
            assertEquals("rehiding system bars must keep the same page document", first.getLong(9), assertFullWindowPage(reader).getLong(9))
        } finally {
            compose.runOnUiThread {
                activity.findViewById<ViewGroup>(android.R.id.content).removeView(observer)
            }
        }
    }

    @Test
    fun firstOpenFinishesFullscreenAfterAnotherWindowReturnsFocus() {
        val opened = mountReader()
        val activity = compose.activity
        val decor = activity.window.decorView
        val (initialStatus, initialNavigation) = compose.runOnIdle {
            requireNotNull(ViewCompat.getRootWindowInsets(decor)).let {
                it.isVisible(WindowInsetsCompat.Type.statusBars()) to it.isVisible(WindowInsetsCompat.Type.navigationBars())
            }
        }
        val dialog = compose.runOnIdle {
            Dialog(activity).apply {
                setContentView(TextView(activity).apply { text = "Temporary window" })
                show()
            }
        }
        try {
            compose.waitUntil(5_000) {
                compose.runOnUiThread { !decor.hasWindowFocus() && dialog.window?.decorView?.hasWindowFocus() == true }
            }
            compose.runOnIdle { opened.value = true }
            compose.onNodeWithTag("manga-reader-surface").assertExists()
            val reader = compose.runOnUiThread { requireNotNull(findWebView(decor)) }
            compose.runOnUiThread {
                // Model a late system-bar visibility request while the reader can't control the window.
                WindowCompat.getInsetsController(activity.window, decor).show(WindowInsetsCompat.Type.systemBars())
                dialog.dismiss()
            }

            compose.waitUntil(10_000) {
                compose.runOnUiThread {
                    val content = activity.findViewById<View>(android.R.id.content)
                    decor.hasWindowFocus() &&
                        ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.systemBars()) == Insets.NONE &&
                        reader.width == content.width && reader.height == content.height
                }
            }
            val (expectedWidth, expectedHeight) = compose.runOnUiThread {
                val density = reader.resources.displayMetrics.density
                reader.width / density to reader.height / density
            }
            compose.waitUntil(10_000) {
                val dimensions = pageDimensions(reader) ?: return@waitUntil false
                dimensions.getInt(0) == 1 &&
                    abs(dimensions.getDouble(1) - expectedWidth) <= 1 &&
                    abs(dimensions.getDouble(2) - expectedHeight) <= 1 &&
                    abs(dimensions.getDouble(3) - minOf(expectedHeight, expectedWidth * 3)) <= 1
            }
            compose.runOnUiThread { assertSame(reader, findWebView(decor)) }

            compose.onNodeWithContentDescription(activity.getString(R.string.manga_reader_close)).performClick()
            compose.onNodeWithTag("manga-reader-surface").assertDoesNotExist()
            compose.waitUntil(5_000) {
                compose.runOnUiThread {
                    ViewCompat.getRootWindowInsets(decor)?.let {
                        it.isVisible(WindowInsetsCompat.Type.statusBars()) == initialStatus &&
                            it.isVisible(WindowInsetsCompat.Type.navigationBars()) == initialNavigation
                    } == true
                }
            }
        } finally {
            compose.runOnUiThread { dialog.dismiss() }
        }
    }

    private fun mountReader(immersive: Boolean = true): MutableState<Boolean> {
        val activity = compose.activity
        val container = (activity.application as HoshiApplication).appContainer
        val root = temporary.newFolder("fullscreen")
        val image = Bitmap.createBitmap(80, 240, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(Color.WHITE)
            root.resolve("page.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            image.recycle()
        }
        val book = MokuroBook("Fullscreen fixture", (0..1).map { index ->
            MokuroPage(index, "page.png", 80, 240, emptyList())
        }, null)
        val repository = BookRepository(temporary.newFolder("library"))
        val opened = mutableStateOf(false)
        compose.runOnUiThread { activity.enableEdgeToEdge() }
        compose.setContent {
            CompositionLocalProvider(LocalHoshiAppContainer provides container) {
                HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                    if (opened.value) {
                        if (immersive) MangaReaderSystemBarsEffect()
                        MangaReaderScreen(
                            book = book,
                            bookRoot = root,
                            initialPageIndex = 1,
                            repository = repository,
                            readerSettings = ReaderSettings(),
                            onReaderSettingsChange = {},
                            dictionarySettings = DictionarySettings(),
                            onReaderKeyEventHandlerChange = {},
                            onBookmarkSaved = {},
                            onClose = { opened.value = false },
                        )
                    }
                }
            }
        }

        return opened
    }

    private fun waitForHiddenBars(decor: View) {
        compose.waitUntil(10_000) {
            compose.runOnUiThread {
                decor.hasWindowFocus() &&
                    ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.systemBars()) == Insets.NONE
            }
        }
    }

    private fun assertFullWindowPage(reader: WebView): JSONArray {
        compose.waitUntil(10_000) { compose.runOnUiThread { reader.isMangaPageReady() } }
        val (expectedWidth, expectedHeight) = compose.runOnUiThread {
            val content = compose.activity.findViewById<View>(android.R.id.content)
            assertSame(reader, findWebView(compose.activity.window.decorView))
            assertEquals("native surface width", content.width, reader.width)
            assertEquals("native surface height", content.height, reader.height)
            val readerOrigin = IntArray(2).also(reader::getLocationInWindow)
            val contentOrigin = IntArray(2).also(content::getLocationInWindow)
            assertArrayEquals("native surface origin", contentOrigin, readerOrigin)
            assertEquals("horizontal pan", 0, reader.scrollX)
            assertEquals("vertical pan", 0, reader.scrollY)
            val density = reader.resources.displayMetrics.density.toDouble()
            content.width / density to content.height / density
        }
        val dimensions = checkNotNull(pageDimensions(reader))
        val expectedImageHeight = minOf(expectedHeight, expectedWidth * 3)
        assertEquals("page number", 1, dimensions.getInt(0))
        assertEquals("DOM viewport width", expectedWidth, dimensions.getDouble(1), 1.0)
        assertEquals("DOM viewport height", expectedHeight, dimensions.getDouble(2), 1.0)
        assertEquals("fitted artwork height", expectedImageHeight, dimensions.getDouble(3), 1.0)
        assertEquals("fitted artwork width", expectedImageHeight / 3, dimensions.getDouble(4), 1.0)
        assertEquals("visual viewport scale", 1.0, dimensions.getDouble(5), 0.01)
        assertEquals("visual viewport width", expectedWidth, dimensions.getDouble(6), 1.0)
        assertEquals("visual viewport height", expectedHeight, dimensions.getDouble(7), 1.0)
        assertEquals("source artwork aspect ratio", 3.0, dimensions.getDouble(8), 0.01)
        return dimensions
    }

    private fun pageDimensions(reader: WebView): JSONArray? {
        val done = CountDownLatch(1)
        var result = "null"
        compose.runOnUiThread {
            reader.evaluateJavascript(
                """
                (() => {
                    const page = document.querySelector('.page');
                    const frame = page && page.querySelector('.frame');
                    const image = frame && frame.querySelector('img');
                    if (!image || !image.naturalWidth) return null;
                    const rect = page.getBoundingClientRect();
                    const artwork = image.getBoundingClientRect();
                    const viewport = window.visualViewport;
                    return [Number(frame.dataset.pageIndex), rect.width, rect.height, artwork.height, artwork.width,
                            viewport.scale, viewport.width, viewport.height, image.naturalHeight / image.naturalWidth,
                            window.hoshiMangaLoadSequence];
                })()
                """.trimIndent(),
            ) {
                result = it ?: "null"
                done.countDown()
            }
        }
        assertTrue("Page measurement timed out", done.await(5, TimeUnit.SECONDS))
        return result.takeUnless { it == "null" }?.let(::JSONArray)
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }
}
