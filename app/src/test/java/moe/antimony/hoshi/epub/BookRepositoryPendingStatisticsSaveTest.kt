package moe.antimony.hoshi.epub

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import moe.antimony.hoshi.features.reader.ReaderStatisticsClock
import moe.antimony.hoshi.features.reader.ReaderStatisticsTracker
import moe.antimony.hoshi.features.reader.loadReadingStatisticsOverview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate

/**
 * A reader's final save is fire-and-forget on the app scope and takes the book lock only after
 * its first IO hop. A second reader on the same book opened in that gap (back, then the same
 * cover again; the sentence reader pushed over the EPUB reader; the activity recreated) must
 * start from the first session's final entry. Otherwise the two sessions' absolute "today"
 * entries race and the per-day merge keeps only one of them.
 */
class BookRepositoryPendingStatisticsSaveTest {
    private class FakeClock(var date: LocalDate, var millis: Long) : ReaderStatisticsClock {
        override fun currentTimeMillis(): Long = millis
        override fun currentDate(): LocalDate = date
        fun advanceSeconds(seconds: Long) { millis += seconds * 1_000L }
    }

    @Test
    fun aReaderOpenedBeforeThePreviousInstancesFinalSaveLandedStartsFromThatSave() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-pending-save").toFile())
        val root = repository.createBookDirectory("book-a")
        repository.saveMetadata(root, BookMetadata(id = "book-a", title = "Book A", cover = null, folder = "book-a", lastAccess = 1.0))
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 1_000_000L)

        // First instance: reads for a minute and is closed. Its dispose save is launched but has
        // not reached the file yet.
        val first = ReaderStatisticsTracker("Book A", repository.loadStatistics(root), enabled = true, clock = clock)
        first.start(0)
        clock.advanceSeconds(60)
        first.update(100)
        val firstFinal = requireNotNull(first.statisticsForPersistenceOrNull())
        val ioHop = CompletableDeferred<Unit>()
        repository.trackStatisticsSave(root, launch { ioHop.await(); repository.saveStatistics(root, firstFinal) })

        // Second instance opens the same book right away and loads its statistics.
        val secondLoad = async { repository.awaitPendingStatisticsSaves(root); repository.loadStatistics(root) }
        yield()
        assertFalse("the load must wait for the tracked save", secondLoad.isCompleted)
        ioHop.complete(Unit)
        val second = ReaderStatisticsTracker("Book A", secondLoad.await(), enabled = true, clock = clock)
        assertEquals(60.0, second.state.allTime.readingTime, 0.0)

        second.start(100)
        clock.advanceSeconds(30)
        second.update(150)
        repository.saveStatistics(root, requireNotNull(second.statisticsForPersistenceOrNull()))

        // Both sessions are in the file: 90 s, the same number the second sheet shows.
        val row = loadReadingStatisticsOverview(repository, todayKey = "2026-09-12").books.single()
        assertEquals(90.0, row.totalSeconds, 0.0)
        assertEquals(second.state.allTime.readingTime, row.totalSeconds, 0.0)
    }
}
