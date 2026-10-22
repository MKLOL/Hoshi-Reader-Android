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
        val getKeys = mutableListOf<String>()
        val requests = mutableListOf<String>()
        override suspend fun get(key: String): moe.antimony.hoshi.features.sync.http.HttpSyncKvFetched? {
            getKeys += key
            requests += "GET $key"
            return fake.get(key)
        }
        override suspend fun getBounded(key: String, maxBytes: Int): moe.antimony.hoshi.features.sync.http.HttpSyncKvFetched? {
            getKeys += key
            requests += "GET $key"
            return fake.getBounded(key, maxBytes)
        }
        override suspend fun getRange(key: String, start: Long?, length: Int): HttpSyncKvRange? {
            rangeCalls += 1
            requests += "RANGE $key"
            if (failRanges) throw HttpSyncException("Sync request timed out. Network is too slow or the server is hung.")
            // The production client's own preconditions.
            require(start == null || start >= 0) { "Range start must not be negative." }
            return fake.getRange(key, start, length)
        }
    }

    @Test
    fun aStoredCoverIsFetchedWithoutAnyArchiveRanges() = runBlocking {
        val transport = Flaky()
        uploadManga(transport, "manga_one")
        val manifest = json.decodeFromString(HttpSyncPayloadManifest.serializer(),
            transport.get(payloadManifestKey("manga_one"))!!.body.toString(Charsets.UTF_8))
        val key = "books/manga_one/cover/${manifest.sha256.removePrefix("sha256:")}"
        val thumbnail = byteArrayOf(1, 2, 3)
        transport.put(key, "image/jpeg", thumbnail)
        val (cloud, engine) = engine(transport)
        transport.failRanges = true

        assertEquals(emptyList<V3Error>(), engine.syncOnce(configured).errors)

        assertEquals("stored covers must avoid the ZIP path", 0, transport.rangeCalls)
        val record = cloud.records().single()
        assertArrayEquals(thumbnail, cloud.coverFile(record)!!.readBytes())
        assertEquals(1, transport.getKeys.count { it == key })
        engine.syncOnce(configured)
        assertEquals("cached thumbnails are not downloaded again", 1, transport.getKeys.count { it == key })
    }

    @Test
    fun aMissingStoredCoverIsCheckedBeforeFallingBackToArchiveRanges() = runBlocking {
        val transport = Flaky()
        uploadManga(transport, "manga_one")
        val manifest = json.decodeFromString(HttpSyncPayloadManifest.serializer(),
            transport.get(payloadManifestKey("manga_one"))!!.body.toString(Charsets.UTF_8))
        val key = "books/manga_one/cover/${manifest.sha256.removePrefix("sha256:")}"
        val (cloud, engine) = engine(transport)

        engine.syncOnce(configured)

        assertTrue("check KVS even when talking to an older server", key in transport.getKeys)
        assertTrue(transport.rangeCalls > 0)
        assertTrue(transport.requests.indexOf("GET $key") < transport.requests.indexOfFirst { it.startsWith("RANGE ") })
        assertArrayEquals(coverPage, cloud.coverFile(cloud.records().single())!!.readBytes())
    }

    @Test
    fun anOversizedStoredCoverFallsBackWithoutStoppingTheOtherCovers() = runBlocking {
        val transport = Flaky()
        uploadManga(transport, "manga_one")
        uploadManga(transport, "manga_two")
        for (syncId in listOf("manga_one", "manga_two")) {
            val manifest = json.decodeFromString(HttpSyncPayloadManifest.serializer(),
                transport.get(payloadManifestKey(syncId))!!.body.toString(Charsets.UTF_8))
            transport.put("books/$syncId/cover/${manifest.sha256.removePrefix("sha256:")}",
                "image/jpeg", ByteArray(moe.antimony.hoshi.features.sync.http.MAX_COVER_BYTES + 1))
        }
        val (cloud, engine) = engine(transport)

        engine.syncOnce(configured)

        assertEquals(2, cloud.records().size)
        for (record in cloud.records()) assertArrayEquals(coverPage, cloud.coverFile(record)!!.readBytes())
    }

    private fun engine(
        transport: HttpSyncKvTransport,
        thumbnail: (ByteArray) -> ByteArray? = { it },
    ): Pair<HttpSyncCloudBooks, V3SyncEngine> {
        val repo = BookRepository(tempFolder.newFolder())
        val cloud = HttpSyncCloudBooks(repo.booksDirectory, coverThumbnail = thumbnail, ioDispatcher = Dispatchers.Unconfined)
        return cloud to V3SyncEngine(
            bookRepository = repo,
            payloadCodec = HttpSyncPayloadCodec(Dispatchers.Unconfined),
            transportFactory = { transport },
            ioDispatcher = Dispatchers.Unconfined,
            cloudBooks = cloud,
        )
    }

    @Test
    fun anUnreadableStoredCoverFallsBackWithoutBlockingLaterRetries() = runBlocking {
        val transport = Flaky()
        uploadManga(transport, "manga_one")
        val manifest = json.decodeFromString(HttpSyncPayloadManifest.serializer(),
            transport.get(payloadManifestKey("manga_one"))!!.body.toString(Charsets.UTF_8))
        transport.put("books/manga_one/cover/${manifest.sha256.removePrefix("sha256:")}",
            "image/jpeg", byteArrayOf(9))
        val (cloud, engine) = engine(transport) { bytes -> bytes.takeIf { it.contentEquals(coverPage) } }
        transport.failRanges = true
        engine.syncOnce(configured)
        assertEquals(null, cloud.records().single().coverFile)

        transport.failRanges = false
        engine.syncOnce(configured)

        assertTrue("invalid stored images must still try ZIP ranges", transport.rangeCalls > 0)
        assertArrayEquals(coverPage, cloud.coverFile(cloud.records().single())!!.readBytes())
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
