package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.PretranslationStore
import moe.antimony.hoshi.features.sync.http.BOOKMARKS_MAP_PREFIX
import moe.antimony.hoshi.features.sync.http.HttpSyncBookmarkBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncBookmarkMapEntry
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadManifest
import moe.antimony.hoshi.features.sync.http.bookmarkKey
import moe.antimony.hoshi.features.sync.http.epubManifestKey
import moe.antimony.hoshi.features.sync.http.epubZipKey
import moe.antimony.hoshi.features.sync.http.metadataKey
import moe.antimony.hoshi.features.sync.http.payloadManifestKey
import moe.antimony.hoshi.features.sync.http.payloadZipKey
import org.junit.After
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

/**
 * End-to-end sync between simulated installs through a REAL server over REAL HTTP, using the
 * production "Sync now" path, engines, payload codec, KV client, bookmark-map exchange and
 * bookshelf hooks. Nothing on the server is placed by hand except the blobs a desktop tool
 * produces in production (pre-translations, sentences), the deliberately corrupted manifest
 * that reproduces the 0.11.3 incident, and injected server faults.
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

        private val bookmarkShardSerializer = MapSerializer(String.serializer(), HttpSyncBookmarkMapEntry.serializer())
    }

    @get:Rule val temp = TemporaryFolder()

    private val server get() = serverRule.server
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val devices = mutableListOf<SyncDevice>()

    @Before
    fun resetServer() {
        server.reset()
    }

    @After
    fun closeDevices() {
        devices.forEach { it.close() }
        devices.clear()
    }

    private fun device(name: String, engine: SyncEngine, multipartBytes: Long = 64L * 1024L * 1024L): SyncDevice =
        SyncDevice(name, engine, temp.newFolder(name), server, multipartBytes, multipartBytes).also { devices += it }

    private fun assertClean(outcome: SyncOutcome) {
        assertEquals("sync errors: ${outcome.errors}", emptyList<String>(), outcome.errors)
    }

    private suspend fun manifest(key: String): HttpSyncPayloadManifest =
        json.decodeFromString(HttpSyncPayloadManifest.serializer(), server.client().get(key)!!.body.toString(Charsets.UTF_8))

    private suspend fun putManifest(key: String, manifest: HttpSyncPayloadManifest) {
        server.client().put(key, "application/json; charset=utf-8", json.encodeToString(HttpSyncPayloadManifest.serializer(), manifest).toByteArray())
    }

    private suspend fun bookmarkShard(key: String): Map<String, HttpSyncBookmarkMapEntry> =
        json.decodeFromString(bookmarkShardSerializer, server.client().get(key)!!.body.toString(Charsets.UTF_8))

    private fun assertSamePayload(origin: SyncDevice, originRoot: File, receiver: SyncDevice, receiverRoot: File) {
        assertSameFiles(origin.payloadFiles(originRoot), receiver.payloadFiles(receiverRoot))
        assertEquals(
            "cross-platform content hash",
            origin.codec.computePayloadContentSha(originRoot),
            receiver.codec.computePayloadContentSha(receiverRoot),
        )
    }

    private fun assertSameFiles(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals("payload file set", expected.keys, actual.keys)
        for ((path, bytes) in expected) assertArrayEquals("bytes of $path", bytes, actual.getValue(path))
    }

    /** No staging directory may survive a failed import next to the books. */
    private fun assertNoImportLeftovers(device: SyncDevice) {
        val parent = device.repo.booksDirectory.parentFile!!
        val leftovers = parent.listFiles().orEmpty().filter { it.name.startsWith(".http-sync-import-") } +
            device.repo.booksDirectory.listFiles().orEmpty().filter { it.name.startsWith(".hoshi-sync-backup-") }
        assertEquals("import leftovers", emptyList<File>(), leftovers)
    }

    /** Publishes A's library, then a brand-new install B syncs from nothing. */
    private suspend fun publishLibraryAndSyncFreshDevice(): Pair<SyncDevice, SyncDevice> {
        val a = device("A", engineA)
        a.publishCorpusLibrary(server)
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
        assertEquals(SyncCorpus.pretranslationsBlobJson(), mangaB.root.resolve("pretranslations.json").readText())
        assertEquals("Hello", PretranslationStore.lookup(mangaB.root, "p0b0", SyncCorpus.BUBBLE_TEXT)?.translation)
        val novelB = b.book(SyncCorpus.NOVEL_SYNC_ID)
        assertTrue("sentence translations landed", novelB.root.resolve("sentence_translations.json").readText().contains("To eat."))
        assertTrue("EPUB progress is derivable before first open", (b.repo.loadBookInfo(novelB.root)?.characterCount ?: 0) > 0)
    }

    @Test
    fun resyncOnConvergedDevicesIsOneListingAndLeavesTheServerUntouched() = runBlocking {
        val (a, b) = publishLibraryAndSyncFreshDevice()
        // One more round lets each device see the other's freshly published bookmark shard.
        assertClean(a.sync())
        assertClean(b.sync())
        val before = server.etags()

        for (device in listOf(a, b, a)) {
            server.clearRequests()
            assertClean(device.sync())
            val requests = server.requests()
            assertEquals(
                "a clean sync on ${device.name} is exactly one metadata listing (docs/HTTP_SYNC_KV.md, request budget): $requests",
                listOf(true),
                requests.map { it.isListing },
            )
        }
        val after = server.etags()
        for (key in before.keys.filter {
            it.endsWith(".zip") || it.endsWith(".manifest") || it.endsWith("/bookmark") || it.endsWith("/metadata")
        }) {
            assertEquals("etag of $key unchanged", before[key], after[key])
        }
        assertEquals("no key appeared or disappeared", before.keys, after.keys)
    }

    @Test
    fun bookmarksRoundTripInBothDirectionsWithNewestWinning() = runBlocking {
        val (a, b) = publishLibraryAndSyncFreshDevice()
        val rootA = a.book(SyncCorpus.MANGA_SYNC_ID).root
        val rootB = b.book(SyncCorpus.MANGA_SYNC_ID).root

        // A marks progress outside the reader ("Mark read"): the direct-key push, no full sync.
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
    fun pageTurnedThroughTheBookmarkMapReachesAFreshInstallInOneSync() = runBlocking {
        val (a, _) = publishLibraryAndSyncFreshDevice()
        val rootA = a.book(SyncCorpus.MANGA_SYNC_ID).root
        val shardsBefore = server.etags().filterKeys { it.startsWith(BOOKMARKS_MAP_PREFIX) }
        assertTrue("both installs published a shard", shardsBefore.size >= 2)

        // A reads on: the reader path, i.e. the durable outbox + the five-second map exchange,
        // never the legacy per-book key.
        a.turnPage(rootA, SyncCorpus.MANGA_TITLE, SyncCorpus.bookmark(chapter = 7, appleSeconds = 800_000_500.0))

        val shardsAfter = server.etags().filterKeys { it.startsWith(BOOKMARKS_MAP_PREFIX) }
        val changed = shardsAfter.filter { (key, etag) -> shardsBefore[key] != etag }.keys
        assertEquals("exactly A's own shard was rewritten", 1, changed.size)
        assertEquals("A's shard carries the new position", 7, bookmarkShard(changed.single())[SyncCorpus.MANGA_SYNC_ID]?.value?.chapterIndex)
        assertEquals("the legacy per-book key is not dual-written", 2, json.decodeFromString(
            HttpSyncBookmarkBlob.serializer(),
            server.client().get(bookmarkKey(SyncCorpus.MANGA_SYNC_ID))!!.body.toString(Charsets.UTF_8),
        ).chapterIndex)

        // A brand-new install must land on that position after a single "Sync now".
        val c = device("C", engineB)
        val fresh = c.sync()
        assertClean(fresh)
        assertEquals(7, c.repo.loadBookmark(c.book(SyncCorpus.MANGA_SYNC_ID).root)!!.chapterIndex)

        // …and the reader-open gate has nothing newer to add.
        c.beforeOpen()
        assertEquals(7, c.repo.loadBookmark(c.book(SyncCorpus.MANGA_SYNC_ID).root)!!.chapterIndex)
        // A device that already had the book converges on its next sync too.
        assertClean(a.sync())
        assertEquals(7, a.repo.loadBookmark(rootA)!!.chapterIndex)
    }

    @Test
    fun pageTurnsOnTwoDevicesAtOnceBothSurviveAndReachEveryone() = runBlocking {
        val (a, b) = publishLibraryAndSyncFreshDevice()
        val mangaA = a.book(SyncCorpus.MANGA_SYNC_ID).root
        val novelB = b.book(SyncCorpus.NOVEL_SYNC_ID).root

        // Each install writes only its own shard, so neither page turn can erase the other's.
        a.turnPage(mangaA, SyncCorpus.MANGA_TITLE, SyncCorpus.bookmark(chapter = 4, appleSeconds = 800_000_600.0))
        b.turnPage(novelB, SyncCorpus.NOVEL_TITLE, SyncCorpus.bookmark(chapter = 3, appleSeconds = 800_000_600.0))

        val c = device("C", engineA)
        assertClean(c.sync())
        assertEquals(4, c.repo.loadBookmark(c.book(SyncCorpus.MANGA_SYNC_ID).root)!!.chapterIndex)
        assertEquals(3, c.repo.loadBookmark(c.book(SyncCorpus.NOVEL_SYNC_ID).root)!!.chapterIndex)
        assertClean(a.sync())
        assertEquals(3, a.repo.loadBookmark(a.book(SyncCorpus.NOVEL_SYNC_ID).root)!!.chapterIndex)
        assertClean(b.sync())
        assertEquals(4, b.repo.loadBookmark(b.book(SyncCorpus.MANGA_SYNC_ID).root)!!.chapterIndex)
    }

    @Test
    fun shelfPlacementRoundTripsInBothDirections() = runBlocking {
        val (a, b) = publishLibraryAndSyncFreshDevice()

        a.placeOnShelf(a.book(SyncCorpus.NOVEL_SYNC_ID), "Favorites")
        assertEquals("Favorites", a.shelfNameOf(SyncCorpus.NOVEL_SYNC_ID))
        assertClean(b.sync())
        assertEquals("the move reaches the other device", "Favorites", b.shelfNameOf(SyncCorpus.NOVEL_SYNC_ID))
        assertNull("the manga stays where it was", b.shelfNameOf(SyncCorpus.MANGA_SYNC_ID))

        b.placeOnShelf(b.book(SyncCorpus.NOVEL_SYNC_ID), null)
        assertClean(a.sync())
        assertNull("unshelving on B reaches A", a.shelfNameOf(SyncCorpus.NOVEL_SYNC_ID))
    }

    @Test
    fun chatSavedOnTheOtherDeviceComesBack() = runBlocking {
        val (a, b) = publishLibraryAndSyncFreshDevice()
        val mangaB = b.book(SyncCorpus.MANGA_SYNC_ID)
        val reply = AiChatEntry("二つ目", "prompt", "model", "Hi from B", 800_000_700.0)

        b.authorChat(mangaB, reply)
        val pulled = a.sync()
        assertClean(pulled)
        assertEquals(1, pulled.downloadedChatEntries)
        val responses = a.history.load(a.book(SyncCorpus.MANGA_SYNC_ID).root).entries.map { it.response }
        assertEquals(listOf("Hello", "Hi from B"), responses)
        // Neither device grows duplicates on later syncs.
        assertClean(b.sync())
        assertClean(a.sync())
        assertEquals(2, b.history.load(mangaB.root).entries.size)
        assertEquals(2, a.history.load(a.book(SyncCorpus.MANGA_SYNC_ID).root).entries.size)
    }

    @Test
    fun reimportedBookReplacesTheRemotePayloadAndOtherDevicesKeepTheirSidecars() = runBlocking {
        val (a, b) = publishLibraryAndSyncFreshDevice()
        val rootA = a.book(SyncCorpus.NOVEL_SYNC_ID).root
        val rootB = b.book(SyncCorpus.NOVEL_SYNC_ID).root
        val zipBefore = server.etags().getValue(epubZipKey(SyncCorpus.NOVEL_SYNC_ID))
        b.repo.saveBookmark(rootB, SyncCorpus.bookmark(chapter = 1, appleSeconds = 800_000_300.0))
        assertClean(b.sync())

        // A re-imports a corrected EPUB. This is the replacement half of
        // BookRepository.importEpub (swap the static files, mark the payload dirty); the
        // reader-lock gate around it is covered by BookRepository's own tests.
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
        server.clearRequests()

        assertClean(b.sync())

        assertEquals("content hash published from the verified archive", good.contentSha256, manifest(key).contentSha256)
        assertEquals("the archive itself is untouched", good.sha256, manifest(key).sha256)
        assertSameFiles(filesBefore, b.payloadFiles(rootB))
        assertEquals("the upgrade verifies the archive once, then keeps the local copy", 1, server.requests().count { it.isPayloadDownload })
        assertEquals(good.contentSha256, b.codec.cachedPayloadSha(rootB))
        server.clearRequests()
        assertClean(a.sync())
        assertEquals("a device with a matching baseline does not download at all", 0, server.requests().count { it.isPayloadDownload })
        assertEquals(good.contentSha256, a.codec.cachedPayloadSha(a.book(SyncCorpus.NOVEL_SYNC_ID).root))
    }

    @Test
    fun aForeignContentHashNeverRedownloadsAnArchiveThisDeviceAlreadyHolds() = runBlocking {
        // Another platform hashing the same bytes differently must not make devices chase the
        // archive they already hold: the archive sha256 says nothing changed, so nothing moves.
        val (a, b) = publishLibraryAndSyncFreshDevice()
        val key = payloadManifestKey(SyncCorpus.MANGA_SYNC_ID)
        val foreign = manifest(key).copy(contentSha256 = "sha256:" + "d".repeat(64))
        putManifest(key, foreign)
        server.clearRequests()

        assertClean(a.sync())
        assertClean(b.sync())
        assertClean(b.sync())

        assertEquals(emptyList<RecordedRequest>(), server.requests().filter { it.isPayloadDownload })
        assertEquals("no device rewrote the other platform's note", foreign.contentSha256, manifest(key).contentSha256)
        assertSamePayload(a, a.book(SyncCorpus.MANGA_SYNC_ID).root, b, b.book(SyncCorpus.MANGA_SYNC_ID).root)
    }

    @Test
    fun deletingABookPropagatesAsATombstone() = runBlocking {
        val (a, b) = publishLibraryAndSyncFreshDevice()

        a.deleteBook(a.book(SyncCorpus.MANGA_SYNC_ID))
        assertClean(a.sync())

        assertClean(b.sync())
        assertNull("deleted on A, gone on B", b.bookOrNull(SyncCorpus.MANGA_SYNC_ID))
        assertNotNull("the other book is untouched", b.bookOrNull(SyncCorpus.NOVEL_SYNC_ID))
        assertClean(b.sync())
        assertClean(a.sync())
        assertNull("the server copy does not resurrect the book on A", a.bookOrNull(SyncCorpus.MANGA_SYNC_ID))
        assertNull("nor on B", b.bookOrNull(SyncCorpus.MANGA_SYNC_ID))

        val d = device("D", engineA)
        val fresh = d.sync()
        assertClean(fresh)
        assertEquals("a new install imports only the living book", listOf(SyncCorpus.NOVEL_SYNC_ID), d.books().map { it.metadata.syncId })
    }

    @Test
    fun largePayloadsGoThroughMultipartUploadAndArriveIntact() = runBlocking {
        val threshold = 64L * 1024L
        val a = device("A-big", engineA, multipartBytes = threshold)
        val manga = a.importManga(title = "Integration Big Manga", pageCount = 3, extraBytesPerPage = 200_000)
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

    @Test
    fun failedArchiveDownloadLeavesNothingBehindAndTheNextSyncRecovers() = runBlocking {
        val (a, _) = publishLibraryAndSyncFreshDevice()
        val zipPath = "/v1/kv/" + payloadZipKey(SyncCorpus.MANGA_SYNC_ID)
        server.failNext(zipPath, status = 500, count = 1, method = "GET")

        val c = device("C", engineB)
        val broken = c.sync()
        assertTrue("the failure is reported: ${broken.errors}", broken.errors.any { SyncCorpus.MANGA_SYNC_ID in it })
        assertNull("no half-imported book", c.bookOrNull(SyncCorpus.MANGA_SYNC_ID))
        assertNotNull("the unaffected book still imported", c.bookOrNull(SyncCorpus.NOVEL_SYNC_ID))
        assertNoImportLeftovers(c)

        val recovered = c.sync()
        assertClean(recovered)
        assertEquals("the retry downloads the missing book", 1, recovered.downloadedPayloads)
        assertSamePayload(a, a.book(SyncCorpus.MANGA_SYNC_ID).root, c, c.book(SyncCorpus.MANGA_SYNC_ID).root)
        assertNoImportLeftovers(c)
    }
}
