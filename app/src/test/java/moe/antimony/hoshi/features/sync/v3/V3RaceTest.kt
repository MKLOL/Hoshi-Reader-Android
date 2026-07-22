package moe.antimony.hoshi.features.sync.v3

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.sync.http.FakeKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncBookLocks
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncKvWriteResponse
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

    private val configured = HttpSyncSettings("https://x", "t")

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

    // --- Bug 1: re-import after delete must not be wiped by stale tombstone ---

    /**
     * Bug 1 regression — `docs/SYNC_V3_SPEC.md` §437 "Mandatory edge cases" #8:
     *
     *   "Re-import after delete. A deletes; tombstone is on server; A re-imports the
     *    same title — local re-import survives, doesn't get wiped by its own tombstone."
     *
     * The buggy flow: a user deletes a book (live root removed + tombstone recorded in
     * `.http_sync_deleted_books.json`), and BEFORE the next sync flushes the tombstone,
     * they re-import the same title. `deriveSyncId(title)` is deterministic, so the
     * fresh local book and the leftover deleted-book record share a syncId. When
     * V3LocalState builds the snapshot it attaches the stale tombstone to the live
     * `V3LocalBook`, the planner sees `pendingDeletion != null`, and emits
     * `PushTombstone` + `DeleteLocalBook` — wiping the book the user just re-imported.
     *
     * Fix: a live local book with the same syncId as a recorded tombstone means the
     * user re-imported. Drop the stale tombstone record before planning (so the
     * planner sees an ordinary live book) AND clear it from the on-disk sidecar so
     * future syncs don't re-trigger the same logic.
     */
    @Test
    fun reImportingDeletedTitleBeforeSyncDoesNotWipeLiveBook() = runBlocking {
        val title = "Reimport Survives"
        val syncId = deriveSyncId(title)!!
        val transport = FakeKvTransport()

        val repoA = newRepo()
        val rootA = importMokuroBook(repoA, title)
        engineFor(repoA, transport).syncOnce(configured)

        // User deletes the book → tombstone recorded + live root removed.
        val pending = moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookRecord(
            title = title,
            contentType = moe.antimony.hoshi.features.sync.http.HttpSyncContentType.Mokuro,
            deletedAt = "2030-06-01T00:00:00Z",
        )
        val deletedStore = moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookStateStore(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        )
        deletedStore.recordDeletedBook(repoA.booksDirectory, syncId, pending)
        repoA.deleteBook(rootA)

        // User re-imports the same title BEFORE the next sync — the tombstone record
        // is still on disk, but a live book now exists for the same syncId.
        val reimportedRoot = importMokuroBook(repoA, title)
        assertTrue(
            "test precondition: tombstone record must exist alongside the live re-import",
            deletedStore.load(repoA.booksDirectory).containsKey(syncId),
        )
        assertTrue(
            "test precondition: live re-imported book must be on disk",
            repoA.loadBookEntries().any { deriveSyncId(it.metadata.title) == syncId },
        )

        // Now sync. Under the bug, the planner sees pendingDeletion on the live entry
        // and emits DeleteLocalBook → the re-imported book is wiped. Under the fix,
        // the stale tombstone is dropped during snapshot building and the live book
        // survives.
        engineFor(repoA, transport).syncOnce(configured)

        assertTrue(
            "Re-imported book must survive the sync — the stale tombstone must not " +
                "be applied to the live local book that shares the same syncId.",
            repoA.loadBookEntries().any { deriveSyncId(it.metadata.title) == syncId },
        )
        assertTrue("the re-imported book root must still exist on disk", reimportedRoot.exists())
        assertTrue(
            "the stale tombstone record must be cleared from the sidecar so future " +
                "syncs don't re-trigger the same wipe path; got " +
                "${deletedStore.load(repoA.booksDirectory)}",
            !deletedStore.load(repoA.booksDirectory).containsKey(syncId),
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

    // --- apply-remote-bookmark mid-sync local write (Bug 4) ------------------

    /**
     * Bug 4: V3Executor's `ApplyRemoteBookmark` blindly overwrites local with the
     * remote blob from the planner's snapshot. The plan is computed from a snapshot
     * taken at the START of sync; if the user advances reading progress locally
     * between snapshot and apply (the same window in which the reader-hook calls
     * V3PushOps.pushBookmarkConditional from another coroutine), the executor must
     * NOT clobber the newer local bookmark with the older remote one.
     *
     * V3PushOps.pushBookmarkConditional already does this for the push side: it
     * grabs the per-book lock, re-reads, then compares lastModified. The apply
     * side must follow the same pattern.
     *
     * Repro: seed remote with an "older" remote bookmark. Local starts empty. The
     * planner snapshots local-empty + remote-present → emits ApplyRemoteBookmark.
     * We suspend during the FIRST `transport.list` (the remote-state read), then
     * — while the engine is parked — write a NEWER local bookmark (simulating a
     * concurrent page-turn). Release. The plan runs. Under the bug, the executor
     * overwrites the newer local with the stale remote. Under the fix, the apply
     * re-reads local under the per-book lock and skips because local is newer.
     */
    @Test
    fun applyRemoteBookmarkSkipsWhenLocalRacedToNewer() = runBlocking {
        val title = "Apply Race"
        val syncId = "apply_race"

        // --- seed remote with the "older" bookmark via Device A's first sync. ---
        val transport = FakeKvTransport()
        val repoA = newRepo()
        val rootA = importMokuroBook(repoA, title)
        // Apple-seconds 800_000_000 → RFC3339 some 2026-ish date; remote's stamp.
        repoA.saveBookmark(rootA, Bookmark(3, 0.0, 3, 800_000_000.0))
        engineFor(repoA, transport).syncOnce(configured)

        // --- Device B: empty local. Plan a sync, but pause during remote list. ---
        val repoB = newRepo()
        val rootB = importMokuroBook(repoB, title)
        // B has NO local bookmark yet — planner will emit ApplyRemoteBookmark.

        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var listCallCount = 0
        val suspendingTransport = object : HttpSyncKvTransport by transport {
            override suspend fun list(
                prefix: String?,
                since: String?,
                cursor: String?,
                limit: Int?,
            ): moe.antimony.hoshi.features.sync.http.HttpSyncKvList {
                val mine = ++listCallCount
                if (mine == 1) {
                    entered.complete(Unit)
                    gate.await()
                }
                return transport.list(prefix, since, cursor, limit)
            }
        }

        val deferred = async {
            engineFor(repoB, suspendingTransport).syncOnce(configured)
        }
        // Wait until the engine is parked inside remote-state listing. Local
        // snapshot is already done; planner will see local-empty + remote-present.
        entered.await()

        // --- concurrent local write: user advances to a NEWER bookmark on B. ---
        // Apple-seconds 2_000_000_000.0 is well past the 800M remote stamp.
        repoB.saveBookmark(rootB, Bookmark(99, 0.0, 99, 2_000_000_000.0))

        // Release the remote-state read; sync proceeds to plan + execute.
        gate.complete(Unit)
        deferred.await()

        val finalLocal = repoB.loadBookmark(rootB)
        assertEquals(
            "ApplyRemoteBookmark must NOT overwrite the newer local bookmark — " +
                "executor must re-read local under the per-book lock and compare " +
                "lastModified before saving the remote blob.",
            99,
            finalLocal!!.chapterIndex,
        )
    }

    // --- cancellation propagation (Bug 8) ------------------------------------

    /**
     * Cancelling the parent coroutine while V3Executor is suspended in an action must
     * abort the executor loop — not be silently converted to a V3Error so the loop
     * happily continues processing the remaining actions.
     *
     * The buggy executor catches `Exception`, which includes CancellationException.
     * After cancellation, the suspended `put` throws CancellationException, the catch
     * swallows it, and the for-loop iterates to the next action — including issuing
     * further `transport.put` / `transport.delete` calls that the caller never
     * expected (and the parent already considered the work cancelled).
     *
     * We expose this by making the FIRST put for the only book suspend indefinitely,
     * but the planner also emits a PushMetadata and a PushPayload for the same book.
     * Under the bug, after cancellation, those subsequent actions run and bump the
     * server-side kv map. Under the fix, the loop bails out immediately and no
     * subsequent puts land.
     */
    @Test
    fun cancellingMidSyncPropagatesCancellationInsteadOfSwallowingIt() = runBlocking {
        val repo = newRepo()
        val rootA = importMokuroBook(repo, "Cancel Mid Sync")
        repo.saveBookmark(rootA, Bookmark(1, 0.0, 1, 800_000_000.0))

        // Delegating transport that suspends indefinitely on the FIRST `put` and
        // passes everything else through to the real fake. `entered` is signaled the
        // moment the executor reaches the first put so the test knows when to cancel.
        // `gate` is never completed, so cancellation is the only exit.
        val fake = FakeKvTransport()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var putCallCount = 0
        val suspendingTransport = object : HttpSyncKvTransport by fake {
            override suspend fun put(
                key: String,
                contentType: String,
                body: ByteArray,
            ): HttpSyncKvWriteResponse {
                val mine = ++putCallCount
                if (mine == 1) {
                    entered.complete(Unit)
                    gate.await()
                }
                return fake.put(key, contentType, body)
            }
        }

        val engine = engineFor(repo, suspendingTransport)

        val deferred = async { engine.syncOnce(configured) }
        // Wait until the executor actually reaches the first push action.
        entered.await()
        val callCountAtCancel = putCallCount
        assertEquals(
            "test setup: expected exactly one put attempt before cancel",
            1,
            callCountAtCancel,
        )

        deferred.cancel()
        runCatching { deferred.await() }

        assertEquals(
            "Cancellation must abort the executor loop. The buggy executor swallows " +
                "CancellationException in its broad `catch (e: Exception)` and " +
                "proceeds to the next action, issuing additional `put` calls after " +
                "cancellation.",
            callCountAtCancel,
            putCallCount,
        )
    }

    // --- tombstone-sidecar full-map save race (Bug 3) ------------------------

    /**
     * Bug 3: V3Executor reads the deleted-books sidecar
     * (`.http_sync_deleted_books.json`) into a mutable map at the start of sync,
     * mutates it during the action loop, and writes the WHOLE map back at the
     * end. If the user deletes a different book during sync,
     * `BookshelfRepository.recordHttpSyncTombstone` calls
     * `HttpSyncDeletedBookStateStore.recordDeletedBook` which load-modify-saves
     * the same file. The executor's final full-map `save` overwrites the
     * concurrent write with its stale snapshot → the user's tombstone is lost.
     *
     * Repro: arrange for the executor loop to suspend mid-action (we intercept
     * the first `put` call). While the engine is parked, record a NEW tombstone
     * for a different syncId directly through the store API (this is exactly the
     * same code path BookshelfRepository takes on user-initiated delete).
     * Release the gate. Sync completes. Assert the concurrently-recorded
     * tombstone is still present in the sidecar after sync.
     *
     * Fix: the executor must mutate the sidecar through atomic per-key calls
     * (each one re-reads the latest disk state) instead of snapshot-mutate-save.
     */
    @Test
    fun concurrentRecordDeletedBookDuringSyncSurvives() = runBlocking {
        val jsonFmt = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true; encodeDefaults = true
        }
        val deletedStore = moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookStateStore(jsonFmt)

        val transport = FakeKvTransport()
        val repo = newRepo()

        // Seed an EXISTING tombstone for a syncId that has no live local book —
        // V3LocalState surfaces it as a headless tombstone row, the planner emits
        // a PushTombstone action for it, and the executor's PushTombstone path
        // (a) writes to remote via transport.put, (b) removes the entry from its
        // in-memory pendingDeletions snapshot, (c) flips deletedStateDirty=true so
        // the final full-map save WILL fire — which is the buggy clobber path.
        val existingSyncId = "existing_tomb"
        val existingRecord = moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookRecord(
            title = "Existing Tomb",
            contentType = moe.antimony.hoshi.features.sync.http.HttpSyncContentType.Mokuro,
            deletedAt = "2030-05-01T00:00:00Z",
        )
        deletedStore.recordDeletedBook(repo.booksDirectory, existingSyncId, existingRecord)

        // Suspend on the FIRST put. With the seeded pending tombstone above the
        // planner emits PushTombstone first (bucket order), so the first `put` is
        // the tombstone push itself. The executor is parked here with the
        // pendingDeletions snapshot already loaded into memory.
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var putCallCount = 0
        val suspendingTransport = object : HttpSyncKvTransport by transport {
            override suspend fun put(
                key: String,
                contentType: String,
                body: ByteArray,
            ): HttpSyncKvWriteResponse {
                val mine = ++putCallCount
                if (mine == 1) {
                    entered.complete(Unit)
                    gate.await()
                }
                return transport.put(key, contentType, body)
            }
        }

        val engine = engineFor(repo, suspendingTransport)
        val deferred = async { engine.syncOnce(configured) }

        // Wait until the executor is parked inside the first put.
        entered.await()

        // --- concurrent user-initiated delete: record a tombstone for a DIFFERENT
        // syncId via the same store API BookshelfRepository.recordHttpSyncTombstone
        // uses. The executor's snapshot does not include this entry.
        val raceSyncId = "concurrent_delete"
        val raceRecord = moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookRecord(
            title = "Concurrent Delete",
            contentType = moe.antimony.hoshi.features.sync.http.HttpSyncContentType.Mokuro,
            deletedAt = "2030-06-01T00:00:00Z",
        )
        deletedStore.recordDeletedBook(repo.booksDirectory, raceSyncId, raceRecord)
        assertTrue(
            "test precondition: concurrent tombstone must be written to disk " +
                "before sync resumes",
            deletedStore.load(repo.booksDirectory).containsKey(raceSyncId),
        )

        // Release the gate; let sync finish.
        gate.complete(Unit)
        deferred.await()

        // Under the bug, V3Executor's final `save(pendingDeletions)` clobbered the
        // concurrent write with its stale snapshot, so the new tombstone is gone.
        val final = deletedStore.load(repo.booksDirectory)
        assertTrue(
            "Concurrent recordDeletedBook during sync must not be clobbered by " +
                "the executor's final full-map save. Expected $raceSyncId to " +
                "remain in the sidecar, got $final",
            final.containsKey(raceSyncId),
        )
    }
}
