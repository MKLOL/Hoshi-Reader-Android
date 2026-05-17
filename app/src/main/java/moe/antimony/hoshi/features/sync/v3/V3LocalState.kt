package moe.antimony.hoshi.features.sync.v3

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.bookContentType
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.ai.AiChatSettingsRepository
import moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookStateStore
import moe.antimony.hoshi.features.sync.http.HttpSyncShelfStateStore
import moe.antimony.hoshi.features.sync.http.deriveSyncId
import java.time.Instant

/**
 * Step 1 of the v3 algorithm. Reads everything an immediate sync would need to know
 * about the local device into a [V3LocalSnapshot]. Stateless; reusable across syncs.
 *
 * Implementation guidance: see `docs/SYNC_V3_SPEC.md` § Step 1. Reads:
 *  - `BookRepository.loadBookEntries()` for the book list, and for each:
 *    - `loadBookmark(root)` (may be null),
 *    - `aiHistoryStore.load(root).entries` (Mokuro only — non-Mokuro returns empty),
 *  - the existing shelf-state sidecar (`.http_sync_shelf_state.json`),
 *  - the existing deleted-book sidecar (`.http_sync_deleted_books.json`),
 *  - `aiSettingsRepository?.settings?.first()` if a repo was supplied.
 *
 * The snapshot's [V3LocalSnapshot.readAt] is captured fresh per call.
 */
class V3LocalState(
    private val bookRepository: BookRepository,
    private val aiHistoryStore: AiChatHistoryStore,
    private val aiSettingsRepository: AiChatSettingsRepository?,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val shelfStateStore = HttpSyncShelfStateStore(json)
    private val deletedBookStateStore = HttpSyncDeletedBookStateStore(json)

    suspend fun read(): V3LocalSnapshot {
        val entries = bookRepository.loadBookEntries()
        val shelfRecords = shelfStateStore.load(bookRepository.booksDirectory)
        val deletedRecords = deletedBookStateStore.load(bookRepository.booksDirectory)
            .let { records ->
                // Bug 1: a live local book with the same syncId as a recorded
                // tombstone means the user re-imported the title after deleting
                // (and before the next sync flushed the tombstone). The user wants
                // this book — drop the stale tombstone BEFORE building the snapshot
                // so the planner sees an ordinary live book, and persist the cleanup
                // so future syncs don't re-trigger the wipe.
                // Spec contract (`docs/SYNC_V3_SPEC.md` §449): "Re-import after delete.
                // A deletes; tombstone is on server; A re-imports the same title —
                // local re-import survives, doesn't get wiped by its own tombstone."
                val liveSyncIds = entries.asSequence()
                    .mapNotNull { e -> e.metadata.title.takeUnless { it.isNullOrBlank() }?.let(::deriveSyncId) }
                    .toSet()
                val stale = records.keys.intersect(liveSyncIds)
                if (stale.isEmpty()) {
                    records
                } else {
                    // Bug 3 coordination: clear stale entries one at a time via
                    // the atomic per-key store helper instead of a full-map
                    // `save(pruned)`. The full-map save would clobber any
                    // tombstone added concurrently by the user's delete path
                    // (BookshelfRepository.recordHttpSyncTombstone) between this
                    // load and the save. The per-key remove re-reads disk under
                    // the store's lock and only touches the keys we want gone.
                    for (syncId in stale) {
                        runCatching {
                            deletedBookStateStore.removeDeletedBook(
                                bookRepository.booksDirectory,
                                syncId,
                            )
                        }
                    }
                    records.filterKeys { it !in stale }
                }
            }
        val shelves = bookRepository.loadShelves()
        val shelvesUpdatedAt: String? = bookRepository.shelvesLastModifiedMillis()
            ?.let { Instant.ofEpochMilli(it).toString() }
        // Build a quick bookId -> shelfName lookup.
        val shelfNameByBookId = linkedMapOf<String, String>()
        for (shelf in shelves) {
            for (bookId in shelf.bookIds) {
                shelfNameByBookId.putIfAbsent(bookId, shelf.name)
            }
        }

        val seenSyncIds = mutableSetOf<String>()
        val books = mutableListOf<V3LocalBook>()
        for (entry in entries) {
            val title = entry.metadata.title.orEmpty()
            if (title.isBlank()) continue
            val syncId = deriveSyncId(title) ?: continue
            // Tolerate a syncId collision across multiple local books — keep the first
            // (most-recently-read on disk per BookRepository sort) and silently drop the
            // duplicate. The planner can only emit one action set per syncId anyway.
            if (!seenSyncIds.add(syncId)) continue

            val bookmark = runCatching { bookRepository.loadBookmark(entry.root) }.getOrNull()
            val contentType = bookContentType(entry.root)
            val chatEntries = if (contentType == moe.antimony.hoshi.epub.ContentType.Mokuro) {
                runCatching { aiHistoryStore.load(entry.root).entries }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val shelfName = shelfNameByBookId[entry.metadata.id]
            val sidecar = shelfRecords[syncId]
            // Mirror v2's `localShelfUpdatedAtForBook`: the sidecar wins when it matches
            // the current shelf; otherwise fall back to the directory mtime so the planner
            // has a non-null stamp to compare against the remote shelfUpdatedAt.
            val shelfUpdatedAt: String? = when {
                sidecar != null && sidecar.shelfName == shelfName -> sidecar.updatedAt
                sidecar != null || shelfName != null -> shelvesUpdatedAt ?: Instant.now().toString()
                else -> null
            }
            val pendingDeletion = deletedRecords[syncId]
            books += V3LocalBook(
                bookId = entry.metadata.id,
                syncId = syncId,
                title = title,
                root = entry.root,
                contentType = contentType,
                shelfName = shelfName,
                shelfUpdatedAt = shelfUpdatedAt,
                bookmark = bookmark,
                chatEntries = chatEntries,
                pendingDeletion = pendingDeletion,
                importedAt = entry.metadata.importedAt,
            )
        }

        // Tombstones can exist for books that have no live local entry — surface them as
        // headless V3LocalBook rows so the planner emits PushTombstone for them.
        for ((syncId, record) in deletedRecords) {
            if (syncId in seenSyncIds) continue
            seenSyncIds += syncId
            books += V3LocalBook(
                bookId = "",
                syncId = syncId,
                title = record.title,
                root = bookRepository.booksDirectory.resolve(".tombstone-$syncId"),
                contentType = record.contentType.toLocal(),
                shelfName = null,
                shelfUpdatedAt = null,
                bookmark = null,
                chatEntries = emptyList(),
                pendingDeletion = record,
            )
        }

        val aiSettings = aiSettingsRepository?.let { repo ->
            runCatching { repo.settings.first() }.getOrNull()
        }

        return V3LocalSnapshot(
            books = books,
            aiSettings = aiSettings,
            readAt = Instant.now(),
        )
    }
}
