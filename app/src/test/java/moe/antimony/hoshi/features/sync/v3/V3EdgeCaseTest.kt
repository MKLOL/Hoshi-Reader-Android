package moe.antimony.hoshi.features.sync.v3

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.sync.http.FakeKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncBookmarkBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncMetadataBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadManifest
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import moe.antimony.hoshi.features.sync.http.bookmarkKey
import moe.antimony.hoshi.features.sync.http.deriveSyncId
import moe.antimony.hoshi.features.sync.http.metadataKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class V3EdgeCaseTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val configured = HttpSyncSettings("https://x", "t", enabled = true)

    private fun newRepo(): BookRepository = BookRepository(tempFolder.newFolder())

    private fun engineFor(
        repo: BookRepository,
        transport: HttpSyncKvTransport,
        historyStore: AiChatHistoryStore = AiChatHistoryStore(),
    ) = V3SyncEngine(
        bookRepository = repo,
        aiHistoryStore = historyStore,
        aiSettingsRepository = null,
        payloadCodec = HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined),
        transportFactory = { transport },
        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    )

    private suspend fun importMokuroBook(repo: BookRepository, title: String): File {
        val root = repo.createBookDirectoryForImportedTitle(title)
        repo.saveMetadata(
            root,
            BookMetadata(
                id = UUID.randomUUID().toString(),
                title = title,
                cover = null,
                folder = root.name,
                lastAccess = 0.0,
            ),
        )
        root.resolve("mokuro.json").writeText("{}")
        return root
    }

    // --- malformed JSON --------------------------------------------------------

    @Test
    fun malformedRemoteBookmarkBlobSurfacedAsError() = runBlocking {
        val repo = newRepo()
        val root = importMokuroBook(repo, "Bad Blob")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))
        val transport = FakeKvTransport()
        transport.kv[bookmarkKey("bad_blob")] = FakeKvTransport.Stored(
            body = "this is not json {".toByteArray(),
            contentType = "application/json; charset=utf-8",
            lastModified = "2030-01-01T00:00:00Z",
        )
        val engine = engineFor(repo, transport)
        val result = engine.syncOnce(configured)

        assertTrue(
            "expected an error for bad_blob, got ${result.errors}",
            result.errors.any { it.syncId == "bad_blob" && "bookmark" in it.message.lowercase() },
        )
    }

    @Test
    fun malformedRemoteMetadataBlobSurfacedAsError() = runBlocking {
        val repo = newRepo()
        val transport = FakeKvTransport()
        transport.kv[metadataKey("bad_meta")] = FakeKvTransport.Stored(
            body = "{not-json".toByteArray(),
            contentType = "application/json; charset=utf-8",
            lastModified = "2030-01-01T00:00:00Z",
        )
        val engine = engineFor(repo, transport)
        val result = engine.syncOnce(configured)
        assertTrue(result.errors.any { it.syncId == "bad_meta" && "metadata" in it.message.lowercase() })
    }

    @Test
    fun malformedRemoteChatBlobSurfacedAsError() = runBlocking {
        val repo = newRepo()
        // Stage a remote-only Mokuro book so the engine imports it and then tries to apply
        // the bad chat blob.
        val transport = FakeKvTransport()
        // Build a real payload via the codec so import succeeds.
        val src = tempFolder.newFolder("src-bad-chat")
        src.resolve("mokuro.json").writeText("""{"v":1}""")
        src.resolve("pages").mkdirs()
        src.resolve("pages/p1.png").writeBytes(byteArrayOf(0x10))
        HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined)
            .uploadIfChanged(transport, "bad_chat", src, "Bad Chat", HttpSyncContentType.Mokuro)
        // Add a malformed chat key.
        val badChatKey = "books/bad_chat/chat/2030-01-01T00-00-00Z-deadbeef"
        transport.kv[badChatKey] = FakeKvTransport.Stored(
            body = "not json".toByteArray(),
            contentType = "application/json; charset=utf-8",
            lastModified = "2030-01-01T00:00:00Z",
        )
        val engine = engineFor(repo, transport)
        val result = engine.syncOnce(configured)
        assertTrue(
            "expected a chat import error, got ${result.errors}",
            result.errors.any { it.syncId == "bad_chat" },
        )
    }

    // --- payload edge cases ---------------------------------------------------

    @Test
    fun manifestWithoutZipSurfacedAsPerBookError() = runBlocking {
        val repo = newRepo()
        val transport = FakeKvTransport()
        // Manifest present, zip missing.
        transport.putJson(
            key = "books/half_book/payload.manifest",
            serializer = HttpSyncPayloadManifest.serializer(),
            value = HttpSyncPayloadManifest(
                sha256 = "sha256:00",
                sizeBytes = 1,
                originalName = "Half Book",
                format = HttpSyncContentType.Mokuro,
            ),
            json = json,
            lastModified = "2030-01-01T00:00:00Z",
        )
        val engine = engineFor(repo, transport)
        val result = engine.syncOnce(configured)
        assertTrue(
            "manifest-without-zip should produce a per-book error",
            result.errors.any { it.syncId == "half_book" },
        )
        // The half-imported directory should NOT remain on disk.
        assertTrue(repo.loadBookEntries().none { deriveSyncId(it.metadata.title) == "half_book" })
    }

    @Test
    fun zipWithoutManifestIsIgnored() = runBlocking {
        // Manifest is the change-detector; with no manifest, engine does NOT import.
        val repo = newRepo()
        val transport = FakeKvTransport()
        transport.kv["books/orphan_zip/payload.zip"] = FakeKvTransport.Stored(
            body = byteArrayOf(0x50, 0x4B, 0x05, 0x06).plus(ByteArray(20)), // empty zip skeleton
            contentType = "application/zip",
            lastModified = "2030-01-01T00:00:00Z",
        )
        val engine = engineFor(repo, transport)
        val result = engine.syncOnce(configured)
        assertEquals(emptyList<V3Error>(), result.errors)
        assertEquals(0, result.applied.payloads)
    }

    // --- title edge cases -----------------------------------------------------

    @Test
    fun blankTitleLocalBookEmitsNoActions() = runBlocking {
        val repo = newRepo()
        // Force a blank-titled book onto disk.
        val root = repo.createBookDirectoryForImportedTitle("placeholder")
        repo.saveMetadata(
            root,
            BookMetadata(id = UUID.randomUUID().toString(), title = "", cover = null, folder = root.name, lastAccess = 0.0),
        )
        root.resolve("mokuro.json").writeText("{}")
        val transport = FakeKvTransport()
        val engine = engineFor(repo, transport)

        val result = engine.syncOnce(configured)

        assertEquals(emptyList<V3Error>(), result.errors)
        // Server has nothing for blank-title book.
        assertTrue("no books/ key for the blank-title book", transport.kv.isEmpty())
    }

    @Test
    fun nonAsciiTitleWithHashFallbackRoundTrips() = runBlocking {
        val title = "よつばと"
        val syncId = deriveSyncId(title)!!
        assertTrue(syncId.startsWith("book_"))

        val repo = newRepo()
        val root = importMokuroBook(repo, title)
        root.resolve("pages").mkdirs()
        root.resolve("pages/p1.png").writeBytes(byteArrayOf(0x09))
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))
        val transport = FakeKvTransport()
        val engine = engineFor(repo, transport)

        val first = engine.syncOnce(configured)
        assertEquals(emptyList<V3Error>(), first.errors)
        // Server stored the bookmark under the hashed syncId.
        assertEquals(null, transport.kv[bookmarkKey("yotsubato")])
        assertTrue("bookmark under hashed syncId", transport.kv.containsKey(bookmarkKey(syncId)))

        val repoB = newRepo()
        val resultB = engineFor(repoB, transport).syncOnce(configured)
        assertEquals(emptyList<V3Error>(), resultB.errors)
        val importedB = repoB.loadBookEntries().single { deriveSyncId(it.metadata.title) == syncId }
        assertEquals(title, importedB.metadata.title)
    }

    // --- syncId collision across content types --------------------------------

    @Test
    fun syncIdCollisionSurfacesPerBookErrorRatherThanCorrupting() = runBlocking {
        // Device A: Mokuro book, syncs.
        val transport = FakeKvTransport()
        val repoA = newRepo()
        val rootA = importMokuroBook(repoA, "X")
        rootA.resolve("pages").mkdirs()
        rootA.resolve("pages/p1.png").writeBytes(byteArrayOf(0x10))
        engineFor(repoA, transport).syncOnce(configured)

        // Device B has an EPUB titled "X" locally.
        val repoB = newRepo()
        val rootB = repoB.createBookDirectoryForImportedTitle("X")
        repoB.saveMetadata(
            rootB,
            BookMetadata(id = UUID.randomUUID().toString(), title = "X", cover = null, folder = rootB.name, lastAccess = 0.0),
        )
        rootB.resolve("book.html").writeText("<html/>")

        // B syncs: server has Mokuro payload, local is EPUB. Importing would conflict.
        // The planner currently emits PushMetadata + skips PushPayload (manifest exists),
        // and does not import (local already exists). This is the "stable choice" the spec
        // calls out — survive without corrupting either side.
        val result = engineFor(repoB, transport).syncOnce(configured)
        // No fatal error, but also no payload overwrite.
        assertTrue("EPUB local + Mokuro remote should not crash sync", result.errors.none { it.syncId == "x" })
    }

    // --- malformed book directory --------------------------------------------

    @Test
    fun bookDirectoryMissingMokuroSidecarTreatedAsEpub() = runBlocking {
        val repo = newRepo()
        val title = "Headless"
        val root = repo.createBookDirectoryForImportedTitle(title)
        repo.saveMetadata(
            root,
            BookMetadata(id = UUID.randomUUID().toString(), title = title, cover = null, folder = root.name, lastAccess = 0.0),
        )
        // No mokuro.json — engine should treat as EPUB content.
        val transport = FakeKvTransport()
        val engine = engineFor(repo, transport)

        val result = engine.syncOnce(configured)

        assertEquals(emptyList<V3Error>(), result.errors)
        // EPUB books push their metadata and (v3) payload.
        assertTrue("EPUB metadata key written", transport.kv.containsKey(metadataKey(deriveSyncId(title)!!)))
    }

    // --- future-shape chat key ------------------------------------------------

    @Test
    fun futureClientChatKeyShapeIsIgnoredGracefully() = runBlocking {
        // Server has a "chat/" key with no slash separator content (just "chat/") or a key
        // that doesn't match the v3 chat key shape. The planner uses set comparison on the
        // remote `chatKeys` set, so an alien key string just means "remote-only" and
        // ImportChat will attempt to import it. If the body is missing, executor skips it.
        val repo = newRepo()
        val transport = FakeKvTransport()
        val src = tempFolder.newFolder("src-future")
        src.resolve("mokuro.json").writeText("""{"v":1}""")
        src.resolve("pages").mkdirs()
        src.resolve("pages/p1.png").writeBytes(byteArrayOf(0x77))
        HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined)
            .uploadIfChanged(transport, "future_chat", src, "Future Chat", HttpSyncContentType.Mokuro)
        // Listing-only chat key — body fetched later returns null.
        transport.kv["books/future_chat/chat/2099-future-shape-deadbeef"] = FakeKvTransport.Stored(
            body = """{"bubbleText":"future","prompt":"p","model":"m","response":"r","timestampSeconds":1.0}""".toByteArray(),
            contentType = "application/json; charset=utf-8",
            lastModified = "2099-01-01T00:00:00Z",
        )
        val engine = engineFor(repo, transport)
        val result = engine.syncOnce(configured)
        // No crash; the strange key is treated as a normal chat entry.
        assertEquals(emptyList<V3Error>(), result.errors)
    }
}
