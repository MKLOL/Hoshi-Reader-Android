package moe.antimony.hoshi.features.sync.v3

import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.ai.AiChatSettingsRepository

/**
 * Step 1 of the v3 algorithm. Reads everything an immediate sync would need to know
 * about the local device into a [V3LocalSnapshot]. Stateless; reusable across syncs.
 *
 * Implementation guidance: see `docs/SYNC_V3_SPEC.md` § Step 1. Must read:
 *  - `BookRepository.loadBookEntries()` for the book list, and for each:
 *    - `loadBookmark(root)` (may be null),
 *    - `aiHistoryStore.load(root).entries` (Mokuro only — non-Mokuro returns empty),
 *  - the existing shelf-state sidecar (`.http_sync_shelf_state.json`),
 *  - the existing deleted-book sidecar (`.http_sync_deleted_books.json`),
 *  - `aiSettingsRepository?.settings?.first()` if a repo was supplied.
 *
 * The snapshot's [V3LocalSnapshot.readAt] should be a fresh `Instant.now()` so the
 * planner can break ties on tombstone vs. live state.
 */
class V3LocalState(
    private val bookRepository: BookRepository,
    private val aiHistoryStore: AiChatHistoryStore,
    private val aiSettingsRepository: AiChatSettingsRepository?,
) {
    suspend fun read(): V3LocalSnapshot {
        TODO("Implementation agent: fill per V3_SPEC § Step 1")
    }
}
