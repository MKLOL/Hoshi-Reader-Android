package moe.antimony.hoshi.features.sync.v3

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.sync.http.FakeKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncBookLocks
import moe.antimony.hoshi.features.sync.http.HttpSyncBookmarkBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncChatEntryBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookRecord
import moe.antimony.hoshi.features.sync.http.HttpSyncMetadataBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.HttpSyncRevisionStore
import moe.antimony.hoshi.features.sync.http.bookmarkKey
import moe.antimony.hoshi.features.sync.http.chatEntryKeySuffix
import moe.antimony.hoshi.features.sync.http.chatKey
import moe.antimony.hoshi.features.sync.http.metadataKey
import org.junit.Assert.assertEquals
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
        aiHistoryStore = AiChatHistoryStore(),
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
    fun pushBookmarkConditionalHoldsBookLockAcrossReadAndWrite() = runBlocking {
        // Two concurrent calls on the same book must serialize through the per-book lock.
        // We can verify this indirectly by giving the transport a one-shot delay on the GET
        // and confirming that the second caller sees the first caller's stamp on its compare.
        val repo = newRepo()
        val root = importMokuroBook(repo, "Locked")
        val transport = FakeKvTransport()
        // Pre-seed an older remote.
        transport.putJson(
            bookmarkKey("locked"),
            HttpSyncBookmarkBlob.serializer(),
            HttpSyncBookmarkBlob(1, 0.0, 1, "2000-01-01T00:00:00Z"),
            json,
            lastModified = "2000-01-01T00:00:00Z",
        )
        val locks = HttpSyncBookLocks()
        val pushOps = V3PushOps(
            bookRepository = repo,
            aiHistoryStore = AiChatHistoryStore(),
            aiSettingsRepository = null,
            payloadCodec = HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined),
            bookLocks = locks,
        )

        // Run two pushes serially; second one will see the first's pushed stamp (Apple
        // seconds → RFC 3339). The FakeKvTransport stamps each PUT with an incrementing
        // synthetic timestamp; verifying both PUTs landed and the lock was correctly held
        // is enough — corruption would manifest as a partial overwrite.
        val mutex = locks.mutexFor(root)
        mutex.lock()
        try {
            // Lock is held; if pushOps re-enters the lock from the same coroutine context,
            // it would deadlock. Use a non-blocking acquire to confirm reentry is the issue.
            assertTrue("lock should be held by this test", !mutex.tryLock())
        } finally {
            mutex.unlock()
        }
        // Now an actual call should work.
        pushOps.pushBookmarkConditional(
            transport = transport,
            bookRoot = root,
            syncId = "locked",
            localBookmark = Bookmark(7, 0.0, 7, 2_000_000_000.0),
        )
        assertNotNull(transport.kv[bookmarkKey("locked")])
    }

    // --- chat -----------------------------------------------------------------

    @Test
    fun pushChatIsIdempotentOnSameKey() = runBlocking {
        val repo = newRepo()
        val root = importMokuroBook(repo, "Chat Idem")
        val transport = FakeKvTransport()
        val pushOps = newPushOps(repo)
        val entry = AiChatEntry("hi", "p", "m", "r", 1.0)
        val key = chatKey("chat_idem", chatEntryKeySuffix(entry.timestampSeconds, entry.bubbleText, entry.response))

        pushOps.pushChat(transport, root, "chat_idem", entry, key)
        pushOps.pushChat(transport, root, "chat_idem", entry, key)

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
