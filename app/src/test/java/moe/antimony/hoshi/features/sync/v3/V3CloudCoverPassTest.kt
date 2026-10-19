package moe.antimony.hoshi.features.sync.v3

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.sync.http.HttpSyncCloudBooks
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncException
import moe.antimony.hoshi.features.sync.http.HttpSyncKvRange
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncMetadataBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadManifest
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import moe.antimony.hoshi.features.sync.http.RangeFakeKvTransport
import moe.antimony.hoshi.features.sync.http.RawZip
import moe.antimony.hoshi.features.sync.http.metadataKey
import moe.antimony.hoshi.features.sync.http.payloadManifestKey
import moe.antimony.hoshi.features.sync.http.payloadZipKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

/** Retry semantics of the cloud-cover pass: what is retried, what is not, and what it costs. */
class V3CloudCoverPassTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val configured = HttpSyncSettings("https://x", "t")
    private val coverPage = ByteArray(5_000) { (it * 31).toByte() }

    /** Counts range calls and can fail them, like a client whose requests time out. */
    private class Flaky(val fake: RangeFakeKvTransport = RangeFakeKvTransport()) : HttpSyncKvTransport by fake {
        var failRanges = false
        var rangeCalls = 0
        override suspend fun getRange(key: String, start: Long?, length: Int): HttpSyncKvRange? {
            rangeCalls += 1
            if (failRanges) throw HttpSyncException("Sync request timed out. Network is too slow or the server is hung.")
            // The production client's own preconditions.
            require(start == null || start >= 0) { "Range start must not be negative." }
            return fake.getRange(key, start, length)
        }
    }

    private fun engine(transport: HttpSyncKvTransport): Pair<HttpSyncCloudBooks, V3SyncEngine> {
        val repo = BookRepository(tempFolder.newFolder())
        val cloud = HttpSyncCloudBooks(repo.booksDirectory, coverThumbnail = { it }, ioDispatcher = Dispatchers.Unconfined)
        return cloud to V3SyncEngine(
            bookRepository = repo,
            payloadCodec = HttpSyncPayloadCodec(Dispatchers.Unconfined),
            transportFactory = { transport },
            ioDispatcher = Dispatchers.Unconfined,
            cloudBooks = cloud,
        )
    }

    private suspend fun uploadManga(transport: HttpSyncKvTransport, syncId: String) {
        val source = tempFolder.newFolder("src-$syncId")
        source.resolve("images").mkdirs()
        source.resolve("images/0001.jpg").writeBytes(coverPage)
        source.resolve("mokuro.json").writeText("""{"title":"$syncId","pages":[{"img_path":"images/0001.jpg"}]}""")
        HttpSyncPayloadCodec(Dispatchers.Unconfined).uploadIfChanged(transport, syncId, source, syncId, HttpSyncContentType.Mokuro)
        putMetadata(transport, syncId)
    }

    private suspend fun putMetadata(transport: HttpSyncKvTransport, syncId: String) {
        transport.put(
            metadataKey(syncId),
            "application/json; charset=utf-8",
            json.encodeToString(HttpSyncMetadataBlob.serializer(), HttpSyncMetadataBlob(syncId, HttpSyncContentType.Mokuro)).toByteArray(),
        )
    }

    /** Publishes [archive] as a manga's payload, with a manifest that matches it. */
    private suspend fun uploadArchive(transport: HttpSyncKvTransport, syncId: String, archive: ByteArray) {
        val sha = "sha256:" + MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        transport.put(payloadZipKey(syncId), "application/zip", archive)
        val manifest = HttpSyncPayloadManifest(sha, archive.size.toLong(), syncId, HttpSyncContentType.Mokuro)
        transport.put(payloadManifestKey(syncId), "application/json", json.encodeToString(HttpSyncPayloadManifest.serializer(), manifest).toByteArray())
        putMetadata(transport, syncId)
    }

    @Test
    fun aTransientRangeFailureIsRetriedByTheNextSync() = runBlocking {
        val transport = Flaky()
        uploadManga(transport, "manga_one")
        val (cloud, engine) = engine(transport)

        transport.failRanges = true
        assertEquals(emptyList<V3Error>(), engine.syncOnce(configured).errors)
        assertEquals(null, cloud.records().single().coverFile)

        transport.failRanges = false
        engine.syncOnce(configured)
        val record = cloud.records().single()
        assertNotNull(record.coverFile)
        assertArrayEquals(coverPage, cloud.coverFile(record)!!.readBytes())
    }

    /**
     * A ZIP64 extra whose local-header offset is negative is a deterministic "no cover", but it
     * escapes as IllegalArgumentException (from the client's `require`), which the executor
     * treats like a network error: every sync reads the archive's tail and directory again.
     */
    @Test
    fun anArchiveWithNoReadableCoverIsNotReadAgainOnEverySync() = runBlocking {
        val transport = Flaky()
        val hostile = RawZip().apply {
            add("images/0001.jpg", coverPage).apply { extra = RawZip.zip64Extra(-1L); localOffset = 0xFFFFFFFFL }
        }.finish()
        uploadArchive(transport, "manga_one", hostile)
        val (_, engine) = engine(transport)

        engine.syncOnce(configured)
        val afterFirst = transport.rangeCalls
        engine.syncOnce(configured)
        engine.syncOnce(configured)

        assertTrue("first sync made range calls", afterFirst > 0)
        assertEquals("later syncs must not retry a deterministic failure", afterFirst, transport.rangeCalls)
    }

    /**
     * With the server unreachable (each request waiting out the client's 15 s connect or 30 s
     * read timeout), the cover pass still tries every listed manga, one after the other, before
     * the executor starts any other action of the sync.
     */
    @Test
    fun anUnreachableServerIsNotTriedOnceForEveryCloudManga() = runBlocking {
        val transport = Flaky()
        repeat(20) { uploadManga(transport, "manga_%02d".format(it)) }
        val (cloud, engine) = engine(transport)
        transport.failRanges = true

        engine.syncOnce(configured)

        assertEquals(20, cloud.records().size)
        assertEquals("cover attempts after the first network failure", 1, transport.rangeCalls)
    }
}
