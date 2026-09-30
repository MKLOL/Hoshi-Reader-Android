package moe.antimony.hoshi.features.sync.v3

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.sync.http.FakeKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncBookLocks
import moe.antimony.hoshi.features.sync.http.HttpSyncBookmarkBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncChatEntryBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookRecord
import moe.antimony.hoshi.features.sync.http.HttpSyncKvFetched
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncMetadataBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.HttpSyncRevisionStore
import moe.antimony.hoshi.features.sync.http.bookmarkKey
import moe.antimony.hoshi.features.sync.http.chatEntryKeySuffix
import moe.antimony.hoshi.features.sync.http.chatKey
import moe.antimony.hoshi.features.sync.http.metadataKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Focused tests for [V3PushOps]. No engine; just the push primitive set.
 */
class V3PushOpsTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun newRepo(): BookRepository = BookRepository(tempFolder.newFolder())

    private fun newPushOps(repo: BookRepository): V3PushOps = V3PushOps(
        bookRepository = repo,
        aiSettingsRepository = null,
        payloadCodec = HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined),
        bookLocks = HttpSyncBookLocks(),
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
        return root
    }

    // --- bookmark conditional --------------------------------------------------

    @Test
    fun pushBookmarkConditionalPushesWhenServerEmpty() = runBlocking {
        val repo = newRepo()
        val root = importMokuroBook(repo, "Bk")
        val pushOps = newPushOps(repo)
        val transport = FakeKvTransport()

        val out = pushOps.pushBookmarkConditional(
            transport = transport,
            bookRoot = root,
            syncId = "bk",
            localBookmark = Bookmark(1, 0.0, 1, 800_000_000.0),
        )
        assertEquals(PushBookmarkOutcome.Pushed, out)
        assertNotNull(transport.kv[bookmarkKey("bk")])
    }

    @Test
    fun pushBookmarkConditionalAppliesWhenRemoteNewer() = runBlocking {
        val repo = newRepo()
        val root = importMokuroBook(repo, "Bk")
        val transport = FakeKvTransport()
        transport.putJson(
            bookmarkKey("bk"),
            HttpSyncBookmarkBlob.serializer(),
            HttpSyncBookmarkBlob(99, 0.0, 99, "2099-01-01T00:00:00Z"),
            json,
            lastModified = "2099-01-01T00:00:00Z",
        )
        val pushOps = newPushOps(repo)

        val out = pushOps.pushBookmarkConditional(
            transport = transport,
            bookRoot = root,
            syncId = "bk",
            localBookmark = Bookmark(1, 0.0, 1, 100.0),
        )
        assertEquals(PushBookmarkOutcome.AppliedRemote, out)
        val local = repo.loadBookmark(root)!!
        assertEquals(99, local.chapterIndex)
    }

    @Test
    fun pushBookmarkConditionalPushesWhenLocalNewer() = runBlocking {
        val repo = newRepo()
        val root = importMokuroBook(repo, "Bk")
        val transport = FakeKvTransport()
        transport.putJson(
            bookmarkKey("bk"),
            HttpSyncBookmarkBlob.serializer(),
            HttpSyncBookmarkBlob(1, 0.0, 1, "2000-01-01T00:00:00Z"),
            json,
            lastModified = "2000-01-01T00:00:00Z",
        )
        val pushOps = newPushOps(repo)

        val out = pushOps.pushBookmarkConditional(
            transport = transport,
            bookRoot = root,
            syncId = "bk",
            localBookmark = Bookmark(50, 0.0, 50, 2_000_000_000.0),
        )
        assertEquals(PushBookmarkOutcome.Pushed, out)
        val pushed = json.decodeFromString(
            HttpSyncBookmarkBlob.serializer(),
            transport.kv[bookmarkKey("bk")]!!.body.toString(Charsets.UTF_8),
        )
        assertEquals(50, pushed.chapterIndex)
    }

    @Test
    fun pushBookmarkConditionalNoOpOnTie() = runBlocking {
        val repo = newRepo()
        val root = importMokuroBook(repo, "Bk")
        val transport = FakeKvTransport()
        val stamp = "2025-01-01T00:00:00Z"
        transport.putJson(
            bookmarkKey("bk"),
            HttpSyncBookmarkBlob.serializer(),
            HttpSyncBookmarkBlob(3, 0.0, 3, stamp),
            json,
            lastModified = stamp,
        )
        val pushOps = newPushOps(repo)

        val out = pushOps.pushBookmarkConditional(
            transport = transport,
            bookRoot = root,
            syncId = "bk",
            localBookmark = Bookmark(
                3, 0.0, 3,
                moe.antimony.hoshi.features.sync.http.rfc3339ToAppleSeconds(stamp),
            ),
        )
        assertEquals(PushBookmarkOutcome.NoOp, out)
    }

    @Test
    fun pushUsesTheNewerSavedBookmarkInsteadOfThePlanSnapshot() = runBlocking {
        val repo = newRepo()
        val root = importMokuroBook(repo, "Advanced")
        val current = Bookmark(9, 0.5, 90, 2_000_000_000.0)
        repo.saveBookmark(root, current)
        val transport = FakeKvTransport()

        assertEquals(PushBookmarkOutcome.Pushed, newPushOps(repo).pushBookmarkConditional(
            transport, root, "advanced", Bookmark(1, 0.0, 10, 100.0),
        ))

        val pushed = json.decodeFromString<HttpSyncBookmarkBlob>(
            transport.kv.getValue(bookmarkKey("advanced")).body.toString(Charsets.UTF_8),
        )
        assertEquals(current.chapterIndex, pushed.chapterIndex)
        assertEquals(current.characterCount, pushed.characterCount)
    }

    @Test(timeout = 10_000)
    fun bookmarkSavedDuringRemoteReadCannotBeReplacedByOlderRemoteProgress() = runBlocking {
        val repo = newRepo()
        val root = importMokuroBook(repo, "Reading")
        val old = Bookmark(1, 0.0, 10, 100.0)
        val current = Bookmark(9, 0.5, 90, 2_000_000_000.0)
        repo.saveBookmark(root, old)
        val server = FakeKvTransport().apply {
            putJson(bookmarkKey("reading"), HttpSyncBookmarkBlob.serializer(),
                HttpSyncBookmarkBlob(4, 0.1, 40, "2025-01-01T00:00:00Z"), json,
                lastModified = "2025-01-01T00:00:00Z")
        }
        val started = CompletableDeferred<Unit>()
        val continueRead = CompletableDeferred<Unit>()
        val transport = object : HttpSyncKvTransport by server {
            override suspend fun get(key: String): HttpSyncKvFetched? {
                started.complete(Unit)
                continueRead.await()
                return server.get(key)
            }
        }
        val push = async { newPushOps(repo).pushBookmarkConditional(transport, root, "reading", old) }
        started.await()
        repo.saveBookmark(root, current)
        continueRead.complete(Unit)

        assertEquals(PushBookmarkOutcome.Pushed, push.await())
        assertEquals(current, repo.loadBookmark(root))
        val pushed = json.decodeFromString<HttpSyncBookmarkBlob>(
            server.kv.getValue(bookmarkKey("reading")).body.toString(Charsets.UTF_8),
        )
        assertEquals(current.characterCount, pushed.characterCount)
    }

    @Test
    fun malformedBookmarkDiscoveredAfterPlanningIsNeverOverwritten() = runBlocking {
        val repo = newRepo()
        val root = importMokuroBook(repo, "Recoverable")
        val current = Bookmark(9, 0.5, 90, 2_000_000_000.0)
        repo.saveBookmark(root, current)
        val transport = FakeKvTransport()
        val original = "{ truncated bookmark".toByteArray()
        transport.put(bookmarkKey("recoverable"), "application/json", original)

        try {
            newPushOps(repo).pushBookmarkConditional(transport, root, "recoverable", current)
            fail("A malformed remote bookmark must fail without overwriting it")
        } catch (_: kotlinx.serialization.SerializationException) {
            assertTrue(original.contentEquals(transport.kv.getValue(bookmarkKey("recoverable")).body))
            assertEquals(current, repo.loadBookmark(root))
        }
    }

    @Test(timeout = 10_000)
    fun pushBookmarkConditionalHoldsBookLockAcrossReadAndWrite() = runBlocking {
        val repo = newRepo()
        val root = importMokuroBook(repo, "Locked")
        val server = FakeKvTransport()
        val started = CompletableDeferred<Unit>()
        val continueRead = CompletableDeferred<Unit>()
        var reads = 0
        val transport = object : HttpSyncKvTransport by server {
            override suspend fun get(key: String): HttpSyncKvFetched? {
                reads++
                if (reads == 1) {
                    started.complete(Unit)
                    continueRead.await()
                }
                return server.get(key)
            }
        }
        val pushOps = newPushOps(repo)
        val first = async {
            pushOps.pushBookmarkConditional(transport, root, "locked", Bookmark(7, 0.0, 70, 2_000_000_000.0))
        }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            pushOps.pushBookmarkConditional(transport, root, "locked", Bookmark(1, 0.0, 10, 100.0))
        }
        assertEquals("Second push must wait before its GET", 1, reads)
        assertFalse(second.isCompleted)
        continueRead.complete(Unit)

        assertEquals(PushBookmarkOutcome.Pushed, first.await())
        assertEquals(PushBookmarkOutcome.AppliedRemote, second.await())
        assertEquals(2, reads)
        assertEquals(70, repo.loadBookmark(root)?.characterCount)
    }

    // --- chat -----------------------------------------------------------------

    @Test
    fun pushChatIsIdempotentOnSameKey() = runBlocking {
        val repo = newRepo()
        val transport = FakeKvTransport()
        val pushOps = newPushOps(repo)
        val entry = AiChatEntry("hi", "p", "m", "r", 1.0)
        val key = chatKey("chat_idem", chatEntryKeySuffix(entry.timestampSeconds, entry.bubbleText, entry.response))

        pushOps.pushChat(transport, entry, key)
        pushOps.pushChat(transport, entry, key)

        // Same key → exactly one entry.
        assertEquals(1, transport.kv.keys.count { it.startsWith("books/chat_idem/chat/") })
    }

    // --- payload --------------------------------------------------------------

    @Test
    fun pushPayloadSkipsWhenRemoteManifestExists() = runBlocking {
        val repo = newRepo()
        val root = importMokuroBook(repo, "Manifest Exists")
        // Add a file inside so the codec has something to zip.
        root.resolve("pages").mkdirs()
        root.resolve("pages/p1.png").writeBytes(byteArrayOf(0x77))
        val transport = FakeKvTransport()
        transport.putJson(
            "books/manifest_exists/payload.manifest",
            moe.antimony.hoshi.features.sync.http.HttpSyncPayloadManifest.serializer(),
            moe.antimony.hoshi.features.sync.http.HttpSyncPayloadManifest(
                sha256 = "sha256:00",
                sizeBytes = 0,
                originalName = "Manifest Exists",
                format = HttpSyncContentType.Mokuro,
            ),
            json,
            lastModified = "2025-01-01T00:00:00Z",
        )
        val pushOps = newPushOps(repo)

        val uploaded = pushOps.pushPayload(
            transport = transport,
            bookRoot = root,
            syncId = "manifest_exists",
            title = "Manifest Exists",
            format = HttpSyncContentType.Mokuro,
        )
        assertEquals(false, uploaded)
    }

    @Test
    fun pushPayloadUploadsMokuroWhenManifestAbsent() = runBlocking {
        val repo = newRepo()
        val root = importMokuroBook(repo, "Fresh Mokuro")
        root.resolve("pages").mkdirs()
        root.resolve("pages/p1.png").writeBytes(byteArrayOf(0x77))
        val transport = FakeKvTransport()
        val pushOps = newPushOps(repo)

        val uploaded = pushOps.pushPayload(
            transport = transport,
            bookRoot = root,
            syncId = "fresh_mokuro",
            title = "Fresh Mokuro",
            format = HttpSyncContentType.Mokuro,
        )
        assertEquals(true, uploaded)
        assertNotNull(transport.kv["books/fresh_mokuro/payload.zip"])
        assertNotNull(transport.kv["books/fresh_mokuro/payload.manifest"])
    }

    @Test
    fun pushPayloadUploadsEpubWhenManifestAbsent() = runBlocking {
        val repo = newRepo()
        val root = importEpubBook(repo, "Fresh Epub")
        root.resolve("book.html").writeText("<html/>")
        val transport = FakeKvTransport()
        val pushOps = newPushOps(repo)

        val uploaded = pushOps.pushPayload(
            transport = transport,
            bookRoot = root,
            syncId = "fresh_epub",
            title = "Fresh Epub",
            format = HttpSyncContentType.Epub,
        )
        assertEquals("EPUB payloads must round-trip via v3", true, uploaded)
        assertNotNull(transport.kv["books/fresh_epub/epub.zip"])
        assertNotNull(transport.kv["books/fresh_epub/epub.manifest"])
    }

    // --- tombstone ------------------------------------------------------------

    @Test
    fun pushTombstoneWritesMetadataBlobWithDeletedAt() = runBlocking {
        val repo = newRepo()
        val transport = FakeKvTransport()
        val pushOps = newPushOps(repo)
        HttpSyncRevisionStore(json).bumpForLocalEdit(repo.booksDirectory, metadataKey("tombed"))

        pushOps.pushTombstone(
            transport = transport,
            syncId = "tombed",
            record = HttpSyncDeletedBookRecord(
                title = "Tombed",
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-06-01T00:00:00Z",
            ),
        )

        val stored = transport.kv[metadataKey("tombed")]!!
        val blob = json.decodeFromString(
            HttpSyncMetadataBlob.serializer(),
            stored.body.toString(Charsets.UTF_8),
        )
        assertEquals("2030-06-01T00:00:00Z", blob.deletedAt)
        assertEquals("Tombed", blob.title)
        assertEquals("tombstone push must preserve the local metadata rev", 1, blob.rev)
    }

    // --- AI settings ----------------------------------------------------------

    @Test
    fun applyAiSettingsReturnsFalseWithoutRepository() = runBlocking {
        val repo = newRepo()
        val pushOps = newPushOps(repo)
        val applied = pushOps.applyAiSettings(
            moe.antimony.hoshi.features.sync.http.HttpSyncAiChatSettingsBlob(
                model = "x", promptText = "p", imagePromptText = "i", lastModified = "2030-01-01T00:00:00Z",
            ),
        )
        assertEquals("no repository configured ⇒ false", false, applied)
    }
}
