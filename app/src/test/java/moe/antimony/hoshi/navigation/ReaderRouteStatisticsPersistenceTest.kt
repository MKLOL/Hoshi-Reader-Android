package moe.antimony.hoshi.navigation

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.reader.ReaderStatisticsClock
import moe.antimony.hoshi.features.reader.ReaderStatisticsTracker
import moe.antimony.hoshi.features.reader.loadReadingStatisticsOverview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/**
 * The EPUB reader persists statistics only through `onSaveBookmark`, which lands in
 * [ReaderRouteStateHolder.saveBookmark] and from there in [BookRepository.saveStatistics].
 * These tests drive that route with the real repository, replaying the exact tracker calls
 * `ReaderWebView` makes at each lifecycle point, and check the Statistics page reads back
 * the sheet's numbers after every save.
 */
class ReaderRouteStatisticsPersistenceTest {
    private class FakeClock(var date: LocalDate, var millis: Long) : ReaderStatisticsClock {
        override fun currentTimeMillis(): Long = millis
        override fun currentDate(): LocalDate = date
        fun advanceSeconds(seconds: Long) { millis += seconds * 1_000L }
    }

    private class Fixture(val repository: BookRepository, val root: File, val book: EpubBook, val state: ReaderRouteLoadState.Ready)

    private suspend fun fixture(name: String, history: List<ReadingStatistics>): Fixture {
        val repository = BookRepository(Files.createTempDirectory("hoshi-epub-route-$name").toFile())
        val root = repository.createBookDirectory("book-a")
        val metadata = BookMetadata(id = "book-a", title = "Book A", cover = null, folder = "book-a", lastAccess = 1.0)
        repository.saveMetadata(root, metadata)
        if (history.isNotEmpty()) repository.saveStatistics(root, history)
        val book = EpubBook(
            title = "Book A",
            coverHref = null,
            chapters = listOf(EpubChapter(id = "c1", href = "c1.xhtml", mediaType = "application/xhtml+xml", html = "1234567890")),
        )
        val state = ReaderRouteLoadState.Ready(entry = BookEntry(root, metadata), bookRoot = root, book = book, bookmark = null)
        return Fixture(repository, root, book, state)
    }

    private suspend fun assertPageShowsTheSheet(fixture: Fixture, tracker: ReaderStatisticsTracker, todayKey: String) {
        val overview = loadReadingStatisticsOverview(fixture.repository, todayKey = todayKey)
        val row = overview.books.single()
        assertEquals(tracker.state.allTime.readingTime, row.totalSeconds, 1e-9)
        assertEquals(tracker.state.allTime.charactersRead, row.charactersRead)
        assertEquals(tracker.state.today.readingTime, overview.todaySeconds, 1e-9)
        assertEquals(tracker.state.today.charactersRead, overview.todayCharacters)
    }

    @Test
    fun everySaveOnTheEpubReaderRouteLeavesThePageEqualToTheSheet() = runBlocking {
        val fixture = fixture(
            "lifecycle",
            listOf(ReadingStatistics(title = "Book A", dateKey = "2026-09-10", charactersRead = 2_000, readingTime = 900.0, lastStatisticModified = 1)),
        )
        val stateHolder = ReaderRouteStateHolder(fixture.repository)
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 1_000_000L)
        val tracker = ReaderStatisticsTracker("Book A", fixture.repository.loadStatistics(fixture.root), enabled = true, clock = clock)
        val book = fixture.book
        var bookmarkSaves = 0
        suspend fun saveLikeTheReader(progress: Double) {
            // ReaderWebView.saveReaderPosition: record the displayed position, then hand the
            // statistics to onSaveBookmark together with the bookmark.
            tracker.update(book.characterCountAt(0, progress))
            stateHolder.saveBookmark(
                state = fixture.state,
                chapterIndex = 0,
                progress = progress,
                statistics = tracker.statisticsForPersistenceOrNull(),
                onBookmarkSaved = { bookmarkSaves += 1 },
            )
        }

        // Opening the book starts tracking at the restored position.
        tracker.start(book.characterCountAt(0, 0.0))

        // A page turn after 30 s saves the bookmark with the statistics.
        clock.advanceSeconds(30)
        val changesBefore = fixture.repository.statisticsChanges.value
        saveLikeTheReader(progress = 0.5)
        assertEquals(changesBefore + 1, fixture.repository.statisticsChanges.value)
        assertPageShowsTheSheet(fixture, tracker, todayKey = "2026-09-12")
        assertEquals(930.0, tracker.state.allTime.readingTime, 0.0)

        // ON_PAUSE: the tracker pauses (flushing the elapsed 20 s), then the position is saved.
        clock.advanceSeconds(20)
        assertTrue(tracker.pause(book.characterCountAt(0, 0.5)))
        saveLikeTheReader(progress = 0.5)
        assertPageShowsTheSheet(fixture, tracker, todayKey = "2026-09-12")
        assertEquals(950.0, tracker.state.allTime.readingTime, 0.0)

        // Ten minutes in the background count nowhere; ON_RESUME restarts from the current position.
        clock.advanceSeconds(600)
        tracker.start(book.characterCountAt(0, 0.5))
        clock.advanceSeconds(45)

        // Closing the reader saves the displayed position one last time.
        saveLikeTheReader(progress = 1.0)
        assertPageShowsTheSheet(fixture, tracker, todayKey = "2026-09-12")
        assertEquals(changesBefore + 3, fixture.repository.statisticsChanges.value)
        assertEquals(3, bookmarkSaves)

        val overview = loadReadingStatisticsOverview(fixture.repository, todayKey = "2026-09-12")
        val row = overview.books.single()
        assertEquals(995.0, row.totalSeconds, 0.0)
        assertEquals(2_010, row.charactersRead)
        assertEquals(95.0, overview.todaySeconds, 0.0)
        assertEquals(10, overview.todayCharacters)
        assertEquals("2026-09-12", row.lastReadDateKey)
        val bookmark = requireNotNull(fixture.repository.loadBookmark(fixture.root))
        assertEquals(0, bookmark.chapterIndex)
        assertEquals(1.0, bookmark.progress, 0.0)
        assertEquals(10, bookmark.characterCount)
    }

    @Test
    fun aProgressOnlySaveLeavesStatisticsAndTheChangeSignalAlone() = runBlocking {
        val history = listOf(ReadingStatistics(title = "Book A", dateKey = "2026-09-10", charactersRead = 2_000, readingTime = 900.0, lastStatisticModified = 1))
        val fixture = fixture("progress-only", history)
        val stateHolder = ReaderRouteStateHolder(fixture.repository)
        val changesBefore = fixture.repository.statisticsChanges.value
        val overviewBefore = loadReadingStatisticsOverview(fixture.repository, todayKey = "2026-09-12")

        stateHolder.saveBookmark(
            state = fixture.state,
            chapterIndex = 0,
            progress = 0.3,
            statistics = null,
            onBookmarkSaved = {},
        )

        assertEquals(changesBefore, fixture.repository.statisticsChanges.value)
        assertEquals(history, fixture.repository.loadStatistics(fixture.root))
        assertEquals(overviewBefore, loadReadingStatisticsOverview(fixture.repository, todayKey = "2026-09-12"))
        assertEquals(3, requireNotNull(fixture.repository.loadBookmark(fixture.root)).characterCount)
    }

    @Test
    fun aFreshBookWithNoRecordedTimeSavesNothingUntilTheFirstTick() = runBlocking {
        val fixture = fixture("fresh", history = emptyList())
        val stateHolder = ReaderRouteStateHolder(fixture.repository)
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 5_000L)
        val tracker = ReaderStatisticsTracker("Book A", fixture.repository.loadStatistics(fixture.root), enabled = true, clock = clock)
        val changesBefore = fixture.repository.statisticsChanges.value

        // Opened and closed within the same millisecond: no time elapsed, nothing to persist.
        tracker.start(0)
        tracker.update(fixture.book.characterCountAt(0, 0.2))
        stateHolder.saveBookmark(fixture.state, 0, 0.2, tracker.statisticsForPersistenceOrNull(), onBookmarkSaved = {})
        assertEquals(changesBefore, fixture.repository.statisticsChanges.value)
        assertTrue(loadReadingStatisticsOverview(fixture.repository, todayKey = "2026-09-12").books.isEmpty())

        // The first second of reading is enough for the page to list the book.
        clock.advanceSeconds(1)
        tracker.update(fixture.book.characterCountAt(0, 0.4))
        stateHolder.saveBookmark(fixture.state, 0, 0.4, tracker.statisticsForPersistenceOrNull(), onBookmarkSaved = {})
        assertEquals(changesBefore + 1, fixture.repository.statisticsChanges.value)
        assertPageShowsTheSheet(fixture, tracker, todayKey = "2026-09-12")
        assertEquals(1.0, loadReadingStatisticsOverview(fixture.repository, todayKey = "2026-09-12").totalSeconds, 0.0)
    }
}
