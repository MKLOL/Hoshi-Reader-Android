package moe.antimony.hoshi.epub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The manga reader can write `statistics.json` from two coroutines at once (the debounced
 * page-turn save and the ON_STOP save both hop to Dispatchers.IO), and the Statistics page
 * reloads on every change signal. A reload must never see a torn file: that would list the
 * book with no time (or drop it) while the sheet still shows the session.
 */
class BookRepositoryConcurrentStatisticsWriteTest {
    private fun days(seconds: Double, count: Int): List<ReadingStatistics> =
        List(count) { index ->
            ReadingStatistics(
                title = "Book",
                dateKey = "2025-%02d-%02d".format(index / 28 + 1, index % 28 + 1),
                charactersRead = index * 37,
                readingTime = seconds,
                lastStatisticModified = 1L,
            )
        }

    @Test
    fun overlappingSavesAlwaysLeaveAParseableFileHoldingOneOfTheWrites() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-concurrent-statistics").toFile())
        val root = repository.createBookDirectory("book-a")
        val first = days(10.0, 300)
        val second = days(20.0, 300)
        repository.saveStatistics(root, first)
        var tornReads = 0

        repeat(200) {
            val writes = listOf(
                async(Dispatchers.IO) { repository.saveStatistics(root, first) },
                async(Dispatchers.IO) { repository.saveStatistics(root, second) },
            )
            val concurrentRead = async(Dispatchers.IO) { repository.loadStatistics(root) }
            writes.awaitAll()
            if (concurrentRead.await().isEmpty()) tornReads += 1
            val settled = repository.loadStatistics(root)
            assertTrue("unexpected content after overlapping saves", settled == first || settled == second)
        }

        assertEquals("reads that saw a torn or missing statistics.json", 0, tornReads)
    }
}
