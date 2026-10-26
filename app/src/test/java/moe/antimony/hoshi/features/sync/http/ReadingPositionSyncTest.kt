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
        // The rewind is newer for released map clients too, which order by this timestamp.
        assertTrue(tablet.loadBookmark(t)!!.lastModified!! > 200.0)
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

    @Test fun pageTurnsAfterBootstrapNeverWriteTheSharedPerBookKey() = runBlocking {
        val server = ContentAddressedKv()
        val (repository, root) = fixture("phone")
        val state = HttpSyncBatchState(repository)
        state.publishMaps(server)
        val old = HttpSyncBookmarkBlob(90, 0.0, 91, appleSecondsToRfc3339(50.0))
        server.put(bookmarkKey("example"), "application/json", json.encodeToString(old).toByteArray())
        move(repository, root, 110, 300.0)
        state.queueBookmark(root, "Example", "example")
        state.syncMaps(server)
        // Builds up to 0.12.1 run a full reconcile for every change of this key.
        assertEquals(old, json.decodeFromString<HttpSyncBookmarkBlob>(server.body(bookmarkKey("example")).toString(Charsets.UTF_8)))
        assertTrue(server.keys().any { it == "${READING_POSITION_PREFIX}example/phone.json" })
    }

    @Test fun aSetAsideJournalGetsThisDevicesPublishedHistoryBackEvenAfterAPageTurn() = runBlocking {
        val server = ContentAddressedKv()
        val (repository, root) = fixture("phone")
        val key = "${READING_POSITION_PREFIX}example/phone.json"
        move(repository, root, 101, 150.0)
        exchange(repository, server)
        val published = json.decodeFromString<ReadingPositionJournal>(server.body(key).toString(Charsets.UTF_8))
        repository.readingPositions.directory.listFiles()!!.filter { it.name.startsWith("book-") }.forEach { it.writeText("{") }
        move(repository, root, 102, 160.0)
        exchange(repository, server)
        val restored = json.decodeFromString<ReadingPositionJournal>(server.body(key).toString(Charsets.UTF_8))
        assertTrue(published.heads.all { old -> restored.heads.any { it.id == old.id } })
        assertEquals(102, repository.loadBookmark(root)!!.chapterIndex)
    }

    @Test fun aMoveOutsideTheRetainedWindowIsStillPublished() = runBlocking {
        val server = ContentAddressedKv()
        val (repository, root) = fixture("phone")
        val first = repository.openReadingPosition(root)
        val second = repository.openReadingPosition(root)
        for (page in 1..60) repository.recordReadingPosition(root, first.navigate(Bookmark(page, 0.0, page + 1, 100.0 + page))!!)
        exchange(repository, server)
        // An older-stamped session's move is the newest head but not among the latest 50 events.
        repository.recordReadingPosition(root, second.navigate(Bookmark(200, 0.0, 201, 101.5))!!)
        exchange(repository, server)
        val journal = json.decodeFromString<ReadingPositionJournal>(server.body("${READING_POSITION_PREFIX}example/phone.json").toString(Charsets.UTF_8))
        assertEquals(200, journal.heads.single().position.chapterIndex)
    }

    @Test fun aLostIndexReadsHistoriesBackWithoutUploadingThemAgain() = runBlocking {
        val server = ContentAddressedKv()
        val (repository, root) = fixture("phone")
        move(repository, root, 101, 150.0)
        exchange(repository, server)
        repository.readingPositions.directory.resolve("sync-index.json").delete()
        server.resetCounts()
        exchange(repository, server)
        assertEquals(emptyList<String>(), server.putKeys)
        server.resetCounts()
        exchange(repository, server)
        assertEquals("the rebuilt index acknowledges the history", 0, server.gets)
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
        remote(90, 20, "zzzz")
        state.syncMaps(server)
        assertEquals(90, repository.loadBookmark(root)!!.chapterIndex)
    }

    @Test fun backwardClockRewindStrictlyAdvancesLegacyWireTimestamp() = runBlocking {
        val server = ContentAddressedKv()
        val (repository, root) = fixture("phone")
        val time = 814000000.06
        move(repository, root, 120, time)
        exchange(repository, server)
        // The projection released clients receive through the bookmark maps.
        val previous = repository.loadBookmark(root)!!.toBlob()
        move(repository, root, 119, time - 20.0)
        exchange(repository, server)
        val next = repository.loadBookmark(root)!!.toBlob()
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

    @Test fun anotherDevicesHistoryWaitsUntilTheBookIsOnThisDevice() = runBlocking {
        val server = ContentAddressedKv()
        val (tablet, t) = fixture("tablet")
        move(tablet, t, 120, 200.0)
        exchange(tablet, server)
        val phone = BookRepository(temporary.newFolder("phone"), deviceIdentity = DeviceIdentity("phone", "phone"))
        server.resetCounts()
        exchange(phone, server)
        assertEquals("a book that is not on this device is not fetched", 0, server.gets)
        val root = phone.createBookDirectoryForImportedTitle("Example")
        phone.saveMetadata(root, BookMetadata("phone", "Example", null, root.name, 0.0, "example"))
        exchange(phone, server)
        assertEquals(120, phone.loadBookmark(root)!!.chapterIndex)
    }

    @Test fun aPassReadsABoundedNumberOfHistoriesWithoutHoldingBackItsOwn() = runBlocking {
        val server = ContentAddressedKv()
        val tablet = BookRepository(temporary.newFolder("tablet"), deviceIdentity = DeviceIdentity("tablet", "tablet"))
        val phone = BookRepository(temporary.newFolder("phone"), deviceIdentity = DeviceIdentity("phone", "phone"))
        val phoneRoots = (1..3).map { number ->
            val id = "book$number"
            val tabletRoot = tablet.createBookDirectoryForImportedTitle("Book $number")
            tablet.saveMetadata(tabletRoot, BookMetadata(id, "Book $number", null, tabletRoot.name, 0.0, id))
            move(tablet, tabletRoot, 120, 200.0)
            phone.createBookDirectoryForImportedTitle("Book $number").also {
                phone.saveMetadata(it, BookMetadata(id, "Book $number", null, it.name, 0.0, id))
            }
        }
        exchange(tablet, server)
        move(phone, phoneRoots.first(), 101, 150.0)
        val sync = ReadingPositionSync(phone, maxReadsPerPass = 2)
        server.resetCounts()
        sync.exchange(server, server.listing(), phone.loadBookEntries())
        assertEquals(2, server.gets)
        assertEquals(listOf("${READING_POSITION_PREFIX}book1/phone.json"), server.putKeys)
        server.resetCounts()
        sync.exchange(server, server.listing(), phone.loadBookEntries())
        assertEquals("the rest follow on the next pass", 1, server.gets)
        assertEquals(listOf(120, 120, 120), phoneRoots.map { phone.loadBookmark(it)!!.chapterIndex })
    }

    @Test fun anUnreadableOwnHistoryIsReplacedInsteadOfBlockingTheBook() =
        ownHistoryThisBuildCannotUseIsReplaced("{\"truncated")

    @Test fun aNewerBuildsOwnHistoryIsSkippedUntilItChangesAndThenReplaced() =
        ownHistoryThisBuildCannotUseIsReplaced("""{"version":2,"bookId":"example","deviceId":"phone"}""")

    private fun ownHistoryThisBuildCannotUseIsReplaced(body: String) = runBlocking {
        val server = ContentAddressedKv()
        val (repository, root) = fixture("phone")
        val key = "${READING_POSITION_PREFIX}example/phone.json"
        server.put(key, "application/json", body.toByteArray())
        server.resetCounts()
        assertTrue(exchange(repository, server).errors.isEmpty())
        assertEquals(1, server.gets)
        server.resetCounts()
        exchange(repository, server)
        assertEquals("an unchanged key is not fetched on every poll", 0, server.gets)
        move(repository, root, 101, 150.0)
        assertTrue(exchange(repository, server).errors.isEmpty())
        val replaced = json.decodeFromString<ReadingPositionJournal>(server.body(key).toString(Charsets.UTF_8))
        assertEquals(101, replaced.heads.single().position.chapterIndex)
        assertTrue(repository.readingPositions.snapshot(root).pending.isEmpty())
    }

    @Test fun anInterruptedPassKeepsWhatItHadAlreadyAcknowledged() = runBlocking {
        val server = ContentAddressedKv()
        val (phone, p) = fixture("phone")
        val (tablet, t) = fixture("tablet")
        move(tablet, t, 120, 200.0)
        exchange(tablet, server)
        move(phone, p, 101, 150.0)
        val interrupting = object : HttpSyncKvTransport by server {
            override suspend fun getBounded(key: String, maxBytes: Int): HttpSyncKvFetched? =
                if (key.endsWith("/tablet.json")) throw kotlinx.coroutines.CancellationException("The app was left")
                else server.getBounded(key, maxBytes)
        }
        try {
            ReadingPositionSync(phone).exchange(interrupting, server.listing(), phone.loadBookEntries())
            fail("the pass was interrupted")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        server.resetCounts()
        exchange(phone, server)
        assertEquals("only the interrupted history is read", 1, server.gets)
        assertEquals(emptyList<String>(), server.putKeys)
        assertEquals(120, phone.loadBookmark(p)!!.chapterIndex)
    }

    @Test fun anUnwritablePositionStoreNeitherEndsThePollNorTheBookmarkMaps() = runBlocking {
        val server = ContentAddressedKv()
        val files = temporary.newFolder("phone")
        val repository = BookRepository(files, deviceIdentity = DeviceIdentity("phone", "phone"),
            positionDirectory = temporary.newFile("not-a-directory"))
        val root = repository.createBookDirectoryForImportedTitle("Example")
        repository.saveMetadata(root, BookMetadata("phone", "Example", null, root.name, 0.0, "example"))
        root.resolve("bookmark.json").writeText(json.encodeToString(Bookmark(100, 0.0, 101, 100.0)))
        val state = HttpSyncBatchState(repository)
        state.publishMaps(server)
        assertTrue(state.syncMaps(server).positionErrors.isNotEmpty())
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val scheduler = HttpSyncBookmarkScheduler(
                state, currentSettings = { HttpSyncSettings("https://example.invalid", "token") },
                syncBooksNow = { _, _, _ -> error("no full reconcile expected") },
                fullCycleRunner = HttpSyncFullCycleRunner(scope), scope = scope,
                transportFactory = { server },
            )
            // Reading what is queued fails too; the poll must survive it.
            assertTrue(scheduler.refreshBeforeOpen(10_000))
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
