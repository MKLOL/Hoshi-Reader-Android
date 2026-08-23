package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.ai.EPUB_TRANSLATIONS_FILENAME
import moe.antimony.hoshi.features.ai.EpubTranslationStore
import moe.antimony.hoshi.features.sync.v3.V3SyncEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class HttpEpubSyncInteropTest {
    @get:Rule val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val settings = HttpSyncSettings("https://sync.invalid", "token", useV3Sync = false)

    @Test
    fun twoDevicesRoundTripCanonicalEpubAndSentenceTranslations() = runBlocking {
        val transport = FakeKvTransport()
        val sender = BookRepository(temp.newFolder("sender"))
        val receiver = BookRepository(temp.newFolder("receiver"))
        val syncId = "Zenitendou-01"
        val sourceRoot = sender.createBookDirectoryForImportedTitle("Interop EPUB")
        writeMinimalEpub(sourceRoot)
        sender.saveMetadata(
            sourceRoot,
            BookMetadata(
                id = UUID.randomUUID().toString(),
                title = "Interop EPUB",
                cover = null,
                folder = sourceRoot.name,
                lastAccess = 0.0,
                syncId = syncId,
            ),
        )

        val senderResult = reconciler(sender, transport).syncOnce(settings)
        assertEquals(1, senderResult.uploadedPayloads)
        assertNotNull(transport.kv[epubZipKey(syncId)])
        assertNotNull(transport.kv[epubManifestKey(syncId)])
        assertFalse("new EPUB uploads must not occupy Mokuro keys", payloadManifestKey(syncId) in transport.kv)

        val sentence = "食べる。"
        val normalized = "食べる"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        val sentences = HttpSyncSentencesBlob(
            kind = "epub",
            syncId = syncId,
            title = "Interop EPUB",
            model = "test-model",
            promptId = "test-prompt",
            generatedAt = "2026-08-23T00:00:00Z",
            spineCount = 1,
            entries = mapOf(
                "c0s0" to HttpSyncSentenceEntry(
                    spine = 0,
                    start = 0,
                    len = 3,
                    text = sentence,
                    hash = hash,
                    translation = "To eat.",
                    explanation = "Dictionary form.",
                ),
            ),
        )
        transport.put(
            sentencesKey(syncId),
            "application/json",
            json.encodeToString(HttpSyncSentencesBlob.serializer(), sentences).toByteArray(),
        )

        val receiverResult = reconciler(receiver, transport).syncOnce(settings)
        assertEquals(receiverResult.errors.joinToString(), 0, receiverResult.errors.size)
        assertEquals(1, receiverResult.downloadedPayloads)
        assertEquals(1, receiverResult.downloadedSentenceTranslations)
        val received = receiver.loadBookEntries().single()
        assertEquals(syncId, received.metadata.syncId)
        assertTrue(received.root.resolve("OEBPS/chapter.xhtml").isFile)
        assertTrue(received.root.resolve(EPUB_TRANSLATIONS_FILENAME).isFile)
        assertTrue(
            "downloaded EPUB must expose shelf progress before it is opened",
            (receiver.loadBookInfo(received.root)?.characterCount ?: 0) > 0,
        )

        assertTrue(EpubTranslationStore.preload(received.root, syncId, spineCount = 1))
        val anchor = EpubTranslationStore.anchors(received.root, syncId, 1, 0).single()
        val translation = EpubTranslationStore.lookup(anchor.id, received.root, syncId, 1)
        assertEquals("To eat.", translation?.translation)
        assertEquals("Dictionary form.", translation?.explanation)

        val followUp = reconciler(receiver, transport).syncOnce(settings)
        assertEquals(followUp.errors.joinToString(), 0, followUp.errors.size)
        assertEquals(
            "a subsequent scan must preserve the canonical iOS sync identity",
            syncId,
            syncIdForMetadata(receiver.loadMetadata(received.root)!!),
        )
    }

    @Test
    fun v3TwoDevicesDownloadCanonicalIosEpubAndSentences() = runBlocking {
        val transport = FakeKvTransport()
        val sender = BookRepository(temp.newFolder("v3-sender"))
        val receiver = BookRepository(temp.newFolder("v3-receiver"))
        val syncId = "v3_interop_epub"
        val root = sender.createBookDirectoryForImportedTitle("V3 Interop EPUB")
        writeMinimalEpub(root)
        sender.saveMetadata(
            root,
            BookMetadata(
                id = UUID.randomUUID().toString(),
                title = "V3 Interop EPUB",
                cover = null,
                folder = root.name,
                lastAccess = 0.0,
                syncId = syncId,
            ),
        )
        val pushed = v3(sender, transport).syncOnce(settings.copy(useV3Sync = true))
        assertTrue("v3 push errors: ${pushed.errors}", pushed.errors.isEmpty())
        assertNotNull(transport.kv[epubManifestKey(syncId)])
        assertNotNull(transport.kv[epubZipKey(syncId)])

        val normalized = "食べる"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        val blob = HttpSyncSentencesBlob(
            kind = "epub",
            syncId = syncId,
            spineCount = 1,
            entries = mapOf(
                "c0s0" to HttpSyncSentenceEntry(
                    spine = 0,
                    start = 0,
                    len = 3,
                    text = "食べる。",
                    hash = hash,
                    translation = "To eat.",
                ),
            ),
        )
        transport.put(
            sentencesKey(syncId),
            "application/json",
            json.encodeToString(HttpSyncSentencesBlob.serializer(), blob).toByteArray(),
        )

        val pulled = v3(receiver, transport).syncOnce(settings.copy(useV3Sync = true))
        assertTrue("v3 pull errors: ${pulled.errors}", pulled.errors.isEmpty())
        assertEquals(1, pulled.applied.payloads)
        assertEquals(1, pulled.applied.sentenceTranslations)
        val received = receiver.loadBookEntries().single()
        assertEquals(syncId, received.metadata.syncId)
        assertTrue(
            "v3 downloaded EPUB must expose shelf progress before it is opened",
            (receiver.loadBookInfo(received.root)?.characterCount ?: 0) > 0,
        )
        assertTrue(EpubTranslationStore.preload(received.root, syncId, 1))
    }

    @Test
    fun v3RepublishesLegacyAndroidEpubUnderCanonicalIosKeys() = runBlocking {
        val transport = FakeKvTransport()
        val repository = BookRepository(temp.newFolder("legacy-v3-sender"))
        val syncId = "legacy_v3_epub"
        val root = repository.createBookDirectoryForImportedTitle("Legacy V3 EPUB")
        writeMinimalEpub(root)
        repository.saveMetadata(
            root,
            BookMetadata(
                id = UUID.randomUUID().toString(),
                title = "Legacy V3 EPUB",
                cover = null,
                folder = root.name,
                lastAccess = 0.0,
                syncId = syncId,
            ),
        )
        val codec = HttpSyncPayloadCodec(Dispatchers.Unconfined)
        codec.uploadIfChanged(
            transport = transport,
            syncId = syncId,
            bookRoot = root,
            originalName = "Legacy V3 EPUB",
            format = HttpSyncContentType.Epub,
        )
        transport.kv[payloadZipKey(syncId)] = transport.kv.remove(epubZipKey(syncId))!!
        transport.kv[payloadManifestKey(syncId)] = transport.kv.remove(epubManifestKey(syncId))!!
        transport.put(
            metadataKey(syncId),
            "application/json",
            json.encodeToString(
                HttpSyncMetadataBlob.serializer(),
                HttpSyncMetadataBlob(title = "Legacy V3 EPUB", contentType = HttpSyncContentType.Epub),
            ).toByteArray(),
        )

        val result = v3(repository, transport).syncOnce(settings.copy(useV3Sync = true))

        assertTrue("v3 migration errors: ${result.errors}", result.errors.isEmpty())
        assertNotNull(transport.kv[epubZipKey(syncId)])
        assertNotNull(transport.kv[epubManifestKey(syncId)])
        assertNotNull("legacy EPUB remains available to older Android clients", transport.kv[payloadManifestKey(syncId)])
    }

    @Test
    fun v3FreshDeviceImportsLegacyAndroidEpubAndRepublishesCanonicalKeysInOnePass() = runBlocking {
        val transport = FakeKvTransport()
        val source = BookRepository(temp.newFolder("legacy-v3-source"))
        val receiver = BookRepository(temp.newFolder("legacy-v3-receiver"))
        val syncId = "remote_legacy_v3_epub"
        val root = source.createBookDirectoryForImportedTitle("Remote Legacy V3 EPUB")
        writeMinimalEpub(root)
        val codec = HttpSyncPayloadCodec(Dispatchers.Unconfined)
        codec.uploadIfChanged(
            transport,
            syncId,
            root,
            "Remote Legacy V3 EPUB",
            HttpSyncContentType.Epub,
        )
        transport.kv[payloadZipKey(syncId)] = transport.kv.remove(epubZipKey(syncId))!!
        transport.kv[payloadManifestKey(syncId)] = transport.kv.remove(epubManifestKey(syncId))!!
        transport.put(
            metadataKey(syncId),
            "application/json",
            json.encodeToString(
                HttpSyncMetadataBlob.serializer(),
                HttpSyncMetadataBlob("Remote Legacy V3 EPUB", HttpSyncContentType.Epub),
            ).toByteArray(),
        )

        val result = v3(receiver, transport).syncOnce(settings.copy(useV3Sync = true))

        assertTrue("v3 remote migration errors: ${result.errors}", result.errors.isEmpty())
        assertEquals(1, result.applied.payloads)
        assertEquals(syncId, receiver.loadBookEntries().single().metadata.syncId)
        assertNotNull(transport.kv[epubZipKey(syncId)])
        assertNotNull(transport.kv[epubManifestKey(syncId)])
    }

    @Test
    fun failedV2RemoteImportNeverDeletesPreexistingCollidingFolder() = runBlocking {
        val transport = FakeKvTransport()
        val source = BookRepository(temp.newFolder("collision-source"))
        val receiver = BookRepository(temp.newFolder("collision-receiver"))
        val syncId = "colliding_folder"
        val occupant = receiver.booksDirectory.resolve(syncId).apply {
            mkdirs()
            resolve("keep-me.txt").writeText("local data")
        }
        val invalid = source.createBookDirectoryForImportedTitle("Invalid Remote EPUB").apply {
            resolve("not-an-epub.txt").writeText("invalid")
        }
        HttpSyncPayloadCodec(Dispatchers.Unconfined).uploadIfChanged(
            transport,
            syncId,
            invalid,
            "Invalid Remote EPUB",
            HttpSyncContentType.Epub,
        )
        transport.put(
            metadataKey(syncId),
            "application/json",
            json.encodeToString(
                HttpSyncMetadataBlob.serializer(),
                HttpSyncMetadataBlob("Invalid Remote EPUB", HttpSyncContentType.Epub),
            ).toByteArray(),
        )

        val result = reconciler(receiver, transport).syncOnce(settings)

        assertTrue("invalid EPUB should be reported", result.errors.any { it.contains(syncId) })
        assertEquals("local data", occupant.resolve("keep-me.txt").readText())
        assertEquals(
            "failed staging import must not leave another book directory",
            listOf(occupant.canonicalFile),
            receiver.booksDirectory.listFiles().orEmpty().filter(File::isDirectory).map(File::getCanonicalFile),
        )
    }

    @Test
    fun identicalMalformedSentenceBlobIsStillRejectedByBothEngines() = runBlocking {
        suspend fun verify(useV3: Boolean) {
            val transport = FakeKvTransport()
            val repository = BookRepository(temp.newFolder("bad-sentences-$useV3"))
            val syncId = "bad_sentences_${if (useV3) "v3" else "v2"}"
            val root = repository.createBookDirectoryForImportedTitle(syncId)
            writeMinimalEpub(root)
            repository.saveMetadata(
                root,
                BookMetadata(
                    UUID.randomUUID().toString(),
                    syncId,
                    null,
                    root.name,
                    0.0,
                    syncId = syncId,
                ),
            )
            root.resolve(EPUB_TRANSLATIONS_FILENAME).writeText("{}")
            transport.put(sentencesKey(syncId), "application/json", "{}".toByteArray())

            if (useV3) {
                val result = v3(repository, transport).syncOnce(settings.copy(useV3Sync = true))
                assertTrue("v3 must reject identical malformed bytes", result.errors.isNotEmpty())
            } else {
                val result = reconciler(repository, transport).syncOnce(settings)
                assertTrue("v2 must reject identical malformed bytes", result.errors.isNotEmpty())
            }
        }

        verify(useV3 = false)
        verify(useV3 = true)
    }

    private fun reconciler(repository: BookRepository, transport: FakeKvTransport) =
        HttpSyncReconciler(
            bookRepository = repository,
            aiHistoryStore = AiChatHistoryStore(),
            transportFactory = { transport },
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun v3(repository: BookRepository, transport: FakeKvTransport) =
        V3SyncEngine(
            bookRepository = repository,
            aiHistoryStore = AiChatHistoryStore(),
            payloadCodec = HttpSyncPayloadCodec(Dispatchers.Unconfined),
            transportFactory = { transport },
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun writeMinimalEpub(root: File) {
        root.resolve("META-INF").mkdirs()
        root.resolve("OEBPS").mkdirs()
        root.resolve("META-INF/container.xml").writeText(
            """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>""".trimIndent(),
        )
        root.resolve("OEBPS/content.opf").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="book-id" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="book-id">interop</dc:identifier><dc:title>Interop EPUB</dc:title><dc:language>ja</dc:language>
              </metadata>
              <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="chapter"/></spine>
            </package>""".trimIndent(),
        )
        root.resolve("OEBPS/chapter.xhtml").writeText(
            """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>食べる。</p></body></html>""",
        )
    }
}
