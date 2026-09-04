package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadManifest
import moe.antimony.hoshi.features.sync.http.HttpSyncSentenceEntry
import moe.antimony.hoshi.features.sync.http.HttpSyncSentencesBlob
import moe.antimony.hoshi.features.sync.http.bookmarkKey
import moe.antimony.hoshi.features.sync.http.epubManifestKey
import moe.antimony.hoshi.features.sync.http.epubZipKey
import moe.antimony.hoshi.features.sync.http.metadataKey
import moe.antimony.hoshi.features.sync.http.payloadManifestKey
import moe.antimony.hoshi.features.sync.http.payloadZipKey
import moe.antimony.hoshi.features.sync.http.pretranslationsKey
import moe.antimony.hoshi.features.sync.http.sentencesKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * End-to-end sync between simulated installs through a REAL server over REAL HTTP, using the
 * production engines, payload codec, KV client, and push hooks. Nothing on the server is placed
 * by hand except the blobs a desktop tool produces in production (pre-translations, sentences)
 * and the deliberately corrupted manifest that reproduces the 0.11.3 incident.
 *
 * Parameterized over the engine each device runs so v3, the v2 rollback path, and mixed fleets
 * are all covered.
 */
@RunWith(Parameterized::class)
class SyncIntegrationTest(private val engineA: SyncEngine, private val engineB: SyncEngine) {
    companion object {
        @JvmField @ClassRule val serverRule = SyncTestServerRule()

        @JvmStatic
        @Parameterized.Parameters(name = "A={0} B={1}")
        fun engines(): List<Array<Any>> = listOf(
            arrayOf(SyncEngine.V3, SyncEngine.V3),
            arrayOf(SyncEngine.V2, SyncEngine.V2),
            arrayOf(SyncEngine.V3, SyncEngine.V2),
            arrayOf(SyncEngine.V2, SyncEngine.V3),
        )
    }

    @get:Rule val temp = TemporaryFolder()

    private val server get() = serverRule.server
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun resetServer() {
        server.reset()
    }

    private fun device(name: String, engine: SyncEngine, multipartBytes: Long = 64L * 1024L * 1024L) =
        SyncDevice(name, engine, temp.newFolder(name), server, multipartBytes, multipartBytes)

    private fun assertClean(outcome: SyncOutcome) {
        assertEquals("sync errors: ${outcome.errors}", emptyList<String>(), outcome.errors)
    }

    private fun sha256(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun manifest(key: String): HttpSyncPayloadManifest =
        json.decodeFromString(HttpSyncPayloadManifest.serializer(), server.client().get(key)!!.body.toString(Charsets.UTF_8))

    private suspend fun putManifest(key: String, manifest: HttpSyncPayloadManifest) {
        server.client().put(key, "application/json; charset=utf-8", json.encodeToString(HttpSyncPayloadManifest.serializer(), manifest).toByteArray())
    }

    private fun assertSamePayload(origin: SyncDevice, originRoot: File, receiver: SyncDevice, receiverRoot: File) {
        val expected = origin.payloadFiles(originRoot)
        val actual = receiver.payloadFiles(receiverRoot)
        assertEquals("payload file set", expected.keys, actual.keys)
        for ((path, bytes) in expected) assertArrayEquals("bytes of $path", bytes, actual.getValue(path))
        assertEquals(
            "cross-platform content hash",
            origin.codec.computePayloadContentSha(originRoot),
            receiver.codec.computePayloadContentSha(receiverRoot),
        )
    }

    /** Publishes A's library, then a brand-new install B syncs from nothing. */
    private suspend fun publishLibraryAndSyncFreshDevice(): Pair<SyncDevice, SyncDevice> {
        val a = device("A", engineA)
        val manga = SyncCorpus.importManga(a.repo)
        val novel = SyncCorpus.importNovel(a.repo)
        a.repo.saveBookmark(manga, SyncCorpus.bookmark(chapter = 2, appleSeconds = 800_000_000.0))
        a.repo.saveBookmark(novel, SyncCorpus.bookmark(chapter = 1, appleSeconds = 800_000_000.0))
        a.history.append(manga, AiChatEntry(SyncCorpus.BUBBLE_TEXT, "prompt", "model", "Hello", 800_000_000.0))

        val first = a.sync()
        assertClean(first)
        assertEquals("both payloads uploaded", 2, first.uploadedPayloads)

        // The desktop tools publish these; every device must receive them.
        server.client().put(pretranslationsKey(SyncCorpus.MANGA_SYNC_ID), "application/json", PRETRANSLATIONS.toByteArray())
        val sentences = HttpSyncSentencesBlob(
            kind = "epub", syncId = SyncCorpus.NOVEL_SYNC_ID, title = SyncCorpus.NOVEL_TITLE, model = "m", promptId = "p",
            generatedAt = "2026-09-01T00:00:00Z", spineCount = 1,
            entries = mapOf("c0s0" to HttpSyncSentenceEntry(0, 0, 3, SyncCorpus.NOVEL_SENTENCE, SyncCorpus.sentenceHash(), "To eat.", "Dictionary form.")),
        )
        server.client().put(sentencesKey(SyncCorpus.NOVEL_SYNC_ID), "application/json", json.encodeToString(HttpSyncSentencesBlob.serializer(), sentences).toByteArray())

        val b = device("B", engineB)
        val fresh = b.sync()
        assertClean(fresh)
        assertEquals("fresh device downloads both payloads", 2, fresh.downloadedPayloads)
        return a to b
    }

    @Test
    fun freshDeviceSyncsTheWholeLibraryByteForByte() = runBlocking {
        val (a, b) = publishLibraryAndSyncFreshDevice()

        // Server state is exactly what the manifests describe.
        val etags = server.etags()
        for ((manifestKey, zipKey) in listOf(
            payloadManifestKey(SyncCorpus.MANGA_SYNC_ID) to payloadZipKey(SyncCorpus.MANGA_SYNC_ID),
            epubManifestKey(SyncCorpus.NOVEL_SYNC_ID) to epubZipKey(SyncCorpus.NOVEL_SYNC_ID),
        )) {
            val m = manifest(manifestKey)
            assertEquals("manifest sha256 is the zip's etag", etags.getValue(zipKey), m.sha256)
            assertNotNull("manifest carries the content hash", m.contentSha256)
        }
        assertTrue(bookmarkKey(SyncCorpus.MANGA_SYNC_ID) in etags)
        assertTrue(metadataKey(SyncCorpus.MANGA_SYNC_ID) in etags)
        assertTrue(metadataKey(SyncCorpus.NOVEL_SYNC_ID) in etags)

        for (syncId in listOf(SyncCorpus.MANGA_SYNC_ID, SyncCorpus.NOVEL_SYNC_ID)) {
            val origin = a.book(syncId)
            val copy = b.book(syncId)
            assertSamePayload(a, origin.root, b, copy.root)
            assertEquals("bookmark", a.repo.loadBookmark(origin.root), b.repo.loadBookmark(copy.root))
            assertNotNull("cover path populated", copy.metadata.cover)
            assertTrue("cover file exists", b.repo.coverFile(copy)!!.isFile)
            val manifestKey = if (syncId == SyncCorpus.MANGA_SYNC_ID) payloadManifestKey(syncId) else epubManifestKey(syncId)
            assertEquals("receiver caches the manifest's content hash", manifest(manifestKey).contentSha256, b.codec.cachedPayloadSha(copy.root))
        }
        val mangaB = b.book(SyncCorpus.MANGA_SYNC_ID)
        assertEquals(listOf("Hello"), b.history.load(mangaB.root).entries.map { it.response })
        assertEquals(PRETRANSLATIONS, mangaB.root.resolve("pretranslations.json").readText())
        val novelB = b.book(SyncCorpus.NOVEL_SYNC_ID)
        assertTrue("sentence translations landed", novelB.root.resolve("sentence_translations.json").readText().contains("To eat."))
        assertTrue("EPUB progress is derivable before first open", (b.repo.loadBookInfo(novelB.root)?.characterCount ?: 0) > 0)
    }

    @Test
    fun resyncOnBothDevicesTransfersNothingAndLeavesTheServerUntouched() = runBlocking {
        val (a, b) = publishLibraryAndSyncFreshDevice()
        val before = server.etags()
        server.clearRequests()

        assertClean(a.sync())
        assertClean(b.sync())
        assertClean(a.sync())

        val requests = server.requests()
        assertEquals("no payload re-uploads", emptyList<RecordedRequest>(), requests.filter { it.isPayloadUpload })
        assertEquals("no payload re-downloads", emptyList<RecordedRequest>(), requests.filter { it.isPayloadDownload })
        assertEquals("no manifest rewrites", emptyList<RecordedRequest>(), requests.filter { it.isManifestWrite })
        val after = server.etags()
        for (key in before.keys.filter { it.endsWith(".zip") || it.endsWith(".manifest") || it.endsWith("/bookmark") }) {
            assertEquals("etag of $key unchanged", before[key], after[key])
        }
    }

    @Test
    fun bookmarksRoundTripInBothDirectionsWithNewestWinning() = runBlocking {
        val (a, b) = publishLibraryAndSyncFreshDevice()
        val rootA = a.book(SyncCorpus.MANGA_SYNC_ID).root
        val rootB = b.book(SyncCorpus.MANGA_SYNC_ID).root

        // A turns pages; the reader's fire-and-forget push publishes without a full sync.
        a.repo.saveBookmark(rootA, SyncCorpus.bookmark(chapter = 5, appleSeconds = 800_000_100.0))
        a.pusher.pushBookmark(rootA, SyncCorpus.MANGA_TITLE, a.settings)
        assertClean(b.sync())
        assertEquals(5, b.repo.loadBookmark(rootB)!!.chapterIndex)

        // B reads further; A picks it up on its next sync.
        b.repo.saveBookmark(rootB, SyncCorpus.bookmark(chapter = 9, appleSeconds = 800_000_200.0))
        assertClean(b.sync())
        assertClean(a.sync())
        assertEquals(9, a.repo.loadBookmark(rootA)!!.chapterIndex)

        // A stale position (older timestamp) never rolls the newer one back.
        a.repo.saveBookmark(rootA, SyncCorpus.bookmark(chapter = 3, appleSeconds = 800_000_050.0))
        assertClean(a.sync())
        assertClean(b.sync())
        assertEquals(9, b.repo.loadBookmark(rootB)!!.chapterIndex)
        assertEquals("the older write is corrected from the server", 9, a.repo.loadBookmark(rootA)!!.chapterIndex)
    }

    @Test
    fun reimportedBookReplacesTheRemotePayloadAndOtherDevicesKeepTheirSidecars() = runBlocking {
        val (a, b) = publishLibraryAndSyncFreshDevice()
        val rootA = a.book(SyncCorpus.NOVEL_SYNC_ID).root
        val rootB = b.book(SyncCorpus.NOVEL_SYNC_ID).root
        val zipBefore = server.etags().getValue(epubZipKey(SyncCorpus.NOVEL_SYNC_ID))
        b.repo.saveBookmark(rootB, SyncCorpus.bookmark(chapter = 1, appleSeconds = 800_000_300.0))
        assertClean(b.sync())

        // A re-imports a corrected EPUB: exactly what BookRepository.importEpub does for a known title.
        val staging = temp.newFolder("novel-v2")
        SyncCorpus.writeEpub(staging, SyncCorpus.NOVEL_TITLE, SyncCorpus.NOVEL_CHAPTER_V2)
        a.codec.installReplacement(rootA, staging, a.codec.computePayloadContentSha(staging))
        a.codec.markPayloadContentDirty(rootA)
        val push = a.sync()
        assertClean(push)
        assertEquals("changed payload is re-uploaded", 1, push.uploadedPayloads)
        val zipAfter = server.etags().getValue(epubZipKey(SyncCorpus.NOVEL_SYNC_ID))
        assertFalse("server holds the new archive", zipBefore == zipAfter)
        assertEquals(zipAfter, manifest(epubManifestKey(SyncCorpus.NOVEL_SYNC_ID)).sha256)

        val pull = b.sync()
        assertClean(pull)
        assertEquals("other device installs the replacement", 1, pull.downloadedPayloads)
        assertTrue(rootB.resolve("OEBPS/chapter.xhtml").readText().contains(SyncCorpus.NOVEL_CHAPTER_V2))
        assertSamePayload(a, rootA, b, rootB)
        assertEquals("bookmark survives the replacement", 1, b.repo.loadBookmark(rootB)!!.chapterIndex)
        assertEquals(manifest(epubManifestKey(SyncCorpus.NOVEL_SYNC_ID)).contentSha256, b.codec.cachedPayloadSha(rootB))
    }

    @Test
    fun corruptManifestContentHashIsRepairedAndNeverBlocksAnyDevice() = runBlocking {
        // The 0.11.3 incident: a client published an unreproducible content hash for every book.
        val (a, b) = publishLibraryAndSyncFreshDevice()
        val key = payloadManifestKey(SyncCorpus.MANGA_SYNC_ID)
        val good = manifest(key)
        putManifest(key, good.copy(contentSha256 = "sha256:" + "0".repeat(64)))

        val c = device("C", engineB)
        val fresh = c.sync()
        assertClean(fresh)
        assertEquals("a brand-new install still imports the book", 2, fresh.downloadedPayloads)
        assertSamePayload(a, a.book(SyncCorpus.MANGA_SYNC_ID).root, c, c.book(SyncCorpus.MANGA_SYNC_ID).root)
        assertEquals("the manifest is corrected on the server", good.contentSha256, manifest(key).contentSha256)
        assertEquals("the archive itself was never touched", good.sha256, manifest(key).sha256)

        // Devices that already hold the book neither fail nor re-download.
        server.clearRequests()
        assertClean(a.sync())
        assertClean(b.sync())
        assertEquals(emptyList<RecordedRequest>(), server.requests().filter { it.isPayloadDownload })
    }

    @Test
    fun legacyManifestWithoutContentHashIsUpgradedWithoutReinstalling() = runBlocking {
        val (a, b) = publishLibraryAndSyncFreshDevice()
        val key = epubManifestKey(SyncCorpus.NOVEL_SYNC_ID)
        val good = manifest(key)
        putManifest(key, good.copy(contentSha256 = null))
        val rootB = b.book(SyncCorpus.NOVEL_SYNC_ID).root
        val filesBefore = b.payloadFiles(rootB)

        assertClean(b.sync())

        assertEquals("content hash published from the verified archive", good.contentSha256, manifest(key).contentSha256)
        assertEquals(filesBefore.keys, b.payloadFiles(rootB).keys)
        assertClean(a.sync())
        assertEquals(good.contentSha256, a.codec.cachedPayloadSha(a.book(SyncCorpus.NOVEL_SYNC_ID).root))
    }

    @Test
    fun deletingABookPropagatesAsATombstone() = runBlocking {
        val (a, b) = publishLibraryAndSyncFreshDevice()
        val rootA = a.book(SyncCorpus.MANGA_SYNC_ID).root

        a.repo.deleteBook(rootA)
        a.autoPush.onBookDeleted(SyncCorpus.MANGA_SYNC_ID, SyncCorpus.MANGA_TITLE, HttpSyncContentType.Mokuro, Instant.now().toString())
        a.awaitPushes()
        assertClean(a.sync())

        assertClean(b.sync())
        assertNull("deleted on A, gone on B", b.bookOrNull(SyncCorpus.MANGA_SYNC_ID))
        assertNotNull("the other book is untouched", b.bookOrNull(SyncCorpus.NOVEL_SYNC_ID))
        assertClean(b.sync())
        assertNull("a tombstone does not resurrect the book", a.bookOrNull(SyncCorpus.MANGA_SYNC_ID))
    }

    @Test
    fun largePayloadsGoThroughMultipartUploadAndArriveIntact() = runBlocking {
        val threshold = 64L * 1024L
        val a = device("A-big", engineA, multipartBytes = threshold)
        val manga = SyncCorpus.importManga(a.repo, title = "Integration Big Manga", pageCount = 3, extraBytesPerPage = 200_000)
        server.clearRequests()
        val push = a.sync()
        assertClean(push)
        assertEquals(1, push.uploadedPayloads)
        val requests = server.requests()
        assertTrue("multipart start used", requests.any { it.method == "POST" && it.path == "/v1/kv-multipart/start" })
        assertTrue("several parts uploaded", requests.count { it.method == "PUT" && it.path.startsWith("/v1/kv-multipart/") } >= 3)

        val b = device("B-big", engineB, multipartBytes = threshold)
        val pull = b.sync()
        assertClean(pull)
        assertEquals(1, pull.downloadedPayloads)
        assertSamePayload(a, manga, b, b.books().single().root)
    }

    private val PRETRANSLATIONS = """{"kind":"mokuro","syncId":"${SyncCorpus.MANGA_SYNC_ID}","entries":{"p0b0":{"text":"${SyncCorpus.BUBBLE_TEXT}","translation":"Hello"}}}"""
}
