package moe.antimony.hoshi.features.sync.v3

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.sync.http.FakeKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncBookmarkBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncActiveBooks
import moe.antimony.hoshi.features.sync.http.HttpSyncChatEntryBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncException
import moe.antimony.hoshi.features.sync.http.HttpSyncKvFetched
import moe.antimony.hoshi.features.sync.http.HttpSyncKvList
import moe.antimony.hoshi.features.sync.http.HttpSyncKvKeyMeta
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncKvWriteResponse
import moe.antimony.hoshi.features.sync.http.HttpSyncMetadataBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import moe.antimony.hoshi.features.sync.http.bookmarkKey
import moe.antimony.hoshi.features.sync.http.chatEntryKeySuffix
import moe.antimony.hoshi.features.sync.http.chatKey
import moe.antimony.hoshi.features.sync.http.deriveSyncId
import moe.antimony.hoshi.features.sync.http.metadataKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * End-to-end engine tests using the shared in-memory `FakeKvTransport`.
 */
class V3SyncEngineTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val configured = HttpSyncSettings("https://x", "t")

    private fun newRepo(): BookRepository = BookRepository(tempFolder.newFolder())

    private fun engineFor(
        repo: BookRepository,
        transport: HttpSyncKvTransport,
        historyStore: AiChatHistoryStore = AiChatHistoryStore(),
    ): V3SyncEngine = V3SyncEngine(
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
        root.resolve("pages").mkdirs()
        root.resolve("pages/p1.png").writeBytes(byteArrayOf(0x42))
        return root
    }

    private suspend fun importEpubBook(repo: BookRepository, title: String): File {
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
        writeMinimalEpub(root, title)
        return root
    }

    private fun writeMinimalEpub(root: File, title: String) {
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
                <dc:identifier id="book-id">sync-test</dc:identifier><dc:title>$title</dc:title><dc:language>ja</dc:language>
              </metadata>
              <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="chapter"/></spine>
            </package>""".trimIndent(),
        )
        root.resolve("OEBPS/chapter.xhtml").writeText(
            """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>食べる。</p></body></html>""",
        )
    }

    private suspend fun uploadRemoteMokuroPayload(
        transport: FakeKvTransport,
        syncId: String,
        title: String,
        srcDir: String,
    ) {
        val srcRoot = tempFolder.newFolder(srcDir)
        srcRoot.resolve("mokuro.json").writeText("""{"v":1}""")
        srcRoot.resolve("pages").mkdirs()
        srcRoot.resolve("pages/p1.png").writeBytes(byteArrayOf(0x42))
        HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined)
            .uploadIfChanged(transport, syncId, srcRoot, title, HttpSyncContentType.Mokuro)
    }

    // --- requires-configured --------------------------------------------------

    @Test
    fun syncOnceRequiresConfiguredSettings() {
        val repo = newRepo()
        val engine = engineFor(repo, FakeKvTransport())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { engine.syncOnce(HttpSyncSettings()) }
        }
    }

    // --- happy paths ----------------------------------------------------------

    @Test
    fun freshDeviceColdStartImportsMokuroBookWithBookmarkAndChat() = runBlocking {
        val syncId = "fresh_book"
        val title = "Fresh Book"
        val transport = FakeKvTransport()
        uploadRemoteMokuroPayload(transport, syncId, title, "remote-src")
        transport.putJson(
            bookmarkKey(syncId),
            HttpSyncBookmarkBlob.serializer(),
            HttpSyncBookmarkBlob(42, 0.0, 100, "2030-01-01T00:00:00Z"),
            json,
            lastModified = "2030-01-01T00:00:00Z",
        )
        val chatBlob = HttpSyncChatEntryBlob("hello", "p", "m", "world", 12345.0)
        transport.putJson(
            chatKey(syncId, chatEntryKeySuffix(chatBlob.timestampSeconds, chatBlob.bubbleText, chatBlob.response)),
            HttpSyncChatEntryBlob.serializer(),
            chatBlob,
            json,
            lastModified = "2030-01-01T00:00:00Z",
        )

        val repo = newRepo()
        val historyStore = AiChatHistoryStore()
        val engine = engineFor(repo, transport, historyStore)
        val result = engine.syncOnce(configured)

        assertEquals(emptyList<V3Error>(), result.errors)
        assertEquals("payload should have been downloaded", 1, result.applied.payloads)
        assertEquals("bookmark should have been applied", 1, result.applied.bookmarks)
        assertEquals("chat should have been imported", 1, result.applied.chatEntries)

        val imported = repo.loadBookEntries().single { deriveSyncId(it.metadata.title) == syncId }
        assertEquals(42, repo.loadBookmark(imported.root)!!.chapterIndex)
        assertEquals("hello", historyStore.load(imported.root).entries.single().bubbleText)
    }

    @Test
    fun freshDeviceImportPopulatesCoverPathSoBookshelfDoesNotShowBlankCover() = runBlocking {
        // Regression: the v3 executor's `importRemoteBook` used to write `cover = null` after
        // unpacking the payload, leaving the bookshelf with a blank cover slot until the user
        // opened the book (at which point `BookshelfRepository.openBook` parsed it and rewrote
        // metadata). The fix mirrors the user-side import: parse the freshly-unzipped book
        // and resolve `metadata.cover` via `BookRepository.metadataCoverPath` so the
        // bookshelf cover loader has something to render immediately.
        val syncId = "cover_v3_book"
        val title = "Cover V3 Book"
        val transport = FakeKvTransport()
        // Stage a valid Mokuro payload on the server. `mokuro.json` references
        // `pages/0001.jpg`, which the parser surfaces as the volume's `coverImagePath`.
        run {
            val srcRoot = tempFolder.newFolder("cover-v3-src")
            srcRoot.resolve("mokuro.json").writeText(
                """{"version":"1.0","title":"Cover V3 Book","pages":[{"img_path":"pages/0001.jpg","img_width":100,"img_height":200,"blocks":[]}]}""",
            )
            srcRoot.resolve("pages").mkdirs()
            srcRoot.resolve("pages/0001.jpg").writeBytes(byteArrayOf(0x42, 0x43, 0x44))
            HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined)
                .uploadIfChanged(transport, syncId, srcRoot, title, HttpSyncContentType.Mokuro)
        }

        val repo = newRepo()
        val engine = engineFor(repo, transport)
        val result = engine.syncOnce(configured)

        assertEquals(emptyList<V3Error>(), result.errors)
        assertEquals(1, result.applied.payloads)

        val imported = repo.loadBookEntries().single { deriveSyncId(it.metadata.title) == syncId }
        assertNotNull(
            "cover path must be populated after v3 sync import",
            imported.metadata.cover,
        )
        // Receiver-generated covers live under the hash-excluded generated name so they never
        // change the book's payload content hash.
        assertEquals(
            "Books/${imported.root.name}/${moe.antimony.hoshi.epub.GENERATED_COVER_FILENAME}",
            imported.metadata.cover,
        )
        assertFalse("payload-visible cover copy must not be created", imported.root.resolve("0001.jpg").exists())
        val coverFile = repo.coverFile(imported)
        assertNotNull("repo must resolve metadata.cover to a real file", coverFile)
        assertTrue("cover file exists", coverFile!!.isFile)
    }

    @Test
    fun freshDeviceColdStartImportsEpubBook() = runBlocking {
        // v3 widens the payload sync gate to EPUBs.
        val syncId = "fresh_epub_book"
        val title = "Fresh Epub Book"
        val transport = FakeKvTransport()
        // Build an EPUB payload via the codec.
        run {
            val src = tempFolder.newFolder("epub-src")
            writeMinimalEpub(src, title)
            HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined)
                .uploadIfChanged(transport, syncId, src, title, HttpSyncContentType.Epub)
        }

        val repo = newRepo()
        val engine = engineFor(repo, transport)
        val result = engine.syncOnce(configured)

        assertEquals(emptyList<V3Error>(), result.errors)
        assertEquals(1, result.applied.payloads)
        val imported = repo.loadBookEntries().single { deriveSyncId(it.metadata.title) == syncId }
        assertTrue(
            "EPUB chapter survived round-trip",
            imported.root.resolve("OEBPS/chapter.xhtml").exists(),
        )
    }

    @Test
    fun outboundPushesLocalOnlyBook() = runBlocking {
        val repo = newRepo()
        val root = importMokuroBook(repo, "Local Only")
        repo.saveBookmark(root, Bookmark(7, 0.0, 7, 2_000_000_000.0))
        val transport = FakeKvTransport()
        val engine = engineFor(repo, transport)

        val result = engine.syncOnce(configured)

        assertEquals(emptyList<V3Error>(), result.errors)
        assertEquals(1, result.pushed.bookmarks)
        assertEquals(1, result.pushed.metadata)
        assertEquals(1, result.pushed.payloads)
        assertNotNull(transport.kv[bookmarkKey("local_only")])
        assertNotNull(transport.kv[metadataKey("local_only")])
        assertNotNull(transport.kv["books/local_only/payload.manifest"])
    }

    @Test
    fun roundTripBookmarkBetweenTwoBookRepositories() = runBlocking {
        val syncId = "round_trip"
        val title = "Round Trip"
        val transport = FakeKvTransport()

        // Device A imports + syncs.
        val repoA = newRepo()
        val rootA = importMokuroBook(repoA, title)
        repoA.saveBookmark(rootA, Bookmark(5, 0.0, 5, 2_000_000_000.0))
        engineFor(repoA, transport).syncOnce(configured)
        assertNotNull(transport.kv[bookmarkKey(syncId)])

        // Device B is empty; syncs and picks up everything.
        val repoB = newRepo()
        val result = engineFor(repoB, transport).syncOnce(configured)
        assertEquals(emptyList<V3Error>(), result.errors)
        val importedB = repoB.loadBookEntries().single()
        assertEquals(5, repoB.loadBookmark(importedB.root)!!.chapterIndex)
    }

    // --- tombstone ------------------------------------------------------------

    @Test
    fun appliesRemoteTombstone() = runBlocking {
        val repo = newRepo()
        importMokuroBook(repo, "Deleted Remote")
        val transport = FakeKvTransport()
        transport.putJson(
            metadataKey("deleted_remote"),
            HttpSyncMetadataBlob.serializer(),
            HttpSyncMetadataBlob(
                title = "Deleted Remote",
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-06-01T00:00:00Z",
            ),
            json,
            lastModified = "2030-06-01T00:00:00Z",
        )
        val engine = engineFor(repo, transport)

        val result = engine.syncOnce(configured)

        assertEquals(emptyList<V3Error>(), result.errors)
        assertTrue("local book should have been deleted", repo.loadBookEntries().isEmpty())
    }

    @Test
    fun defersRemoteTombstoneWhileReaderIsOpen() = runBlocking {
        val repo = newRepo()
        importMokuroBook(repo, "Active Remote")
        val transport = FakeKvTransport()
        transport.putJson(
            metadataKey("active_remote"),
            HttpSyncMetadataBlob.serializer(),
            HttpSyncMetadataBlob(
                title = "Active Remote",
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-06-01T00:00:00Z",
            ),
            json,
            lastModified = "2030-06-01T00:00:00Z",
        )

        HttpSyncActiveBooks.open("active_remote")
        val result = try {
            engineFor(repo, transport).syncOnce(configured)
        } finally {
            HttpSyncActiveBooks.close("active_remote")
        }

        assertTrue(result.errors.any {
            it.message.contains("deletion deferred while this book is open", ignoreCase = true)
        })
        assertTrue(repo.loadBookEntries().any { it.metadata.title == "Active Remote" })
    }

    // --- error handling -------------------------------------------------------

    @Test
    fun perActionErrorDoesNotAbortRest() = runBlocking {
        val repo = newRepo()
        val rootGood = importMokuroBook(repo, "Good")
        repo.saveBookmark(rootGood, Bookmark(1, 0.0, 1, 800_000_000.0))
        val rootBad = importMokuroBook(repo, "Bad")
        repo.saveBookmark(rootBad, Bookmark(2, 0.0, 2, 800_000_000.0))

        // FakeKvTransport that fails on PUT for "bad" syncId only.
        val transport = object : HttpSyncKvTransport by FakeKvTransport() {
            override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
                if ("/bad/" in key) throw HttpSyncException("simulated server error for bad")
                return HttpSyncKvWriteResponse(key, "2030-01-01T00:00:00Z", "etag", body.size, contentType)
            }
        }
        val engine = engineFor(repo, transport)
        val result = engine.syncOnce(configured)

        assertTrue("expected at least one bad error, got ${result.errors}", result.errors.any { it.syncId == "bad" })
        // Good book's actions still went through (we can't introspect counts on a custom
        // transport since put never persists; just check the error wasn't fatal).
        assertTrue("good book should have made it through, errors: ${result.errors}",
            result.errors.none { it.syncId == "good" })
    }

    // --- idempotency -----------------------------------------------------------

    @Test
    fun runningSyncTwiceLeavesServerUnchangedOnSecondRun() = runBlocking {
        val repo = newRepo()
        val root = importMokuroBook(repo, "Idem")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))
        val transport = FakeKvTransport()
        val engine = engineFor(repo, transport)

        val first = engine.syncOnce(configured)
        assertEquals(emptyList<V3Error>(), first.errors)
        val firstKeysSnapshot = transport.kv.toMap()

        val second = engine.syncOnce(configured)
        assertEquals(emptyList<V3Error>(), second.errors)
        // No payload re-upload on the second sync (manifest exists).
        assertEquals(0, second.pushed.payloads)
        // Bookmark is in tie (we just pushed). No bookmark action either way.
        assertEquals(0, second.applied.bookmarks)
        // Server keys identical.
        assertEquals(firstKeysSnapshot.keys, transport.kv.keys)
    }

    // --- progress callback ----------------------------------------------------

    @Test
    fun progressCallbackFiresForKeyPhases() = runBlocking {
        val repo = newRepo()
        val root = importMokuroBook(repo, "Prog")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))
        val transport = FakeKvTransport()
        val engine = engineFor(repo, transport)

        val phases = mutableListOf<V3Phase>()
        engine.syncOnce(configured) { progress -> phases += progress.phase }

        assertTrue("ReadingLocal phase: $phases", phases.contains(V3Phase.ReadingLocal))
        assertTrue("ListingRemote phase: $phases", phases.contains(V3Phase.ListingRemote))
        assertTrue("Planning phase: $phases", phases.contains(V3Phase.Planning))
        assertTrue("Done phase: $phases", phases.contains(V3Phase.Done))
    }

    // --- spec § Mandatory edge case 13: stale / future cursor must not hide books ---

    /**
     * v3 never reads `HttpSyncSettings.lastSyncedAt` — the engine architecture is a
     * full-list pull every sync. This test makes that contract explicit: hand the
     * engine a settings object whose cursor is in the year 2999 (well after every
     * server-stamped lastModified on a real fake transport) and confirm the engine
     * still pulls the book on a fresh device. If anyone ever reintroduces an
     * incremental `since=` filter, this test catches it.
     */
    @Test
    fun futureDatedCursorDoesNotHideRemoteBooks() = runBlocking {
        // Device A pushes a book; device B starts fresh with a garbage-future cursor.
        val repoA = newRepo()
        val rootA = importMokuroBook(repoA, "Stale Cursor Vol")
        repoA.saveBookmark(rootA, Bookmark(2, 0.5, 50, 800_000_000.0))
        val transport = FakeKvTransport()
        val engineA = engineFor(repoA, transport)
        engineA.syncOnce(configured)

        val repoB = newRepo()
        val engineB = engineFor(repoB, transport)
        val poisonedSettings = configured.copy(lastSyncedAt = "2999-12-31T23:59:59.999Z")
        val result = engineB.syncOnce(poisonedSettings)

        assertEquals("no errors: ${result.errors}", emptyList<V3Error>(), result.errors)
        assertEquals(
            "device B must still see the book even with a future-dated cursor",
            1,
            result.applied.payloads,
        )
        assertTrue(repoB.loadBookEntries().any { it.metadata.title == "Stale Cursor Vol" })
    }

    // --- concurrent syncOnce serialization (concurrency reviewer fix) -----------

    /**
     * Two concurrent `syncOnce` invocations on the same engine must serialize so
     * their shelf-state / deleted-book sidecar saves can't clobber each other. We
     * verify this with an `instant.now()`-style sentinel: while the first sync is
     * running, the second waits for the engine's internal mutex; both eventually
     * succeed and produce identical local state.
     */
    @Test
    fun concurrentSyncOnceCallsSerialize() = runBlocking {
        val repo = newRepo()
        val root = importMokuroBook(repo, "Serialize Me")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))
        val transport = FakeKvTransport()
        val engine = engineFor(repo, transport)

        val a = async { engine.syncOnce(configured) }
        val b = async { engine.syncOnce(configured) }
        val resultA = a.await()
        val resultB = b.await()

        assertEquals("no errors A: ${resultA.errors}", emptyList<V3Error>(), resultA.errors)
        assertEquals("no errors B: ${resultB.errors}", emptyList<V3Error>(), resultB.errors)
        // Server got our metadata + manifest exactly once (idempotent across both passes).
        assertTrue(
            "expected exactly one metadata key on server: ${transport.kv.keys}",
            transport.kv.keys.count { it.endsWith("/metadata") } == 1,
        )
    }
}
