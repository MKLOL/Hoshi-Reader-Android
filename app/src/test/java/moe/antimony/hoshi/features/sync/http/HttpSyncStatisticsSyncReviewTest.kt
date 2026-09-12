package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Review tests for the per-device state cache of [HttpSyncStatisticsSync]: every way the cache
 * can be invalidated (or wrongly kept) and the interleaving of the reader push with a reconcile.
 */
class HttpSyncStatisticsSyncReviewTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val syncId = "shirokuma"
    private val key = statisticsKey(syncId)
    private val mangaKey = mangaStatisticsKey(syncId)

    private fun day(dateKey: String, seconds: Double, characters: Int, modified: Long) =
        ReadingStatistics(title = "Shirokuma", dateKey = dateKey, charactersRead = characters, readingTime = seconds, lastStatisticModified = modified)

    private suspend fun book(repository: BookRepository, folder: String = "book"): File {
        val root = repository.createBookDirectory(folder)
        repository.saveMetadata(root, BookMetadata(id = folder, title = "Shirokuma", cover = null, folder = folder, lastAccess = 1.0, syncId = syncId))
        root.resolve("mokuro.json").writeText("{}")
        return root
    }

    private fun FakeKvTransport.remoteEntries(): List<ReadingStatistics> =
        json.decodeFromString(HttpSyncStatisticsBlob.serializer(), kv.getValue(key).body.decodeToString()).entries.sortedBy { it.dateKey }

    private fun FakeKvTransport.remoteManga(): List<MangaTextStatistic> =
        json.decodeFromString(HttpSyncMangaStatisticsBlob.serializer(), kv.getValue(mangaKey).body.decodeToString()).entries

    private fun listed(transport: FakeKvTransport, k: String = key) =
        transport.kv.getValue(k).let { StatisticsRemoteListing.Listed(it.body.size, it.lastModified) }

    /** Wraps the fake so every request can be counted per key. */
    private class CountingTransport(private val delegate: FakeKvTransport, private val getDelayMs: Long = 0L) : HttpSyncKvTransport by delegate {
        val requests = mutableListOf<Pair<String, String>>()

        @Synchronized
        private fun record(method: String, key: String) {
            requests += method to key
        }

        fun count(method: String, key: String) = synchronized(this) { requests.count { it.first == method && it.second == key } }

        override suspend fun get(key: String): HttpSyncKvFetched? {
            record("GET", key)
            if (getDelayMs > 0) delay(getDelayMs)
            return delegate.get(key)
        }

        override suspend fun getBounded(key: String, maxBytes: Int): HttpSyncKvFetched? {
            record("GET", key)
            if (getDelayMs > 0) delay(getDelayMs)
            return delegate.getBounded(key, maxBytes)
        }

        override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
            record("PUT", key)
            return delegate.put(key, contentType, body)
        }
    }

    // ── State-cache invalidation ─────────────────────────────────────────────────────────

    @Test
    fun aRemoteChangeThatKeepsTheBlobSizeMustStillBePulled() = runBlocking {
        // Device A and B share the server; B updates today's entry in place with numbers of the
        // same digit count (the common case: more minutes on the same day, epoch-ms stamps).
        val transport = FakeKvTransport()
        val repoA = BookRepository(temp.newFolder("A"))
        val repoB = BookRepository(temp.newFolder("B"))
        val rootA = book(repoA)
        val rootB = book(repoB)
        val syncA = HttpSyncStatisticsSync(repoA)
        val syncB = HttpSyncStatisticsSync(repoB)

        repoB.saveStatistics(rootB, listOf(day("2026-09-12", 600.0, 1000, 1_000_000_000_000)))
        syncB.sync(transport, rootB, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)
        syncA.sync(transport, rootA, syncId, StatisticsSyncKind.Reading, listed(transport))
        assertEquals(600.0, repoA.loadStatistics(rootA).single().readingTime, 0.0)
        val sizeBefore = transport.kv.getValue(key).body.size

        // B reads five more minutes: same day, same digit counts everywhere.
        repoB.saveStatistics(rootB, listOf(day("2026-09-12", 900.0, 2000, 1_000_000_000_001)))
        val pushedB = syncB.sync(transport, rootB, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Unknown)
        assertEquals(StatisticsSyncOutcome(downloaded = false, uploaded = true), pushedB)
        assertEquals("the size collision this test is about", sizeBefore, transport.kv.getValue(key).body.size)

        val outcomeA = syncA.sync(transport, rootA, syncId, StatisticsSyncKind.Reading, listed(transport))

        assertEquals("A must notice the changed remote body", StatisticsSyncOutcome(downloaded = true, uploaded = false), outcomeA)
        assertEquals(900.0, repoA.loadStatistics(rootA).single().readingTime, 0.0)
    }

    @Test
    fun mangaCharacterCountsOfTheSameDigitCountMustStillBePulled() = runBlocking {
        val transport = FakeKvTransport()
        val repoA = BookRepository(temp.newFolder("A"))
        val repoB = BookRepository(temp.newFolder("B"))
        val rootA = book(repoA)
        val rootB = book(repoB)
        val syncA = HttpSyncStatisticsSync(repoA)
        val syncB = HttpSyncStatisticsSync(repoB)

        repoB.saveMangaTextStatistics(rootB, listOf(MangaTextStatistic("2026-09-12", 3000, 1_000_000_000_000)))
        syncB.sync(transport, rootB, syncId, StatisticsSyncKind.MangaText, StatisticsRemoteListing.Absent)
        syncA.sync(transport, rootA, syncId, StatisticsSyncKind.MangaText, listed(transport, mangaKey))
        assertEquals(3000, repoA.loadMangaTextStatistics(rootA).single().charactersRead)

        repoB.saveMangaTextStatistics(rootB, listOf(MangaTextStatistic("2026-09-12", 4500, 1_000_000_000_001)))
        syncB.sync(transport, rootB, syncId, StatisticsSyncKind.MangaText, StatisticsRemoteListing.Unknown)
        assertEquals(4500, transport.remoteManga().single().charactersRead)

        val outcomeA = syncA.sync(transport, rootA, syncId, StatisticsSyncKind.MangaText, listed(transport, mangaKey))

        assertEquals(StatisticsSyncOutcome(downloaded = true, uploaded = false), outcomeA)
        assertEquals(4500, repoA.loadMangaTextStatistics(rootA).single().charactersRead)
    }

    @Test
    fun aLostStateFileCostsOneGetAndNoPutAndIsRebuilt() = runBlocking {
        val fake = FakeKvTransport()
        val transport = CountingTransport(fake)
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        repository.saveStatistics(root, listOf(day("2026-09-10", 600.0, 12, 1)))
        sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)
        assertTrue(root.resolve(STATISTICS_SYNC_STATE_FILENAME).delete())
        val stamp = fake.kv.getValue(key).lastModified
        val before = transport.requests.size

        val outcome = sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, listed(fake))

        assertEquals(StatisticsSyncOutcome.NONE, outcome)
        assertEquals(listOf("GET" to key), transport.requests.drop(before))
        assertEquals("nothing was re-uploaded", stamp, fake.kv.getValue(key).lastModified)
        assertTrue("state rebuilt", root.resolve(STATISTICS_SYNC_STATE_FILENAME).isFile)
        assertEquals(StatisticsSyncOutcome.NONE, sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, listed(fake)))
        assertEquals(before + 1, transport.requests.size)
    }

    @Test
    fun aLocalFileRewrittenWithIdenticalContentCostsNothing() = runBlocking {
        val fake = FakeKvTransport()
        val transport = CountingTransport(fake)
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        val entries = listOf(day("2026-09-10", 600.0, 12, 1), day("2026-09-11", 60.0, 2, 2))
        repository.saveStatistics(root, entries)
        sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)
        val before = transport.requests.size

        // The reader saves the same days again (possibly in another order).
        repository.saveStatistics(root, entries.reversed())
        repository.saveStatistics(root, entries)

        assertEquals(StatisticsSyncOutcome.NONE, sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Unknown))
        assertEquals(StatisticsSyncOutcome.NONE, sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, listed(fake)))
        assertEquals(before, transport.requests.size)
    }

    @Test
    fun aBookReimportedUnderTheSameSyncIdPullsTheServerDaysWithOneGet() = runBlocking {
        val fake = FakeKvTransport()
        val transport = CountingTransport(fake)
        val repository = BookRepository(temp.newFolder())
        val first = book(repository, "first")
        val sync = HttpSyncStatisticsSync(repository)
        repository.saveStatistics(first, listOf(day("2026-09-10", 600.0, 12, 1)))
        sync.sync(transport, first, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)
        repository.deleteBook(first)
        val again = book(repository, "again")
        val before = transport.requests.size

        val outcome = sync.sync(transport, again, syncId, StatisticsSyncKind.Reading, listed(fake))

        assertEquals(StatisticsSyncOutcome(downloaded = true, uploaded = false), outcome)
        assertEquals(listOf("GET" to key), transport.requests.drop(before))
        assertEquals(listOf("2026-09-10"), repository.loadStatistics(again).map { it.dateKey })
    }

    // ── Reader path (no listing) ────────────────────────────────────────────────────────

    @Test
    fun theReaderPathMakesNoRequestForABookThatHasNothingLocalToPush() = runBlocking {
        // A manga whose OCR text counter has not fired yet has no manga_statistics.json. The
        // reader push runs both kinds every time; the empty kind must not cost a GET each time.
        val fake = FakeKvTransport()
        val transport = CountingTransport(fake)
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        repository.saveStatistics(root, listOf(day("2026-09-10", 600.0, 12, 1)))

        repeat(3) {
            sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Unknown)
            sync.sync(transport, root, syncId, StatisticsSyncKind.MangaText, StatisticsRemoteListing.Unknown)
        }

        assertEquals(listOf("GET" to key, "PUT" to key), transport.requests.take(2))
        assertEquals("no request for the empty manga_statistics kind: ${transport.requests}", 0, transport.count("GET", mangaKey))
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun aReaderPushAndAListedReconcileOnTheSameKeySerializeToOneExchange() = runBlocking {
        val fake = FakeKvTransport()
        val transport = CountingTransport(fake, getDelayMs = 150)
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        repository.saveStatistics(root, listOf(day("2026-09-10", 600.0, 12, 1)))
        fake.put(key, "application/json", json.encodeToString(HttpSyncStatisticsBlob.serializer(), HttpSyncStatisticsBlob(syncId = syncId, entries = listOf(day("2026-09-11", 60.0, 2, 2)))).toByteArray())
        val listed = listed(fake)

        val outcomes = listOf(
            async(Dispatchers.Default) { sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Unknown) },
            async(Dispatchers.Default) { sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, listed) },
        ).awaitAll()

        assertEquals("exactly one of them did the exchange: $outcomes", 1, outcomes.count { it == StatisticsSyncOutcome(downloaded = true, uploaded = true) })
        assertEquals(1, outcomes.count { it == StatisticsSyncOutcome.NONE })
        // The loser may re-fetch once because its listing predates the winner's PUT; it must never PUT.
        assertTrue("GETs: ${transport.requests}", transport.count("GET", key) in 1..2)
        assertEquals(1, transport.count("PUT", key))
        assertEquals(listOf("2026-09-10", "2026-09-11"), repository.loadStatistics(root).map { it.dateKey }.sorted())
        assertEquals(listOf("2026-09-10", "2026-09-11"), fake.remoteEntries().map { it.dateKey })
    }

    // ── Convergence ─────────────────────────────────────────────────────────────────────

    @Test
    fun twoDevicesEditingTheSameAndDifferentDaysConvergeInOnePassEach() = runBlocking {
        val fake = FakeKvTransport()
        val transport = CountingTransport(fake)
        val repoA = BookRepository(temp.newFolder("A"))
        val repoB = BookRepository(temp.newFolder("B"))
        val rootA = book(repoA)
        val rootB = book(repoB)
        val syncA = HttpSyncStatisticsSync(repoA)
        val syncB = HttpSyncStatisticsSync(repoB)
        repoA.saveStatistics(rootA, listOf(day("2026-09-10", 600.0, 12, 10), day("2026-09-11", 100.0, 1, 3)))
        repoB.saveStatistics(rootB, listOf(day("2026-09-10", 1.0, 1, 1), day("2026-09-12", 200.0, 2, 4)))

        syncA.sync(transport, rootA, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)
        syncB.sync(transport, rootB, syncId, StatisticsSyncKind.Reading, listed(fake))
        syncA.sync(transport, rootA, syncId, StatisticsSyncKind.Reading, listed(fake))

        val expected = listOf(day("2026-09-10", 600.0, 12, 10), day("2026-09-11", 100.0, 1, 3), day("2026-09-12", 200.0, 2, 4))
        assertEquals(expected, repoA.loadStatistics(rootA).sortedBy { it.dateKey })
        assertEquals(expected, repoB.loadStatistics(rootB).sortedBy { it.dateKey })
        assertEquals(expected, fake.remoteEntries())
        val requests = transport.requests.size
        assertEquals(StatisticsSyncOutcome.NONE, syncA.sync(transport, rootA, syncId, StatisticsSyncKind.Reading, listed(fake)))
        assertEquals(StatisticsSyncOutcome.NONE, syncB.sync(transport, rootB, syncId, StatisticsSyncKind.Reading, listed(fake)))
        assertEquals(requests, transport.requests.size)
    }

    @Test
    fun anEqualStampWithDifferentContentResolvesTheSameWayOnBothDevices() = runBlocking {
        // Ties are rare (epoch-ms stamps) but must not leave the two devices disagreeing, and
        // must not ping-pong PUTs forever once the state cache compares content.
        val fake = FakeKvTransport()
        val transport = CountingTransport(fake)
        val repoA = BookRepository(temp.newFolder("A"))
        val repoB = BookRepository(temp.newFolder("B"))
        val rootA = book(repoA)
        val rootB = book(repoB)
        val syncA = HttpSyncStatisticsSync(repoA)
        val syncB = HttpSyncStatisticsSync(repoB)
        repoA.saveStatistics(rootA, listOf(day("2026-09-10", 600.0, 12, 5)))
        repoB.saveStatistics(rootB, listOf(day("2026-09-10", 900.0, 15, 5)))

        syncA.sync(transport, rootA, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)
        syncB.sync(transport, rootB, syncId, StatisticsSyncKind.Reading, listed(fake))
        syncA.sync(transport, rootA, syncId, StatisticsSyncKind.Reading, listed(fake))
        syncB.sync(transport, rootB, syncId, StatisticsSyncKind.Reading, listed(fake))

        val a = repoA.loadStatistics(rootA).single()
        val b = repoB.loadStatistics(rootB).single()
        assertEquals("both devices settle on the same entry", a, b)
        assertEquals(a, fake.remoteEntries().single())
        val puts = transport.count("PUT", key)
        syncA.sync(transport, rootA, syncId, StatisticsSyncKind.Reading, listed(fake))
        syncB.sync(transport, rootB, syncId, StatisticsSyncKind.Reading, listed(fake))
        assertEquals("no further PUT once settled", puts, transport.count("PUT", key))
    }

    @Test
    fun anOversizedRemoteBlobIsRefusedWithoutTouchingLocalOrRemoteState() = runBlocking {
        val fake = FakeKvTransport()
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        val local = listOf(day("2026-09-10", 600.0, 12, 5))
        repository.saveStatistics(root, local)
        val huge = ByteArray(MAX_STATISTICS_BLOB_BYTES + 1) { '{'.code.toByte() }
        fake.put(key, "application/json", huge)
        val stamp = fake.kv.getValue(key).lastModified

        val error = runCatching { sync.sync(transport = fake, bookRoot = root, syncId = syncId, kind = StatisticsSyncKind.Reading, remote = StatisticsRemoteListing.Listed(huge.size)) }.exceptionOrNull()

        assertTrue("$error", error is HttpSyncException && error.message.orEmpty().contains("exceeds"))
        assertEquals(local, repository.loadStatistics(root))
        assertEquals(stamp, fake.kv.getValue(key).lastModified)
        assertTrue(!root.resolve(STATISTICS_SYNC_STATE_FILENAME).exists())
    }

    @Test
    fun unknownTopLevelAndEntryFieldsFromANewerClientAreIgnoredAtTheSameVersion() = runBlocking {
        val fake = FakeKvTransport()
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        fake.put(
            key,
            "application/json",
            """{"version":1,"syncId":"shirokuma","future":true,"entries":[{"title":"Shirokuma","dateKey":"2026-09-10","charactersRead":3,"readingTime":30.0,"lastStatisticModified":7,"mood":"happy"}]}""".toByteArray(),
        )

        val outcome = sync.sync(fake, root, syncId, StatisticsSyncKind.Reading, listed(fake))

        assertEquals(StatisticsSyncOutcome(downloaded = true, uploaded = false), outcome)
        assertEquals(listOf(day("2026-09-10", 30.0, 3, 7)), repository.loadStatistics(root))
    }
}
