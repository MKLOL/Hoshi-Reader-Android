package moe.antimony.hoshi.features.sync.v3

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.sync.http.FakeKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncChatEntryBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncCloudBooks
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncKvFileFetched
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncMetadataBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import moe.antimony.hoshi.features.sync.http.RangeFakeKvTransport
import moe.antimony.hoshi.features.sync.http.chatEntryKeySuffix
import moe.antimony.hoshi.features.sync.http.chatKey
import moe.antimony.hoshi.features.sync.http.metadataKey
import moe.antimony.hoshi.features.sync.http.payloadZipKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Remote-only manga go to the cloud catalogue and download only when tapped. */
class V3CloudBooksTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val configured = HttpSyncSettings("https://x", "t")
    private val coverPage = ByteArray(5_000) { (it * 31).toByte() }

    private class Device(val repo: BookRepository, val cloud: HttpSyncCloudBooks, val history: AiChatHistoryStore, val engine: V3SyncEngine)

    private fun device(transport: HttpSyncKvTransport): Device {
        val repo = BookRepository(tempFolder.newFolder())
        val cloud = HttpSyncCloudBooks(repo.booksDirectory, coverThumbnail = { it }, ioDispatcher = Dispatchers.Unconfined)
        val history = AiChatHistoryStore()
        val engine = V3SyncEngine(
            bookRepository = repo,
            aiHistoryStore = history,
            payloadCodec = HttpSyncPayloadCodec(Dispatchers.Unconfined),
            transportFactory = { transport },
            ioDispatcher = Dispatchers.Unconfined,
            cloudBooks = cloud,
        )
        return Device(repo, cloud, history, engine)
    }

    private suspend fun uploadManga(transport: HttpSyncKvTransport, syncId: String, title: String, shelfName: String? = null) {
        val source = tempFolder.newFolder("src-$syncId")
        source.resolve("images").mkdirs()
        source.resolve("images/0001.jpg").writeBytes(coverPage)
        source.resolve("images/0002.jpg").writeBytes(ByteArray(3_000) { 7 })
        source.resolve("mokuro.json").writeText(
            """{"title":"$title","volume":"$title","pages":[""" +
                """{"img_path":"images/0001.jpg","img_width":1,"img_height":1,"blocks":[]},""" +
                """{"img_path":"images/0002.jpg","img_width":1,"img_height":1,"blocks":[]}]}""",
        )
        HttpSyncPayloadCodec(Dispatchers.Unconfined).uploadIfChanged(transport, syncId, source, title, HttpSyncContentType.Mokuro)
        transport.put(
            metadataKey(syncId),
            "application/json; charset=utf-8",
            json.encodeToString(
                HttpSyncMetadataBlob.serializer(),
                HttpSyncMetadataBlob(
                    title = title,
                    contentType = HttpSyncContentType.Mokuro,
                    shelfName = shelfName,
                    shelfUpdatedAt = shelfName?.let { "2030-01-01T00:00:00Z" },
                ),
            ).toByteArray(),
        )
    }

    @Test
    fun remoteOnlyMangaIsListedWithItsCoverInsteadOfDownloaded() = runBlocking {
        val transport = RangeFakeKvTransport()
        uploadManga(transport, "manga_one", "Manga One")
        val archiveSize = transport.delegate.kv.getValue(payloadZipKey("manga_one")).body.size
        val device = device(transport)

        val result = device.engine.syncOnce(configured)

        assertEquals(emptyList<V3Error>(), result.errors)
        assertEquals(0, result.applied.payloads)
        assertEquals(1, result.newCloudBooks)
        assertTrue(device.repo.loadBookEntries().isEmpty())
        val record = device.cloud.records().single()
        assertEquals("manga_one", record.syncId)
        assertEquals("Manga One", record.title)
        assertEquals(archiveSize.toLong(), record.sizeBytes)
        assertArrayEquals(coverPage, device.cloud.coverFile(record)!!.readBytes())
        assertEquals(device.cloud.books.value, device.cloud.records())

        // A second listing keeps the record and does not read the cover again.
        val ranges = transport.rangeRequests
        val again = device.engine.syncOnce(configured)
        assertEquals(0, again.newCloudBooks)
        assertEquals(ranges, transport.rangeRequests)
        assertEquals(listOf(record), device.cloud.records())
    }

    @Test
    fun aServerWithoutRangesStillListsTheMangaWithoutACoverAndDoesNotRetryIt() = runBlocking {
        val transport = FakeKvTransport()
        uploadManga(transport, "manga_one", "Manga One")
        val device = device(transport)

        assertEquals(emptyList<V3Error>(), device.engine.syncOnce(configured).errors)

        val record = device.cloud.records().single()
        assertNull(record.coverFile)
        assertFalse(
            "an archive without a readable cover is not retried",
            device.cloud.needsCover(record.syncId, record.archiveSha256),
        )
    }

    @Test
    fun remoteOnlyEpubsStillDownloadDuringSync() = runBlocking {
        val transport = RangeFakeKvTransport()
        val source = tempFolder.newFolder("epub-src")
        source.resolve("META-INF").mkdirs()
        source.resolve("OEBPS").mkdirs()
        source.resolve("META-INF/container.xml").writeText(
            """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">""" +
                """<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
        )
        source.resolve("OEBPS/content.opf").writeText(
            """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" unique-identifier="id" version="3.0">""" +
                """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">x</dc:identifier><dc:title>Novel</dc:title><dc:language>ja</dc:language></metadata>""" +
                """<manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>""",
        )
        source.resolve("OEBPS/c.xhtml").writeText("""<html xmlns="http://www.w3.org/1999/xhtml"><body><p>食べる。</p></body></html>""")
        HttpSyncPayloadCodec(Dispatchers.Unconfined).uploadIfChanged(transport, "novel", source, "Novel", HttpSyncContentType.Epub)
        val device = device(transport)

        val result = device.engine.syncOnce(configured)

        assertEquals(emptyList<V3Error>(), result.errors)
        assertEquals(1, result.applied.payloads)
        assertEquals(1, device.repo.loadBookEntries().size)
        assertTrue(device.cloud.records().isEmpty())
    }

    @Test
    fun aTappedMangaDownloadsWithItsChatsAndShelfAndLeavesTheCatalogue() = runBlocking {
        val fake = RangeFakeKvTransport()
        uploadManga(fake, "manga_one", "Manga One", shelfName = "Reading")
        uploadManga(fake, "manga_two", "Manga Two")
        val chat = HttpSyncChatEntryBlob("bubble", "p", "m", "reply", 12345.0)
        fake.put(
            chatKey("manga_one", chatEntryKeySuffix(chat.timestampSeconds, chat.bubbleText, chat.response)),
            "application/json; charset=utf-8",
            json.encodeToString(HttpSyncChatEntryBlob.serializer(), chat).toByteArray(),
        )
        val progressSeen = mutableListOf<Float?>()
        lateinit var observed: HttpSyncCloudBooks
        // Reports progress halfway through the archive, as the streaming client does.
        val transport = object : HttpSyncKvTransport by fake {
            override suspend fun downloadToFile(
                key: String,
                targetFile: File,
                onByteProgress: ((Long, Long) -> Unit)?,
            ): HttpSyncKvFileFetched? {
                val body = fake.get(key)?.body ?: return null
                onByteProgress?.invoke(body.size / 2L, body.size.toLong())
                progressSeen += observed.downloads.value["manga_one"]
                targetFile.parentFile?.mkdirs()
                targetFile.writeBytes(body)
                onByteProgress?.invoke(body.size.toLong(), body.size.toLong())
                return HttpSyncKvFileFetched("application/zip", "", "")
            }
        }
        val device = device(transport)
        observed = device.cloud
        device.engine.syncOnce(configured)
        assertEquals(2, device.cloud.records().size)

        assertTrue(device.cloud.requestDownload("manga_one"))
        assertTrue(device.cloud.hasRequestedDownloads())
        val result = device.engine.syncOnce(configured)

        assertEquals(emptyList<V3Error>(), result.errors)
        assertEquals(1, result.applied.payloads)
        assertEquals(listOf<Float?>(0.5f), progressSeen)
        val installed = device.repo.loadBookEntries().single()
        assertEquals("manga_one", installed.metadata.syncId)
        assertEquals("bubble", device.history.load(installed.root).entries.single().bubbleText)
        assertEquals(
            "the remote shelf placement comes with the book",
            listOf(installed.metadata.id),
            device.repo.loadShelves().single { it.name == "Reading" }.bookIds,
        )
        assertEquals(listOf("manga_two"), device.cloud.records().map { it.syncId })
        assertTrue(device.cloud.downloads.value.isEmpty())
        assertFalse(device.cloud.hasRequestedDownloads())
    }

    @Test
    fun aFailedDownloadStaysInTheCatalogueWithItsReason() = runBlocking {
        val transport = RangeFakeKvTransport()
        uploadManga(transport, "manga_one", "Manga One")
        val device = device(transport)
        device.engine.syncOnce(configured)
        assertTrue(device.cloud.requestDownload("manga_one"))
        // The archive vanished after the listing: the manifest still points at it.
        transport.delegate.kv.remove(payloadZipKey("manga_one"))

        val result = device.engine.syncOnce(configured)

        assertEquals(1, result.errors.size)
        assertTrue(device.repo.loadBookEntries().isEmpty())
        val record = device.cloud.records().single()
        assertNull("the request is answered, so the shelf can offer a retry", record.downloadRequestedAt)
        assertNotNull(record.downloadError)
        assertTrue(device.cloud.downloads.value.isEmpty())
        val staging = device.repo.booksDirectory.parentFile!!.listFiles().orEmpty().filter { it.name.startsWith(".http-sync-import-") }
        assertEquals(emptyList<File>(), staging)
    }

    @Test
    fun aMangaDeletedOnAnotherDeviceLeavesTheCatalogueAndItsCover() = runBlocking {
        val transport = RangeFakeKvTransport()
        uploadManga(transport, "manga_one", "Manga One")
        val device = device(transport)
        device.engine.syncOnce(configured)
        val cover = device.cloud.coverFile(device.cloud.records().single())!!
        assertTrue(cover.isFile)
        transport.put(
            metadataKey("manga_one"),
            "application/json; charset=utf-8",
            json.encodeToString(
                HttpSyncMetadataBlob.serializer(),
                HttpSyncMetadataBlob("Manga One", HttpSyncContentType.Mokuro, deletedAt = "2030-01-02T00:00:00Z"),
            ).toByteArray(),
        )

        val result = device.engine.syncOnce(configured)

        assertEquals(emptyList<V3Error>(), result.errors)
        assertTrue(device.cloud.records().isEmpty())
        assertFalse(cover.exists())
    }

    @Test
    fun aTapAfterThePlanWasTakenIsLeftForAnotherPass() = runBlocking {
        val transport = RangeFakeKvTransport()
        uploadManga(transport, "manga_one", "Manga One")
        val device = device(transport)
        device.engine.syncOnce(configured)
        assertFalse(device.cloud.hasUnplannedRequests())

        device.cloud.requestDownload("manga_one")
        assertTrue(device.cloud.hasUnplannedRequests())
        assertEquals(setOf("manga_one"), device.cloud.takeRequestsForPlan().keys)
        assertFalse(device.cloud.hasUnplannedRequests())
        assertFalse("an unknown book is not requested", device.cloud.requestDownload("missing"))
    }

    @Test
    fun theOriginalEngineWithoutACatalogueStillDownloadsEveryManga() = runBlocking {
        val transport = RangeFakeKvTransport()
        uploadManga(transport, "manga_one", "Manga One")
        val repo = BookRepository(tempFolder.newFolder())
        val engine = V3SyncEngine(
            bookRepository = repo,
            payloadCodec = HttpSyncPayloadCodec(Dispatchers.Unconfined),
            transportFactory = { transport },
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertEquals(1, engine.syncOnce(configured).applied.payloads)
        assertEquals(1, repo.loadBookEntries().size)
    }

    @Test
    fun plannerListsEveryRemoteOnlyMangaAndImportsOnlyRequestedOnes() {
        fun remote(syncId: String) = V3RemoteBook(
            syncId = syncId,
            manifest = moe.antimony.hoshi.features.sync.http.HttpSyncPayloadManifest(
                sha256 = "sha256:" + "a".repeat(64),
                sizeBytes = 10,
                originalName = "Folder $syncId",
                format = HttpSyncContentType.Mokuro,
            ),
            metadata = HttpSyncMetadataBlob("Title $syncId", HttpSyncContentType.Mokuro, shelfName = "Shelf"),
        )
        val local = V3LocalSnapshot(
            books = emptyList(),
            aiSettings = null,
            readAt = java.time.Instant.EPOCH,
            cloudDownloadRequests = mapOf("b" to "2030-01-01T00:00:00Z"),
        )
        val plan = V3Planner().compute(local, V3RemoteSnapshot(mapOf("a" to remote("a"), "b" to remote("b"))))

        assertEquals(listOf("a", "b"), plan.cloudBooks!!.map { it.syncId })
        assertEquals("Title a", plan.cloudBooks!!.first().title)
        assertEquals("Shelf", plan.cloudBooks!!.first().shelfName)
        assertEquals(listOf(null, "2030-01-01T00:00:00Z"), plan.cloudBooks!!.map { it.requestedAt })
        assertEquals(
            listOf("b"),
            plan.actions.filterIsInstance<V3Action.ImportRemoteBook>().map { it.syncId },
        )
        // Without the catalogue the plan is unchanged: every book is imported.
        val legacy = V3Planner().compute(local.copy(cloudDownloadRequests = null), V3RemoteSnapshot(mapOf("a" to remote("a"))))
        assertNull(legacy.cloudBooks)
        assertEquals(1, legacy.actions.filterIsInstance<V3Action.ImportRemoteBook>().size)
    }
}
