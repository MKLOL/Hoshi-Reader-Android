package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HttpSyncStatisticsConcurrencyTest {
    @get:Rule val temp = TemporaryFolder()
    private val json = Json { encodeDefaults = true }
    private val syncId = "book"
    private val reading = ReadingStatistics(
        "Book", "2026-09-30", readingTime = 600.0, charactersRead = 500,
        lastStatisticModified = 1,
    )

    private suspend fun book(repository: BookRepository): File = repository.createBookDirectory("book").also {
        repository.saveMetadata(it, BookMetadata("book", "Book", null, it.name, 1.0, syncId = syncId))
        it.resolve("mokuro.json").writeText("{}")
    }

    @Test(timeout = 5_000)
    fun overlappingReadingAndMangaExchangesKeepBothValidatorsAndConvergeWithoutRequests() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val locks = HttpSyncBookLocks()
        // Production has distinct exchange instances for background and reader pushes.
        val readingSync = HttpSyncStatisticsSync(repository, locks)
        val mangaSync = HttpSyncStatisticsSync(repository, locks)
        repository.saveStatistics(root, listOf(reading))
        repository.saveMangaTextStatistics(root, listOf(MangaTextStatistic("2026-09-30", 400, 1)))
        val readingFetched = CompletableDeferred<Unit>()
        val finishReading = CompletableDeferred<Unit>()
        val server = FakeKvTransport()
        var requests = 0
        val transport = object : HttpSyncKvTransport by server {
            override suspend fun getBounded(key: String, maxBytes: Int): HttpSyncKvFetched? {
                requests++
                if (key == statisticsKey(syncId)) {
                    readingFetched.complete(Unit)
                    finishReading.await()
                }
                return server.getBounded(key, maxBytes)
            }

            override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
                requests++
                return server.put(key, contentType, body)
            }
        }
        val first = async {
            readingSync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Unknown)
        }
        readingFetched.await()
        mangaSync.sync(transport, root, syncId, StatisticsSyncKind.MangaText, StatisticsRemoteListing.Unknown)
        finishReading.complete(Unit)
        first.await()

        assertFalse("one kind must not erase the other's exchanged SHA", readingSync.hasLocalChanges(root, syncId))
        val before = requests
        for (kind in StatisticsSyncKind.entries) {
            assertEquals(StatisticsSyncOutcome.NONE,
                readingSync.sync(transport, root, syncId, kind, StatisticsRemoteListing.Unknown))
        }
        assertEquals("both kinds must converge without another GET or PUT", before, requests)
    }

    @Test(timeout = 5_000)
    fun observingFirstLegacyDownloadCannotAttributeTheRemoteHistoryToThisDevice() = runBlocking {
        // Inline observer runs at the statistics change notification, before sync resumes.
        val repository = BookRepository(temp.newFolder(), ioDispatcher = Dispatchers.Unconfined,
            deviceIdentity = DeviceIdentity("tablet", "Tablet"))
        val root = book(repository)
        val server = FakeKvTransport()
        server.put(statisticsKey(syncId), "application/json", json.encodeToString(
            HttpSyncStatisticsBlob.serializer(), HttpSyncStatisticsBlob(syncId = syncId, entries = listOf(reading)),
        ).toByteArray())
        var observed: List<ReadingStatistics>? = null
        val observer = launch(Dispatchers.Unconfined) {
            repository.statisticsChanges.drop(1).take(1).collect {
                observed = repository.loadStatistics(root)
            }
        }
        val sync = HttpSyncStatisticsSync(repository)
        val result = sync.sync(server, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Listed(1))
        observer.join()

        assertTrue(result.downloaded)
        assertEquals(listOf(reading), observed)
        assertNull(repository.loadStatistics(root).single().deviceId)
        assertFalse(sync.hasLocalChanges(root, syncId))
        assertEquals(StatisticsSyncOutcome.NONE,
            sync.sync(server, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Unknown))
        val remote = json.decodeFromString(HttpSyncStatisticsBlob.serializer(),
            server.kv.getValue(statisticsKey(syncId)).body.decodeToString())
        assertEquals("imported legacy totals must not gain a second tablet bucket", listOf(reading), remote.entries)
    }
}
