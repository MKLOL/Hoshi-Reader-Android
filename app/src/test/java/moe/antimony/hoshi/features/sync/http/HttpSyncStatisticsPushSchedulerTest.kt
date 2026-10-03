package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class HttpSyncStatisticsPushSchedulerTest {
    private val configured = HttpSyncSettings(baseUrl = "http://127.0.0.1:1", bearerToken = "token")
    private val root = File("/books/a")

    @Test
    fun rapidChangesCollapseIntoOnePushPerBookAndFlushPushesSooner() = runTest {
        val pushed = mutableListOf<String?>()
        val scheduler = HttpSyncStatisticsPushScheduler(
            scope = backgroundScope,
            currentSettings = { configured },
            push = { _, _, _, id -> pushed += id },
            delayMs = 200,
            flushDelayMs = 20,
        )
        val second = File("/books/b")
        scheduler.onStatisticsChanged(root, "A", "a")
        runCurrent()
        advanceTimeBy(100)
        repeat(5) { scheduler.onStatisticsChanged(root, "A", "a") }
        scheduler.onStatisticsChanged(second, "B", "b")
        assertTrue(scheduler.isPending(root))
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("a", "b"), pushed)
        assertFalse(scheduler.isPending(root))
        assertFalse(scheduler.isPending(second))

        scheduler.onStatisticsChanged(root, "A", "a")
        scheduler.flushNow(root, "A", "a")
        advanceTimeBy(20)
        runCurrent()
        assertEquals(listOf("a", "b", "a"), pushed)
        assertFalse(scheduler.isPending(root))
    }

    @Test
    fun failuresBackOffAndASuccessResetsTheConsecutiveFailures() = runTest {
        var attempts = 0
        var offline = true
        val scheduler = HttpSyncStatisticsPushScheduler(
            scope = backgroundScope,
            currentSettings = { configured },
            push = { _, _, _, _ -> attempts++; if (offline) throw IOException("offline") },
            flushDelayMs = 0,
            clock = { testScheduler.currentTime },
        )
        repeat(HttpSyncStatisticsPushScheduler.FAILURE_THRESHOLD) {
            scheduler.flushNow(root, "A", "a")
            runCurrent()
        }
        repeat(5) { scheduler.flushNow(root, "A", "a"); runCurrent() }
        assertEquals(HttpSyncStatisticsPushScheduler.FAILURE_THRESHOLD, attempts)
        assertFalse(scheduler.isPending(root))

        advanceTimeBy(HttpSyncStatisticsPushScheduler.BACKOFF_MS)
        offline = false
        scheduler.flushNow(root, "A", "a")
        runCurrent()
        offline = true
        repeat(HttpSyncStatisticsPushScheduler.FAILURE_THRESHOLD) {
            scheduler.flushNow(root, "A", "a")
            runCurrent()
        }
        assertEquals(HttpSyncStatisticsPushScheduler.FAILURE_THRESHOLD * 2 + 1, attempts)
        scheduler.flushNow(root, "A", "a")
        runCurrent()
        assertEquals("a new run of failures opens the breaker again", 7, attempts)
    }

    @Test
    fun absentOrUnconfiguredSettingsDoNotPushAndReleaseTheirPendingJobs() = runTest {
        for (settings in listOf(null, HttpSyncSettings())) {
            val scheduler = HttpSyncStatisticsPushScheduler(
                scope = backgroundScope,
                currentSettings = { settings },
                push = { _, _, _, _ -> error("unconfigured push") },
                flushDelayMs = 0,
            )
            scheduler.flushNow(root, "A", "a")
            runCurrent()
            assertFalse(scheduler.isPending(root))
        }
    }

    @Test
    fun inlineCompletionDoesNotLeaveACompletedJobPending() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var pushes = 0
            val scheduler = HttpSyncStatisticsPushScheduler(
                scope = scope,
                currentSettings = { configured },
                push = { _, _, _, _ -> pushes++ },
                flushDelayMs = 0,
            )
            repeat(2) {
                scheduler.flushNow(root, "A", "a")
                assertFalse(scheduler.isPending(root))
            }
            assertEquals(2, pushes)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellationBeforeStartOrDuringDebounceRemovesPendingWork() = runTest {
        for (startDebounce in listOf(false, true)) {
            val owner = SupervisorJob(backgroundScope.coroutineContext[kotlinx.coroutines.Job])
            val scope = CoroutineScope(backgroundScope.coroutineContext + owner)
            val scheduler = HttpSyncStatisticsPushScheduler(
                scope = scope,
                currentSettings = { configured },
                push = { _, _, _, _ -> error("cancelled work must not push") },
                delayMs = 30_000,
            )
            scheduler.onStatisticsChanged(root, "A", "a")
            if (startDebounce) runCurrent()
            assertTrue(scheduler.isPending(root))
            owner.cancel()
            runCurrent()
            assertFalse(scheduler.isPending(root))
            // Scheduling on an already cancelled owner also cannot retain a dead job.
            scheduler.onStatisticsChanged(root, "A", "a")
            runCurrent()
            assertFalse(scheduler.isPending(root))
        }
    }

    @Test
    fun aChangeDuringARunningPushQueuesAFollowUpWithoutCancellingIt() = runTest {
        var started = 0
        var completed = 0
        val scheduler = HttpSyncStatisticsPushScheduler(
            scope = backgroundScope,
            currentSettings = { configured },
            push = { _, _, _, _ -> started++; delay(400); completed++ },
            flushDelayMs = 0,
            clock = { testScheduler.currentTime },
        )
        scheduler.flushNow(root, "A", "a")
        runCurrent()
        // The running push may have read the files before this change: it finishes, and a
        // follow-up carries the change instead of the change waiting for the next poll.
        scheduler.flushNow(root, "A", "a")
        runCurrent()
        assertEquals(2, started)
        assertTrue(scheduler.isPending(root))
        advanceTimeBy(400)
        runCurrent()
        assertEquals("a running push is never cancelled", 2, completed)
        assertFalse(scheduler.isPending(root))
        scheduler.flushNow(root, "A", "a")
        runCurrent()
        assertEquals(3, started)
    }

    @Test
    fun aPushWaitingToRunCoversLaterChangesInsteadOfMovingBack() = runTest {
        var pushes = 0
        val scheduler = HttpSyncStatisticsPushScheduler(
            scope = backgroundScope,
            currentSettings = { configured },
            push = { _, _, _, _ -> pushes++ },
            delayMs = 30_000,
            clock = { testScheduler.currentTime },
        )
        // A page turn every ten seconds: the push still goes out 30 s after the first one.
        repeat(5) {
            scheduler.onStatisticsChanged(root, "A", "a")
            advanceTimeBy(10_000)
            runCurrent()
        }
        assertEquals("continuous reading must not postpone the push forever", 1, pushes)
    }

    @Test
    fun aSettingsReadThatThrowsCanBeRetriedWithoutEscapingTheScope() = runTest {
        var failing = true
        var pushed = false
        val scheduler = HttpSyncStatisticsPushScheduler(
            scope = backgroundScope,
            currentSettings = { if (failing) throw IOException("datastore unreadable") else configured },
            push = { _, _, _, _ -> pushed = true },
            flushDelayMs = 0,
        )
        scheduler.flushNow(root, "A", "a")
        runCurrent()
        assertFalse(pushed)
        assertFalse(scheduler.isPending(root))
        failing = false
        scheduler.flushNow(root, "A", "a")
        runCurrent()
        assertTrue(pushed)
    }

    @Test
    fun leavingTheReaderFlushesWhileReadingPushesFollowTheirCadence() = runTest {
        val calls = mutableListOf<String>()
        val scheduler = HttpSyncStatisticsPushScheduler(
            scope = backgroundScope,
            currentSettings = { configured },
            push = { _, _, _, _ -> calls += "batched" },
            flushDelayMs = 0,
            delayMs = 1_000,
            clock = { testScheduler.currentTime },
            flushPush = { _, _, _, _ -> calls += "flush" },
        )
        scheduler.onStatisticsChanged(root, "A", "a")
        advanceTimeBy(1_001)
        runCurrent()
        scheduler.flushNow(root, "A", "a")
        runCurrent()
        assertEquals(listOf("batched", "flush"), calls)
    }
}
