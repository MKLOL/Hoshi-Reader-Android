package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.reader.loadReadingStatisticsOverview
import moe.antimony.hoshi.features.sync.http.BOOKS_MAP_KEY
import moe.antimony.hoshi.features.sync.http.HttpSyncDownloadSpool
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
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
 * How the cloud catalogue fits the rest of a library's life over REAL HTTP: upgrading from a
 * build that auto-downloaded every manga, deleting a manga that was downloaded from the cloud,
 * remote shelf moves, importing a listed manga by hand, the v2 rollback engine, and reading
 * history across download / delete / download again.
 */
class CloudMangaLifecycleIntegrationTest {
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

    private fun spooledArchives(filesDir: File): List<File> =
        filesDir.resolve(HttpSyncDownloadSpool.DIRECTORY).walkTopDown().filter { it.isFile && it.name.endsWith(".zip") }.toList()

    /**
     * Characterizes what "Delete" means for a manga that came from the "Not downloaded" folder.
     * It does not go back to the folder: it is tombstoned and the uploader's own copy (the
     * original import) is deleted too, while the archive stays on the server where no device
     * can reach it. This is the pre-catalogue delete semantics, unchanged, but it is now the
     * only way to free the space a tapped download took.
     */
    @Test
    fun deletingAMangaDownloadedFromTheCloudDeletesTheUploadersCopyToo() = runBlocking {
        val a = device("A", cloudCatalogue = false)
        val manga = a.importManga()
        val mangaId = a.repo.loadMetadata(manga)!!.syncId!!
        a.importNovel()
        assertClean(a.sync())
        val b = device("B")
        val c = device("C")
        assertClean(b.sync())
        assertClean(c.sync())
        assertEquals(listOf(mangaId), c.cloudBooks!!.records().map { it.syncId })

        assertTrue(b.cloudBooks!!.requestDownload(mangaId))
        assertClean(b.sync())
        val downloaded = b.book(mangaId)
        // The user reads it, then deletes the download to free space.
        b.deleteBook(downloaded)
        assertClean(b.sync())
        assertClean(a.sync())
        assertClean(c.sync())

        assertNull("the deleted download does not return to B's Not downloaded folder", b.cloudBooks!!.records().singleOrNull())
        assertNull("the uploader's original copy is deleted", a.bookOrNull(mangaId))
        assertTrue("C's listing is gone too", c.cloudBooks!!.records().isEmpty())
        assertNotNull("the archive stays on the server, unreachable", server.client().get(payloadZipKey(mangaId)))
        assertFalse(mangaId in booksMap())
    }

    /** A shelf move made on another device while the manga is listed is where it lands on download. */
    @Test
    fun aMangaMovedToAnotherShelfWhileListedArrivesOnThatShelf() = runBlocking {
        val a = device("A", cloudCatalogue = false)
        val manga = a.importManga()
        val mangaId = a.repo.loadMetadata(manga)!!.syncId!!
        assertClean(a.sync())
        val b = device("B")
        assertClean(b.sync())
        assertNull(b.cloudBooks!!.records().single().shelfName)

        a.placeOnShelf(a.book(mangaId), "Later")
        assertClean(a.sync())
        assertClean(b.sync())
        assertEquals("Later", b.cloudBooks!!.records().single().shelfName)

        assertTrue(b.cloudBooks!!.requestDownload(mangaId))
        assertClean(b.sync())
        assertEquals("Later", b.shelfNameOf(mangaId))
        assertEquals("Later", a.shelfNameOf(mangaId))
    }

    /**
     * The user imports by hand a manga the folder already lists: same title, so the same sync
     * id. It must end as one book, with nothing downloaded and the listing gone.
     */
    @Test
    fun importingAListedMangaByHandLeavesOneBookAndDownloadsNothing() = runBlocking {
        val a = device("A", cloudCatalogue = false)
        val manga = a.importManga()
        val mangaId = a.repo.loadMetadata(manga)!!.syncId!!
        assertClean(a.sync())
        val b = device("B")
        assertClean(b.sync())
        assertEquals(listOf(mangaId), b.cloudBooks!!.records().map { it.syncId })
        val mapBefore = booksMap()

        val local = b.importManga()
        assertEquals("same title, same sync id", mangaId, b.repo.loadMetadata(local)!!.syncId)
        val outcome = b.sync()

        assertClean(outcome)
        assertEquals(0, outcome.downloadedPayloads)
        assertEquals(listOf(local.canonicalPath), b.books().filter { it.metadata.syncId == mangaId }.map { it.root.canonicalPath })
        assertTrue(b.cloudBooks!!.records().isEmpty())
        assertNotNull(a.bookOrNull(mangaId))
        assertEquals(mapBefore, booksMap())
        assertFalse(a.batchState.syncMaps(server.client()).booksChanged)
        assertFalse(b.batchState.syncMaps(server.client()).booksChanged)
    }

    /**
     * Upgrading from a build that auto-downloaded every manga, in the middle of such a download:
     * the old build left a partial archive in the download spool. After the upgrade the manga is
     * listed instead of downloaded, the partial is kept, and a tap resumes from it.
     */
    @Test
    fun aMangaHalfDownloadedBeforeTheUpgradeIsListedAndResumesFromItsPartialArchive() = runBlocking {
        val a = device("A", cloudCatalogue = false)
        val manga = a.importManga(pageCount = 3, extraBytesPerPage = 100_000)
        val mangaId = a.repo.loadMetadata(manga)!!.syncId!!
        a.importNovel()
        assertClean(a.sync())
        val archiveSize = server.client().get(payloadZipKey(mangaId))!!.body.size.toLong()

        // 0.12.4: no catalogue, so sync downloads the manga; every attempt drops after 16 KiB.
        val filesDir = temp.newFolder("B")
        val old = device("Bold", folder = filesDir, installationId = "install-B", cloudCatalogue = false)
        server.downloadFault(payloadZipKey(mangaId), count = 3, disconnectAfter = 16 * 1024)
        val interrupted = old.sync()
        assertTrue(interrupted.errors.isNotEmpty())
        assertNull(old.bookOrNull(mangaId))
        val partial = spooledArchives(filesDir).single()
        assertEquals(48 * 1024L, partial.length())
        old.close()

        // The upgrade: same files, now with the catalogue.
        val b = device("B", folder = filesDir, installationId = "install-B")
        server.clearRequests()
        assertClean(b.sync())
        assertNull("the upgrade does not resume the old auto-download", b.bookOrNull(mangaId))
        assertEquals(listOf(mangaId), b.cloudBooks!!.records().map { it.syncId })
        assertTrue(server.requests().none { it.isPayloadDownload && it.range == null && it.path.endsWith(payloadZipKey(mangaId)) })
        assertEquals("the partial is kept for a tap", 48 * 1024L, spooledArchives(filesDir).single().length())

        assertTrue(b.cloudBooks!!.requestDownload(mangaId))
        server.clearRequests()
        assertClean(b.sync())
        assertNotNull(b.bookOrNull(mangaId))
        val downloads = server.requests().filter { it.isPayloadDownload && it.path.endsWith(payloadZipKey(mangaId)) && it.method == "GET" }
        assertEquals("only the missing bytes are fetched", archiveSize - 48 * 1024L, downloads.sumOf { it.responseBytes.toLong() })
        assertTrue(spooledArchives(filesDir).isEmpty())
    }

    /**
     * The v2 rollback engine (a device-local setting) keeps no catalogue and is documented to
     * download every manga. A device that listed a manga with v3 and then rolled back must
     * still get it: before the catalogue existed, the books-map difference made v2 download it.
     */
    @Test
    fun aDeviceRolledBackToV2StillDownloadsAMangaItHadListed() = runBlocking {
        val a = device("A", cloudCatalogue = false)
        val manga = a.importManga()
        val mangaId = a.repo.loadMetadata(manga)!!.syncId!!
        a.importNovel()
        assertClean(a.sync())

        val filesDir = temp.newFolder("B")
        val b = device("B", folder = filesDir, installationId = "install-B")
        assertClean(b.sync())
        assertEquals(listOf(mangaId), b.cloudBooks!!.records().map { it.syncId })
        b.close()

        // Same files, v2 engine; the app still hands the batch state its catalogue.
        val bV2 = device("Bv2", engine = SyncEngine.V2, folder = filesDir, installationId = "install-B")
        val changed = bV2.batchState.syncMaps(server.client()).booksChanged
        repeat(2) { assertClean(bV2.sync()) }

        assertNotNull(
            "v2 must download the listed manga (five-second check saw a change: $changed); " +
                "the shelf hides the Not downloaded folder under v2, so otherwise the manga is invisible",
            bV2.bookOrNull(mangaId),
        )
    }

    /**
     * Reading history through download, delete, re-import on the uploader and download again:
     * every device must show the same totals, with nothing counted twice or lost.
     */
    @Test
    fun readingHistorySurvivesDownloadDeleteAndDownloadAgainWithoutDoubleCounting() = runBlocking {
        val today = "2026-10-03"
        val a = device("A", cloudCatalogue = false, identity = DeviceIdentity("tablet", "Tablet"))
        val manga = a.importManga()
        val mangaId = a.repo.loadMetadata(manga)!!.syncId!!
        a.importNovel()
        a.repo.saveStatistics(manga, listOf(stat(today, 900.0, "tablet", "Tablet", 1)))
        assertClean(a.sync())
        a.pushReaderStatistics()

        val b = device("B", identity = DeviceIdentity("phone", "Phone"))
        assertClean(b.sync())
        b.poll()
        assertEquals(900.0, loadReadingStatisticsOverview(b.repo, today, 0).todaySeconds, 0.0)

        // B downloads it and reads for five minutes.
        assertTrue(b.cloudBooks!!.requestDownload(mangaId))
        assertClean(b.sync())
        val onB = b.book(mangaId)
        b.repo.saveStatistics(onB.root, b.repo.loadStatistics(onB.root) + stat(today, 300.0, "phone", "Phone", 2))
        b.pushReaderStatistics()
        assertClean(b.sync())
        assertEquals(1_200.0, loadReadingStatisticsOverview(b.repo, today, 0).todaySeconds, 0.0)

        // B deletes it (everywhere); A imports it again, which lifts the tombstone.
        b.deleteBook(b.book(mangaId))
        assertClean(b.sync())
        assertClean(a.sync())
        assertNull(a.bookOrNull(mangaId))
        // A real import stamps "now" (the corpus uses a fixed past stamp), which lifts the tombstone.
        val again = SyncCorpus.importManga(a.repo)
        val stamped = a.repo.loadMetadata(again)!!.copy(importedAt = java.time.Instant.now().toString())
        a.repo.saveMetadata(again, stamped)
        a.autoPush.onBookImported(again, stamped.title, moe.antimony.hoshi.epub.ContentType.Mokuro, stamped.importedAt, stamped.syncId)
        a.awaitPushes()
        assertClean(a.sync())
        assertNotNull(a.bookOrNull(mangaId))
        a.poll()
        assertEquals(1_200.0, loadReadingStatisticsOverview(a.repo, today, 0).todaySeconds, 0.0)

        assertClean(b.sync())
        b.poll()
        assertEquals("listed again", listOf(mangaId), b.cloudBooks!!.records().map { it.syncId })
        assertEquals(1_200.0, loadReadingStatisticsOverview(b.repo, today, 0).todaySeconds, 0.0)

        assertTrue(b.cloudBooks!!.requestDownload(mangaId))
        assertClean(b.sync())
        b.poll()
        a.poll()
        assertClean(b.sync())
        assertClean(a.sync())
        val overviewB = loadReadingStatisticsOverview(b.repo, today, 0)
        val overviewA = loadReadingStatisticsOverview(a.repo, today, 0)
        assertEquals(1_200.0, overviewB.todaySeconds, 0.0)
        assertEquals(1_200.0, overviewA.todaySeconds, 0.0)
        assertEquals(overviewA.totalSeconds, overviewB.totalSeconds, 0.0)
        assertTrue(
            "B's history folder is absorbed",
            b.repo.loadStatisticsHistory().none { it.syncId == mangaId },
        )
    }

    private fun stat(day: String, seconds: Double, deviceId: String, deviceName: String, modified: Long) = ReadingStatistics(
        title = SyncCorpus.MANGA_TITLE,
        dateKey = day,
        readingTime = seconds,
        charactersRead = 10,
        lastStatisticModified = modified,
        deviceId = deviceId,
        deviceName = deviceName,
        readingTimeByHour = mapOf("${day}T20:00" to seconds),
    )
}
