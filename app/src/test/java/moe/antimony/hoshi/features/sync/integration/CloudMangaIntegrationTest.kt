package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.sync.http.BOOKS_MAP_KEY
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

/**
 * Manga another device uploads reach this one as cloud placeholders over REAL HTTP: the listing
 * brings the title and the cover (read with byte ranges), a tap downloads the book with its
 * reading position, and the book map stays stable so neither device keeps re-syncing.
 */
class CloudMangaIntegrationTest {
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

    private fun device(name: String) =
        SyncDevice(name, SyncEngine.V3, temp.newFolder(name), server, cloudCatalogue = true).also { devices += it }

    private fun assertClean(outcome: SyncOutcome) =
        assertEquals("sync errors: ${outcome.errors}", emptyList<String>(), outcome.errors)

    private suspend fun booksMap(): Map<String, String> = json.decodeFromString(
        MapSerializer(String.serializer(), String.serializer()),
        server.client().get(BOOKS_MAP_KEY)!!.body.toString(Charsets.UTF_8),
    )

    @Test
    fun aNewMangaIsListedWithItsCoverAndDownloadsWithItsPositionWhenTapped() = runBlocking {
        val a = device("A")
        val manga = a.importManga(pageCount = 3, extraBytesPerPage = 200_000)
        val syncId = a.repo.loadMetadata(manga)!!.syncId!!
        a.importNovel()
        assertClean(a.sync())
        a.turnPage(manga, SyncCorpus.MANGA_TITLE, SyncCorpus.bookmark(chapter = 2, appleSeconds = 800_000_100.0))
        val archiveSize = server.client().get(payloadZipKey(syncId))!!.body.size

        val b = device("B")
        server.clearRequests()
        val listed = b.sync()
        assertClean(listed)
        assertEquals("only the novel is downloaded", 1, listed.downloadedPayloads)
        assertEquals(1, b.lastResult!!.newCloudBooks)
        assertNull(b.bookOrNull(syncId))
        val record = b.cloudBooks!!.records().single()
        assertEquals(syncId, record.syncId)
        assertEquals(SyncCorpus.MANGA_TITLE, record.title)
        assertEquals(archiveSize.toLong(), record.sizeBytes)
        assertArrayEquals("the cover is the first page", manga.resolve("images/0001.jpg").readBytes(), b.cloudBooks!!.coverFile(record)!!.readBytes())
        val archiveReads = server.requests().filter { it.isPayloadDownload && it.path.endsWith(payloadZipKey(syncId)) }
        assertTrue("the archive is only read in ranges", archiveReads.isNotEmpty() && archiveReads.all { it.range != null })
        assertTrue(archiveReads.sumOf { it.responseBytes } < archiveSize / 2)

        // The listed manga belongs to the library: the book map keeps it, and neither device's
        // five-second poll sees a difference that would start another full sync.
        assertTrue(syncId in booksMap())
        assertFalse(b.batchState.syncMaps(server.client()).booksChanged)
        assertFalse(a.batchState.syncMaps(server.client()).booksChanged)
        server.clearRequests()
        val again = b.sync()
        assertClean(again)
        assertEquals(0, again.transferredPayloads)
        assertTrue(server.requests().none { it.isPayloadDownload })

        assertTrue(b.cloudBooks!!.requestDownload(syncId))
        val downloaded = b.sync()

        assertClean(downloaded)
        assertEquals(1, downloaded.downloadedPayloads)
        val installed = b.book(syncId)
        assertEquals("A's reading position comes with the book", 2, b.repo.loadBookmark(installed.root)!!.chapterIndex)
        assertTrue(b.cloudBooks!!.records().isEmpty())
        assertTrue(b.cloudBooks!!.downloads.value.isEmpty())
        assertTrue(syncId in booksMap())
        assertFalse(b.batchState.syncMaps(server.client()).booksChanged)
    }

    @Test
    fun aDownloadThatFailsCanBeRetriedAndADeletedMangaDisappears() = runBlocking {
        val a = device("A")
        val manga = a.importManga()
        val syncId = a.repo.loadMetadata(manga)!!.syncId!!
        assertClean(a.sync())
        val b = device("B")
        assertClean(b.sync())

        b.cloudBooks!!.requestDownload(syncId)
        server.failNext("/v1/kv/${payloadZipKey(syncId)}", status = 403, count = 1, method = "GET")
        val failed = b.sync()

        assertTrue(failed.errors.isNotEmpty())
        assertNull(b.bookOrNull(syncId))
        val record = b.cloudBooks!!.records().single()
        assertNull(record.downloadRequestedAt)
        assertNotNull(record.downloadError)

        b.cloudBooks!!.requestDownload(syncId)
        assertClean(b.sync())
        assertNotNull(b.bookOrNull(syncId))
        assertTrue(b.cloudBooks!!.records().isEmpty())

        // A second manga is deleted on A before B ever downloads it.
        val other = a.importManga(title = "Second Manga")
        val otherSyncId = a.repo.loadMetadata(other)!!.syncId!!
        assertClean(a.sync())
        assertClean(b.sync())
        assertEquals(listOf(otherSyncId), b.cloudBooks!!.records().map { it.syncId })
        a.deleteBook(a.book(otherSyncId))
        assertClean(a.sync())
        assertClean(b.sync())
        assertTrue(b.cloudBooks!!.records().isEmpty())
        assertFalse(otherSyncId in booksMap())
    }
}
