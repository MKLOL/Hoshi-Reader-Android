package moe.antimony.hoshi.features.mangareader

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.reader.ReaderStatisticsClock
import moe.antimony.hoshi.features.reader.ReaderStatisticsTracker
import moe.antimony.hoshi.features.reader.loadReadingStatisticsOverview
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.mokuro.MokuroPage
import moe.antimony.hoshi.mokuro.MokuroTextBox
import moe.antimony.hoshi.mokuro.ocrCharactersTurnedPast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/**
 * The manga reader persists at three points: the debounced save after a page turn, ON_STOP,
 * and dispose. This replays the calls `MangaReaderScreen` makes at each of them (page counter
 * arithmetic, OCR characters of the pages turned past, tracker pause/start) against the real
 * repository and checks that the Statistics page reads back exactly what the sheet shows.
 */
class MangaReaderStatisticsPathsTest {
    private class FakeClock(var date: LocalDate, var millis: Long) : ReaderStatisticsClock {
        override fun currentTimeMillis(): Long = millis
        override fun currentDate(): LocalDate = date
        fun advanceSeconds(seconds: Long) { millis += seconds * 1_000L }
    }

    private fun box(vararg lines: String) = MokuroTextBox(0, 0, 10, 10, 12, true, lines.toList())
    private fun page(index: Int, vararg boxes: MokuroTextBox) = MokuroPage(index, "p$index.png", 100, 100, boxes.toList())

    private val book = MokuroBook(
        title = "Manga",
        pages = listOf(
            page(0, box("しろくま")), // 4 characters
            page(1, box("カフェ", "へ")), // 4 characters
            page(2, box("ようこそ")), // 4 characters
            page(3, box("！")), // 1 character
            page(4),
        ),
        coverImagePath = null,
    )

    /** The statistics state MangaReaderScreen keeps, with its persistence points as methods. */
    private class MangaSession(
        val repository: BookRepository,
        val root: File,
        val book: MokuroBook,
        val tracker: ReaderStatisticsTracker,
        val counter: MangaTextReadCounter,
    ) {
        var pageIndex = 0
        var statisticsPageCounter = 0

        fun open() = tracker.start(statisticsPageCounter)

        /** MangaReaderScreen.goToPage without the WebView: counter, characters, tracker update. */
        fun goToPage(index: Int) {
            val clamped = index.coerceIn(0, book.pages.lastIndex)
            if (clamped == pageIndex) return
            statisticsPageCounter = mangaStatisticsCounterAfterPageChange(statisticsPageCounter, pageIndex, clamped)
            counter.add(book.ocrCharactersTurnedPast(pageIndex, clamped))
            pageIndex = clamped
            tracker.update(statisticsPageCounter)
        }

        /** The debounced bookmark save that follows a page turn. */
        suspend fun debouncedSave() {
            tracker.update(statisticsPageCounter)
            persistBoth()
        }

        /** Lifecycle ON_STOP: pause (flushing elapsed time), then persist. */
        suspend fun onStop() {
            tracker.pause(statisticsPageCounter)
            persistBoth()
        }

        /** Lifecycle ON_START after a pause. */
        fun onStart() = tracker.start(statisticsPageCounter)

        /** Leaving the reader: one last update, then persist. */
        suspend fun dispose() {
            tracker.update(statisticsPageCounter)
            persistBoth()
        }

        private suspend fun persistBoth() {
            tracker.statisticsForPersistenceOrNull()?.let { repository.saveStatistics(root, it) }
            counter.statisticsForPersistenceOrNull()?.let { repository.saveMangaTextStatistics(root, it) }
        }
    }

    private suspend fun openManga(
        name: String,
        clock: FakeClock,
        history: List<ReadingStatistics> = emptyList(),
        textHistory: List<MangaTextStatistic> = emptyList(),
    ): MangaSession {
        val repository = BookRepository(Files.createTempDirectory("hoshi-manga-paths-$name").toFile())
        val root = repository.createBookDirectory("manga-a")
        repository.saveMetadata(root, BookMetadata(id = "manga-a", title = "Manga", cover = null, folder = "manga-a", lastAccess = 1.0))
        root.resolve("mokuro.json").writeText("{}")
        if (history.isNotEmpty()) repository.saveStatistics(root, history)
        if (textHistory.isNotEmpty()) repository.saveMangaTextStatistics(root, textHistory)
        return MangaSession(
            repository = repository,
            root = root,
            book = book,
            tracker = ReaderStatisticsTracker("Manga", repository.loadStatistics(root), enabled = true, clock = clock),
            counter = MangaTextReadCounter(repository.loadMangaTextStatistics(root), clock = clock),
        )
    }

    private suspend fun assertPageShowsTheSheet(session: MangaSession, todayKey: String) {
        val overview = loadReadingStatisticsOverview(session.repository, todayKey = todayKey)
        val row = overview.books.single()
        assertEquals(ContentType.Mokuro, row.contentType)
        assertEquals(session.tracker.state.allTime.readingTime, row.totalSeconds, 1e-9)
        assertEquals(session.tracker.state.allTime.charactersRead, row.pagesRead)
        assertEquals(session.counter.state.allTimeCharacters, row.charactersRead)
        assertEquals(session.tracker.state.today.readingTime, overview.todaySeconds, 1e-9)
        assertEquals(session.counter.state.todayCharacters, overview.todayCharacters)
    }

    @Test
    fun pageTurnsInBothDirectionsKeepThePageEqualToTheSheet() = runBlocking {
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 7_000L)
        val session = openManga("turns", clock)
        session.open()

        clock.advanceSeconds(20)
        session.goToPage(1)
        session.debouncedSave()
        assertPageShowsTheSheet(session, "2026-09-12")
        assertEquals(1, session.tracker.state.allTime.charactersRead)
        assertEquals(4, session.counter.state.allTimeCharacters)

        clock.advanceSeconds(20)
        session.goToPage(2)
        session.debouncedSave()
        assertPageShowsTheSheet(session, "2026-09-12")

        clock.advanceSeconds(20)
        session.goToPage(1) // backwards: no page and no character is added
        session.debouncedSave()
        assertPageShowsTheSheet(session, "2026-09-12")
        assertEquals(2, session.tracker.state.allTime.charactersRead)
        assertEquals(8, session.counter.state.allTimeCharacters)

        clock.advanceSeconds(20)
        session.goToPage(3) // forward over two pages
        session.debouncedSave()
        assertPageShowsTheSheet(session, "2026-09-12")

        val row = loadReadingStatisticsOverview(session.repository, todayKey = "2026-09-12").books.single()
        assertEquals(80.0, row.totalSeconds, 0.0)
        assertEquals(4, row.pagesRead)
        assertEquals(16, row.charactersRead)
    }

    @Test
    fun stopStartAndDisposePersistTheSheetAndSkipBackgroundTime() = runBlocking {
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 90_000L)
        val session = openManga(
            "lifecycle",
            clock,
            history = listOf(ReadingStatistics(title = "Manga", dateKey = "2026-09-10", charactersRead = 12, readingTime = 600.0, lastStatisticModified = 1)),
            textHistory = listOf(MangaTextStatistic("2026-09-10", 1_500, lastModified = 1)),
        )
        assertEquals(600.0, session.tracker.state.allTime.readingTime, 0.0)
        assertEquals(1_500, session.counter.state.allTimeCharacters)
        val changesBefore = session.repository.statisticsChanges.value
        session.open()

        clock.advanceSeconds(30)
        session.goToPage(1)
        session.debouncedSave()
        assertPageShowsTheSheet(session, "2026-09-12")

        // Backgrounded: ON_STOP pauses and persists; the hour away must not count anywhere.
        clock.advanceSeconds(15)
        session.onStop()
        assertPageShowsTheSheet(session, "2026-09-12")
        assertEquals(645.0, session.tracker.state.allTime.readingTime, 0.0)
        clock.advanceSeconds(3_600)

        // Foregrounded: ON_START resumes; a page turn whose debounced save has not fired yet
        // is still persisted by dispose when the reader is left.
        session.onStart()
        clock.advanceSeconds(25)
        session.goToPage(2)
        session.dispose()
        assertPageShowsTheSheet(session, "2026-09-12")
        assertEquals(changesBefore + 6, session.repository.statisticsChanges.value)

        val overview = loadReadingStatisticsOverview(session.repository, todayKey = "2026-09-12")
        val row = overview.books.single()
        assertEquals(670.0, row.totalSeconds, 0.0)
        assertEquals(14, row.pagesRead)
        assertEquals(1_508, row.charactersRead)
        assertEquals(70.0, overview.todaySeconds, 0.0)
        assertEquals(8, overview.todayCharacters)

        // Reopening the manga builds the sheet from the files: it shows what the page shows.
        clock.advanceSeconds(120)
        val reopenedTracker = ReaderStatisticsTracker("Manga", session.repository.loadStatistics(session.root), enabled = true, clock = clock)
        val reopenedCounter = MangaTextReadCounter(session.repository.loadMangaTextStatistics(session.root), clock = clock)
        assertEquals(row.totalSeconds, reopenedTracker.state.allTime.readingTime, 0.0)
        assertEquals(row.pagesRead, reopenedTracker.state.allTime.charactersRead)
        assertEquals(row.charactersRead, reopenedCounter.state.allTimeCharacters)
        assertEquals(overview.todaySeconds, reopenedTracker.state.today.readingTime, 0.0)
        assertEquals(overview.todayCharacters, reopenedCounter.state.todayCharacters)
        assertEquals(session.tracker.state.today, reopenedTracker.state.today)
    }

    @Test
    fun stoppingBeforeAnyTimeElapsedPersistsNothingForAFreshManga() = runBlocking {
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 1L)
        val session = openManga("instant-stop", clock)
        val changesBefore = session.repository.statisticsChanges.value
        session.open()

        session.onStop()

        assertEquals(changesBefore, session.repository.statisticsChanges.value)
        assertTrue(loadReadingStatisticsOverview(session.repository, todayKey = "2026-09-12").books.isEmpty())
    }
}
