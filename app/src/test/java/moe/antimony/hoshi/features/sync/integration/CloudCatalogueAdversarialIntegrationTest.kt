package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.reader.loadReadingStatisticsOverview
import moe.antimony.hoshi.features.sync.http.BOOKS_MAP_KEY
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import moe.antimony.hoshi.features.sync.http.HttpSyncKvFileFetched
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadManifest
import moe.antimony.hoshi.features.sync.http.payloadManifestKey
import moe.antimony.hoshi.features.sync.http.payloadZipKey
import org.junit.After
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
import java.io.File

/**
 * Adversarial cross-device scenarios for the cloud manga catalogue over REAL HTTP: engine
 * rollback, cancellation mid-download, process death and mixed catalogue/no-catalogue devices.
 */
class CloudCatalogueAdversarialIntegrationTest {
    companion object {
        @JvmField @ClassRule val serverRule = SyncTestServerRule()
    }

    @get:Rule val temp = TemporaryFolder()

    private val server get() = serverRule.server
    private val json = Json { ignoreUnknownKeys = true }
    private val devices = mutableListOf<SyncDevice>()

    @Before
    fun resetServer() = server.reset()

    @After
    fun closeDevices() {
        devices.forEach { it.close() }
        devices.clear()
    }

    private fun device(
        name: String,
        engine: SyncEngine = SyncEngine.V3,
        folder: File = temp.newFolder(name),
        installationId: String = "install-$name",
        cloudCatalogue: Boolean = true,
        identity: DeviceIdentity? = null,
        decorate: (HttpSyncKvTransport) -> HttpSyncKvTransport = { it },
    ) = SyncDevice(
        name, engine, folder, server,
        installationId = installationId,
        deviceIdentity = identity,
        cloudCatalogue = cloudCatalogue,
        decorateTransport = decorate,
    ).also { devices += it }

    private fun assertClean(outcome: SyncOutcome) =
        assertEquals("sync errors: ${outcome.errors}", emptyList<String>(), outcome.errors)

    private suspend fun booksMap(): Map<String, String> = json.decodeFromString(
        MapSerializer(String.serializer(), String.serializer()),
        server.client().get(BOOKS_MAP_KEY)!!.body.toString(Charsets.UTF_8),
    )

    /**
     * A device that listed a manga with v3 and then rolled back to the v2 engine (a supported
     * device-local setting) keeps the catalogue record forever: only V3Executor ever prunes it.
     * Once the manga is deleted elsewhere, the stale record keeps counting as "present" in the
     * five-second map comparison and keeps being re-published into sync/maps/books.json, so the
     * deleting device and the rolled-back device trade the map and both run a full reconcile on
     * every poll.
     */
    @Test
    fun aRolledBackV2DeviceDoesNotResurrectADeletedCloudMangaInTheBooksMap() = runBlocking {
        val a = device("A", cloudCatalogue = false)
        val manga = a.importManga()
        val mangaId = a.repo.loadMetadata(manga)!!.syncId!!
        a.importNovel()
        assertClean(a.sync())

        val bFolder = temp.newFolder("B")
        val b = device("B", folder = bFolder)
        assertClean(b.sync())
        assertEquals(listOf(mangaId), b.cloudBooks!!.records().map { it.syncId })
        b.close()

        // The manga is deleted on A before B (now on the v2 rollback engine) syncs again.
        a.deleteBook(a.book(mangaId))
        assertClean(a.sync())
        assertFalse(mangaId in booksMap())

        val bV2 = device("Bv2", engine = SyncEngine.V2, folder = bFolder, installationId = "install-B")
        // Each round is what both devices' five-second polls do: compare, reconcile, publish.
        val trace = mutableListOf<String>()
        repeat(3) { round ->
            val bChanged = bV2.batchState.syncMaps(server.client()).booksChanged
            bV2.sync()
            val inMapAfterB = mangaId in booksMap()
            val aChanged = a.batchState.syncMaps(server.client()).booksChanged
            a.sync()
            trace += "round $round: B saw change=$bChanged, map has deleted manga after B=$inMapAfterB, " +
                "A saw change=$aChanged, after A=${mangaId in booksMap()}"
        }
        bV2.sync()

        assertFalse("a deleted manga must not come back into the books map\n" + trace.joinToString("\n"), mangaId in booksMap())
        assertFalse("A's poll must settle", a.batchState.syncMaps(server.client()).booksChanged)
        assertFalse("B's poll must settle", bV2.batchState.syncMaps(server.client()).booksChanged)
    }

    /**
     * Android stops the transfer job mid-download (the sync coroutine is cancelled). The tap must
     * survive that, and survive process death (a fresh store on the same directory), and the
     * next sync must finish the download with the reading position.
     */
    @Test
    fun aDownloadCancelledMidTransferResumesAfterProcessDeath() = runBlocking {
        val a = device("A", cloudCatalogue = false)
        val manga = a.importManga(pageCount = 3, extraBytesPerPage = 300_000)
        val mangaId = a.repo.loadMetadata(manga)!!.syncId!!
        assertClean(a.sync())
        a.turnPage(manga, SyncCorpus.MANGA_TITLE, SyncCorpus.bookmark(chapter = 2, appleSeconds = 800_000_100.0))

        val started = CompletableDeferred<Unit>()
        var hang = true
        val bFolder = temp.newFolder("B")
        val b = device("B", folder = bFolder, decorate = { inner ->
            object : HttpSyncKvTransport by inner {
                override suspend fun downloadToFile(
                    key: String,
                    targetFile: File,
                    onByteProgress: ((Long, Long) -> Unit)?,
                ): HttpSyncKvFileFetched? {
                    if (hang && key == payloadZipKey(mangaId)) {
                        onByteProgress?.invoke(10, 100)
                        started.complete(Unit)
                        awaitCancellation()
                    }
                    return inner.downloadToFile(key, targetFile, onByteProgress)
                }
            }
        })
        assertClean(b.sync())
        assertTrue(b.cloudBooks!!.requestDownload(mangaId))

        val job = launch(Dispatchers.IO) { b.sync() }
        withTimeout(30_000) { started.await() }
        assertEquals(mapOf(mangaId to 0.1f), b.cloudBooks!!.downloads.value)
        // As HttpSyncManualSync does on a stop (onCancelled -> cancelActive): the app-scope flight
        // is cancelled, not just the caller waiting on it.
        b.close()
        job.join()
        b.awaitPushes()

        assertNull(b.bookOrNull(mangaId))
        assertTrue("no ring keeps spinning after the stop", b.cloudBooks!!.downloads.value.isEmpty())
        assertNotNull("the tap survives the stop", b.cloudBooks!!.records().single().downloadRequestedAt)
        hang = false

        // Process death: a new store, generations reset, same directory.
        val restarted = device("B2", folder = bFolder, installationId = "install-B")
        assertTrue(restarted.cloudBooks!!.hasRequestedDownloads())
        // An earlier process's tap waits for a plan, which the next sync makes.
        assertTrue(restarted.cloudBooks!!.hasUnplannedRequests())
        assertClean(restarted.sync())
        assertFalse(restarted.cloudBooks!!.hasUnplannedRequests())
        val installed = restarted.book(mangaId)
        assertEquals(2, restarted.repo.loadBookmark(installed.root)!!.chapterIndex)
        assertTrue(restarted.cloudBooks!!.records().isEmpty())
        assertFalse(restarted.batchState.syncMaps(server.client()).booksChanged)
        assertFalse(a.batchState.syncMaps(server.client()).booksChanged)
    }

    /**
     * Uploader A, catalogue device B and an old-behaviour device C (no catalogue: downloads
     * everything) all poll the books map. Nobody may keep seeing a change, including after A
     * replaces the manga's content.
     */
    @Test
    fun catalogueAndNonCatalogueDevicesAgreeOnTheBooksMap() = runBlocking {
        val a = device("A", cloudCatalogue = false)
        val manga = a.importManga()
        val mangaId = a.repo.loadMetadata(manga)!!.syncId!!
        assertClean(a.sync())
        val b = device("B")
        val c = device("C", cloudCatalogue = false)
        val d = device("D", engine = SyncEngine.V2, cloudCatalogue = false)
        repeat(2) {
            assertClean(b.sync())
            assertClean(c.sync())
            assertClean(d.sync())
            assertClean(a.sync())
        }
        assertEquals(listOf(mangaId), b.cloudBooks!!.records().map { it.syncId })
        assertNotNull(c.bookOrNull(mangaId))
        assertNotNull(d.bookOrNull(mangaId))
        for (device in listOf(a, b, c, d)) {
            assertFalse("${device.name} keeps seeing a books change", device.batchState.syncMaps(server.client()).booksChanged)
        }
        val before = booksMap()

        // B taps and downloads; the map value stays what the uploader published.
        b.cloudBooks!!.requestDownload(mangaId)
        assertClean(b.sync())
        assertNotNull(b.bookOrNull(mangaId))
        assertEquals(before, booksMap())
        for (device in listOf(a, b, c, d)) {
            assertFalse("${device.name} keeps seeing a books change", device.batchState.syncMaps(server.client()).booksChanged)
        }
    }

    /** A request for a manga deleted remotely before the pass ends quietly, with no ghost. */
    @Test
    fun aRequestForAMangaDeletedRemotelyIsDropped() = runBlocking {
        val a = device("A", cloudCatalogue = false)
        val manga = a.importManga()
        val mangaId = a.repo.loadMetadata(manga)!!.syncId!!
        a.importNovel()
        assertClean(a.sync())
        val b = device("B")
        assertClean(b.sync())
        assertTrue(b.cloudBooks!!.requestDownload(mangaId))
        a.deleteBook(a.book(mangaId))
        assertClean(a.sync())

        assertClean(b.sync())

        assertNull(b.bookOrNull(mangaId))
        assertTrue(b.cloudBooks!!.records().isEmpty())
        assertFalse(b.cloudBooks!!.hasRequestedDownloads())
        assertFalse(mangaId in booksMap())
        assertFalse(a.batchState.syncMaps(server.client()).booksChanged)
        assertFalse(b.batchState.syncMaps(server.client()).booksChanged)
    }

    /**
     * A tapped manga must arrive with exactly what the old auto-download brought: position,
     * chat, offline translations, shelf and reading history (the history folder kept while it
     * was not downloaded is absorbed, never double counted).
     */
    @Test
    fun aTappedMangaArrivesWithTheSameStateAsTheOldAutoDownload() = runBlocking {
        val today = "2026-10-03"
        val a = device("A", cloudCatalogue = false, identity = DeviceIdentity("tablet", "Tablet"))
        val library = a.publishCorpusLibrary(server)
        a.repo.saveStatistics(library.manga, listOf(ReadingStatistics(
            title = SyncCorpus.MANGA_TITLE, dateKey = today, readingTime = 900.0, charactersRead = 12,
            lastStatisticModified = 1, deviceId = "tablet", deviceName = "Tablet",
            readingTimeByHour = mapOf("${today}T20:00" to 900.0),
        )))
        a.repo.saveMangaTextStatistics(library.manga, listOf(MangaTextStatistic(today, 1_234, 1, "tablet", "Tablet")))
        a.placeOnShelf(a.book(SyncCorpus.MANGA_SYNC_ID), "Reading")
        assertClean(a.sync())

        val b = device("B", identity = DeviceIdentity("phone", "Phone"))
        val c = device("C", cloudCatalogue = false, identity = DeviceIdentity("laptop", "Laptop"))
        assertClean(b.sync())
        assertClean(c.sync())
        assertNull(b.bookOrNull(SyncCorpus.MANGA_SYNC_ID))
        val beforeTap = loadReadingStatisticsOverview(b.repo, today, 0)

        assertTrue(b.cloudBooks!!.requestDownload(SyncCorpus.MANGA_SYNC_ID))
        assertClean(b.sync())
        b.poll()
        c.poll()
        assertClean(b.sync())
        assertClean(c.sync())

        val mangaB = b.book(SyncCorpus.MANGA_SYNC_ID)
        val mangaC = c.book(SyncCorpus.MANGA_SYNC_ID)
        assertEquals(c.repo.loadBookmark(mangaC.root), b.repo.loadBookmark(mangaB.root))
        assertEquals(2, b.repo.loadBookmark(mangaB.root)!!.chapterIndex)
        assertEquals(listOf("Hello"), b.history.load(mangaB.root).entries.map { it.response })
        assertEquals(SyncCorpus.pretranslationsBlobJson(), mangaB.root.resolve("pretranslations.json").readText())
        assertEquals("Reading", b.shelfNameOf(SyncCorpus.MANGA_SYNC_ID))
        assertEquals(c.shelfNameOf(SyncCorpus.MANGA_SYNC_ID), b.shelfNameOf(SyncCorpus.MANGA_SYNC_ID))
        val overviewB = loadReadingStatisticsOverview(b.repo, today, 0)
        val overviewC = loadReadingStatisticsOverview(c.repo, today, 0)
        assertEquals("history shown before the tap", 900.0, beforeTap.todaySeconds, 0.0)
        assertEquals(overviewC.todaySeconds, overviewB.todaySeconds, 0.0)
        assertEquals(overviewC.totalSeconds, overviewB.totalSeconds, 0.0)
        assertEquals(overviewC.todayCharacters, overviewB.todayCharacters)
        assertEquals(1_234, overviewB.todayCharacters)
        assertEquals(
            "the history kept while it was not downloaded is absorbed",
            emptyList<String>(),
            b.repo.loadStatisticsHistory().map { it.syncId }.filter { it == SyncCorpus.MANGA_SYNC_ID },
        )
        for (device in listOf(a, b, c)) {
            assertFalse("${device.name} keeps seeing a books change", device.batchState.syncMaps(server.client()).booksChanged)
        }
    }

    /** HoshiAppContainer's manual sync: one pass, then more (at most 4) while taps are unplanned. */
    private suspend fun manualSync(device: SyncDevice): Int {
        device.sync()
        var passes = 1
        while (passes < 4 && device.cloudBooks!!.hasUnplannedRequests()) {
            device.sync()
            passes += 1
        }
        return passes
    }

    /**
     * A tap that lands after the plan was taken for a manga the same pass then installs (the
     * tile shows "Available" while a background pass started from a kept request is still
     * reading covers) bumps the in-memory request generation. The book leaves the catalogue, so
     * no later pass is forced and nothing calls takeRequestsForPlan again: the generation stays
     * "unplanned", and every later "Sync now" in this process runs the maximum four passes.
     */
    @Test
    fun aTapAnsweredByTheRunningPassDoesNotMakeEveryLaterSyncRunFourPasses() = runBlocking {
        val a = device("A", cloudCatalogue = false)
        val manga = a.importManga()
        val mangaId = a.repo.loadMetadata(manga)!!.syncId!!
        assertClean(a.sync())
        lateinit var store: moe.antimony.hoshi.features.sync.http.HttpSyncCloudBooks
        val b = device("B", decorate = { inner ->
            object : HttpSyncKvTransport by inner {
                override suspend fun downloadToFile(
                    key: String,
                    targetFile: File,
                    onByteProgress: ((Long, Long) -> Unit)?,
                ): HttpSyncKvFileFetched? {
                    // The second tap, after this pass planned the first one.
                    if (key == payloadZipKey(mangaId)) store.requestDownload(mangaId)
                    return inner.downloadToFile(key, targetFile, onByteProgress)
                }
            }
        })
        store = b.cloudBooks!!
        assertClean(b.sync())
        assertTrue(store.requestDownload(mangaId))

        val first = manualSync(b)
        assertNotNull(b.bookOrNull(mangaId))
        assertTrue(store.records().isEmpty())

        server.clearRequests()
        val later = manualSync(b)
        val listings = server.requests().count { it.isListing }
        assertEquals(
            "a later sync with nothing tapped is one pass (first sync ran $first passes, later one listed $listings times)",
            1,
            later,
        )
    }

    /**
     * An older uploader's manifest without contentSha256, and a books map that does not list
     * the manga yet (the uploader's map publication was skipped): the catalogue device seeds the
     * map with a value only it derives. Every device must still settle within a few polls.
     */
    @Test
    fun aManifestWithoutAContentHashStillSettlesTheBooksMap() = runBlocking {
        val a = device("A", cloudCatalogue = false)
        val manga = a.importManga()
        val mangaId = a.repo.loadMetadata(manga)!!.syncId!!
        a.importNovel()
        assertClean(a.sync())
        val manifestJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val manifest = manifestJson.decodeFromString(
            HttpSyncPayloadManifest.serializer(),
            server.client().get(payloadManifestKey(mangaId))!!.body.toString(Charsets.UTF_8),
        )
        server.client().put(
            payloadManifestKey(mangaId), "application/json",
            manifestJson.encodeToString(HttpSyncPayloadManifest.serializer(), manifest.copy(contentSha256 = null)).toByteArray(),
        )
        server.client().put(
            BOOKS_MAP_KEY, "application/json",
            json.encodeToString(MapSerializer(String.serializer(), String.serializer()), booksMap() - mangaId).toByteArray(),
        )
        val b1 = device("B1")
        val b2 = device("B2")
        val trace = mutableListOf<String>()
        repeat(4) { round ->
            for (device in listOf(b1, b2, a)) {
                device.sync()
                trace += "round $round ${device.name}: map value=${booksMap()[mangaId]}"
            }
        }
        val changed = listOf(a, b1, b2).filter { it.batchState.syncMaps(server.client()).booksChanged }.map { it.name }
        assertEquals("devices still seeing a books change:\n" + trace.joinToString("\n"), emptyList<String>(), changed)
        assertEquals(listOf(mangaId), b1.cloudBooks!!.records().map { it.syncId })
        assertEquals(listOf(mangaId), b2.cloudBooks!!.records().map { it.syncId })
    }

    /**
     * A transient failure reading a changed manifest makes that pass see the manga as
     * "pending remote-only". The catalogue must not forget the user's tap (or the cover, or
     * count the book as new again) because one GET failed.
     */
    @Test
    fun aTransientManifestReadFailureDoesNotDropATappedManga() = runBlocking {
        val a = device("A", cloudCatalogue = false)
        val manga = a.importManga()
        val mangaId = a.repo.loadMetadata(manga)!!.syncId!!
        a.importNovel()
        assertClean(a.sync())
        val b = device("B")
        assertClean(b.sync())
        val cover = b.cloudBooks!!.coverFile(b.cloudBooks!!.records().single())
        assertNotNull(cover)
        assertTrue(b.cloudBooks!!.requestDownload(mangaId))

        // The manifest's bytes change (same content, new ETag), and its next read fails once.
        val manifestBody = server.client().get(payloadManifestKey(mangaId))!!.body.toString(Charsets.UTF_8)
        server.client().put(payloadManifestKey(mangaId), "application/json", ("$manifestBody ").toByteArray())
        server.failNext("/v1/kv/${payloadManifestKey(mangaId)}", status = 503, count = 1, method = "GET")
        val failed = b.sync()
        assertTrue("the failed read is reported", failed.errors.isNotEmpty())

        val record = b.cloudBooks!!.records().singleOrNull { it.syncId == mangaId }
        assertNotNull("one failed GET must not drop the manga from the catalogue", record)
        assertNotNull("...nor the user's tap", record!!.downloadRequestedAt)
    }
}
