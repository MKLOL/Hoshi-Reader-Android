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
        syncBooksNow = { _, _, _ -> error("no full sync expected") },
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

    private val configured = HttpSyncSettings(baseUrl = "https://example.invalid", bearerToken = "token")

    private fun backgroundScheduler(state: HttpSyncBatchState, server: ContentAddressedKv) = HttpSyncBookmarkScheduler(
        state = state,
        currentSettings = { configured },
        syncBooksNow = { _, _, _ ->
            HttpSyncResult(
                uploadedBookmarks = 0, uploadedChatEntries = 0, uploadedMetadata = 0,
                downloadedBookmarks = 0, downloadedChatEntries = 0, remoteOnlyBooks = 0, errors = emptyList(),
            )
        },
        fullCycleRunner = HttpSyncFullCycleRunner(scope),
        scope = scope,
        transportFactory = { server },
        initiallyForeground = false,
    )

    @Test
    fun aProcessStartedInTheBackgroundDoesNotPollUntilAScreenIsShown() = runBlocking<Unit> {
        val server = ContentAddressedKv()
        val scheduler = backgroundScheduler(HttpSyncBatchState(BookRepository(temporaryFolder.newFolder())), server)

        scheduler.start()
        kotlinx.coroutines.delay(300)
        assertEquals("a background job's process must not start syncing books", 0, server.lists)

        scheduler.setForeground(true)
        withTimeout(5_000) { while (server.lists == 0) kotlinx.coroutines.delay(20) }
    }

    @Test
    fun aPageTurnSavedAfterTheAppWasLeftIsPublishedRightAway() = runBlocking<Unit> {
        val repository = BookRepository(temporaryFolder.newFolder())
        val root = repository.createBookDirectory("book")
        repository.saveMetadata(root, moe.antimony.hoshi.epub.BookMetadata(
            id = "book", title = "Book", cover = null, folder = "book", lastAccess = 0.0, syncId = "book",
        ))
        val installation = "11111111-1111-1111-1111-111111111111"
        val state = HttpSyncBatchState(repository, installationId = installation)
        val server = ContentAddressedKv()
        state.publishMaps(server)
        val scheduler = backgroundScheduler(state, server)

        // The app is in the background; the manga reader's debounced save lands now.
        repository.saveBookmark(root, moe.antimony.hoshi.epub.Bookmark(3, 0.3, 30, 800_000_000.0))
        scheduler.onBookmarkChanged(root, "Book", "book")

        val shard = "sync/maps/bookmarks/$installation.json"
        withTimeout(5_000) {
            while (!server.body(shard).toString(Charsets.UTF_8).contains("\"chapterIndex\":3")) kotlinx.coroutines.delay(20)
        }
    }
}
