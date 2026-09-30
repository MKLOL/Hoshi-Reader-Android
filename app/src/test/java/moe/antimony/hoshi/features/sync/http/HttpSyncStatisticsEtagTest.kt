package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ReadingStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class HttpSyncStatisticsEtagTest {
    @get:Rule val temp = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val syncId = "book"
    private val key = statisticsKey(syncId)
    private val original = ReadingStatistics("Book", "2026-09-29", readingTime = 600.0,
        charactersRead = 100, lastStatisticModified = 1, deviceId = "phone")

    /** Models a server whose timestamp precision is coarser than consecutive PUTs. */
    private class SameStampTransport(private val delegate: FakeKvTransport = FakeKvTransport()) : HttpSyncKvTransport by delegate {
        var gets = 0
        private val stamp = "2026-09-30T00:00:00Z"
        private fun etag(bytes: ByteArray): String = "sha256:" + MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
            delegate.put(key, contentType, body)
            delegate.kv[key] = delegate.kv.getValue(key).copy(lastModified = stamp)
            return HttpSyncKvWriteResponse(key, stamp, etag(body), body.size, contentType)
        }

        override suspend fun get(key: String): HttpSyncKvFetched? {
            gets++
            val row = delegate.kv[key] ?: return null
            return HttpSyncKvFetched(row.body, row.contentType, stamp, etag(row.body))
        }

        override suspend fun getBounded(key: String, maxBytes: Int): HttpSyncKvFetched? = get(key)

        fun listed(key: String): StatisticsRemoteListing.Listed = delegate.kv.getValue(key).let {
            StatisticsRemoteListing.Listed(it.body.size, stamp, etag(it.body))
        }
    }

    private suspend fun book(repository: BookRepository): File = repository.createBookDirectory("book").also {
        repository.saveMetadata(it, BookMetadata("book", "Book", null, it.name, 1.0, syncId = syncId))
    }

    private fun encode(entries: List<ReadingStatistics>) = json.encodeToString(
        HttpSyncStatisticsBlob.serializer(), HttpSyncStatisticsBlob(syncId = syncId, entries = entries),
    ).toByteArray()

    @Test fun changedEtagPullsSameSizeSameTimestampStatisticsAndThenConverges() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        val server = SameStampTransport()
        repository.saveStatistics(root, listOf(original))
        sync.sync(server, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)
        val before = server.listed(key)
        val edited = original.copy(readingTime = 900.0, charactersRead = 200, lastStatisticModified = 2)
        server.put(key, "application/json", encode(listOf(edited)))
        val after = server.listed(key)
        assertEquals(before.size, after.size)
        assertEquals(before.lastModified, after.lastModified)
        assertFalse(before.remoteEtag == after.remoteEtag)

        val result = sync.sync(server, root, syncId, StatisticsSyncKind.Reading, after)
        assertEquals(StatisticsSyncOutcome(downloaded = true, uploaded = false), result)
        assertEquals(listOf(edited), repository.loadStatistics(root))
        val gets = server.gets
        assertEquals(StatisticsSyncOutcome.NONE, sync.sync(server, root, syncId, StatisticsSyncKind.Reading, after))
        assertEquals("a confirmed unchanged ETag needs no GET", gets, server.gets)
    }

    @Test fun olderCacheWithoutEtagUsesMatchingSha256AndSizeWithoutDownloadingTheSameBytes() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        val server = SameStampTransport()
        repository.saveStatistics(root, listOf(original))
        sync.sync(server, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)
        val stateFile = root.resolve(STATISTICS_SYNC_STATE_FILENAME)
        val state = json.decodeFromString(StatisticsSyncState.serializer(), stateFile.readText())
        assertEquals(server.listed(key).remoteEtag, state.reading!!.remoteEtag)
        stateFile.writeText(json.encodeToString(StatisticsSyncState.serializer(),
            state.copy(reading = state.reading.copy(remoteEtag = null))))
        val gets = server.gets
        assertTrue("the fast path must revisit a listed key whose old cache never checked ETags",
            sync.hasLocalChanges(root, syncId, remoteReadingPresent = true))

        assertEquals(StatisticsSyncOutcome.NONE, sync.sync(server, root, syncId, StatisticsSyncKind.Reading, server.listed(key)))
        assertEquals("the server hash proves the local bytes already match", gets, server.gets)
        assertFalse(sync.hasLocalChanges(root, syncId, remoteReadingPresent = true))
        val upgraded = json.decodeFromString(StatisticsSyncState.serializer(), stateFile.readText())
        assertEquals(server.listed(key).remoteEtag, upgraded.reading!!.remoteEtag)
        assertEquals(StatisticsSyncOutcome.NONE, sync.sync(server, root, syncId, StatisticsSyncKind.Reading, server.listed(key)))
        assertEquals(gets, server.gets)
    }

    @Test fun oldAcknowledgedCacheStillRecoversAChangedBodyBeforeAnotherRemoteWrite() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        val server = SameStampTransport()
        repository.saveStatistics(root, listOf(original))
        sync.sync(server, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)
        val stateFile = root.resolve(STATISTICS_SYNC_STATE_FILENAME)
        val state = json.decodeFromString(StatisticsSyncState.serializer(), stateFile.readText())
        stateFile.writeText(json.encodeToString(StatisticsSyncState.serializer(),
            state.copy(reading = state.reading!!.copy(remoteEtag = null))))
        val changed = original.copy(readingTime = 900.0, lastStatisticModified = 2)
        server.put(key, "application/json", encode(listOf(changed)))

        // The batch cache may already have acknowledged the new remote ETag. The per-book
        // legacy validator is independent and must force this one-time repair anyway.
        assertTrue(sync.hasLocalChanges(root, syncId, remoteReadingPresent = true))
        assertEquals(StatisticsSyncOutcome(downloaded = true, uploaded = false),
            sync.sync(server, root, syncId, StatisticsSyncKind.Reading, server.listed(key)))
        assertEquals(listOf(changed), repository.loadStatistics(root))
        assertFalse(sync.hasLocalChanges(root, syncId, remoteReadingPresent = true))
    }

    @Test fun anOpaqueEtagOrMismatchedByteSizeStillRequiresFetchingTheBody() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        val server = SameStampTransport()
        repository.saveStatistics(root, listOf(original))
        sync.sync(server, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)
        val listed = server.listed(key)
        for (unverified in listOf(listed.copy(size = listed.size + 1), listed.copy(remoteEtag = "opaque-version"))) {
            val gets = server.gets
            assertEquals(StatisticsSyncOutcome.NONE, sync.sync(server, root, syncId, StatisticsSyncKind.Reading, unverified))
            assertEquals("only an exact SHA-256 and byte-size match can replace a GET", gets + 1, server.gets)
            assertEquals(listOf(original), repository.loadStatistics(root))
        }
    }

    @Test fun anAbsentListingCannotOverwriteStatisticsCreatedBeforeTheUpload() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        val server = SameStampTransport()
        repository.saveStatistics(root, listOf(original))
        val earlierListing = StatisticsRemoteListing.Absent
        val tablet = original.copy(deviceId = "tablet", readingTime = 300.0)
        server.put(key, "application/json", encode(listOf(tablet)))

        val result = sync.sync(server, root, syncId, StatisticsSyncKind.Reading, earlierListing)
        assertEquals(StatisticsSyncOutcome(downloaded = true, uploaded = true), result)
        assertEquals(setOf(original, tablet), repository.loadStatistics(root).toSet())
        val remote = json.decodeFromString(HttpSyncStatisticsBlob.serializer(), server.get(key)!!.body.decodeToString())
        assertEquals(setOf(original, tablet), remote.entries.toSet())
        assertTrue(server.gets > 0)
    }
}
