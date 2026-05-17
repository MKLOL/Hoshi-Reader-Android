package moe.antimony.hoshi.features.sync.v3

/**
 * Single-device round-trip tests for [V3SyncEngine] against a real [StubKvServer].
 *
 * Required coverage:
 *  - Fresh device imports a Mokuro book, syncs, server has metadata + payload.zip + manifest.
 *  - Same for EPUB (proves v3 widens the gate).
 *  - Two BookRepositorys against the same stub server: A imports + syncs, B syncs → B has the book.
 *  - Bookmark round-trip: A.saveBookmark, A.sync, B.sync, B.loadBookmark matches.
 *  - Chat round-trip: A.append, A.sync, B.sync, B's history contains the entry.
 *  - ai_chat_settings round-trip.
 *  - Tombstone round-trip: A.delete, A.sync, B.sync, B's local book is gone.
 *  - Repeat the same syncOnce 3x — no growth in server state, no errors.
 */
class V3SyncIntegrationTest {
    // Implementation agent: populate this file with @Test methods.
}
