package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingStatisticsOverviewTest {
    private fun day(dateKey: String, seconds: Double, units: Int = 0, modified: Long = 0L) =
        ReadingStatistics(
            title = "title",
            dateKey = dateKey,
            charactersRead = units,
            readingTime = seconds,
            lastStatisticModified = modified,
        )

    private fun book(
        id: String,
        title: String,
        type: ContentType = ContentType.Epub,
        vararg days: ReadingStatistics,
    ) = BookStatisticsInput(bookId = id, title = title, contentType = type, statistics = days.toList())

    @Test
    fun booksAreListedLongestFirstWithTheirTotalsAndLastReadDay() {
        val overview = summarizeReadingStatistics(
            listOf(
                book("a", "Short", ContentType.Epub, day("2026-09-01", 60.0, units = 500)),
                book("b", "Long manga", ContentType.Mokuro, day("2026-08-30", 900.0, units = 40), day("2026-09-02", 300.0, units = 12)),
            ),
            todayKey = "2026-09-12",
        )

        assertEquals(listOf("b", "a"), overview.books.map { it.bookId })
        val manga = overview.books.first()
        assertEquals(1200.0, manga.totalSeconds, 0.0)
        assertEquals(52, manga.unitsRead)
        assertEquals("2026-09-02", manga.lastReadDateKey)
        assertEquals(ContentType.Mokuro, manga.contentType)
        assertEquals(1260.0, overview.totalSeconds, 0.0)
        assertEquals(0.0, overview.todaySeconds, 0.0)
    }

    @Test
    fun booksWithoutReadingTimeAreOmitted() {
        val overview = summarizeReadingStatistics(
            listOf(
                book("empty", "Never opened"),
                book("zero", "Opened but idle", ContentType.Epub, day("2026-09-10", 0.0, units = 3)),
                book("read", "Read", ContentType.Epub, day("2026-09-10", 45.0)),
            ),
            todayKey = "2026-09-12",
        )

        assertEquals(listOf("read"), overview.books.map { it.bookId })
        assertEquals(45.0, overview.totalSeconds, 0.0)
    }

    @Test
    fun todayOnlyCountsTheCurrentDayAcrossEveryBook() {
        val overview = summarizeReadingStatistics(
            listOf(
                book("a", "A", ContentType.Epub, day("2026-09-12", 120.0), day("2026-09-11", 600.0)),
                book("b", "B", ContentType.Mokuro, day("2026-09-12", 30.0)),
            ),
            todayKey = "2026-09-12",
        )

        assertEquals(150.0, overview.todaySeconds, 0.0)
        assertEquals(750.0, overview.totalSeconds, 0.0)
    }

    @Test
    fun duplicateDayRecordsCountOnceUsingTheNewestEntry() {
        val overview = summarizeReadingStatistics(
            listOf(
                book(
                    "a", "A", ContentType.Epub,
                    day("2026-09-12", 100.0, units = 10, modified = 1L),
                    day("2026-09-12", 250.0, units = 25, modified = 2L),
                ),
            ),
            todayKey = "2026-09-12",
        )

        val summary = overview.books.single()
        assertEquals(250.0, summary.totalSeconds, 0.0)
        assertEquals(25, summary.unitsRead)
        assertEquals(250.0, overview.todaySeconds, 0.0)
    }

    @Test
    fun equalTotalsFallBackToTitleOrder() {
        val overview = summarizeReadingStatistics(
            listOf(
                book("z", "Zeta", ContentType.Epub, day("2026-09-01", 10.0)),
                book("a", "Alpha", ContentType.Epub, day("2026-09-01", 10.0)),
            ),
            todayKey = "2026-09-12",
        )

        assertEquals(listOf("Alpha", "Zeta"), overview.books.map { it.title })
    }

    @Test
    fun emptyLibraryProducesAnEmptyOverview() {
        val overview = summarizeReadingStatistics(emptyList(), todayKey = "2026-09-12")

        assertTrue(overview.books.isEmpty())
        assertEquals(0.0, overview.totalSeconds, 0.0)
        assertNull(overview.books.firstOrNull())
    }
}
