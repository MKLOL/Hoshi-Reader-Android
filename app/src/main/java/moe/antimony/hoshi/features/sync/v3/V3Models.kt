package moe.antimony.hoshi.features.sync.v3

import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatSettings
import moe.antimony.hoshi.features.sync.http.HttpSyncAiChatSettingsBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncBookmarkBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookRecord
import moe.antimony.hoshi.features.sync.http.HttpSyncMetadataBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadManifest
import java.io.File
import java.time.Instant

/**
 * Data types used by the v3 sync engine. See `docs/SYNC_V3_SPEC.md` for the full design.
 *
 * v3 deliberately reuses the v2 wire-blob types from
 * [moe.antimony.hoshi.features.sync.http]; the only NEW types live here.
 */

// ─── Snapshots ───────────────────────────────────────────────────────────────────

/**
 * Local-state snapshot captured at the start of a sync pass. Captured all at once
 * so concurrent local edits during sync don't poison the planner's inputs.
 */
data class V3LocalSnapshot(
    val books: List<V3LocalBook>,
    val aiSettings: AiChatSettings?,
    val readAt: Instant,
)

/**
 * One local book's state as visible to the planner.
 */
data class V3LocalBook(
    val bookId: String,
    val syncId: String,
    val title: String,
    val root: File,
    val contentType: ContentType,
    val shelfName: String?,
    /** RFC 3339 UTC from `.http_sync_shelf_state.json`; null if no per-book shelf record exists. */
    val shelfUpdatedAt: String?,
    val bookmark: Bookmark?,
    /** Empty for non-Mokuro books. */
    val chatEntries: List<AiChatEntry>,
    /** Set when a local delete is staged but not yet pushed. */
    val pendingDeletion: HttpSyncDeletedBookRecord?,
)

/**
 * Remote-state snapshot built from a paginated `list` + per-key body fetches.
 *
 * Bodies for metadata, manifest, bookmark, and ai_chat_settings ARE fetched here
 * because the planner needs them to decide. Chat entry bodies and payload zip
 * bodies are NOT fetched here — only their key presence is recorded.
 */
data class V3RemoteSnapshot(
    val books: Map<String, V3RemoteBook>,
    val aiSettings: HttpSyncAiChatSettingsBlob? = null,
    val aiSettingsLastModified: String? = null,
)

data class V3RemoteBook(
    val syncId: String,
    val metadata: HttpSyncMetadataBlob? = null,
    val metadataLastModified: String? = null,
    val manifest: HttpSyncPayloadManifest? = null,
    val manifestLastModified: String? = null,
    val bookmark: HttpSyncBookmarkBlob? = null,
    val bookmarkLastModified: String? = null,
    /** All `books/{syncId}/chat/...` keys observed on the server. */
    val chatKeys: Set<String> = emptySet(),
)

// ─── Plan and actions ────────────────────────────────────────────────────────────

sealed interface V3Action {
    val syncId: String?

    data class DeleteLocalBook(val root: File, override val syncId: String) : V3Action
    data class ImportRemoteBook(override val syncId: String, val manifest: HttpSyncPayloadManifest) : V3Action
    data class ApplyRemoteBookmark(val root: File, override val syncId: String, val blob: HttpSyncBookmarkBlob) : V3Action
    data class ApplyRemoteMetadata(val root: File?, override val syncId: String, val blob: HttpSyncMetadataBlob) : V3Action
    data class ImportChat(val root: File, override val syncId: String, val key: String) : V3Action
    data class PushBookmark(val root: File, override val syncId: String, val bookmark: Bookmark, val expectedRemote: HttpSyncBookmarkBlob?) : V3Action
    data class PushMetadata(override val syncId: String, val title: String, val blob: HttpSyncMetadataBlob) : V3Action
    data class PushChat(val root: File, override val syncId: String, val entry: AiChatEntry, val key: String) : V3Action
    data class PushPayload(val root: File, override val syncId: String, val title: String, val format: HttpSyncContentType) : V3Action
    data class PushTombstone(override val syncId: String, val record: HttpSyncDeletedBookRecord) : V3Action
    data class PushAiSettings(val local: AiChatSettings) : V3Action {
        override val syncId: String? = null
    }
    data class ApplyAiSettings(val remote: HttpSyncAiChatSettingsBlob) : V3Action {
        override val syncId: String? = null
    }
}

data class V3Plan(
    val actions: List<V3Action>,
    /** Books that exist on the server but cannot be imported yet (e.g. manifest missing). */
    val pendingRemoteOnlyBooks: Set<String> = emptySet(),
)

// ─── Result ──────────────────────────────────────────────────────────────────────

data class V3SyncResult(
    val applied: V3AppliedCounts,
    val pushed: V3PushedCounts,
    val remoteOnlyBooks: Int,
    val errors: List<V3Error>,
) {
    val totalActions: Int
        get() = applied.total + pushed.total
}

data class V3AppliedCounts(
    val bookmarks: Int = 0,
    val chatEntries: Int = 0,
    val payloads: Int = 0,
    val metadataDeletes: Int = 0,
    val shelfPlacements: Int = 0,
    val aiSettings: Int = 0,
) {
    val total: Int get() = bookmarks + chatEntries + payloads + metadataDeletes + shelfPlacements + aiSettings
}

data class V3PushedCounts(
    val bookmarks: Int = 0,
    val chatEntries: Int = 0,
    val metadata: Int = 0,
    val payloads: Int = 0,
    val tombstones: Int = 0,
    val aiSettings: Int = 0,
) {
    val total: Int get() = bookmarks + chatEntries + metadata + payloads + tombstones + aiSettings
}

data class V3Error(
    val syncId: String?,
    val action: String,
    val message: String,
)

// ─── Progress ────────────────────────────────────────────────────────────────────

data class V3Progress(
    val phase: V3Phase,
    val message: String,
    val detail: String? = null,
    val completed: Int? = null,
    val total: Int? = null,
)

enum class V3Phase {
    ReadingLocal,
    ListingRemote,
    Planning,
    PushingTombstones,
    ApplyingMetadata,
    DeletingBooks,
    ImportingPayloads,
    ApplyingRemoteState,
    PushingLocalState,
    SyncingAppSettings,
    Done,
}

/** Outcome of [V3PushOps.pushBookmarkConditional]. */
sealed interface PushBookmarkOutcome {
    /** Server didn't have a bookmark, or local was strictly newer; we PUT local. */
    data object Pushed : PushBookmarkOutcome

    /** Remote was strictly newer; we applied it locally instead of pushing. */
    data object AppliedRemote : PushBookmarkOutcome

    /** Tie / no-op. */
    data object NoOp : PushBookmarkOutcome
}
