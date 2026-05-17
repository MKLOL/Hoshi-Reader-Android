package moe.antimony.hoshi.features.sync.v3

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.sync.http.FakeKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncBookLocks
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import moe.antimony.hoshi.features.sync.http.bookmarkKey
import moe.antimony.hoshi.features.sync.http.deriveSyncId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Two simulated devices against one shared FakeKvTransport, scripted A→B interleavings.
 * Each device is a fresh BookRepository + V3SyncEngine.
 */
class V3RaceTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private val configured = HttpSyncSettings("https://x", "t", enabled = true)

    private fun newRepo(): BookRepository = BookRepository(tempFolder.newFolder())

    private fun engineFor(
        repo: BookRepository,
        transport: HttpSyncKvTransport,
        historyStore: AiChatHistoryStore = AiChatHistoryStore(),
        bookLocks: HttpSyncBookLocks = HttpSyncBookLocks(),
    ): V3SyncEngine = V3SyncEngine(
        bookRepository = repo,
        aiHistoryStore = historyStore,
        aiSettingsRepository = null,
        payloadCodec = HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined),
        bookLocks = bookLocks,
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

    // --- bookmark LWW across devices ----------------------------------------

    @Test
    fun bookmarkLwwConvergesToNewerWriter() = runBlocking {
        val title = "Race"
        val syncId = "race"
        val transport = FakeKvTransport()

        // Device A imports + syncs.
        val repoA = newRepo()
        val rootA = importMokuroBook(repoA, title)
        repoA.saveBookmark(rootA, Bookmark(3, 0.0, 3, 800_000_000.0))
        engineFor(repoA, transport).syncOnce(configured)

        // Device B syncs cold, gets the book + bookmark.
        val repoB = newRepo()
        engineFor(repoB, transport).syncOnce(configured)
        val rootB = repoB.loadBookEntries().single { deriveSyncId(it.metadata.title) == syncId }.root
        assertEquals(3, repoB.loadBookmark(rootB)!!.chapterIndex)

        // Device A turns to page 4 (newer stamp), syncs.
        repoA.saveBookmark(rootA, Bookmark(4, 0.0, 4, 900_000_000.0))
        engineFor(repoA, transport).syncOnce(configured)

        // Device B's next sync picks up page 4.
        engineFor(repoB, transport).syncOnce(configured)
        assertEquals(4, repoB.loadBookmark(rootB)!!.chapterIndex)

        // Now device B advances further (newest stamp) and syncs.
        repoB.saveBookmark(rootB, Bookmark(7, 0.0, 7, 1_100_000_000.0))
        engineFor(repoB, transport).syncOnce(configured)
        engineFor(repoA, transport).syncOnce(configured)
        assertEquals("A should now have B's later bookmark", 7, repoA.loadBookmark(rootA)!!.chapterIndex)
    }

    // --- chat set-union across devices ----------------------------------------

    @Test
    fun chatSetUnionAcrossDevices() = runBlocking {
        val title = "Chat Race"
        val syncId = "chat_race"
        val transport = FakeKvTransport()

        // Both devices independently get the book first.
        val repoA = newRepo()
        val rootA = importMokuroBook(repoA, title)
        val historyA = AiChatHistoryStore()
        historyA.append(rootA, AiChatEntry("x", "p", "m", "r", 1.0))
        historyA.append(rootA, AiChatEntry("y", "p", "m", "r", 2.0))
        engineFor(repoA, transport, historyA).syncOnce(configured)

        val repoB = newRepo()
        val historyB = AiChatHistoryStore()
        engineFor(repoB, transport, historyB).syncOnce(configured)
        val rootB = repoB.loadBookEntries().single { deriveSyncId(it.metadata.title) == syncId }.root
        // B should have A's two entries.
        assertEquals(setOf("x", "y"), historyB.load(rootB).entries.map { it.bubbleText }.toSet())

        // B writes its own chat entries (overlapping y, plus z).
        historyB.append(rootB, AiChatEntry("z", "p", "m", "r", 3.0))
        engineFor(repoB, transport, historyB).syncOnce(configured)

        // A syncs again and ends with [x, y, z].
        engineFor(repoA, transport, historyA).syncOnce(configured)
        assertEquals(
            setOf("x", "y", "z"),
            historyA.load(rootA).entries.map { it.bubbleText }.toSet(),
        )
    }

    @Test
    fun sameChatCollisionDedupesAcrossDevices() = runBlocking {
        val title = "Dup Chat"
        val syncId = "dup_chat"
        val transport = FakeKvTransport()

        // Both devices generate the same chat with identical content and timestamp.
        val ts = 9999.0
        val sameEntry = AiChatEntry("hi", "p", "m", "r", ts)

        val repoA = newRepo()
        val rootA = importMokuroBook(repoA, title)
        val historyA = AiChatHistoryStore()
        historyA.append(rootA, sameEntry)
        engineFor(repoA, transport, historyA).syncOnce(configured)

        val repoB = newRepo()
        val historyB = AiChatHistoryStore()
        engineFor(repoB, transport, historyB).syncOnce(configured)
        val rootB = repoB.loadBookEntries().single { deriveSyncId(it.metadata.title) == syncId }.root
        // Even if B happens to also have the same entry locally already…
        historyB.save(rootB, moe.antimony.hoshi.features.ai.AiChatLog(historyB.load(rootB).entries.toSet().toList()))

        // Re-sync both — there must still be exactly one chat key on the server.
        engineFor(repoB, transport, historyB).syncOnce(configured)
        engineFor(repoA, transport, historyA).syncOnce(configured)
        val serverChatKeys = transport.kv.keys.count { it.startsWith("books/$syncId/chat/") }
        assertEquals("content-addressable key → one entry only", 1, serverChatKeys)
    }

    // --- shelf moves across devices -------------------------------------------

    @Test
    fun shelfMoveConvergesAfterSync() = runBlocking {
        val title = "Shelf Race"
        val syncId = "shelf_race"
        val transport = FakeKvTransport()

        val repoA = newRepo()
        val rootA = importMokuroBook(repoA, title)
        engineFor(repoA, transport).syncOnce(configured)

        val repoB = newRepo()
        engineFor(repoB, transport).syncOnce(configured)
        val rootB = repoB.loadBookEntries().single { deriveSyncId(it.metadata.title) == syncId }.root

        // A moves it to "Reading".
        val bookIdA = repoA.loadMetadata(rootA)!!.id
        repoA.saveShelves(
            listOf(moe.antimony.hoshi.epub.BookShelf("Reading", listOf(bookIdA))),
        )
        engineFor(repoA, transport).syncOnce(configured)

        // B picks up the shelf.
        engineFor(repoB, transport).syncOnce(configured)
        val bookIdB = repoB.loadMetadata(rootB)!!.id
        val shelvesB = repoB.loadShelves()
        assertTrue(
            "B should have a 'Reading' shelf containing the book, got $shelvesB",
            shelvesB.any { it.name == "Reading" && bookIdB in it.bookIds },
        )
    }

    // --- tombstone + reimport -------------------------------------------------

    @Test
    fun tombstoneThenReimportSurvives() = runBlocking {
        val title = "Tomb Reimport"
        val syncId = "tomb_reimport"
        val transport = FakeKvTransport()

        val repoA = newRepo()
        val rootA = importMokuroBook(repoA, title)
        engineFor(repoA, transport).syncOnce(configured)

        val repoB = newRepo()
        engineFor(repoB, transport).syncOnce(configured)
        assertTrue(repoB.loadBookEntries().any { deriveSyncId(it.metadata.title) == syncId })

        // A deletes the book + records tombstone, then syncs.
        val pending = moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookRecord(
            title = title,
            contentType = moe.antimony.hoshi.features.sync.http.HttpSyncContentType.Mokuro,
            deletedAt = "2030-06-01T00:00:00Z",
        )
        moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookStateStore(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        ).recordDeletedBook(repoA.booksDirectory, syncId, pending)
        repoA.deleteBook(rootA)
        engineFor(repoA, transport).syncOnce(configured)

        // B syncs and picks up the tombstone.
        engineFor(repoB, transport).syncOnce(configured)
        assertTrue(
            "B should no longer have the book locally",
            repoB.loadBookEntries().none { deriveSyncId(it.metadata.title) == syncId },
        )
    }

    // --- reader-hook style mid-sync write ------------------------------------

    @Test
    fun perBookLockSerializesReaderPushAndSync() = runBlocking {
        // Single device, but simulate a reader-hook push concurrent with a sync. Both go
        // through the same per-book lock; the test asserts no torn state.
        val transport = FakeKvTransport()
        val repoA = newRepo()
        val rootA = importMokuroBook(repoA, "Hot Reader")
        repoA.saveBookmark(rootA, Bookmark(1, 0.0, 1, 100.0))
        val locks = HttpSyncBookLocks()
        val engine = engineFor(repoA, transport, AiChatHistoryStore(), locks)
        val pushOps = V3PushOps(
            bookRepository = repoA,
            aiHistoryStore = AiChatHistoryStore(),
            aiSettingsRepository = null,
            payloadCodec = HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined),
            bookLocks = locks,
        )

        // Reader-hook style push of a much newer bookmark.
        pushOps.pushBookmarkConditional(
            transport = transport,
            bookRoot = rootA,
            syncId = "hot_reader",
            localBookmark = Bookmark(100, 0.0, 100, 2_000_000_000.0),
        )
        // Engine runs after; should leave the newer bookmark intact.
        engine.syncOnce(configured)

        val finalRemote = transport.kv[bookmarkKey("hot_reader")]
        assertEquals(
            "the reader's newer bookmark should still be on the server after the sync",
            100,
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString(
                    moe.antimony.hoshi.features.sync.http.HttpSyncBookmarkBlob.serializer(),
                    finalRemote!!.body.toString(Charsets.UTF_8),
                ).chapterIndex,
        )
    }
}
