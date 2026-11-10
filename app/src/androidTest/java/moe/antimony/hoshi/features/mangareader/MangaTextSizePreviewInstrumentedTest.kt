package moe.antimony.hoshi.features.mangareader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

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
        canvas.drawOval(45f, 40f, 330f, 175f, paint)
        canvas.drawOval(45f, 350f, 350f, 540f, paint)
        root.resolve("page.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        val boxes = listOf(
            MokuroTextBox(85, 65, 200, 40, 21, false, listOf("学校で本を読む"), originalFontSize = 12),
            MokuroTextBox(85, 115, 200, 45, 25, false, listOf("こんにちは"), originalFontSize = 20),
            MokuroTextBox(85, 400, 210, 65, 40, false, listOf("大きな文字"), originalFontSize = 40))
        val book = MokuroBook("Preview test", (0..2).map { MokuroPage(it, "page.png", 400, 600, boxes) }, null)
        val container = (compose.activity.application as HoshiApplication).appContainer
        val repository = BookRepository(temporary.newFolder("library"))
        val original = ReaderSettings(mangaShowFurigana = false, mangaSmallTextAmount = 25)
        var settings by mutableStateOf(original)
        compose.setContent {
            CompositionLocalProvider(LocalHoshiAppContainer provides container) {
                HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
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
        openPreview()
        compose.onNodeWithText(label(R.string.manga_small_text_strong)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(original, settings) }
        screenshot("android-manga-page-adjusted.png")
        compose.onNodeWithText(label(R.string.manga_small_text_original)).performClick()
        screenshot("android-manga-page-original.png")
        compose.onNodeWithText(label(R.string.manga_preview_next)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.manga_preview_page, 3, 3)).assertExists()
        compose.onNodeWithContentDescription(label(R.string.action_back)).performClick()
        compose.runOnIdle { assertEquals(original, settings) }
        openPreview()
        compose.onNodeWithText(label(R.string.manga_small_text_strong)).performScrollTo().performClick()
        compose.onNodeWithTag("manga-preview-apply").performClick()
        compose.runOnIdle { assertEquals(original.copy(mangaEnlargeSmallText = true, mangaSmallTextAmount = 75, mangaSmallTextThreshold = 40), settings) }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("manga-preview-loading").fetchSemanticsNodes().isEmpty() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
