package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.mokuro.MangaTextStatistic
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
        assertEquals(52, manga.pagesRead)
        assertEquals(0, manga.charactersRead)
        val epub = overview.books.last()
        assertEquals(null, epub.pagesRead)
        assertEquals(500, epub.charactersRead)
        assertEquals("2026-09-02", manga.lastReadDateKey)
        assertEquals("2026-08-30", manga.startedDateKey)
        assertEquals(2, manga.daysRead)
        assertEquals(listOf("2026-09-02", "2026-08-30"), manga.days.map { it.dateKey })
        assertEquals(listOf("2026-09-02", "2026-09-01", "2026-08-30"), overview.daily.map { it.dateKey })
        assertEquals(60.0, overview.daily.first { it.dateKey == "2026-09-01" }.seconds, 0.0)
        assertEquals(500, overview.daily.first { it.dateKey == "2026-09-01" }.characters)
        assertEquals(ContentType.Mokuro, manga.contentType)
        assertEquals(1260.0, overview.totalSeconds, 0.0)
        assertEquals(0.0, overview.todaySeconds, 0.0)
    }

    @Test
    fun booksWithNeitherTimeNorCharactersAreOmitted() {
        val overview = summarizeReadingStatistics(
            listOf(
                book("empty", "Never opened"),
                book("idle", "Opened but idle", ContentType.Epub, day("2026-09-10", 0.0, units = 0)),
                book("chars", "Characters, no time", ContentType.Epub, day("2026-09-10", 0.0, units = 3)),
                book("read", "Read", ContentType.Epub, day("2026-09-10", 45.0)),
            ),
            todayKey = "2026-09-12",
        )

        assertEquals(listOf("read", "chars"), overview.books.map { it.bookId })
        assertEquals(45.0, overview.totalSeconds, 0.0)
        assertEquals(3, overview.totalCharacters)
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
        assertEquals(25, summary.charactersRead)
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
    fun mangaCharactersComeFromTheTextSidecarAndCountTowardsTotals() {
        val manga = BookStatisticsInput(
            bookId = "m", title = "Manga", contentType = ContentType.Mokuro,
            statistics = listOf(day("2026-09-12", 120.0, units = 3), day("2026-09-10", 60.0, units = 9)),
            mangaTextStatistics = listOf(
                MangaTextStatistic("2026-09-12", 450, lastModified = 1),
                MangaTextStatistic("2026-09-10", 1_200, lastModified = 1),
            ),
        )
        val epub = book("e", "Book", ContentType.Epub, day("2026-09-12", 30.0, units = 800))

        val overview = summarizeReadingStatistics(listOf(manga, epub), todayKey = "2026-09-12")

        val mangaSummary = overview.books.first { it.bookId == "m" }
        assertEquals(12, mangaSummary.pagesRead)
        assertEquals(1_650, mangaSummary.charactersRead)
        assertEquals(2_450, overview.totalCharacters)
        assertEquals(1_250, overview.todayCharacters)
        assertEquals(150.0, overview.todaySeconds, 0.0)
    }

    @Test
    fun emptyLibraryProducesAnEmptyOverview() {
        val overview = summarizeReadingStatistics(emptyList(), todayKey = "2026-09-12")

        assertTrue(overview.books.isEmpty())
        assertEquals(0.0, overview.totalSeconds, 0.0)
        assertEquals(0, overview.totalCharacters)
        assertEquals(0, overview.todayCharacters)
        assertNull(overview.books.firstOrNull())
    }

    @Test
    fun todayNeverExceedsAllTimeBecauseOnlyListedBooksFeedTheDailyTotals() {
        val overview = summarizeReadingStatistics(
            listOf(
                book("timeless", "Synced characters, no time", ContentType.Epub, day("2026-09-12", 0.0, units = 5_000)),
                book("read", "Read today", ContentType.Epub, day("2026-09-12", 120.0, units = 300)),
            ),
            todayKey = "2026-09-12",
        )

        assertEquals(listOf("read", "timeless"), overview.books.map { it.bookId })
        assertEquals(5_300, overview.totalCharacters)
        assertEquals(5_300, overview.todayCharacters)
        assertEquals(120.0, overview.todaySeconds, 0.0)
        assertEquals(listOf("2026-09-12"), overview.daily.map { it.dateKey })
        assertTrue(overview.todayCharacters <= overview.totalCharacters)
    }
}
