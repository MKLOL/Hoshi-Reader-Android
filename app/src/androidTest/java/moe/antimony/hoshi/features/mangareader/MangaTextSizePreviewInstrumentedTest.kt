package moe.antimony.hoshi.features.mangareader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.antimony.hoshi.HoshiApplication
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.mokuro.*
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MangaTextSizePreviewInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun currentPageComparisonDraftCancelAndApply() {
        val root = temporary.newFolder("preview")
        val image = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = 2f }
        canvas.drawRect(8f, 8f, 392f, 292f, paint)
        canvas.drawRect(8f, 306f, 392f, 592f, paint)
        canvas.drawOval(38f, 38f, 355f, 156f, paint)
        canvas.drawOval(46f, 165f, 355f, 258f, paint)
        canvas.drawOval(45f, 350f, 350f, 540f, paint)
        root.resolve("page.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        val boxes = listOf(
            MokuroTextBox(85, 65, 200, 40, 21, false, listOf("学校で本を読む"), blockIndex = 0, originalFontSize = 12),
            MokuroTextBox(85, 188, 200, 45, 25, false, listOf("こんにちは"), blockIndex = 1, originalFontSize = 20),
            MokuroTextBox(85, 400, 210, 65, 40, false, listOf("大きな文字"), blockIndex = 2, originalFontSize = 40))
        val book = MokuroBook("Preview test", (0..2).map { MokuroPage(it, "page.png", 400, 600, if (it == 2) emptyList() else boxes) }, null)
        val container = (compose.activity.application as HoshiApplication).appContainer
        val repository = BookRepository(temporary.newFolder("library"))
        val original = ReaderSettings(mangaSmallTextAmount = 25)
        var settings by mutableStateOf(original)
        compose.setContent {
            CompositionLocalProvider(LocalHoshiAppContainer provides container) {
                HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                    MangaReaderSystemBarsEffect()
                    MangaReaderScreen(book, root, 1, repository, settings, { settings = it }, DictionarySettings(), {}, {}, {})
                }
            }
        }
        fun label(id: Int) = compose.activity.getString(id)
        fun openPreview() {
            compose.onNodeWithContentDescription(label(R.string.manga_reader_more_options)).performClick()
            compose.onNodeWithText(label(R.string.manga_preview_title)).performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.manga_preview_page, 2, 3)).assertExists()
        }
        compose.waitForIdle()
        val readerBounds = compose.onNodeWithTag("manga-reader-surface").fetchSemanticsNode().boundsInWindow
        fun assertReaderViewport() {
            val previewBounds = compose.onNodeWithTag("manga-preview-surface").fetchSemanticsNode().boundsInWindow
            assertEquals(readerBounds.width.toDouble(), previewBounds.width.toDouble(), 0.5)
            assertEquals(readerBounds.height.toDouble(), previewBounds.height.toDouble(), 0.5)
            assertEquals(readerBounds.left.toDouble(), previewBounds.left.toDouble(), 0.5)
            assertEquals(readerBounds.top.toDouble(), previewBounds.top.toDouble(), 0.5)
        }
        openPreview()
        assertReaderViewport()
        compose.onNodeWithText(label(R.string.manga_preview_hide_controls)).performClick()
        assertReaderViewport()
        compose.onNodeWithText(label(R.string.manga_preview_show_controls)).performClick()
        assertReaderViewport()
        compose.onNodeWithText(label(R.string.manga_small_text_strong)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(original, settings) }
        compose.onNodeWithContentDescription(label(R.string.manga_small_text_amount)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.manga_small_text_threshold)).assertIsDisplayed()
        screenshot("android-manga-page-adjusted.png")
        compose.onNodeWithText(label(R.string.manga_preview_hide_controls)).performClick()
        assertReaderViewport()
        screenshot("android-manga-page-fullscreen.png")
        compose.onNodeWithText(label(R.string.manga_preview_show_controls)).performClick()
        compose.onNodeWithText(label(R.string.manga_preview_original_size)).performClick()
        screenshot("android-manga-page-original.png")
        compose.onNodeWithText(label(R.string.manga_preview_next)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.manga_preview_page, 3, 3)).assertExists()
        waitForPreview()
        compose.onNodeWithText(label(R.string.manga_preview_adjusted)).performClick()
        waitForPreview()
        compose.onNodeWithText(label(R.string.manga_preview_cancel)).performClick()
        compose.runOnIdle { assertEquals(original, settings) }
        openPreview()
        val synced = original.copy(mangaSmallTextAmount = 30)
        compose.runOnIdle { settings = synced }
        compose.onNodeWithTag("manga-preview-apply").performClick()
        compose.runOnIdle { assertEquals(synced, settings) }
        val reader = compose.runOnIdle { requireNotNull(findWebView(compose.activity.window.decorView)) }
        val before = script(reader, "window.hoshiMangaLoadSequence")
        compose.runOnIdle {
            reader.zoomBy(2f)
            reader.scrollTo(90, 180)
        }
        val zoomed = compose.runOnIdle { reader.captureMangaPreviewViewport() }
        assertTrue(zoomed.scrollX > 0 && zoomed.scrollY > 0)
        openPreview()
        waitForPreview()
        if (Build.VERSION.SDK_INT >= 29) {
            val preview = compose.runOnIdle {
                WindowInspector.getGlobalWindowViews().mapNotNull(::findWebView).first { it !== reader }
            }
            assertViewport(zoomed, preview)
            compose.onNodeWithText(label(R.string.manga_small_text_strong)).performScrollTo().performClick()
            waitForPreview()
            assertViewport(zoomed, preview)
        } else {
            compose.onNodeWithText(label(R.string.manga_small_text_strong)).performScrollTo().performClick()
        }
        compose.onNodeWithTag("manga-preview-apply").performClick()
        compose.runOnIdle { assertEquals(original.copy(mangaEnlargeSmallText = true, mangaSmallTextAmount = 75, mangaSmallTextThreshold = 40), settings) }
        compose.waitUntil(10_000) {
            script(reader, "window.hoshiMangaLoadSequence").let { it != "null" && it != before } &&
                compose.runOnIdle { reader.mangaViewportForReload() == reader.captureMangaPreviewViewport() }
        }
        assertViewport(zoomed, reader)
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    private fun script(webView: WebView, source: String): String {
        val ready = CountDownLatch(1)
        var result: String? = null
        compose.runOnUiThread { webView.evaluateJavascript(source) { result = it; ready.countDown() } }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return requireNotNull(result)
    }

    private fun assertViewport(expected: MangaPreviewViewport, webView: WebView) {
        val actual = compose.runOnIdle { webView.captureMangaPreviewViewport() }
        assertEquals(expected.scale, actual.scale, 0.01f)
        assertEquals(expected.scrollX.toDouble(), actual.scrollX.toDouble(), 1.0)
        assertEquals(expected.scrollY.toDouble(), actual.scrollY.toDouble(), 1.0)
    }

    private fun waitForPreview() {
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("manga-preview-loading").fetchSemanticsNodes().isEmpty() }
    }

    private fun screenshot(name: String) {
        waitForPreview()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
