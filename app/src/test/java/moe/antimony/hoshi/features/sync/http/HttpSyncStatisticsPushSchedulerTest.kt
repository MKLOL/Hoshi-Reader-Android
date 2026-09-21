package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class HttpSyncStatisticsPushSchedulerTest {
    private val configured = HttpSyncSettings(baseUrl = "http://127.0.0.1:1", bearerToken = "token")

    private suspend fun awaitPushes(counter: AtomicInteger, expected: Int, timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (counter.get() < expected && System.currentTimeMillis() < deadline) delay(10)
    }

    @Test
    fun rapidChangesCollapseIntoOnePushPerBookAndFlushPushesSooner() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pushes = AtomicInteger()
        val scheduler = HttpSyncStatisticsPushScheduler(
            scope = scope,
            currentSettings = { configured },
            push = { _, _, _, _ -> pushes.incrementAndGet() },
            delayMs = 200,
            flushDelayMs = 20,
        )
        val a = File("/books/a")
        val b = File("/books/b")

        repeat(5) { scheduler.onStatisticsChanged(a, "A", "a") }
        scheduler.onStatisticsChanged(b, "B", "b")
        assertTrue(scheduler.isPending(a))
        awaitPushes(pushes, 2)
        assertEquals(2, pushes.get())

        scheduler.onStatisticsChanged(a, "A", "a")
        scheduler.flushNow(a, "A", "a")
        awaitPushes(pushes, 3, timeoutMs = 1_000)
        assertEquals(3, pushes.get())
        scope.cancel()
    }

    @Test
    fun unconfiguredSyncNeverPushesAndFailuresBackOff() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val attempts = AtomicInteger()
        var now = 0L
        val scheduler = HttpSyncStatisticsPushScheduler(
            scope = scope,
            currentSettings = { configured },
            push = { _, _, _, _ -> attempts.incrementAndGet(); throw IllegalStateException("offline") },
            delayMs = 10,
            flushDelayMs = 10,
            clock = { now },
        )
        val root = File("/books/a")
        repeat(HttpSyncStatisticsPushScheduler.FAILURE_THRESHOLD) {
            scheduler.flushNow(root, "A", "a")
            awaitPushes(attempts, it + 1)
        }
        assertEquals(HttpSyncStatisticsPushScheduler.FAILURE_THRESHOLD, attempts.get())

        // Backed off: no attempt until the clock moves past the pause.
        scheduler.flushNow(root, "A", "a")
        delay(100)
        assertEquals(HttpSyncStatisticsPushScheduler.FAILURE_THRESHOLD, attempts.get())
        now += HttpSyncStatisticsPushScheduler.BACKOFF_MS + 1
        scheduler.flushNow(root, "A", "a")
        awaitPushes(attempts, HttpSyncStatisticsPushScheduler.FAILURE_THRESHOLD + 1)
        assertEquals(HttpSyncStatisticsPushScheduler.FAILURE_THRESHOLD + 1, attempts.get())

        val silent = HttpSyncStatisticsPushScheduler(
            scope = scope,
            currentSettings = { HttpSyncSettings() },
            push = { _, _, _, _ -> attempts.incrementAndGet() },
            delayMs = 10,
        )
        silent.onStatisticsChanged(root, "A", "a")
        delay(100)
        assertEquals(HttpSyncStatisticsPushScheduler.FAILURE_THRESHOLD + 1, attempts.get())
        scope.cancel()
    }
}
