package moe.antimony.hoshi.features.sync.v3

import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.ai.AiChatSettings
import moe.antimony.hoshi.features.ai.AiChatSettingsRepository
import moe.antimony.hoshi.features.sync.http.HttpSyncAiChatSettingsBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncBookLocks
import moe.antimony.hoshi.features.sync.http.HttpSyncBookmarkBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookRecord
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncKvWriteResponse
import moe.antimony.hoshi.features.sync.http.HttpSyncMetadataBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import java.io.File

/**
 * The single push primitive set for v3. Every write that v3 performs goes through
 * this class so:
 *  - the fetch-then-PUT pattern is implemented once,
 *  - per-book locking is consistent,
 *  - the future reader-hook migration plugs in here without code duplication.
 *
 * Implementation guidance: see `docs/SYNC_V3_SPEC.md` § V3PushOps.
 *
 * Reuse from v2 (DO NOT duplicate):
 *  - [HttpSyncPayloadCodec.uploadIfChanged] for payload push (it already implements
 *    the SHA cache + manifest-existence policy).
 *  - [HttpSyncBookLocks] for per-book mutexes.
 *  - All blob types in [moe.antimony.hoshi.features.sync.http.HttpSyncBlobs].
 */
class V3PushOps(
    private val bookRepository: BookRepository,
    private val aiHistoryStore: AiChatHistoryStore,
    private val aiSettingsRepository: AiChatSettingsRepository?,
    private val payloadCodec: HttpSyncPayloadCodec,
    private val bookLocks: HttpSyncBookLocks,
) {

    /**
     * Conditional bookmark PUT.
     *
     * Holds the per-book lock for the full read-then-write sequence. Compares local
     * `Bookmark.lastModified` (Apple seconds) to the remote
     * `HttpSyncBookmarkBlob.lastModified` (RFC 3339) via the shared helpers.
     *
     * - local strictly newer → PUT local → [PushBookmarkOutcome.Pushed].
     * - remote strictly newer → apply remote locally → [PushBookmarkOutcome.AppliedRemote].
     * - tie → no-op → [PushBookmarkOutcome.NoOp].
     */
    suspend fun pushBookmarkConditional(
        transport: HttpSyncKvTransport,
        bookRoot: File,
        syncId: String,
        localBookmark: Bookmark,
    ): PushBookmarkOutcome {
        TODO("Implementation agent: fill per V3_SPEC § V3PushOps")
    }

    /**
     * Idempotent chat-entry PUT. Server's content-addressable key means duplicate
     * pushes overwrite with identical bytes.
     *
     * @return true if the entry was uploaded; false if a malformed entry was skipped.
     */
    suspend fun pushChat(
        transport: HttpSyncKvTransport,
        bookRoot: File,
        syncId: String,
        entry: AiChatEntry,
        chatKey: String,
    ): Boolean {
        TODO("Implementation agent: fill per V3_SPEC § V3PushOps")
    }

    /** Plain metadata PUT (no conditional logic; planner already decided we want this state). */
    suspend fun pushMetadata(
        transport: HttpSyncKvTransport,
        syncId: String,
        blob: HttpSyncMetadataBlob,
    ): HttpSyncKvWriteResponse {
        TODO("Implementation agent: fill per V3_SPEC § V3PushOps")
    }

    /**
     * Payload upload. Returns `true` if a new payload was uploaded, `false` if the
     * server already had it (per [HttpSyncPayloadCodec.uploadIfChanged]'s policy).
     */
    suspend fun pushPayload(
        transport: HttpSyncKvTransport,
        bookRoot: File,
        syncId: String,
        title: String,
        format: HttpSyncContentType,
    ): Boolean {
        TODO("Implementation agent: fill per V3_SPEC § V3PushOps")
    }

    /** Tombstone push: writes a metadata blob with `deletedAt` set. */
    suspend fun pushTombstone(
        transport: HttpSyncKvTransport,
        syncId: String,
        record: HttpSyncDeletedBookRecord,
    ): HttpSyncKvWriteResponse {
        TODO("Implementation agent: fill per V3_SPEC § V3PushOps")
    }

    /** Global ChatGPT settings PUT. */
    suspend fun pushAiSettings(
        transport: HttpSyncKvTransport,
        local: AiChatSettings,
    ): HttpSyncKvWriteResponse {
        TODO("Implementation agent: fill per V3_SPEC § V3PushOps")
    }

    /**
     * Applies an inbound AI-settings blob into local storage via
     * [AiChatSettingsRepository.applyFromSync].
     *
     * @return true if the local store accepted the apply (no concurrent local edit
     *   happened between snapshot read and apply), false otherwise.
     */
    suspend fun applyAiSettings(remote: HttpSyncAiChatSettingsBlob): Boolean {
        TODO("Implementation agent: fill per V3_SPEC § V3PushOps")
    }
}
