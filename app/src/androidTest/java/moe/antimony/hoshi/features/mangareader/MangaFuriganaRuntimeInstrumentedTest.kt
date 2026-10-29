package moe.antimony.hoshi.features.mangareader

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.mokuro.MokuroPage
import moe.antimony.hoshi.mokuro.MokuroTextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual bundled tokenizer resources on Android, including the page-cache path. */
@RunWith(AndroidJUnit4::class)
class MangaFuriganaRuntimeInstrumentedTest {
    @Test
    fun packagedDictionaryGeneratesSurfaceReadingsWithoutAnImportedYomitanDictionary() {
        assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
        val text = "漢字を食べました。\n大丈夫！"
        val readings = MangaFurigana.annotations(text)
            .associate { span -> text.substring(span.start, span.start + span.length) to span.reading }

        assertEquals("かんじ", readings["漢字"])
        assertEquals("た", readings["食"])
        assertEquals("だいじょうぶ", readings["大丈夫"])
    }

    @Test
    fun existingMangaGetsGeneratedRubyByDefaultAndTheOffSettingRestoresPlainOcr() {
        val page = MokuroPage(0, "page.png", 800, 1200, listOf(
            MokuroTextBox(100, 100, 180, 300, 28, true, listOf("漢字を読む"), blockIndex = 3),
        ))
        val config = MangaPageRenderConfig("#ffffff", false, false, 400, 600)
        val cache = MangaPageRenderCache()

        assertNull(cache.knownHtmlFor(page, config))
        val enabled = cache.htmlFor(page, config)
        assertTrue(enabled.contains("<rt>かんじ</rt>"))
        assertTrue(enabled === cache.knownHtmlFor(page, config))

        val disabled = cache.htmlFor(page, config.copy(showFurigana = false))
        assertFalse(disabled.contains("<rt>"))
        assertTrue(disabled.contains("漢字を読む"))
    }
}
