package moe.antimony.hoshi.features.sync.v3

import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec

/**
 * Step 4 of the v3 algorithm. Applies a [V3Plan] against the transport.
 *
 * Implementation guidance: see `docs/SYNC_V3_SPEC.md` § Step 4. The executor:
 *  - runs actions one at a time, in the order produced by the planner;
 *  - catches per-action exceptions and converts them to [V3Error] entries;
 *  - counts applied/pushed actions into [V3SyncResult.applied] / [V3SyncResult.pushed];
 *  - reports phase + counter progress so the UI can render a meaningful status line.
 *
 * Never calls `transport.put` / `transport.delete` directly — all writes go through
 * [V3PushOps]. Reads are fine to do directly (e.g., fetching a chat body before
 * appending it via [AiChatHistoryStore.append]).
 *
 * When importing a remote-only book, the executor:
 *  - creates the local directory via [BookRepository.createBookDirectoryForImportedTitle],
 *  - delegates unpacking to [HttpSyncPayloadCodec.downloadAndUnpack],
 *  - writes a fresh metadata sidecar with a new UUID and the manifest's originalName,
 *  - threads the resulting root through subsequent actions for the same syncId
 *    (e.g., ApplyRemoteBookmark, ImportChat that came after ImportRemoteBook in the plan).
 */
class V3Executor(
    private val bookRepository: BookRepository,
    private val aiHistoryStore: AiChatHistoryStore,
    private val payloadCodec: HttpSyncPayloadCodec,
    private val pushOps: V3PushOps,
) {
    suspend fun run(
        plan: V3Plan,
        transport: HttpSyncKvTransport,
        onProgress: suspend (V3Progress) -> Unit,
    ): V3SyncResult {
        TODO("Implementation agent: fill per V3_SPEC § Step 4")
    }
}
