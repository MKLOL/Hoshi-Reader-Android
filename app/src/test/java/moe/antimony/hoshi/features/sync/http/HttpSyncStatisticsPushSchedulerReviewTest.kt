package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class HttpSyncStatisticsPushSchedulerReviewTest {
    private val configured = HttpSyncSettings(baseUrl = "http://127.0.0.1:1", bearerToken = "token")

    private suspend fun await(counter: AtomicInteger, expected: Int, timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (counter.get() < expected && System.currentTimeMillis() < deadline) delay(5)
    }

    @Test
    fun aPushSupersededByANewerChangeIsNotAFailureAndDoesNotTripTheBackoff() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = AtomicInteger()
        val completed = AtomicInteger()
        val now = 0L
        val scheduler = HttpSyncStatisticsPushScheduler(
            scope = scope,
            currentSettings = { configured },
            push = { _, _, _, _ ->
                started.incrementAndGet()
                delay(400)
                completed.incrementAndGet()
            },
            delayMs = 10,
            flushDelayMs = 10,
            clock = { now },
        )
        val root = File("/books/a")

        // Three times: a push is in flight when the reader saves again, which supersedes it.
        repeat(HttpSyncStatisticsPushScheduler.FAILURE_THRESHOLD) { round ->
            scheduler.onStatisticsChanged(root, "A", "a")
            await(started, round + 1)
            scheduler.onStatisticsChanged(root, "A", "a")
            delay(50)
        }
        assertEquals(HttpSyncStatisticsPushScheduler.FAILURE_THRESHOLD + 1, started.get())
        await(completed, 1)
        assertEquals("the superseding push itself completed", 1, completed.get())

        // Superseded pushes were never failures, so leaving the reader must still push.
        scheduler.flushNow(root, "A", "a")
        await(started, HttpSyncStatisticsPushScheduler.FAILURE_THRESHOLD + 2, timeoutMs = 1_000)
        assertEquals("flush after supersessions still pushes", HttpSyncStatisticsPushScheduler.FAILURE_THRESHOLD + 2, started.get())
        scope.cancel()
    }

    @Test
    fun aSettingsReadThatThrowsDoesNotEscapeTheScope() = runBlocking {
        val failures = AtomicInteger()
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> failures.incrementAndGet() },
        )
        val pushes = AtomicInteger()
        val scheduler = HttpSyncStatisticsPushScheduler(
            scope = scope,
            currentSettings = { throw java.io.IOException("datastore unreadable") },
            push = { _, _, _, _ -> pushes.incrementAndGet() },
            delayMs = 10,
            flushDelayMs = 10,
        )

        scheduler.flushNow(File("/books/a"), "A", "a")
        delay(200)

        assertEquals(0, pushes.get())
        assertEquals("a failing settings read is swallowed like a failed push", 0, failures.get())
        scope.cancel()
    }
}
