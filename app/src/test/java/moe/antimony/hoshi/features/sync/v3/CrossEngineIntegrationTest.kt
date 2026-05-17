package moe.antimony.hoshi.features.sync.v3

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.ai.AiChatSettings
import moe.antimony.hoshi.features.ai.AiChatSettingsRepository
import moe.antimony.hoshi.features.sync.http.FakeKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookRecord
import moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookStateStore
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.HttpSyncReconciler
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import moe.antimony.hoshi.features.sync.http.bookmarkKey
import moe.antimony.hoshi.features.sync.http.deriveSyncId
import moe.antimony.hoshi.features.sync.http.metadataKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Cross-engine integration tests for the v2 ↔ v3 sync cutover.
 *
 * Two clients share the same in-memory KV (a single [FakeKvTransport]). Client A uses
 * the legacy [HttpSyncReconciler] (v2). Client B uses the new [V3SyncEngine] (v3). We
 * script user actions against each and assert the other side converges — that's the
 * safety net behind the "v2 → v3 transition is seamless" claim.
 *
 * Scenarios covered (matches Rec 2 in the redesign notes; user-prioritized 1/3/4 plus
 * the reverse-direction bookmark variant for symmetry):
 *  - v2-writes-bookmark → v3-reads-bookmark.
 *  - v3-writes-bookmark → v2-reads-bookmark (reverse symmetry).
 *  - v2 vs v3 LWW: the newer timestamp wins regardless of which engine wrote it.
 *  - v2 delete + tombstone → v3 sees the book vanish.
 *
 * Two devices share the [FakeKvTransport] (the "server"); each device has its own
 * [BookRepository] root and its own copy of the engine pointed at the shared transport.
 * No production code is modified — this is purely a test harness over the existing
 * engines and the existing fake transport.
 */
class CrossEngineIntegrationTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val configured = HttpSyncSettings("https://x", "t", enabled = true)

    private fun newRepo(name: String): BookRepository =
        BookRepository(tempFolder.newFolder(name))

    private fun v2Reconciler(
        repo: BookRepository,
        transport: HttpSyncKvTransport,
        history: AiChatHistoryStore = AiChatHistoryStore(),
        aiSettings: AiChatSettingsRepository? = null,
    ): HttpSyncReconciler = HttpSyncReconciler(
        bookRepository = repo,
        aiHistoryStore = history,
        aiSettingsRepository = aiSettings,
        transportFactory = { transport },
        payloadCodec = HttpSyncPayloadCodec(Dispatchers.Unconfined),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun v3Engine(
        repo: BookRepository,
        transport: HttpSyncKvTransport,
        history: AiChatHistoryStore = AiChatHistoryStore(),
        aiSettings: AiChatSettingsRepository? = null,
    ): V3SyncEngine = V3SyncEngine(
        bookRepository = repo,
        aiHistoryStore = history,
        aiSettingsRepository = aiSettings,
        payloadCodec = HttpSyncPayloadCodec(Dispatchers.Unconfined),
        transportFactory = { transport },
        ioDispatcher = Dispatchers.Unconfined,
    )

    /** Minimal in-memory [DataStore] so AI-settings tests don't need an Android Context. */
    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(mutablePreferencesOf())
        override val data: Flow<Preferences> get() = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.update { next }
            return next
        }
    }

    private fun newAiRepo(): AiChatSettingsRepository =
        AiChatSettingsRepository(InMemoryPreferencesDataStore())

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
                // Mirror BookshelfRepository.saveMokuroMetadata: a real import always stamps
                // `importedAt` so the re-import-after-tombstone path in both engines has the
                // local timestamp it needs to compare against any remote `deletedAt`.
                importedAt = Instant.now().toString(),
            ),
        )
        root.resolve("mokuro.json").writeText("{}")
        root.resolve("pages").mkdirs()
        root.resolve("pages/p1.png").writeBytes(byteArrayOf(0x42))
        return root
    }

    /**
     * Like [importMokuroBook], but writes a parseable `mokuro.json` that references a real
     * `pages/0001.jpg`. The parser surfaces that first page as the volume's `coverImagePath`,
     * so the *receiving* device's executor can resolve a non-null `metadata.cover` after
     * unpacking the payload.
     */
    private suspend fun importMokuroBookWithCover(
        repo: BookRepository,
        title: String,
    ): File {
        val root = repo.createBookDirectoryForImportedTitle(title)
        repo.saveMetadata(
            root,
            BookMetadata(
                id = UUID.randomUUID().toString(),
                title = title,
                cover = null,
                folder = root.name,
                lastAccess = 0.0,
                importedAt = Instant.now().toString(),
            ),
        )
        root.resolve("mokuro.json").writeText(
            """{"version":"1.0","title":"$title","pages":[{"img_path":"pages/0001.jpg","img_width":100,"img_height":200,"blocks":[]}]}""",
        )
        root.resolve("pages").mkdirs()
        root.resolve("pages/0001.jpg").writeBytes(byteArrayOf(0x42, 0x43, 0x44))
        return root
    }

    /** Local-shelf write helper — mirrors the user action of moving a book onto a named shelf. */
    private suspend fun placeBookOnShelf(repo: BookRepository, root: File, shelfName: String) {
        val bookId = repo.loadMetadata(root)?.id ?: error("metadata missing for ${root.name}")
        val existing = repo.loadShelves()
        val cleaned = existing.map { it.copy(bookIds = it.bookIds.filterNot { id -> id == bookId }) }
        val target = cleaned.find { it.name == shelfName }
        val updated = if (target != null) {
            cleaned.map { s ->
                if (s.name == shelfName) s.copy(bookIds = (s.bookIds + bookId).distinct()) else s
            }
        } else {
            cleaned + BookShelf(shelfName, listOf(bookId))
        }
        repo.saveShelves(updated)
    }

    /** Reads the shelf name a book currently lives on, or `null` if unshelved. */
    private suspend fun shelfOf(repo: BookRepository, root: File): String? {
        val bookId = repo.loadMetadata(root)?.id ?: return null
        return repo.loadShelves().firstOrNull { bookId in it.bookIds }?.name
    }

    /**
     * Stages a tombstone for [title] in [repo]'s `.http_sync_deleted_books.json` sidecar
     * the same way `BookshelfRepository.recordHttpSyncTombstone` would on a user-initiated
     * delete. Both the v2 reconciler and the v3 engine read this sidecar to decide which
     * sync IDs to push tombstone metadata for.
     */
    private fun stageTombstone(repo: BookRepository, title: String) {
        val syncId = deriveSyncId(title) ?: return
        val booksRoot = repo.booksDirectory
        booksRoot.mkdirs()
        val store = HttpSyncDeletedBookStateStore(json)
        store.recordDeletedBook(
            booksRoot = booksRoot,
            syncId = syncId,
            record = HttpSyncDeletedBookRecord(
                title = title,
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = Instant.now().toString(),
            ),
        )
    }

    // ── Scenario 1: v2 writes a bookmark, v3 reads it ──────────────────────────────────

    /**
     * Round-trip user flow:
     *  - Device A (v2) imports a Mokuro book + records a bookmark at page 50.
     *  - Device A syncs (via the legacy [HttpSyncReconciler]).
     *  - Device B (v3) syncs (via the [V3SyncEngine]).
     *  - Device B sees the book + bookmark at page 50.
     *
     * This is the highest-priority scenario for the cutover: an existing v2 user is on the
     * old build and the new build pulls their state.
     */
    @Test
    fun v2WritesBookmark_v3PullsIt() = runBlocking {
        val transport = FakeKvTransport()
        val title = "Crossover One"

        // Device A on v2 imports + bookmarks + syncs.
        val repoA = newRepo("a-root")
        val rootA = importMokuroBook(repoA, title)
        repoA.saveBookmark(
            rootA,
            Bookmark(chapterIndex = 0, progress = 0.5, characterCount = 50, lastModified = 800_000_000.0),
        )
        val v2Result = v2Reconciler(repoA, transport).syncOnce(configured)
        assertTrue("v2 push errors: ${v2Result.errors}", v2Result.errors.isEmpty())

        // Sanity: the bookmark key landed on the shared server.
        val syncId = deriveSyncId(title) ?: error("syncId derive failed for $title")
        assertNotNull("v2 didn't push bookmark", transport.kv[bookmarkKey(syncId)])

        // Device B on v3 syncs and picks the book + bookmark up.
        val repoB = newRepo("b-root")
        val v3Result = v3Engine(repoB, transport).syncOnce(configured)
        assertEquals("v3 pull errors", emptyList<V3Error>(), v3Result.errors)

        val bookB = repoB.loadBookEntries().single { it.metadata.title == title }.root
        val bookmarkB = repoB.loadBookmark(bookB)
        assertNotNull("v3 device did not pick up the bookmark", bookmarkB)
        assertEquals(50, bookmarkB!!.characterCount)
        assertEquals(0.5, bookmarkB.progress, 0.0)
    }

    // ── Scenario 2: v3 writes a bookmark, v2 reads it ──────────────────────────────────

    /**
     * The reverse direction of scenario 1. After cutover, a v3-only user shares a server
     * with a v2 client (older build that hasn't yet flipped the feature flag); the v2
     * client must converge on the v3 device's writes.
     */
    @Test
    fun v3WritesBookmark_v2PullsIt() = runBlocking {
        val transport = FakeKvTransport()
        val title = "Crossover Two"

        // Device B on v3 imports + bookmarks + syncs.
        val repoB = newRepo("b-root")
        val rootB = importMokuroBook(repoB, title)
        repoB.saveBookmark(
            rootB,
            Bookmark(chapterIndex = 0, progress = 0.3, characterCount = 30, lastModified = 800_000_001.0),
        )
        val v3Result = v3Engine(repoB, transport).syncOnce(configured)
        assertEquals("v3 push errors", emptyList<V3Error>(), v3Result.errors)

        val syncId = deriveSyncId(title) ?: error("syncId derive failed for $title")
        assertNotNull("v3 didn't push bookmark", transport.kv[bookmarkKey(syncId)])

        // Device A on v2 syncs and picks the book + bookmark up.
        val repoA = newRepo("a-root")
        val v2Result = v2Reconciler(repoA, transport).syncOnce(configured)
        assertTrue("v2 pull errors: ${v2Result.errors}", v2Result.errors.isEmpty())

        val bookA = repoA.loadBookEntries().single { it.metadata.title == title }.root
        val bookmarkA = repoA.loadBookmark(bookA)
        assertNotNull("v2 device did not pick up the bookmark", bookmarkA)
        assertEquals(30, bookmarkA!!.characterCount)
        assertEquals(0.3, bookmarkA.progress, 0.0)
    }

    // ── Scenario 3: v2 and v3 race on the same bookmark — LWW picks the newer one ─────

    /**
     * Both devices already have the book. Each writes a bookmark at a different timestamp
     * and syncs. The contract: regardless of which engine wrote first, the device that
     * pulls last should see the bookmark with the *newer* timestamp.
     *
     * Sequence:
     *  1. A (v2) imports, syncs → server has the book.
     *  2. B (v3) syncs → both devices have the book.
     *  3. A (v2) writes bookmark at lastModified=T1=1_000.0, syncs.
     *  4. B (v3) writes bookmark at lastModified=T2=2_000.0 (strictly newer), syncs.
     *  5. A (v2) re-syncs.
     *
     * Final state on both devices: characterCount=20 (B's write).
     *
     * If a real cross-version LWW bug exists, this is the test that catches it — for
     * example if one engine compares bookmark `lastModified` in Apple seconds while the
     * other compares the wrapper key's RFC 3339 stamp on a value whose internal time
     * doesn't match. The v2 reconciler converts via [appleSecondsToRfc3339] before
     * comparing remote bookmark stamps, so a 2_000.0 vs 1_000.0 gap in Apple seconds maps
     * directly to a strict-greater RFC 3339 ordering.
     */
    @Test
    fun bookmarkLwwBetweenV2AndV3PicksNewerSide() = runBlocking {
        val transport = FakeKvTransport()
        val title = "Crossover LWW"

        // 1. v2 device imports + syncs.
        val repoA = newRepo("a-root")
        val rootA = importMokuroBook(repoA, title)
        v2Reconciler(repoA, transport).syncOnce(configured).also {
            assertTrue("v2 import sync errors: ${it.errors}", it.errors.isEmpty())
        }

        // 2. v3 device pulls the book down.
        val repoB = newRepo("b-root")
        v3Engine(repoB, transport).syncOnce(configured).also {
            assertEquals("v3 initial pull errors", emptyList<V3Error>(), it.errors)
        }
        val rootB = repoB.loadBookEntries().single { it.metadata.title == title }.root

        // 3. A writes an OLDER bookmark and pushes via v2.
        repoA.saveBookmark(
            rootA,
            Bookmark(chapterIndex = 0, progress = 0.1, characterCount = 10, lastModified = 1_000.0),
        )
        v2Reconciler(repoA, transport).syncOnce(configured).also {
            assertTrue("v2 LWW push errors: ${it.errors}", it.errors.isEmpty())
        }

        // 4. B writes a NEWER bookmark (strictly greater Apple-seconds stamp) and pushes via v3.
        repoB.saveBookmark(
            rootB,
            Bookmark(chapterIndex = 0, progress = 0.2, characterCount = 20, lastModified = 2_000.0),
        )
        v3Engine(repoB, transport).syncOnce(configured).also {
            assertEquals("v3 LWW push errors", emptyList<V3Error>(), it.errors)
        }

        // 5. A re-syncs via v2 — must converge on B's newer write.
        v2Reconciler(repoA, transport).syncOnce(configured).also {
            assertTrue("v2 LWW pull errors: ${it.errors}", it.errors.isEmpty())
        }

        val bookmarkA = repoA.loadBookmark(rootA)
        assertNotNull("v2 device lost its bookmark", bookmarkA)
        assertEquals(
            "LWW must pick the newer (v3-written) bookmark",
            20,
            bookmarkA!!.characterCount,
        )

        // And convergence: B is still on its own write.
        val bookmarkB = repoB.loadBookmark(rootB)
        assertNotNull(bookmarkB)
        assertEquals(20, bookmarkB!!.characterCount)
    }

    // ── Scenario 4: v2 deletes a book + tombstone; v3 sees it vanish ─────────────────

    /**
     * v2 tombstone interop. Sequence:
     *  1. A (v2) imports, syncs.
     *  2. B (v3) syncs → both have the book.
     *  3. A (v2) records a tombstone (same sidecar code path BookshelfRepository uses on a
     *     user-initiated delete), deletes the local book, syncs.
     *  4. B (v3) syncs → B's local book is gone.
     *
     * The server-side contract is that a `metadata` blob with `deletedAt != null` is a
     * tombstone — both engines recognise this shape (v2 in `pullChangedKeys` Pass 2, v3
     * in `V3Planner.compute`/`V3Executor.applyMetadataFromRemote`).
     */
    @Test
    fun v2DeletesBookWithTombstone_v3SeesItGone() = runBlocking {
        val transport = FakeKvTransport()
        val title = "Crossover Tomb"

        // 1. A imports + syncs.
        val repoA = newRepo("a-root")
        val rootA = importMokuroBook(repoA, title)
        v2Reconciler(repoA, transport).syncOnce(configured).also {
            assertTrue("v2 import sync errors: ${it.errors}", it.errors.isEmpty())
        }

        // 2. B pulls.
        val repoB = newRepo("b-root")
        v3Engine(repoB, transport).syncOnce(configured).also {
            assertEquals("v3 initial pull errors", emptyList<V3Error>(), it.errors)
        }
        assertTrue(
            "B should have the book before deletion",
            repoB.loadBookEntries().any { it.metadata.title == title },
        )

        // 3. A records a tombstone (BookshelfRepository.recordHttpSyncTombstone equivalent),
        //    deletes the book, and syncs via v2.
        stageTombstone(repoA, title)
        repoA.deleteBook(rootA)
        val v2Tomb = v2Reconciler(repoA, transport).syncOnce(configured)
        assertTrue("v2 tombstone push errors: ${v2Tomb.errors}", v2Tomb.errors.isEmpty())

        // Server should now expose the tombstone shape on the metadata key. The blob
        // schema always serializes `deletedAt` (as `null` when absent), so we have to
        // assert a NON-null value rather than just key presence.
        val syncId = deriveSyncId(title) ?: error("syncId derive failed for $title")
        val metadataBytes = transport.kv[metadataKey(syncId)]?.body
        assertNotNull("metadata key missing after v2 tombstone push", metadataBytes)
        val rawMetadata = metadataBytes!!.toString(Charsets.UTF_8)
        assertFalse(
            "v2 published metadata with deletedAt=null after a tombstone push; got: $rawMetadata",
            rawMetadata.contains("\"deletedAt\":null"),
        )
        assertTrue(
            "v2 didn't publish a non-null deletedAt tombstone, got: $rawMetadata",
            Regex("\"deletedAt\":\"[^\"]+\"").containsMatchIn(rawMetadata),
        )

        // 4. B syncs via v3 — book must be gone.
        val v3Pull = v3Engine(repoB, transport).syncOnce(configured)
        assertEquals("v3 tombstone pull errors", emptyList<V3Error>(), v3Pull.errors)

        val bTitlesAfter = repoB.loadBookEntries().map { it.metadata.title }
        assertFalse(
            "B (v3) should have deleted $title after pulling the v2 tombstone, still has: $bTitlesAfter",
            title in bTitlesAfter,
        )
    }

    // ── Scenario 5: v3 deletes a book + tombstone; v2 sees it vanish ─────────────────

    /**
     * Reverse-direction tombstone interop. Same shape as scenario 4 but engines swapped:
     *  1. B (v3) imports + syncs.
     *  2. A (v2) syncs → both have the book.
     *  3. B (v3) stages a tombstone + deletes the local book + syncs.
     *  4. A (v2) syncs → A's local book is gone.
     *
     * Added for symmetry: if scenario 4 reveals a re-import bug in v2's
     * inbound-then-outbound ordering, this test confirms whether v3 has the same shape on
     * the push side. Per spec, the v3 planner handles `pendingDeletion` *before* checking
     * remote state, so the tombstone push should win regardless of any remote-only-import
     * race.
     */
    @Test
    fun v3DeletesBookWithTombstone_v2SeesItGone() = runBlocking {
        val transport = FakeKvTransport()
        val title = "Crossover Tomb Reverse"

        // 1. B (v3) imports + syncs.
        val repoB = newRepo("b-root")
        val rootB = importMokuroBook(repoB, title)
        v3Engine(repoB, transport).syncOnce(configured).also {
            assertEquals("v3 import sync errors", emptyList<V3Error>(), it.errors)
        }

        // 2. A (v2) pulls.
        val repoA = newRepo("a-root")
        v2Reconciler(repoA, transport).syncOnce(configured).also {
            assertTrue("v2 initial pull errors: ${it.errors}", it.errors.isEmpty())
        }
        assertTrue(
            "A should have the book before deletion",
            repoA.loadBookEntries().any { it.metadata.title == title },
        )

        // 3. B records a tombstone, deletes the book, and syncs via v3.
        stageTombstone(repoB, title)
        repoB.deleteBook(rootB)
        val v3Tomb = v3Engine(repoB, transport).syncOnce(configured)
        assertEquals("v3 tombstone push errors", emptyList<V3Error>(), v3Tomb.errors)

        // Server metadata must now carry a non-null deletedAt.
        val syncId = deriveSyncId(title) ?: error("syncId derive failed for $title")
        val metadataBytes = transport.kv[metadataKey(syncId)]?.body
        assertNotNull("metadata key missing after v3 tombstone push", metadataBytes)
        val rawMetadata = metadataBytes!!.toString(Charsets.UTF_8)
        assertFalse(
            "v3 published metadata with deletedAt=null after a tombstone push; got: $rawMetadata",
            rawMetadata.contains("\"deletedAt\":null"),
        )
        assertTrue(
            "v3 didn't publish a non-null deletedAt tombstone, got: $rawMetadata",
            Regex("\"deletedAt\":\"[^\"]+\"").containsMatchIn(rawMetadata),
        )

        // 4. A syncs via v2 — book must be gone.
        val v2Pull = v2Reconciler(repoA, transport).syncOnce(configured)
        assertTrue("v2 tombstone pull errors: ${v2Pull.errors}", v2Pull.errors.isEmpty())

        val aTitlesAfter = repoA.loadBookEntries().map { it.metadata.title }
        assertFalse(
            "A (v2) should have deleted $title after pulling the v3 tombstone, still has: $aTitlesAfter",
            title in aTitlesAfter,
        )
    }

    // ── Scenario 6: v2 imports a book onto shelf X; fresh v3 device pulls — book lands on X.

    /**
     * Cross-version shelf interop on a fresh device. v2 imports a book + places it on the
     * "Light Novels" shelf via `BookRepository.saveShelves`, then syncs. The v2 outbound
     * writes a metadata blob carrying `shelfName=Light Novels` (and a `shelfUpdatedAt`
     * derived from the local `.bookshelves.json` mtime — see [HttpSyncReconciler.localShelvesUpdatedAt]).
     * The v3 fresh device's planner sees a remote-only book + `shelfName`, threads the
     * shelf placement into the [V3Action.ImportRemoteBook] action (Bug 2 codepath), and
     * the executor applies it.
     */
    @Test
    fun v2ImportsOnShelf_v3FreshDeviceLandsOnSameShelf() = runBlocking {
        val transport = FakeKvTransport()
        val title = "Crossover Shelf v2 → v3"
        val shelf = "Light Novels"

        // A (v2) imports + places on shelf + syncs.
        val repoA = newRepo("a-root")
        val rootA = importMokuroBook(repoA, title)
        placeBookOnShelf(repoA, rootA, shelf)
        v2Reconciler(repoA, transport).syncOnce(configured).also {
            assertTrue("v2 push errors: ${it.errors}", it.errors.isEmpty())
        }

        // B (v3) syncs from scratch.
        val repoB = newRepo("b-root")
        val v3Result = v3Engine(repoB, transport).syncOnce(configured)
        assertEquals("v3 pull errors", emptyList<V3Error>(), v3Result.errors)

        val bookB = repoB.loadBookEntries().single { it.metadata.title == title }.root
        assertEquals(
            "v3 fresh device should have placed the book on the v2-imported shelf",
            shelf,
            shelfOf(repoB, bookB),
        )
    }

    // ── Scenario 7: v3 imports a book onto shelf X; fresh v2 device pulls — book lands on X.

    /**
     * The reverse of scenario 6. v3 publishes shelf placement in the same `shelfName` /
     * `shelfUpdatedAt` fields of the metadata blob, so a fresh v2 device that hits the
     * "remote-only book" branch (`importRemoteOnlyBook`) reads those fields and applies
     * the shelf during inbound.
     */
    @Test
    fun v3ImportsOnShelf_v2FreshDeviceLandsOnSameShelf() = runBlocking {
        val transport = FakeKvTransport()
        val title = "Crossover Shelf v3 → v2"
        val shelf = "Manga"

        // B (v3) imports + places on shelf + syncs.
        val repoB = newRepo("b-root")
        val rootB = importMokuroBook(repoB, title)
        placeBookOnShelf(repoB, rootB, shelf)
        v3Engine(repoB, transport).syncOnce(configured).also {
            assertEquals("v3 push errors", emptyList<V3Error>(), it.errors)
        }

        // A (v2) syncs from scratch.
        val repoA = newRepo("a-root")
        val v2Result = v2Reconciler(repoA, transport).syncOnce(configured)
        assertTrue("v2 pull errors: ${v2Result.errors}", v2Result.errors.isEmpty())

        val bookA = repoA.loadBookEntries().single { it.metadata.title == title }.root
        assertEquals(
            "v2 fresh device should have placed the book on the v3-imported shelf",
            shelf,
            shelfOf(repoA, bookA),
        )
    }

    // ── Scenario 8: shelf LWW — newer write wins regardless of which engine made it. ──

    /**
     * Both devices already have the book on "Original". A (v2) moves it to "Shelf A" at T1
     * and syncs. B (v3) moves it to "Shelf B" at T2 > T1 and syncs. A (v2) re-syncs and
     * must converge on "Shelf B".
     *
     * v2 keys its shelf-LWW timestamp on the `.bookshelves.json` mtime ([HttpSyncReconciler.localShelvesUpdatedAt])
     * unless a `.http_sync_shelf_state.json` sidecar already pins a specific
     * `shelfUpdatedAt`. To make the timestamps deterministic without sleeping, we set the
     * shelves file's mtime explicitly via `File.setLastModified` after each write.
     */
    @Test
    fun shelfLwwBetweenV2AndV3PicksNewerSide() = runBlocking {
        val transport = FakeKvTransport()
        val title = "Crossover Shelf LWW"

        // 1. A (v2) imports + syncs.
        val repoA = newRepo("a-root")
        val rootA = importMokuroBook(repoA, title)
        v2Reconciler(repoA, transport).syncOnce(configured).also {
            assertTrue("v2 import sync errors: ${it.errors}", it.errors.isEmpty())
        }

        // 2. B (v3) syncs to pull the book.
        val repoB = newRepo("b-root")
        v3Engine(repoB, transport).syncOnce(configured).also {
            assertEquals("v3 initial pull errors", emptyList<V3Error>(), it.errors)
        }
        val rootB = repoB.loadBookEntries().single { it.metadata.title == title }.root

        // 3. A places on "Shelf A" at T1 (older) and syncs.
        placeBookOnShelf(repoA, rootA, "Shelf A")
        val t1Millis = Instant.parse("2030-01-01T00:00:00Z").toEpochMilli()
        repoA.booksDirectory.resolve(".bookshelves.json").setLastModified(t1Millis)
        v2Reconciler(repoA, transport).syncOnce(configured).also {
            assertTrue("v2 shelf-A push errors: ${it.errors}", it.errors.isEmpty())
        }

        // 4. B places on "Shelf B" at T2 > T1 and syncs.
        placeBookOnShelf(repoB, rootB, "Shelf B")
        val t2Millis = Instant.parse("2030-01-02T00:00:00Z").toEpochMilli()
        repoB.booksDirectory.resolve(".bookshelves.json").setLastModified(t2Millis)
        v3Engine(repoB, transport).syncOnce(configured).also {
            assertEquals("v3 shelf-B push errors", emptyList<V3Error>(), it.errors)
        }

        // 5. A re-syncs via v2 — must converge on B's newer "Shelf B".
        v2Reconciler(repoA, transport).syncOnce(configured).also {
            assertTrue("v2 LWW pull errors: ${it.errors}", it.errors.isEmpty())
        }

        assertEquals(
            "shelf LWW must pick the newer (v3-written) shelf",
            "Shelf B",
            shelfOf(repoA, rootA),
        )
        assertEquals("B should still be on its own shelf", "Shelf B", shelfOf(repoB, rootB))
    }

    // ── Scenario 9: AI chat settings v2 → v3 (Bug 7 fresh-install scenario). ──────────

    /**
     * v2 writes AI settings (model / promptText / imagePromptText) with a custom prompt and
     * syncs. A fresh v3 device with no local AI settings syncs → its settings must reflect
     * the same blob. This is the round-trip flavour of Bug 7: a fresh install's v3 device
     * must not "win" the LWW with its null `lastEditedAt` over a real remote stamp.
     */
    @Test
    fun aiSettingsRoundTripV2ToV3() = runBlocking {
        val transport = FakeKvTransport()
        val aiA = newAiRepo()
        aiA.update {
            it.copy(
                model = "v2-model",
                promptText = "v2 custom prompt.",
                imagePromptText = "v2 custom image prompt.",
            )
        }
        val expectedStamp = aiA.settings.first().lastEditedAt
        assertNotNull("v2 device should have a non-null stamp after a user edit", expectedStamp)

        val repoA = newRepo("a-root")
        v2Reconciler(repoA, transport, aiSettings = aiA).syncOnce(configured).also {
            assertTrue("v2 push errors: ${it.errors}", it.errors.isEmpty())
        }

        // Fresh v3 device.
        val repoB = newRepo("b-root")
        val aiB = newAiRepo()
        val v3Result = v3Engine(repoB, transport, aiSettings = aiB).syncOnce(configured)
        assertEquals("v3 AI settings pull errors", emptyList<V3Error>(), v3Result.errors)

        val pulled = aiB.settings.first()
        assertEquals("v2-model", pulled.model)
        assertEquals("v2 custom prompt.", pulled.promptText)
        assertEquals("v2 custom image prompt.", pulled.imagePromptText)
        assertEquals(expectedStamp, pulled.lastEditedAt)
    }

    // ── Scenario 10: AI chat settings v3 → v2. ────────────────────────────────────────

    /**
     * Reverse of scenario 9. v3 writes AI settings + syncs. Fresh v2 device syncs → v2's
     * settings must reflect the v3-written blob.
     */
    @Test
    fun aiSettingsRoundTripV3ToV2() = runBlocking {
        val transport = FakeKvTransport()
        val aiB = newAiRepo()
        aiB.update {
            it.copy(
                model = "v3-model",
                promptText = "v3 custom prompt.",
                imagePromptText = "v3 custom image prompt.",
            )
        }
        val expectedStamp = aiB.settings.first().lastEditedAt
        assertNotNull("v3 device should have a non-null stamp after a user edit", expectedStamp)

        val repoB = newRepo("b-root")
        v3Engine(repoB, transport, aiSettings = aiB).syncOnce(configured).also {
            assertEquals("v3 push errors", emptyList<V3Error>(), it.errors)
        }

        // Fresh v2 device.
        val repoA = newRepo("a-root")
        val aiA = newAiRepo()
        val v2Result = v2Reconciler(repoA, transport, aiSettings = aiA).syncOnce(configured)
        assertTrue("v2 AI settings pull errors: ${v2Result.errors}", v2Result.errors.isEmpty())

        val pulled = aiA.settings.first()
        assertEquals("v3-model", pulled.model)
        assertEquals("v3 custom prompt.", pulled.promptText)
        assertEquals("v3 custom image prompt.", pulled.imagePromptText)
        assertEquals(expectedStamp, pulled.lastEditedAt)
    }

    // ── Scenario 11: AI chat settings LWW — both devices write, newer wins. ───────────

    /**
     * Both devices have AI settings populated. A (v2) makes a change. B (v3) makes a later
     * change (Lamport-stamp `lastEditedAt` guarantees strictly newer than any seen stamp).
     * B pushes via v3, A re-pulls via v2 — A's settings reflect B's newer edit.
     */
    @Test
    fun aiSettingsLwwBetweenV2AndV3PicksNewerSide() = runBlocking {
        val transport = FakeKvTransport()
        val aiA = newAiRepo()
        val aiB = newAiRepo()

        // 1. A edits and syncs (so server has A's settings).
        aiA.update { it.copy(model = "a-old", promptText = "a-old prompt", imagePromptText = "a-old image") }
        val repoA = newRepo("a-root")
        v2Reconciler(repoA, transport, aiSettings = aiA).syncOnce(configured)

        // 2. B syncs to pull A's settings.
        val repoB = newRepo("b-root")
        v3Engine(repoB, transport, aiSettings = aiB).syncOnce(configured)
        // Sanity: B should now have A's settings.
        assertEquals("a-old", aiB.settings.first().model)

        // 3. A makes its OWN edit (older stamp will be re-stamped by Lamport via update()).
        aiA.update { it.copy(model = "a-newer-than-original") }
        v2Reconciler(repoA, transport, aiSettings = aiA).syncOnce(configured)

        // 4. B makes a still-newer edit (strictly later than A's via Lamport-monotonic stamping
        //    against B's current `lastEditedAt`, which is at least as recent as A's last edit
        //    after the previous sync).
        v3Engine(repoB, transport, aiSettings = aiB).syncOnce(configured) // pull A's edit
        aiB.update { it.copy(model = "b-newest") }
        val bStamp = aiB.settings.first().lastEditedAt
        assertNotNull(bStamp)
        v3Engine(repoB, transport, aiSettings = aiB).syncOnce(configured)

        // 5. A re-syncs — must converge on B's newer write.
        v2Reconciler(repoA, transport, aiSettings = aiA).syncOnce(configured)

        val finalA = aiA.settings.first()
        assertEquals("LWW must pick the newer (v3-written) AI settings", "b-newest", finalA.model)
        assertEquals(bStamp, finalA.lastEditedAt)
        // And B is still on its own write.
        assertEquals("b-newest", aiB.settings.first().model)
    }

    // ── Scenario 12: Re-import after delete (cross-engine, Bug 1 interaction). ────────

    /**
     * v2 imports a book, syncs, deletes (with tombstone), syncs again. v3 device pulls and
     * loses its local copy of the book. Then v2 re-imports a book with the same syncId
     * (same title), syncs. v3 must pick the book back up — Bug 1 was the v3 planner's old
     * behaviour of preferring its local tombstone over a remote re-import; the fix lets a
     * remote import-stamp >= local tombstone-stamp restore the book.
     *
     * **FAILS — real cross-version bug** (currently ignored so the rest of the suite stays
     * green). The failure mode is reproducible and symmetric across both engines:
     *
     *  - The server retains a metadata blob with `deletedAt != null` indefinitely after a
     *    tombstone push; nothing in the protocol garbage-collects it.
     *  - When A re-imports the same title (same syncId) without first re-issuing a "live"
     *    marker on the server, **both engines unconditionally honour the remote tombstone**:
     *    - v2 reconciler (`HttpSyncReconciler.kt:454`, Pass 2 of `pullChangedKeys`):
     *      `if (remote.blob.deletedAt != null) { bookRepository.deleteBook(it); ... }` —
     *      deletes A's re-imported book regardless of when it was imported locally.
     *    - v2 reconciler outbound (`HttpSyncReconciler.kt:984`): same shape, deletes again
     *      on the outbound pass if Pass 2 somehow missed it.
     *    - v3 planner (`V3Planner.kt:62-73`): `val remoteDeletedAt = r?.metadata?.deletedAt;
     *      if (r != null && remoteDeletedAt != null) { ... deleteLocalBooks += ...; continue }`
     *      — same unconditional delete.
     *  - Net effect: A's re-import sync (Step 5) deletes A's own book locally and pushes
     *    nothing; B never sees the book come back.
     *
     * Documented contract gap: neither engine has an "import-after-tombstone" path. The
     * correct fix is probably an `importedAt` LWW compare against `deletedAt` in both
     * engines: if local `importedAt > remote.deletedAt`, treat it as a re-import and
     * overwrite the tombstone. That's a real protocol change, not a test fix, so this
     * scenario is held out until that fix lands.
     *
     * Fixed: both engines now compare local `importedAt` against the remote tombstone's
     * `deletedAt` and treat a strictly-newer local import as an overwrite of the tombstone.
     * See `HttpSyncMetadataBlob.importedAt` (stamped on every fresh import) and the matching
     * branches in `V3Planner.compute` and `HttpSyncReconciler.pullChangedKeys` / `pushAllLocal`.
     */
    @Test
    fun reImportAfterDeleteCrossEngine() = runBlocking {
        val transport = FakeKvTransport()
        val title = "Crossover Re-Import"

        // 1. A (v2) imports + syncs.
        val repoA = newRepo("a-root")
        val rootA = importMokuroBook(repoA, title)
        v2Reconciler(repoA, transport).syncOnce(configured).also {
            assertTrue("v2 import sync errors: ${it.errors}", it.errors.isEmpty())
        }

        // 2. B (v3) syncs — pulls the book.
        val repoB = newRepo("b-root")
        v3Engine(repoB, transport).syncOnce(configured).also {
            assertEquals("v3 initial pull errors", emptyList<V3Error>(), it.errors)
        }
        assertTrue(repoB.loadBookEntries().any { it.metadata.title == title })

        // 3. A deletes (with tombstone) + syncs.
        stageTombstone(repoA, title)
        repoA.deleteBook(rootA)
        v2Reconciler(repoA, transport).syncOnce(configured).also {
            assertTrue("v2 tombstone sync errors: ${it.errors}", it.errors.isEmpty())
        }

        // 4. B (v3) syncs — picks up the tombstone, deletes locally.
        v3Engine(repoB, transport).syncOnce(configured).also {
            assertEquals("v3 tombstone pull errors", emptyList<V3Error>(), it.errors)
        }
        assertFalse(
            "B should have deleted the book after pulling the tombstone",
            repoB.loadBookEntries().any { it.metadata.title == title },
        )

        // 5. A re-imports the same title and syncs. The new local import has no local
        //    tombstone (deleteBook + the v2 tombstone-clear pass on sync removed it from A's
        //    sidecar), so the metadata push must overwrite the server-side tombstone.
        val rootA2 = importMokuroBook(repoA, title)
        v2Reconciler(repoA, transport).syncOnce(configured).also {
            assertTrue("v2 re-import sync errors: ${it.errors}", it.errors.isEmpty())
        }

        // 6. B (v3) syncs again — must get the book back.
        v3Engine(repoB, transport).syncOnce(configured).also {
            assertEquals("v3 re-import pull errors", emptyList<V3Error>(), it.errors)
        }
        assertTrue(
            "B (v3) should have re-imported $title after A's re-import; current titles: ${repoB.loadBookEntries().map { it.metadata.title }}",
            repoB.loadBookEntries().any { it.metadata.title == title },
        )
        // Stop referencing rootA2 once syncs are done; the path stays valid only on A.
        @Suppress("UNUSED_VARIABLE")
        val _unused = rootA2
    }

    // ── Scenario 13: Cover after sync (regression for the cover bug), v2 → v3. ────────

    /**
     * v2 imports a Mokuro book (with a parseable `mokuro.json` referencing `pages/0001.jpg`),
     * syncs. v3 fresh device syncs → the entry must have a non-null `metadata.cover` AND
     * `BookRepository.coverFile` must resolve to a real file. Mirrors the
     * `freshDeviceImportPopulatesCoverPath…` regression test, but cross-engine.
     */
    @Test
    fun coverAfterCrossEngineSyncV2ToV3() = runBlocking {
        val transport = FakeKvTransport()
        val title = "Crossover Cover v2→v3"

        // A (v2) imports a parseable book + syncs.
        val repoA = newRepo("a-root")
        importMokuroBookWithCover(repoA, title)
        v2Reconciler(repoA, transport).syncOnce(configured).also {
            assertTrue("v2 push errors: ${it.errors}", it.errors.isEmpty())
        }

        // B (v3) syncs from scratch.
        val repoB = newRepo("b-root")
        v3Engine(repoB, transport).syncOnce(configured).also {
            assertEquals("v3 pull errors", emptyList<V3Error>(), it.errors)
        }

        val imported = repoB.loadBookEntries().single { it.metadata.title == title }
        assertNotNull(
            "v3 entry's metadata.cover must be populated after v2 → v3 sync",
            imported.metadata.cover,
        )
        val coverFile = repoB.coverFile(imported)
        assertNotNull("coverFile must resolve to a real file after v2 → v3 sync", coverFile)
        assertTrue("cover file must exist on disk", coverFile!!.isFile)
    }

    // ── Scenario 14: Cover after sync, v3 → v2. ───────────────────────────────────────

    /** Reverse direction of scenario 13. */
    @Test
    fun coverAfterCrossEngineSyncV3ToV2() = runBlocking {
        val transport = FakeKvTransport()
        val title = "Crossover Cover v3→v2"

        val repoB = newRepo("b-root")
        importMokuroBookWithCover(repoB, title)
        v3Engine(repoB, transport).syncOnce(configured).also {
            assertEquals("v3 push errors", emptyList<V3Error>(), it.errors)
        }

        val repoA = newRepo("a-root")
        v2Reconciler(repoA, transport).syncOnce(configured).also {
            assertTrue("v2 pull errors: ${it.errors}", it.errors.isEmpty())
        }

        val imported = repoA.loadBookEntries().single { it.metadata.title == title }
        assertNotNull(
            "v2 entry's metadata.cover must be populated after v3 → v2 sync",
            imported.metadata.cover,
        )
        val coverFile = repoA.coverFile(imported)
        assertNotNull("coverFile must resolve to a real file after v3 → v2 sync", coverFile)
        assertTrue("cover file must exist on disk", coverFile!!.isFile)
    }

    // ── Scenario 15: Bookmark conflict on the same chapter, different progress, LWW. ──

    /**
     * Same chapter, different intra-chapter progress, different timestamps. LWW must
     * pick the strictly-newer write regardless of which side it came from. Mirrors
     * scenario 3 but with both bookmarks landing in the same chapter — guards against a
     * hypothetical bug where one engine "merges" within a chapter and the other replaces.
     */
    @Test
    fun bookmarkConflictSameChapterDifferentProgressLww() = runBlocking {
        val transport = FakeKvTransport()
        val title = "Crossover Same-Chapter LWW"

        val repoA = newRepo("a-root")
        val rootA = importMokuroBook(repoA, title)
        v2Reconciler(repoA, transport).syncOnce(configured).also {
            assertTrue("v2 import sync errors: ${it.errors}", it.errors.isEmpty())
        }

        val repoB = newRepo("b-root")
        v3Engine(repoB, transport).syncOnce(configured).also {
            assertEquals("v3 initial pull errors", emptyList<V3Error>(), it.errors)
        }
        val rootB = repoB.loadBookEntries().single { it.metadata.title == title }.root

        // Both bookmarks live in chapter 0, but with different progress.
        // A (v2) writes a NEWER bookmark this time, to vary direction.
        repoB.saveBookmark(
            rootB,
            Bookmark(chapterIndex = 0, progress = 0.25, characterCount = 25, lastModified = 3_000.0),
        )
        v3Engine(repoB, transport).syncOnce(configured).also {
            assertEquals("v3 push errors", emptyList<V3Error>(), it.errors)
        }
        repoA.saveBookmark(
            rootA,
            Bookmark(chapterIndex = 0, progress = 0.85, characterCount = 85, lastModified = 4_000.0),
        )
        v2Reconciler(repoA, transport).syncOnce(configured).also {
            assertTrue("v2 push errors: ${it.errors}", it.errors.isEmpty())
        }

        // B re-syncs — must converge on A's newer write.
        v3Engine(repoB, transport).syncOnce(configured).also {
            assertEquals("v3 LWW pull errors", emptyList<V3Error>(), it.errors)
        }
        val finalB = repoB.loadBookmark(rootB)
        assertNotNull("v3 lost its bookmark", finalB)
        assertEquals(
            "same-chapter LWW must pick the newer (v2-written) bookmark",
            85,
            finalB!!.characterCount,
        )
        assertEquals(0, finalB.chapterIndex)
        assertEquals(0.85, finalB.progress, 0.0)
    }
}
