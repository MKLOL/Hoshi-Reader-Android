package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * Race tests for the v2 [HttpSyncReconciler].
 *
 * Mirrors `V3RaceTest.concurrentRecordDeletedBookDuringSyncSurvives` — the v2
 * reconciler used the same snapshot-mutate-save pattern for the
 * `.http_sync_deleted_books.json` sidecar that Bug 3 fixed in V3Executor.
 *
 * Even after the in-store lock (`HttpSyncDeletedBookStateStore.recordDeletedBook`
 * / `removeDeletedBook` serialize on a process-wide lock), v2's reconciler still
 * races: it loads the map at the start of `pushAllLocal`, mutates a local copy,
 * and writes the WHOLE map back at the end. A concurrent
 * `BookshelfRepository.recordHttpSyncTombstone` that lands between the load and
 * the final save is silently clobbered by the reconciler's stale snapshot.
 */
class HttpSyncReconcilerRaceTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val configured = HttpSyncSettings("https://x", "t")

    private fun newBookRepository(): BookRepository =
        BookRepository(tempFolder.newFolder())

    private suspend fun importMokuroBook(repo: BookRepository, title: String) {
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
    }

    private fun reconcilerFor(
        repo: BookRepository,
        transport: HttpSyncKvTransport,
    ): HttpSyncReconciler = HttpSyncReconciler(
        bookRepository = repo,
        transportFactory = { transport },
        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    )

    /**
     * Bug 3 backport: the v2 reconciler must not clobber a concurrently-recorded
     * user tombstone with its load-modify-save full-map write.
     *
     * Repro:
     *  1. Seed an existing pending tombstone for syncId `existing_tomb`. This
     *     forces the reconciler into its `pendingDeletedBooks` loop, so the FIRST
     *     `transport.put` is the tombstone push.
     *  2. Intercept the first `put` so the reconciler parks mid-action with its
     *     stale `pendingDeletedBooks` snapshot already loaded in memory.
     *  3. While the reconciler is parked, call `recordDeletedBook` for a
     *     DIFFERENT syncId — this is exactly the code path
     *     `BookshelfRepository.recordHttpSyncTombstone` uses on a user-initiated
     *     delete. Verify the write reached disk.
     *  4. Release the gate. Let sync complete.
     *  5. Assert the concurrently-recorded tombstone survived the reconciler's
     *     final save.
     *
     * Under the bug, the reconciler's final `deletedBookStateStore.save(map)` at
     * the end of `pushAllLocal` rewrites the sidecar with its stale snapshot
     * (which never contained the concurrent entry) → the user's delete is lost.
     *
     * Under the fix, the reconciler mutates the sidecar through the atomic
     * `recordDeletedBook` / `removeDeletedBook` helpers, each of which re-reads
     * the latest disk state under the in-store lock, so a concurrent write to a
     * different key cannot be clobbered.
     */
    @Test
    fun concurrentRecordDeletedBookDuringSyncSurvives() = runBlocking {
        val deletedStore = HttpSyncDeletedBookStateStore(json)

        val transport = FakeKvTransport()
        val repo = newBookRepository()

        // Seed an existing pending tombstone — the reconciler will iterate it
        // and the FIRST transport.put it issues is the tombstone push (the
        // `pendingDeletedBooks` loop runs before `localBooks`).
        val existingSyncId = "existing_tomb"
        val existingRecord = HttpSyncDeletedBookRecord(
            title = "Existing Tomb",
            contentType = HttpSyncContentType.Mokuro,
            deletedAt = "2030-05-01T00:00:00Z",
        )
        deletedStore.recordDeletedBook(repo.booksDirectory, existingSyncId, existingRecord)

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

        val reconciler = reconcilerFor(repo, suspendingTransport)
        val deferred = async { reconciler.syncOnce(configured) }

        // Wait until the reconciler is parked inside the first put.
        entered.await()

        // Concurrent user-initiated delete for a DIFFERENT syncId. The
        // reconciler's snapshot (taken before the loop started) does not
        // include this entry.
        val raceSyncId = "concurrent_delete"
        val raceRecord = HttpSyncDeletedBookRecord(
            title = "Concurrent Delete",
            contentType = HttpSyncContentType.Mokuro,
            deletedAt = "2030-06-01T00:00:00Z",
        )
        deletedStore.recordDeletedBook(repo.booksDirectory, raceSyncId, raceRecord)
        assertTrue(
            "test precondition: concurrent tombstone must be on disk before sync resumes",
            deletedStore.load(repo.booksDirectory).containsKey(raceSyncId),
        )

        // Release the gate; let sync finish.
        gate.complete(Unit)
        deferred.await()

        val final = deletedStore.load(repo.booksDirectory)
        assertTrue(
            "Concurrent recordDeletedBook during v2 reconciler sync must not be " +
                "clobbered by the reconciler's final full-map save. Expected " +
                "$raceSyncId to remain in the sidecar, got $final",
            final.containsKey(raceSyncId),
        )
    }
}
