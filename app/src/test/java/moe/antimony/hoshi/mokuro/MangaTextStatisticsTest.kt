package moe.antimony.hoshi.mokuro

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaTextStatisticsTest {
    private fun box(vararg lines: String) = MokuroTextBox(0, 0, 10, 10, 12, true, lines.toList())
    private fun page(index: Int, vararg boxes: MokuroTextBox) = MokuroPage(index, "p$index.png", 100, 100, boxes.toList())

    @Test
    fun pageCharacterCountIgnoresWhitespaceAndEmptyBoxes() {
        val page = page(0, box("しろくま", "カフェ へ"), box(""), box(" \n"))

        assertEquals(8, page.ocrCharacterCount())
    }

    @Test
    fun forwardTurnsCountThePagesLeftBehindAndBackwardTurnsCountNothing() {
        val book = MokuroBook(
            title = "T",
            pages = listOf(page(0, box("ab")), page(1, box("cde")), page(2, box("f")), page(3)),
            coverImagePath = null,
        )

        assertEquals(2, book.ocrCharactersTurnedPast(0, 1))
        assertEquals(5, book.ocrCharactersTurnedPast(0, 2))
        assertEquals(4, book.ocrCharactersTurnedPast(1, 3))
        assertEquals(0, book.ocrCharactersTurnedPast(2, 1))
        assertEquals(0, book.ocrCharactersTurnedPast(1, 1))
        assertEquals(6, book.ocrCharactersTurnedPast(-5, 99))
    }

    @Test
    fun duplicateDaysKeepTheNewestEntry() {
        val statistics = listOf(
            MangaTextStatistic("2026-09-12", 10, lastModified = 1),
            MangaTextStatistic("2026-09-12", 25, lastModified = 2),
            MangaTextStatistic("2026-09-11", 5, lastModified = 9),
        ).deduplicateMangaTextStatistics()

        assertEquals(listOf(MangaTextStatistic("2026-09-12", 25, 2), MangaTextStatistic("2026-09-11", 5, 9)), statistics)
    }
}
