package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReadingPositionSyncTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private suspend fun fixture(device: String): Pair<BookRepository, File> {
        val repository = BookRepository(temporary.newFolder(device), deviceIdentity = DeviceIdentity(device, device))
        val root = repository.createBookDirectoryForImportedTitle("Example")
        repository.saveMetadata(root, BookMetadata(device, "Example", null, root.name, 0.0, "example"))
        repository.saveBookmark(root, Bookmark(100, 0.0, 101, 100.0))
        return repository to root
    }

    private suspend fun move(repository: BookRepository, root: File, page: Int, time: Double) {
        repository.recordReadingPosition(root, repository.openReadingPosition(root).navigate(Bookmark(page, 0.0, page + 1, time))!!)
    }

    private suspend fun exchange(repository: BookRepository, server: ContentAddressedKv) =
        ReadingPositionSync(repository).exchange(server, server.listing(), repository.loadBookEntries())

    @Test fun twoDevicesResolveReopenAndIntentionalBackwardNavigation() = runBlocking {
        val server = ContentAddressedKv()
        val (phone, p) = fixture("phone")
        val (tablet, t) = fixture("tablet")
        move(tablet, t, 120, 200.0)
        exchange(tablet, server)
        assertTrue(tablet.readingPositions.snapshot(t).pending.isEmpty())
        exchange(phone, server)
        assertEquals(120, phone.loadBookmark(p)!!.chapterIndex)
        assertNull(phone.openReadingPosition(p).navigate(Bookmark(120, 0.0, 121, 500.0)))
        move(phone, p, 100, 190.0)
        exchange(phone, server)
        exchange(tablet, server)
        assertEquals(100, tablet.loadBookmark(t)!!.chapterIndex)
        val oldClient = json.decodeFromString<HttpSyncBookmarkBlob>(server.body(bookmarkKey("example")).toString(Charsets.UTF_8))
        assertEquals(100, oldClient.chapterIndex)
        assertTrue(rfc3339ToAppleSeconds(oldClient.lastModified) > 200.0)
    }

    @Test fun releasedMapOnlyClientStillSendsAndReceivesPositions() = runBlocking {
        val server = ContentAddressedKv()
        val (repository, root) = fixture("phone")
        val state = HttpSyncBatchState(repository)
        state.publishMaps(server)
        val old = HttpSyncBookmarkBlob(120, 0.0, 121, appleSecondsToRfc3339(200.0), 3)
        val entry = HttpSyncBookmarkMapEntry("old", old.lastModified, old)
        server.put("${BOOKMARKS_MAP_PREFIX}tablet.json", "application/json", json.encodeToString(mapOf("example" to entry)).toByteArray())
        state.syncMaps(server)
        assertEquals(120, repository.loadBookmark(root)!!.chapterIndex)
        move(repository, root, 100, 210.0)
        state.queueBookmark(root, "Example", "example")
        state.syncMaps(server)
        val maps = server.keys().filter { it.startsWith(BOOKMARKS_MAP_PREFIX) }.flatMap { key ->
            json.decodeFromString<Map<String, HttpSyncBookmarkMapEntry>>(server.body(key).toString(Charsets.UTF_8)).values
        }
        assertTrue(maps.any { it.value?.chapterIndex == 100 && it.lastModified == appleSecondsToRfc3339(210.0) })
    }

    @Test fun legacyPerBookClientsRemainBidirectionalAfterBootstrap() = runBlocking {
        val server = ContentAddressedKv()
        val (repository, root) = fixture("phone")
        val state = HttpSyncBatchState(repository)
        state.publishMaps(server)
        val old = HttpSyncBookmarkBlob(120, 0.0, 121, appleSecondsToRfc3339(200.0))
        server.put(bookmarkKey("example"), "application/json", json.encodeToString(old).toByteArray())
        state.syncMaps(server)
        assertEquals(120, repository.loadBookmark(root)!!.chapterIndex)
        move(repository, root, 110, 300.0)
        state.queueBookmark(root, "Example", "example")
        state.syncMaps(server)
        assertEquals(110, json.decodeFromString<HttpSyncBookmarkBlob>(server.body(bookmarkKey("example")).toString(Charsets.UTF_8)).chapterIndex)
    }

    @Test fun equalTimestampMapWinnersRespectReleasedRevisionAndEtagOrdering() = runBlocking {
        val server = ContentAddressedKv()
        val (repository, root) = fixture("phone")
        repository.saveBookmark(root, Bookmark(110, 0.0, 111, 200.0009))
        val state = HttpSyncBatchState(repository)
        state.publishMaps(server)
        suspend fun remote(page: Int, rev: Int, etag: String) {
            val value = HttpSyncBookmarkBlob(page, 0.0, page + 1, appleSecondsToRfc3339(200.0), rev)
            server.put("${BOOKMARKS_MAP_PREFIX}tablet.json", "application/json",
                json.encodeToString(mapOf("example" to HttpSyncBookmarkMapEntry(etag, value.lastModified, value))).toByteArray())
            state.syncMaps(server)
            assertEquals(page, repository.loadBookmark(root)!!.chapterIndex)
        }
        remote(120, 20, "old")
        val old = HttpSyncBookmarkBlob(110, 0.0, 111, appleSecondsToRfc3339(200.0), 1)
        server.put(bookmarkKey("example"), "application/json", json.encodeToString(old).toByteArray())
        state.syncMaps(server)
        assertEquals(120, json.decodeFromString<HttpSyncBookmarkBlob>(server.body(bookmarkKey("example")).toString(Charsets.UTF_8)).chapterIndex)
        remote(90, 20, "zzzz")
        state.syncMaps(server)
        assertEquals(90, json.decodeFromString<HttpSyncBookmarkBlob>(server.body(bookmarkKey("example")).toString(Charsets.UTF_8)).chapterIndex)
    }

    @Test fun backwardClockRewindStrictlyAdvancesLegacyWireTimestamp() = runBlocking {
        val server = ContentAddressedKv()
        val (repository, root) = fixture("phone")
        val time = 814000000.06
        move(repository, root, 120, time)
        exchange(repository, server)
        val previous = json.decodeFromString<HttpSyncBookmarkBlob>(server.body(bookmarkKey("example")).toString(Charsets.UTF_8))
        move(repository, root, 119, time - 20.0)
        exchange(repository, server)
        val next = json.decodeFromString<HttpSyncBookmarkBlob>(server.body(bookmarkKey("example")).toString(Charsets.UTF_8))
        assertEquals(119, next.chapterIndex)
        assertTrue(compareRfc3339(next.lastModified, previous.lastModified) > 0)
    }

    @Test fun failedOtherWriterDoesNotBlockPublishingOwnHistoryOrAcknowledgeUnreadBody() = runBlocking {
        val server = ContentAddressedKv()
        val (repository, root) = fixture("phone")
        move(repository, root, 101, 150.0)
        val foreign = "${READING_POSITION_PREFIX}example/tablet.json"
        server.put(foreign, "application/json", "not json".toByteArray())
        val sync = ReadingPositionSync(repository)
        val result = sync.exchange(server, server.listing(), repository.loadBookEntries())
        assertTrue(result.errors.isNotEmpty())
        assertTrue(server.keys().any { it == "${READING_POSITION_PREFIX}example/phone.json" })
        assertTrue(repository.readingPositions.snapshot(root).pending.isEmpty())
        // A changed remote ETag immediately gets another attempt even during failure backoff.
        val (tablet, t) = fixture("tablet")
        move(tablet, t, 120, 200.0)
        server.put(foreign, "application/json", json.encodeToString(tablet.readingPositions.export("example")!!).toByteArray())
        sync.exchange(server, server.listing(), repository.loadBookEntries())
        assertEquals(120, repository.loadBookmark(root)!!.chapterIndex)
    }

    @Test fun foregroundFlushDrainsAPageCreatedDuringAnOlderUpload() = runBlocking {
        val server = ContentAddressedKv()
        val (repository, root) = fixture("phone")
        val state = HttpSyncBatchState(repository)
        state.publishMaps(server)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val transport = object : HttpSyncKvTransport by server {
            override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
                if (key.startsWith(READING_POSITION_PREFIX) && first.getAndSet(false)) {
                    entered.complete(Unit)
                    release.await()
                }
                return server.put(key, contentType, body)
            }
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val scheduler = HttpSyncBookmarkScheduler(
                state, currentSettings = { HttpSyncSettings("https://example.invalid", "token") },
                syncBooksNow = { _, _, _ -> error("A page turn must not start a full reconcile") },
                fullCycleRunner = HttpSyncFullCycleRunner(scope), scope = scope,
                transportFactory = { transport }, initiallyForeground = false,
            )
            move(repository, root, 101, 150.0)
            scheduler.onBookmarkChanged(root, "Example", "example")
            kotlinx.coroutines.withTimeout(5_000) { entered.await() }
            move(repository, root, 102, 160.0)
            scheduler.onBookmarkChanged(root, "Example", "example")
            val flushed = async { scheduler.refreshBeforeOpen(10_000) }
            release.complete(Unit)
            assertTrue(flushed.await())
            assertTrue(repository.readingPositions.snapshot(root).pending.isEmpty())
            val journal = json.decodeFromString<ReadingPositionJournal>(server.body("${READING_POSITION_PREFIX}example/phone.json").toString(Charsets.UTF_8))
            assertEquals(102, journal.heads.single().position.chapterIndex)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test fun pageArrivingDuringUploadRemainsPendingAndSurvivesRestart() = runBlocking {
        val server = ContentAddressedKv()
        val (repository, root) = fixture("phone")
        move(repository, root, 101, 150.0)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val transport = object : HttpSyncKvTransport by server {
            override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
                if (key.startsWith(READING_POSITION_PREFIX)) { entered.complete(Unit); release.await() }
                return server.put(key, contentType, body)
            }
        }
        val inFlight = async { ReadingPositionSync(repository).exchange(transport, server.listing(), repository.loadBookEntries()) }
        entered.await()
        move(repository, root, 102, 160.0)
        release.complete(Unit)
        inFlight.await()
        assertEquals(1, repository.readingPositions.snapshot(root).pending.size)
        exchange(repository, server)
        assertTrue(repository.readingPositions.snapshot(root).pending.isEmpty())
        val journal = json.decodeFromString<ReadingPositionJournal>(server.body("${READING_POSITION_PREFIX}example/phone.json").toString(Charsets.UTF_8))
        assertTrue(journal.events.any { it.position.chapterIndex == 101 })
        assertTrue(journal.events.any { it.position.chapterIndex == 102 })
    }
}
