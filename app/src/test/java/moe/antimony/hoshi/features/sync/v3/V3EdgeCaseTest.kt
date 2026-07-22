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
    private val configured = HttpSyncSettings("https://x", "t")

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

        // B syncs: server has Mokuro payload, local is EPUB. The planner blocks the
        // EPUB-typed PushMetadata so other clients keep seeing Mokuro-typed metadata
        // matching the actual Mokuro payload, and surfaces the collision as a V3Error
        // (Bug 6).
        val transportBefore = transport.kv.toMap()
        val result = engineFor(repoB, transport).syncOnce(configured)
        // Conflict is surfaced as a per-book error rather than silent corruption.
        assertTrue(
            "EPUB-local vs Mokuro-remote collision must surface a per-book error, got ${result.errors}",
            result.errors.any { it.syncId == "x" },
        )
        // Server-side Mokuro metadata is NOT overwritten with EPUB-typed metadata.
        val metaAfter = transport.kv[metadataKey("x")]
        assertEquals(
            "Mokuro metadata blob on the server must be left intact",
            transportBefore[metadataKey("x")]?.body?.toList(),
            metaAfter?.body?.toList(),
        )
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

    // --- Bug 5: malformed remote must not be overwritten by local --------------

    /**
     * Bug 5 regression. The server has a malformed metadata blob (server bug, partial
     * write, etc). Before the fix, the planner saw `remote.metadata == null`
     * (decode failure left it null) and pushed the local metadata, destroying the
     * only copy of the corrupt remote data. The fix: per-field "malformed" markers
     * propagated through V3RemoteState → V3RemoteBook → V3Planner. The planner must
     * skip PushMetadata for syncIds where the remote metadata is marked malformed,
     * and the error from the read stage must still surface.
     */
    @Test
    fun malformedRemoteMetadataDoesNotOverwriteRemote() = runBlocking {
        val repo = newRepo()
        // Local has a real Mokuro book under this syncId — without the fix, the planner
        // would push our local metadata over the malformed remote.
        val root = importMokuroBook(repo, "bad meta")
        root.resolve("pages").mkdirs()
        root.resolve("pages/p1.png").writeBytes(byteArrayOf(0x42))
        val transport = FakeKvTransport()
        val malformed = "{not-valid-json".toByteArray()
        val syncId = moe.antimony.hoshi.features.sync.http.deriveSyncId("bad meta")!!
        transport.kv[metadataKey(syncId)] = FakeKvTransport.Stored(
            body = malformed,
            contentType = "application/json; charset=utf-8",
            lastModified = "2030-01-01T00:00:00Z",
        )
        val engine = engineFor(repo, transport)
        val result = engine.syncOnce(configured)

        // The error from the decode must still be surfaced.
        assertTrue(
            "expected a metadata decode error, got ${result.errors}",
            result.errors.any { it.syncId == syncId && "metadata" in it.message.lowercase() },
        )
        // The bytes on the server must be the original malformed bytes — NOT replaced
        // by a serialized local HttpSyncMetadataBlob.
        assertEquals(
            "remote malformed metadata must be left intact",
            malformed.toList(),
            transport.kv[metadataKey(syncId)]?.body?.toList(),
        )
    }

    @Test
    fun malformedRemoteBookmarkDoesNotOverwriteRemote() = runBlocking {
        val repo = newRepo()
        val root = importMokuroBook(repo, "bad bm")
        root.resolve("pages").mkdirs()
        root.resolve("pages/p1.png").writeBytes(byteArrayOf(0x11))
        // Local has a bookmark — without the fix, the planner would push it over the
        // malformed remote bookmark blob.
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))
        val transport = FakeKvTransport()
        val syncId = moe.antimony.hoshi.features.sync.http.deriveSyncId("bad bm")!!
        val malformed = "this is not json {".toByteArray()
        transport.kv[bookmarkKey(syncId)] = FakeKvTransport.Stored(
            body = malformed,
            contentType = "application/json; charset=utf-8",
            lastModified = "2030-01-01T00:00:00Z",
        )
        val engine = engineFor(repo, transport)
        val result = engine.syncOnce(configured)

        assertTrue(
            "expected a bookmark decode error, got ${result.errors}",
            result.errors.any { it.syncId == syncId && "bookmark" in it.message.lowercase() },
        )
        assertEquals(
            "remote malformed bookmark must be left intact",
            malformed.toList(),
            transport.kv[bookmarkKey(syncId)]?.body?.toList(),
        )
    }

    // --- remote-only import preserves shelf placement (Bug 2) -----------------

    /**
     * Bug 2 reproducer. A book that exists only on the remote, with a manifest AND a
     * metadata blob carrying a `shelfName` / `shelfUpdatedAt`, must end up assigned to
     * that shelf on first import. The planner used to emit `ApplyRemoteMetadata` with
     * `root = null` ahead of `ImportRemoteBook`, so the executor's shelf-apply branch
     * silently skipped (no root yet for that syncId in `rootBySyncId`).
     */
    @Test
    fun remoteOnlyImportAppliesShelfPlacement() = runBlocking {
        val syncId = "shelved_remote"
        val title = "Shelved Remote"
        val shelfName = "Light Novels"
        val shelfUpdatedAt = "2030-01-01T00:00:00Z"

        val transport = FakeKvTransport()
        // Build a real payload + manifest via the codec so import succeeds.
        val src = tempFolder.newFolder("src-shelved-remote")
        src.resolve("mokuro.json").writeText("""{"v":1}""")
        src.resolve("pages").mkdirs()
        src.resolve("pages/p1.png").writeBytes(byteArrayOf(0x55))
        HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined)
            .uploadIfChanged(transport, syncId, src, title, HttpSyncContentType.Mokuro)
        // Remote metadata stamped with shelf placement.
        transport.putJson(
            key = metadataKey(syncId),
            serializer = HttpSyncMetadataBlob.serializer(),
            value = HttpSyncMetadataBlob(
                title = title,
                contentType = HttpSyncContentType.Mokuro,
                shelfName = shelfName,
                shelfUpdatedAt = shelfUpdatedAt,
            ),
            json = json,
            lastModified = shelfUpdatedAt,
        )

        val repo = newRepo()
        val engine = engineFor(repo, transport)
        val result = engine.syncOnce(configured)

        assertEquals(emptyList<V3Error>(), result.errors)
        // Book made it to disk.
        val imported = repo.loadBookEntries().single { deriveSyncId(it.metadata.title) == syncId }
        // Shelf assignment must have followed.
        val shelves = repo.loadShelves()
        val targetShelf = shelves.find { it.name == shelfName }
        assertTrue(
            "expected a '$shelfName' shelf containing the imported book, got shelves=$shelves",
            targetShelf != null && imported.metadata.id in targetShelf.bookIds,
        )
        assertEquals(
            "executor should report exactly one applied shelf placement",
            1,
            result.applied.shelfPlacements,
        )
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
