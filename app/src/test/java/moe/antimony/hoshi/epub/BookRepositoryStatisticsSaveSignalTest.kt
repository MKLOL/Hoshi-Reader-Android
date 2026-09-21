package moe.antimony.hoshi.epub

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext

/**
 * The manga reader cancels its debounced page-turn save whenever the next page turn arrives.
 * A save that already reached `statistics.json` by then must still bump `statisticsChanges`,
 * or the file holds a session that no screen is told to reload for.
 */
class BookRepositoryStatisticsSaveSignalTest {
    /** Runs dispatched blocks only when the test says so, to stop between the write and the signal. */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }
        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    @Test
    fun aSaveCancelledAfterItsWriteLandedStillSignalsTheChange() = runBlocking {
        val io = ManualDispatcher()
        val repository = BookRepository(Files.createTempDirectory("hoshi-save-signal").toFile(), ioDispatcher = io)
        val root = repository.booksDirectory.resolve("book-a").also { check(it.mkdirs()) }
        val entry = ReadingStatistics(title = "Book", dateKey = "2026-09-12", charactersRead = 10, readingTime = 30.0, lastStatisticModified = 1)
        val file = root.resolve("statistics.json")
        val before = repository.statisticsChanges.value

        val save = launch(start = CoroutineStart.UNDISPATCHED) { repository.saveStatistics(root, listOf(entry)) }
        // Drive the save's IO hops one at a time until the sidecar holds the entry, then cancel
        // it the way the next page turn does, before its post-write code has run.
        var steps = 0
        while (true) {
            io.runAll()
            if (file.isFile && file.readText().contains("2026-09-12")) break
            yield()
            check(++steps < 100) { "the save never reached the file" }
        }
        // The write has landed; its continuation is queued but has not run yet.
        save.cancel()
        save.join()

        assertTrue(file.readText().contains("2026-09-12"))
        assertEquals(
            "a write that reached statistics.json must bump statisticsChanges",
            before + 1,
            repository.statisticsChanges.value,
        )
    }
}
