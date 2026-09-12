package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.mangareader.MangaTextReadCounter
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/**
 * Every save merges per day and keeps the entry with the newest modification stamp, and the
 * tracker stamps its entries with the wall clock. Whenever the wall clock is behind the stamp
 * already on disk (another device with a fast clock synced the same day; this device's clock
 * was set back after an earlier session) the reader's own entry must still supersede the entry
 * it was derived from. Otherwise the sheet shows a session that the Statistics page never
 * receives, and the time is gone when the reader closes.
 */
class ReadingStatisticsClockSkewTest {
    private class FakeClock(var date: LocalDate, var millis: Long) : ReaderStatisticsClock {
        override fun currentTimeMillis(): Long = millis
        override fun currentDate(): LocalDate = date
        fun advanceSeconds(seconds: Long) { millis += seconds * 1_000L }
    }

    private fun day(dateKey: String, seconds: Double, characters: Int, modified: Long) =
        ReadingStatistics(title = "Book", dateKey = dateKey, charactersRead = characters, readingTime = seconds, lastStatisticModified = modified)

    private suspend fun BookRepository.newBook(folder: String, manga: Boolean = false): File {
        val root = createBookDirectory(folder)
        saveMetadata(root, BookMetadata(id = folder, title = folder, cover = null, folder = folder, lastAccess = 1.0))
        if (manga) root.resolve("mokuro.json").writeText("{}")
        return root
    }

    @Test
    fun aSessionOnTopOfADayStampedAheadOfTheClockIsNotDroppedByTheSave() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-skew-time").toFile())
        val root = repository.newBook("book-a")
        // Today's entry carries a stamp an hour ahead of this reader's clock.
        val stampAhead = 5_000_000L
        repository.saveStatistics(root, listOf(day("2026-09-12", 600.0, 1_000, modified = stampAhead)))
        val clock = FakeClock(LocalDate.of(2026, 9, 12), stampAhead - 3_600_000L)
        val tracker = ReaderStatisticsTracker("book-a", repository.loadStatistics(root), enabled = true, clock = clock)

        tracker.start(1_000)
        clock.advanceSeconds(60)
        tracker.update(1_100)
        repository.saveStatistics(root, requireNotNull(tracker.statisticsForPersistenceOrNull()))

        // The sheet says 660 s all time and 1,100 characters today; the page must say the same.
        assertEquals(660.0, tracker.state.allTime.readingTime, 0.0)
        val overview = loadReadingStatisticsOverview(repository, todayKey = "2026-09-12")
        val row = overview.books.single()
        assertEquals(tracker.state.allTime.readingTime, row.totalSeconds, 0.0)
        assertEquals(tracker.state.today.charactersRead, overview.todayCharacters)
    }

    @Test
    fun mangaCharactersAddedOnTopOfADayStampedAheadOfTheClockSurviveTheSave() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-skew-text").toFile())
        val root = repository.newBook("manga-a", manga = true)
        val stampAhead = 5_000_000L
        repository.saveMangaTextStatistics(root, listOf(MangaTextStatistic("2026-09-12", charactersRead = 400, lastModified = stampAhead)))
        val clock = FakeClock(LocalDate.of(2026, 9, 12), stampAhead - 3_600_000L)
        val counter = MangaTextReadCounter(repository.loadMangaTextStatistics(root), clock = clock)

        counter.add(25)
        repository.saveMangaTextStatistics(root, requireNotNull(counter.statisticsForPersistenceOrNull()))

        assertEquals(425, counter.state.allTimeCharacters)
        val overview = loadReadingStatisticsOverview(repository, todayKey = "2026-09-12")
        assertEquals(counter.state.allTimeCharacters, overview.totalCharacters)
    }

    @Test
    fun aClockStepBackwardsMidSessionLosesAtMostOneTick() {
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 10_000_000L)
        val tracker = ReaderStatisticsTracker("Book", emptyList(), enabled = true, clock = clock)
        tracker.start(0)
        clock.advanceSeconds(10)
        tracker.update(10)
        assertEquals(10.0, tracker.state.session.readingTime, 0.0)

        // The system clock is corrected ten minutes backwards while the reader keeps ticking every second.
        clock.millis -= 600_000L
        repeat(30) {
            clock.advanceSeconds(1)
            tracker.update(11 + it)
        }

        // Thirty seconds of reading followed the correction. Losing the one tick that straddled
        // it is fine; losing every tick until the clock catches up with its old value is not.
        assertTrue("read ${tracker.state.session.readingTime} s of 40", tracker.state.session.readingTime >= 39.0)
        assertEquals(40, tracker.state.session.charactersRead)
    }

    @Test
    fun theSheetShowsTheNewDayAsTodayOnceMidnightPassedWhileTrackingWasStopped() {
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 1_000_000L)
        val tracker = ReaderStatisticsTracker("Book", listOf(day("2026-09-12", 100.0, 10, modified = 1)), enabled = true, clock = clock)
        tracker.start(10)
        clock.advanceSeconds(20)
        tracker.stop(30) // paused from the sheet just before midnight
        assertEquals(120.0, tracker.state.today.readingTime, 0.0)

        clock.date = LocalDate.of(2026, 9, 13)
        clock.advanceSeconds(120)

        // The Statistics page counts "today" from the clock: 0 s on the 13th. The sheet, still
        // open or reopened from the menu, must not keep labelling the 12th's 120 s as today.
        assertEquals("2026-09-13", tracker.state.today.dateKey)
        assertEquals(0.0, tracker.state.today.readingTime, 0.0)
        assertEquals(120.0, tracker.state.allTime.readingTime, 0.0)

        // Resuming keeps the 12th's 120 s and starts the 13th from zero.
        tracker.start(30)
        clock.advanceSeconds(5)
        tracker.update(35)
        assertEquals(5.0, tracker.state.today.readingTime, 0.0)
        assertEquals(125.0, tracker.state.allTime.readingTime, 0.0)
        val persisted = tracker.statisticsForPersistence()
        assertEquals(120.0, persisted.single { it.dateKey == "2026-09-12" }.readingTime, 0.0)
        assertEquals(5.0, persisted.single { it.dateKey == "2026-09-13" }.readingTime, 0.0)
    }
}
