package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.mangareader.MangaTextReadCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate

/**
 * What [summarizeReadingStatistics] leaves out, and whether what it shows on one line can
 * contradict another line of the same page or the reader's own sheet.
 */
class ReadingStatisticsOverviewFilteringTest {
    private class FakeClock(var date: LocalDate, var millis: Long) : ReaderStatisticsClock {
        override fun currentTimeMillis(): Long = millis
        override fun currentDate(): LocalDate = date
    }

    private fun day(dateKey: String, seconds: Double, characters: Int = 0, modified: Long = 1L) =
        ReadingStatistics(
            title = "Book",
            dateKey = dateKey,
            charactersRead = characters,
            readingTime = seconds,
            lastStatisticModified = modified,
        )

    private fun epub(id: String, vararg days: ReadingStatistics) =
        BookStatisticsInput(bookId = id, title = id, contentType = ContentType.Epub, statistics = days.toList())

    @Test
    fun aBookReadOnlyTodayIsListedWithTodayEqualToItsTotal() {
        val overview = summarizeReadingStatistics(
            listOf(epub("only-today", day("2026-09-12", 420.0, characters = 900))),
            todayKey = "2026-09-12",
        )

        val row = overview.books.single()
        assertEquals(420.0, row.totalSeconds, 0.0)
        assertEquals(900, row.charactersRead)
        assertEquals("2026-09-12", row.lastReadDateKey)
        assertEquals(420.0, overview.totalSeconds, 0.0)
        assertEquals(420.0, overview.todaySeconds, 0.0)
        assertEquals(900, overview.totalCharacters)
        assertEquals(900, overview.todayCharacters)
    }

    @Test
    fun aNegativeDayLowersTheTotalTheSameWayOnBothScreens() {
        val days = listOf(day("2026-09-10", -100.0, characters = 5), day("2026-09-11", 400.0, characters = 30), day("2026-09-12", 40.0, characters = 8))
        val tracker = ReaderStatisticsTracker("Book", days, enabled = true, clock = FakeClock(LocalDate.of(2026, 9, 12), 1L))

        val overview = summarizeReadingStatistics(listOf(epub("negative-day", *days.toTypedArray())), todayKey = "2026-09-12")

        val row = overview.books.single()
        assertEquals(340.0, row.totalSeconds, 0.0)
        assertEquals(tracker.state.allTime.readingTime, row.totalSeconds, 0.0)
        assertEquals(tracker.state.allTime.charactersRead, row.charactersRead)
        assertEquals("2026-09-12", row.lastReadDateKey)
    }

    @Test
    fun todayCharactersNeverExceedTotalCharactersOnTheSamePage() {
        // Today's record has characters but no time: the book still counts, on both lines.
        val overview = summarizeReadingStatistics(
            listOf(epub("characters-without-time", day("2026-09-12", 0.0, characters = 150))),
            todayKey = "2026-09-12",
        )

        // Anything the reader's sheet counts as read is listed, so both lines carry the same 150.
        assertEquals(listOf("characters-without-time"), overview.books.map { it.bookId })
        assertEquals(150, overview.totalCharacters)
        assertEquals(150, overview.todayCharacters)
        assertTrue(
            "today shows ${overview.todayCharacters} characters but the total shows ${overview.totalCharacters}",
            overview.todayCharacters <= overview.totalCharacters,
        )
    }

    @Test
    fun mangaCharactersWithoutRecordedTimeShowTheSameOnTheSheetAndThePage() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-manga-characters-only").toFile())
        val root = repository.createBookDirectory("manga-x")
        repository.saveMetadata(root, BookMetadata(id = "manga-x", title = "Manga X", cover = null, folder = "manga-x", lastAccess = 1.0))
        root.resolve("mokuro.json").writeText("{}")
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 1L)
        val counter = MangaTextReadCounter(repository.loadMangaTextStatistics(root), clock = clock)
        counter.add(505)
        repository.saveMangaTextStatistics(root, requireNotNull(counter.statisticsForPersistenceOrNull()))
        // The page-time sidecar exists but holds nothing.
        repository.saveStatistics(root, emptyList())

        val overview = loadReadingStatisticsOverview(repository, todayKey = "2026-09-12")

        // The manga sheet shows 505 characters today and all time; the page must not show
        // 505 on one line and 0 on the other.
        assertEquals(counter.state.todayCharacters, overview.todayCharacters)
        assertEquals(counter.state.allTimeCharacters, overview.totalCharacters)
    }
}
