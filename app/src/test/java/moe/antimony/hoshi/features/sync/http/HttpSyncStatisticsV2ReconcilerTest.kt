package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
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

/**
 * The v2 reconciler's two statistics paths against the shared [FakeKvTransport]: pass 5 (listed
 * keys, `Listed(size)`) and the extra loop before `OutboundResult` (`Unknown` for every live
 * local book). Requests are counted per key so the "converged book costs nothing" promise is
 * checked for the fallback engine, not only for v3.
 */
class HttpSyncStatisticsV2ReconcilerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val configured = HttpSyncSettings("https://x", "t")
    private val syncId = "shirokuma"

    private class CountingTransport(private val delegate: FakeKvTransport) : HttpSyncKvTransport by delegate {
        val requests = mutableListOf<Pair<String, String>>()
        fun statisticsRequests() = requests.filter { it.second.endsWith("/statistics") || it.second.endsWith("/manga_statistics") }
        fun clear() = requests.clear()

        override suspend fun get(key: String): HttpSyncKvFetched? {
            requests += "GET" to key
            return delegate.get(key)
        }

        override suspend fun getBounded(key: String, maxBytes: Int): HttpSyncKvFetched? {
            requests += "GET" to key
            return delegate.getBounded(key, maxBytes)
        }

        override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
            requests += "PUT" to key
            return delegate.put(key, contentType, body)
        }
    }

    private fun day(dateKey: String, seconds: Double, characters: Int, modified: Long) =
        ReadingStatistics(title = "Shirokuma", dateKey = dateKey, charactersRead = characters, readingTime = seconds, lastStatisticModified = modified)

    private suspend fun manga(repository: BookRepository, folder: String = "shirokuma"): File {
        val root = repository.createBookDirectory(folder)
        repository.saveMetadata(root, BookMetadata(id = folder, title = "Shirokuma", cover = null, folder = folder, lastAccess = 1.0, syncId = syncId))
        root.resolve("mokuro.json").writeText("{}")
        root.resolve("pages").mkdirs()
        root.resolve("pages/p1.png").writeBytes(byteArrayOf(0x42))
        return root
    }

    private fun reconciler(repository: BookRepository, transport: HttpSyncKvTransport) = HttpSyncReconciler(
        bookRepository = repository,
        transportFactory = { transport },
        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    )

    private fun FakeKvTransport.putStatistics(entries: List<ReadingStatistics>) = runBlocking {
        put(statisticsKey(syncId), "application/json", json.encodeToString(HttpSyncStatisticsBlob.serializer(), HttpSyncStatisticsBlob(syncId = syncId, entries = entries)).toByteArray())
    }

    private fun FakeKvTransport.remoteEntries(): List<ReadingStatistics> =
        json.decodeFromString(HttpSyncStatisticsBlob.serializer(), kv.getValue(statisticsKey(syncId)).body.decodeToString()).entries

    @Test
    fun pass5PullsListedStatisticsAndTheExtraLoopDoesNotRepeatTheExchange() = runBlocking {
        val fake = FakeKvTransport()
        val transport = CountingTransport(fake)
        val repository = BookRepository(temp.newFolder())
        val root = manga(repository)
        fake.putStatistics(listOf(day("2026-09-10", 600.0, 12, 10)))
        repository.saveMangaTextStatistics(root, listOf(MangaTextStatistic("2026-09-10", 500, 1)))

        val first = reconciler(repository, transport).syncOnce(configured)

        assertEquals(emptyList<String>(), first.errors)
        assertEquals(1, first.downloadedStatistics)
        assertEquals(1, first.uploadedStatistics)
        assertEquals(listOf("2026-09-10"), repository.loadStatistics(root).map { it.dateKey })
        assertEquals(1, transport.statisticsRequests().count { it == ("GET" to statisticsKey(syncId)) })
        assertEquals(0, transport.statisticsRequests().count { it == ("PUT" to statisticsKey(syncId)) })
        assertEquals(1, transport.statisticsRequests().count { it == ("PUT" to mangaStatisticsKey(syncId)) })

        // Converged: neither pass 5 (both keys are listed now) nor the extra loop makes a request.
        transport.clear()
        val second = reconciler(repository, transport).syncOnce(configured)
        assertEquals(emptyList<String>(), second.errors)
        assertEquals(0, second.downloadedStatistics + second.uploadedStatistics)
        assertEquals("converged v2 sync makes no statistics request: ${transport.statisticsRequests()}", emptyList<Pair<String, String>>(), transport.statisticsRequests())
    }

    @Test
    fun theExtraLoopPushesLocalStatisticsTheListingDoesNotMentionAndThenGoesQuiet() = runBlocking {
        val fake = FakeKvTransport()
        val transport = CountingTransport(fake)
        val repository = BookRepository(temp.newFolder())
        val root = manga(repository)
        repository.saveStatistics(root, listOf(day("2026-09-10", 600.0, 12, 10)))

        val first = reconciler(repository, transport).syncOnce(configured)

        assertEquals(emptyList<String>(), first.errors)
        assertEquals(1, first.uploadedStatistics)
        assertEquals(listOf("2026-09-10"), fake.remoteEntries().map { it.dateKey })

        transport.clear()
        val second = reconciler(repository, transport).syncOnce(configured)
        assertEquals(emptyList<String>(), second.errors)
        assertEquals(0, second.uploadedStatistics + second.downloadedStatistics)
        assertEquals("converged v2 sync makes no statistics request: ${transport.statisticsRequests()}", emptyList<Pair<String, String>>(), transport.statisticsRequests())
    }

    @Test
    fun aBookThatWasNeverReadCostsNoStatisticsRequestOnAnyV2Sync() = runBlocking {
        // Neither sidecar exists locally and the server has no statistics keys. The extra loop
        // asks with `Unknown`; there is nothing to push, so nothing may be fetched either.
        val fake = FakeKvTransport()
        val transport = CountingTransport(fake)
        val repository = BookRepository(temp.newFolder())
        manga(repository)

        reconciler(repository, transport).syncOnce(configured)
        transport.clear()
        val second = reconciler(repository, transport).syncOnce(configured)

        assertEquals(emptyList<String>(), second.errors)
        assertEquals("never-read book must not cost a GET per sync: ${transport.statisticsRequests()}", emptyList<Pair<String, String>>(), transport.statisticsRequests())
    }

    @Test
    fun aRemoteTombstoneRemovesTheBookAndNoStatisticsArePushedOverIt() = runBlocking {
        val fake = FakeKvTransport()
        val transport = CountingTransport(fake)
        val repository = BookRepository(temp.newFolder())
        val root = manga(repository)
        repository.saveStatistics(root, listOf(day("2026-09-10", 600.0, 12, 10)))
        fake.putJson(
            metadataKey(syncId),
            HttpSyncMetadataBlob.serializer(),
            HttpSyncMetadataBlob(title = "Shirokuma", contentType = HttpSyncContentType.Mokuro, deletedAt = "2030-01-01T00:00:00Z"),
            json,
            "2030-01-01T00:00:00Z",
        )
        fake.putStatistics(listOf(day("2026-09-01", 1.0, 1, 1)))

        val result = reconciler(repository, transport).syncOnce(configured)

        assertEquals(emptyList<String>(), result.errors)
        assertFalse("tombstone applied", root.exists())
        assertEquals(0, result.uploadedStatistics + result.downloadedStatistics)
        assertEquals("no statistics traffic for a tombstoned book: ${transport.statisticsRequests()}", emptyList<Pair<String, String>>(), transport.statisticsRequests())
        assertEquals("server statistics untouched", listOf("2026-09-01"), fake.remoteEntries().map { it.dateKey })
    }

    @Test
    fun aMalformedRemoteBlobIsAPerBookErrorThatLeavesLocalAndRemoteAlone() = runBlocking {
        val fake = FakeKvTransport()
        val transport = CountingTransport(fake)
        val repository = BookRepository(temp.newFolder())
        val root = manga(repository)
        val local = listOf(day("2026-09-10", 600.0, 12, 10))
        repository.saveStatistics(root, local)
        fake.put(statisticsKey(syncId), "application/json", "<html>proxy error</html>".toByteArray())
        val stamp = fake.kv.getValue(statisticsKey(syncId)).lastModified

        val result = reconciler(repository, transport).syncOnce(configured)

        assertTrue("$result", result.errors.any { it.startsWith("statistics $syncId") && "malformed" in it })
        assertEquals(local, repository.loadStatistics(root))
        assertEquals(stamp, fake.kv.getValue(statisticsKey(syncId)).lastModified)
        assertNull(root.resolve(STATISTICS_SYNC_STATE_FILENAME).takeIf { it.exists() })
        // Everything else about the book still synced.
        assertTrue(metadataKey(syncId) in fake.kv)
        assertTrue(payloadManifestKey(syncId) in fake.kv)
    }
}
