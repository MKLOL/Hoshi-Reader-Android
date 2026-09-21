package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.readingTotals
import moe.antimony.hoshi.features.mangareader.MangaTextReadCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/**
 * The reader's Statistics sheet (fed by [ReaderStatisticsTracker]) and the Statistics screens
 * (fed by [loadReadingStatisticsOverview]) must never disagree about the same book. These tests
 * drive a real session through the tracker, persist it through the real repository, and compare
 * what each side would display.
 */
class ReadingStatisticsConsistencyTest {
    private class FakeClock(var date: LocalDate, var millis: Long) : ReaderStatisticsClock {
        override fun currentTimeMillis(): Long = millis
        override fun currentDate(): LocalDate = date
        fun advanceSeconds(seconds: Int) { millis += seconds * 1_000L }
    }

    private fun day(dateKey: String, seconds: Double, characters: Int, modified: Long = 1L) =
        ReadingStatistics(title = "Book", dateKey = dateKey, charactersRead = characters, readingTime = seconds, lastStatisticModified = modified)

    private suspend fun BookRepository.newBook(folder: String, title: String, manga: Boolean = false): File {
        val root = createBookDirectory(folder)
        saveMetadata(root, BookMetadata(id = folder, title = title, cover = null, folder = folder, lastAccess = 1.0))
        if (manga) root.resolve("mokuro.json").writeText("{}")
        return root
    }

    @Test
    fun sheetAllTimeAndOverviewTotalsComeFromTheSameNumbers() {
        val statistics = listOf(
            day("2026-09-01", 600.0, 1_200),
            day("2026-09-02", 1_845.5, 3_010),
            day("2026-09-02", 12.0, 5, modified = 0L), // stale duplicate that must lose
            day("2026-09-12", 30.0, 40),
        )
        val tracker = ReaderStatisticsTracker("Book", statistics, enabled = true, clock = FakeClock(LocalDate.of(2026, 9, 12), 0L))
        val overview = summarizeReadingStatistics(
            listOf(BookStatisticsInput("b", "Book", ContentType.Epub, statistics)),
            todayKey = "2026-09-12",
        )

        val sheet = tracker.state.allTime
        val row = overview.books.single()
        assertEquals(sheet.readingTime, row.totalSeconds, 0.0)
        assertEquals(sheet.charactersRead, row.charactersRead)
        assertEquals(statistics.readingTotals().readingTime, row.totalSeconds, 0.0)
        assertEquals(tracker.state.today.readingTime, overview.todaySeconds, 0.0)
    }

    @Test
    fun aReadingSessionPersistedByTheReaderIsWhatTheOverviewShows() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-consistency").toFile())
        val root = repository.newBook("book-a", "Book A")
        repository.saveStatistics(root, listOf(day("2026-09-10", 900.0, 2_000)))
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 1_000_000L)
        val tracker = ReaderStatisticsTracker("Book A", repository.loadStatistics(root), enabled = true, clock = clock)

        tracker.start(currentCharacter = 100)
        clock.advanceSeconds(65)
        tracker.update(currentCharacter = 460)
        clock.advanceSeconds(35)
        tracker.pause(currentCharacter = 700)
        repository.saveStatistics(root, requireNotNull(tracker.statisticsForPersistenceOrNull()))

        val overview = loadReadingStatisticsOverview(repository, todayKey = "2026-09-12")
        val row = overview.books.single()
        assertEquals(1_000.0, row.totalSeconds, 0.0)
        assertEquals(tracker.state.allTime.readingTime, row.totalSeconds, 0.0)
        assertEquals(tracker.state.allTime.charactersRead, row.charactersRead)
        assertEquals(2_600, row.charactersRead)
        assertEquals(tracker.state.today.readingTime, overview.todaySeconds, 0.0)
        assertEquals(tracker.state.today.charactersRead, overview.todayCharacters)
        assertEquals("2026-09-12", row.lastReadDateKey)
    }

    @Test
    fun aSessionThatCrossesMidnightStaysConsistentAcrossBothDays() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-midnight").toFile())
        val root = repository.newBook("book-b", "Book B")
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 5_000L)
        val tracker = ReaderStatisticsTracker("Book B", emptyList(), enabled = true, clock = clock)

        tracker.start(0)
        clock.advanceSeconds(120)
        tracker.update(300)
        clock.date = LocalDate.of(2026, 9, 13)
        clock.advanceSeconds(60)
        tracker.update(500)
        repository.saveStatistics(root, requireNotNull(tracker.statisticsForPersistenceOrNull()))

        val overview = loadReadingStatisticsOverview(repository, todayKey = "2026-09-13")
        val row = overview.books.single()
        assertEquals(180.0, row.totalSeconds, 0.0)
        assertEquals(tracker.state.allTime.readingTime, row.totalSeconds, 0.0)
        assertEquals(60.0, overview.todaySeconds, 0.0)
        assertEquals(tracker.state.today.readingTime, overview.todaySeconds, 0.0)
    }

    @Test
    fun mangaCharactersCountedInTheReaderMatchTheOverview() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-manga-consistency").toFile())
        val root = repository.newBook("manga-a", "Manga A", manga = true)
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 7_000L)
        val tracker = ReaderStatisticsTracker("Manga A", emptyList(), enabled = true, clock = clock)
        val counter = MangaTextReadCounter(repository.loadMangaTextStatistics(root), clock = clock)

        tracker.start(0)
        clock.advanceSeconds(30)
        counter.add(410)
        tracker.update(1)
        clock.advanceSeconds(30)
        counter.add(95)
        tracker.update(2)
        repository.saveStatistics(root, requireNotNull(tracker.statisticsForPersistenceOrNull()))
        repository.saveMangaTextStatistics(root, requireNotNull(counter.statisticsForPersistenceOrNull()))

        val overview = loadReadingStatisticsOverview(repository, todayKey = "2026-09-12")
        val row = overview.books.single()
        assertEquals(ContentType.Mokuro, row.contentType)
        assertEquals(tracker.state.allTime.readingTime, row.totalSeconds, 0.0)
        assertEquals(tracker.state.allTime.charactersRead, row.pagesRead)
        assertEquals(counter.state.allTimeCharacters, row.charactersRead)
        assertEquals(505, row.charactersRead)
        assertEquals(counter.state.todayCharacters, overview.todayCharacters)
    }

    @Test
    fun everyStatisticsSaveSignalsAChangeSoOpenScreensReload() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-changes").toFile())
        val root = repository.newBook("book-c", "Book C", manga = true)
        val before = repository.statisticsChanges.value

        repository.saveStatistics(root, listOf(day("2026-09-12", 10.0, 1)))
        assertEquals(before + 1, repository.statisticsChanges.value)

        repository.saveMangaTextStatistics(root, emptyList())
        assertEquals(before + 2, repository.statisticsChanges.value)

        repository.loadStatistics(root)
        assertEquals(before + 2, repository.statisticsChanges.value)
        assertTrue(repository.statisticsChanges.value > before)
    }

    @Test
    fun daysImportedBySyncWhileTheBookIsOpenSurviveTheReadersNextSave() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-import-while-open").toFile())
        val root = repository.newBook("book-d", "Book D")
        repository.saveStatistics(root, listOf(day("2026-09-10", 900.0, 2_000, modified = 10L)))
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 1_000_000L)
        val tracker = ReaderStatisticsTracker("Book D", repository.loadStatistics(root), enabled = true, clock = clock)
        tracker.start(0)
        clock.advanceSeconds(60)
        tracker.update(120)

        // A Drive/HTTP import lands while the reader is open and adds an older day.
        repository.saveStatistics(root, listOf(day("2026-09-05", 1_200.0, 3_000, modified = 5L)))
        // The reader saves its session afterwards, knowing nothing about that day.
        repository.saveStatistics(root, requireNotNull(tracker.statisticsForPersistenceOrNull()))

        val overview = loadReadingStatisticsOverview(repository, todayKey = "2026-09-12")
        val row = overview.books.single()
        assertEquals(listOf("2026-09-12", "2026-09-10", "2026-09-05"), row.days.map { it.dateKey })
        assertEquals(900.0 + 1_200.0 + 60.0, row.totalSeconds, 0.0)
        assertEquals(tracker.state.today.readingTime, overview.todaySeconds, 0.0)
    }

    @Test
    fun deletingABookSignalsAStatisticsChange() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-delete").toFile())
        val root = repository.newBook("book-e", "Book E")
        val before = repository.statisticsChanges.value

        repository.deleteBook(root)

        assertTrue(repository.statisticsChanges.value > before)
        repository.notifyStatisticsChanged()
        assertEquals(before + 2, repository.statisticsChanges.value)
    }
}
