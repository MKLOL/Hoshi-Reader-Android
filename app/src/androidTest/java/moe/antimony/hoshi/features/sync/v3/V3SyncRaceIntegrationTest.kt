package moe.antimony.hoshi.features.sync.v3

/**
 * Multi-device race scenarios for [V3SyncEngine] against a real [StubKvServer].
 * These are the "really good" scenarios the user asked for: multiple devices,
 * scripted page-turns, mid-sync edits, opposite operations, long-running sessions.
 *
 * Implementation agent: implement EVERY scenario from `docs/SYNC_V3_SPEC.md`
 * § Scripted multi-device integration scenarios. Each scenario is its own @Test.
 *
 * Use the harness:
 * ```
 * private class SimDevice(val name: String, val repo: BookRepository, val history: AiChatHistoryStore, val engine: V3SyncEngine) {
 *     suspend fun importMokuro(title: String, pages: Int = 1): File
 *     suspend fun importEpub(title: String): File
 *     suspend fun turnPage(book: File, toCharacter: Int)
 *     suspend fun chat(book: File, bubbleText: String, response: String)
 *     suspend fun moveToShelf(book: File, shelfName: String?)
 *     suspend fun delete(book: File)
 *     suspend fun sync(): V3SyncResult
 * }
 * ```
 *
 * Each test:
 *  1. spins up a [StubKvServer],
 *  2. constructs N `SimDevice`s with separate `BookRepository`s under temp dirs,
 *  3. scripts a sequence of actions across them,
 *  4. asserts the final state of EACH device matches the script's expectations,
 *  5. asserts no per-action errors except where the spec explicitly allows them.
 *
 * The scenarios in particular the user wants to nail:
 *  - "go pages" (scripted page turns through many positions)
 *  - "change shit" (shelf moves, chat, delete + reimport)
 *  - "upload shit" (large payloads, multipart)
 *  - "should behave really well" (convergence + idempotency + no data loss)
 */
class V3SyncRaceIntegrationTest {
    // Implementation agent: populate this file with @Test methods for spec scenarios 1–20.
}
