package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.antimony.hoshi.epub.BookRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.system.measureTimeMillis

/**
 * The reader's pre-open map refresh must never hold a book on its loading spinner: a slow or
 * unreachable sync server used to add seconds to every manga open. No real network here — the
 * refresh is stalled inside the `currentSettings` callback, which runs before any request.
 */
class HttpSyncBookmarkSchedulerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newScheduler(currentSettings: suspend () -> HttpSyncSettings?) = HttpSyncBookmarkScheduler(
        state = HttpSyncBatchState(BookRepository(temporaryFolder.newFolder())),
        currentSettings = currentSettings,
        syncBooksNow = { _, _ -> error("no full sync expected") },
        fullCycleRunner = HttpSyncFullCycleRunner(scope),
        scope = scope,
    )

    @Test
    fun refreshBeforeOpenReturnsAtOnceWhenSyncIsNotConfigured() = runBlocking<Unit> {
        val scheduler = newScheduler { HttpSyncSettings(baseUrl = "", bearerToken = "") }

        var completed = false
        val elapsed = measureTimeMillis { completed = scheduler.refreshBeforeOpen(timeoutMillis = 10_000) }

        assertTrue(completed)
        assertTrue("took ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun refreshBeforeOpenStopsWaitingAfterTheTimeout() = runBlocking<Unit> {
        val stall = CompletableDeferred<Unit>()
        val scheduler = newScheduler { stall.await(); null }

        var completed = true
        val elapsed = measureTimeMillis { completed = scheduler.refreshBeforeOpen(timeoutMillis = 200) }

        assertFalse("reader must open on the local bookmark once the timeout passes", completed)
        assertTrue("took ${elapsed}ms", elapsed < 5_000)
        stall.complete(Unit)
    }

    @Test
    fun aTimedOutRefreshKeepsRunningAndFinishesLater() = runBlocking<Unit> {
        val stall = CompletableDeferred<Unit>()
        val settingsReads = CompletableDeferred<Unit>()
        val scheduler = newScheduler {
            stall.await()
            settingsReads.complete(Unit)
            null
        }

        assertFalse(scheduler.refreshBeforeOpen(timeoutMillis = 100))
        assertFalse("the refresh is still parked on the stall", settingsReads.isCompleted)

        // Giving up on the wait must not cancel the refresh itself: it is the background job
        // whose late result reloads the reader through remoteBookmarkUpdates.
        stall.complete(Unit)
        withTimeout(5_000) { settingsReads.await() }
        assertTrue(scheduler.refreshBeforeOpen(timeoutMillis = 10_000))
    }

    @Test
    fun concurrentOpensShareOneRefreshFlight() = runBlocking<Unit> {
        val stall = CompletableDeferred<Unit>()
        var settingsCalls = 0
        val scheduler = newScheduler {
            settingsCalls += 1
            stall.await()
            null
        }

        assertFalse(scheduler.refreshBeforeOpen(timeoutMillis = 100))
        assertFalse(scheduler.refreshBeforeOpen(timeoutMillis = 100))

        assertEquals("both timed-out opens joined the same in-flight refresh", 1, settingsCalls)
        stall.complete(Unit)
        assertTrue(scheduler.refreshBeforeOpen(timeoutMillis = 10_000))
    }

    @Test
    fun defaultTimeoutKeepsTheOpenPathWellUnderTheOldWorstCase() {
        // HttpSyncKvClient alone allows 15 s to connect plus 30 s to read per request; the reader
        // must not inherit that budget.
        assertTrue(HttpSyncBookmarkScheduler.REFRESH_BEFORE_OPEN_TIMEOUT_MS in 500L..2_000L)
    }
}
