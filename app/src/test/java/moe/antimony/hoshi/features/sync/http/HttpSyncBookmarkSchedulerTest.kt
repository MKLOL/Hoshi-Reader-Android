package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
    private fun emptyResult() = HttpSyncResult(
        uploadedBookmarks = 0, uploadedChatEntries = 0, uploadedMetadata = 0,
        downloadedBookmarks = 0, downloadedChatEntries = 0, remoteOnlyBooks = 0, errors = emptyList(),
    )

    private suspend fun BookRepository.installedBook(): java.io.File {
        val root = createBookDirectory("book")
        saveMetadata(root, moe.antimony.hoshi.epub.BookMetadata(
            id = "book", title = "Book", cover = null, folder = "book", lastAccess = 0.0, syncId = "book",
        ))
        return root
    }

    @Test
    fun nothingIsQueuedOrSentWhileSyncIsOffAndTheFirstPassAfterSetupSendsIt() = runBlocking<Unit> {
        val repository = BookRepository(temporaryFolder.newFolder())
        val root = repository.installedBook()
        val installation = "22222222-2222-2222-2222-222222222222"
        val state = HttpSyncBatchState(repository, installationId = installation)
        val server = ContentAddressedKv()
        state.publishMaps(server)
        server.resetCounts()
        val listsBefore = server.lists
        val settings = java.util.concurrent.atomic.AtomicReference(HttpSyncSettings(baseUrl = "https://example.invalid", bearerToken = ""))
        val transports = java.util.concurrent.atomic.AtomicInteger()
        val scheduler = HttpSyncBookmarkScheduler(
            state = state,
            currentSettings = { settings.get() },
            syncBooksNow = { _, _, _ -> emptyResult() },
            fullCycleRunner = HttpSyncFullCycleRunner(scope),
            scope = scope,
            transportFactory = { transports.incrementAndGet(); server },
        )

        // Reading with sync off: the position is kept on this device only.
        scheduler.start()
        val session = repository.openReadingPosition(root)
        repository.recordReadingPosition(root, session.navigate(moe.antimony.hoshi.epub.Bookmark(3, 0.3, 30, 800_000_000.0))!!)
        scheduler.onBookmarkChanged(root, "Book", "book")
        assertTrue(scheduler.refreshBeforeOpen(timeoutMillis = 5_000))
        kotlinx.coroutines.delay(200)
        assertFalse("nothing is queued while sync is off", state.hasPending())
        assertEquals("no request is made or prepared", 0, transports.get())
        assertEquals(listsBefore, server.lists)

        // Setting sync up again: the next pass sends the map position and the history.
        settings.set(settings.get().copy(bearerToken = "token"))
        val shard = "sync/maps/bookmarks/$installation.json"
        withTimeout(10_000) {
            // A poll that read the old setting may still be finishing; the next pass is set up.
            while (shard !in server.keys() || !server.body(shard).toString(Charsets.UTF_8).contains("\"chapterIndex\":3") ||
                repository.readingPositions.snapshot(root).pending.isNotEmpty()) {
                scheduler.refreshNow()
                kotlinx.coroutines.delay(50)
            }
        }
        assertTrue(server.keys().any { it.startsWith(READING_POSITION_PREFIX) })
    }

    @Test
    fun bookTransfersStartWithoutWaitingForPositionHistories() = runBlocking<Unit> {
        val tablet = BookRepository(temporaryFolder.newFolder(), deviceIdentity = moe.antimony.hoshi.epub.DeviceIdentity("tablet", "Tablet"))
        val tabletRoot = tablet.installedBook()
        val server = ContentAddressedKv()
        tablet.recordReadingPosition(tabletRoot, tablet.openReadingPosition(tabletRoot)
            .navigate(moe.antimony.hoshi.epub.Bookmark(3, 0.3, 30, 800_000_000.0))!!)
        ReadingPositionSync(tablet).exchange(server, server.listing(), tablet.loadBookEntries())

        val phone = BookRepository(temporaryFolder.newFolder())
        phone.installedBook()
        val release = CompletableDeferred<Unit>()
        val stalling = object : HttpSyncKvTransport by server {
            override suspend fun getBounded(key: String, maxBytes: Int): HttpSyncKvFetched? {
                if (key.startsWith(READING_POSITION_PREFIX)) release.await()
                return server.getBounded(key, maxBytes)
            }
        }
        val transfers = CompletableDeferred<Unit>()
        val scheduler = HttpSyncBookmarkScheduler(
            state = HttpSyncBatchState(phone),
            currentSettings = { configured },
            syncBooksNow = { _, _, _ -> transfers.complete(Unit); emptyResult() },
            fullCycleRunner = HttpSyncFullCycleRunner(scope),
            scope = scope,
            transportFactory = { stalling },
        )

        // A new install: the full reconcile starts while the tablet's history is still being read.
        scheduler.refreshNow()
        withTimeout(5_000) { transfers.await() }
        release.complete(Unit)
        assertTrue(scheduler.refreshBeforeOpen(timeoutMillis = 10_000))
    }
    @Test
    fun aReaderOpenWaitsForTheMapsButNotForPositionHistories() = runBlocking<Unit> {
        val server = ContentAddressedKv()
        val phone = BookRepository(temporaryFolder.newFolder())
        val root = phone.installedBook()
        val state = HttpSyncBatchState(phone)
        state.publishMaps(server)
        // An older build's position in the maps, and a newer one in the tablet's history.
        val mapped = HttpSyncBookmarkBlob(5, 0.0, 6, appleSecondsToRfc3339(800_000_000.0), 1)
        server.put("${BOOKMARKS_MAP_PREFIX}older.json", "application/json", kotlinx.serialization.json.Json.encodeToString(
            MapSerializer(String.serializer(), HttpSyncBookmarkMapEntry.serializer()),
            mapOf("book" to HttpSyncBookmarkMapEntry("older", mapped.lastModified, mapped)),
        ).toByteArray())
        val tablet = BookRepository(temporaryFolder.newFolder(), deviceIdentity = moe.antimony.hoshi.epub.DeviceIdentity("tablet", "Tablet"))
        val tabletRoot = tablet.installedBook()
        tablet.recordReadingPosition(tabletRoot, tablet.openReadingPosition(tabletRoot)
            .navigate(moe.antimony.hoshi.epub.Bookmark(7, 0.0, 8, 800_000_100.0))!!)
        ReadingPositionSync(tablet).exchange(server, server.listing(), tablet.loadBookEntries())
        val release = CompletableDeferred<Unit>()
        val stalling = object : HttpSyncKvTransport by server {
            override suspend fun getBounded(key: String, maxBytes: Int): HttpSyncKvFetched? {
                if (key.startsWith(READING_POSITION_PREFIX)) release.await()
                return server.getBounded(key, maxBytes)
            }
        }
        val scheduler = HttpSyncBookmarkScheduler(
            state = state,
            currentSettings = { configured },
            syncBooksNow = { _, _, _ -> emptyResult() },
            fullCycleRunner = HttpSyncFullCycleRunner(scope),
            scope = scope,
            transportFactory = { stalling },
        )

        // The history read never finishes until released, yet the reader opens on the maps.
        assertTrue(scheduler.refreshBeforeOpen(timeoutMillis = 5_000))
        assertEquals(5, phone.loadBookmark(root)!!.chapterIndex)
        // The history lane finishes in the background and reloads the reader with its move.
        val update = scope.async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            state.remoteBookmarkUpdates.first { it == "book" }
        }
        release.complete(Unit)
        withTimeout(5_000) { update.await() }
        assertEquals(7, phone.loadBookmark(root)!!.chapterIndex)
    }
}
