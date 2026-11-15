package moe.antimony.hoshi.features.mangareader

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
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
    fun firstOpenFinishesFullscreenAfterAnotherWindowReturnsFocus() {
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
                        MangaReaderSystemBarsEffect()
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
                    return [Number(frame.dataset.pageIndex), rect.width, rect.height, image.getBoundingClientRect().height];
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
