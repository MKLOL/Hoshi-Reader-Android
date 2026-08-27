package moe.antimony.hoshi.features.sync.v3

import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.ai.AiChatSettings
import moe.antimony.hoshi.features.ai.AiChatSettingsRepository
import moe.antimony.hoshi.features.sync.http.HttpSyncAiChatSettingsBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncBookLocks
import moe.antimony.hoshi.features.sync.http.HttpSyncBookmarkBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncChatEntryBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookRecord
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncKvWriteResponse
import moe.antimony.hoshi.features.sync.http.HttpSyncMetadataBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.HttpSyncRevisionStore
import moe.antimony.hoshi.features.sync.http.AI_CHAT_SETTINGS_KEY
import moe.antimony.hoshi.features.sync.http.SyncComparison
import moe.antimony.hoshi.features.sync.http.appleSecondsToRfc3339
import moe.antimony.hoshi.features.sync.http.bookmarkKey
import moe.antimony.hoshi.features.sync.http.compareRevisioned
import moe.antimony.hoshi.features.sync.http.metadataKey
import moe.antimony.hoshi.features.sync.http.rfc3339ToAppleSeconds
import moe.antimony.hoshi.features.sync.http.toBlob
import java.io.File

/**
 * The single push primitive set for v3. Every write that v3 performs goes through
 * this class so:
 *  - the fetch-then-PUT pattern is implemented once,
 *  - per-book locking is consistent,
 *  - the future reader-hook migration plugs in here without code duplication.
 *
 * See `docs/SYNC_V3_SPEC.md` § V3PushOps.
 */
class V3PushOps(
    private val bookRepository: BookRepository,
    private val aiHistoryStore: AiChatHistoryStore,
    private val aiSettingsRepository: AiChatSettingsRepository?,
    private val payloadCodec: HttpSyncPayloadCodec,
    private val bookLocks: HttpSyncBookLocks,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val revisionStore = HttpSyncRevisionStore(json)
    internal companion object {
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
    }

    /**
     * Conditional bookmark PUT. Holds the per-book lock for the read-then-write so a
     * concurrent reader-hook push cannot interleave with apply or push.
     *
     * Winner selection is event-time first (via [compareRevisioned]); `rev` breaks ties.
     * Uploads are stamped with the revision store's current localRev
     * — a sync is a merge, not an edit, so nothing is bumped here (only the deliberate-edit
     * hooks bump). Same rules as the v2 reconciler's `pushBookmarkIfLocalNewer`.
     */
    suspend fun pushBookmarkConditional(
        transport: HttpSyncKvTransport,
        bookRoot: File,
        syncId: String,
        localBookmark: Bookmark,
    ): PushBookmarkOutcome = bookLocks.withBookLock(bookRoot) {
        val key = bookmarkKey(syncId)
        val booksRoot = bookRepository.booksDirectory
        val localRev = revisionStore.current(booksRoot, key).localRev
        val remote = transport.get(key)
        if (remote != null) {
            val remoteBlob = runCatching {
                json.decodeFromString(
                    HttpSyncBookmarkBlob.serializer(),
                    remote.body.toString(Charsets.UTF_8),
                )
            }.getOrNull()
            if (remoteBlob != null) {
                val localStamp = localBookmark.lastModified?.let(::appleSecondsToRfc3339)
                when (compareRevisioned(
                    localRev = localRev,
                    remoteRev = remoteBlob.rev,
                    localStamp = localStamp,
                    remoteStamp = remoteBlob.lastModified,
                )) {
                    SyncComparison.REMOTE_WINS -> {
                        // Remote out-revisions us — apply locally instead of pushing.
                        bookRepository.saveBookmark(
                            bookRoot,
                            Bookmark(
                                chapterIndex = remoteBlob.chapterIndex,
                                progress = remoteBlob.progress,
                                characterCount = remoteBlob.characterCount,
                                lastModified = rfc3339ToAppleSeconds(remoteBlob.lastModified),
                            ),
                        )
                        revisionStore.noteRemote(booksRoot, key, remoteBlob.rev, appliedLocally = true)
                        return@withBookLock PushBookmarkOutcome.AppliedRemote
                    }
                    SyncComparison.TIE -> {
                        revisionStore.noteRemote(booksRoot, key, remoteBlob.rev, appliedLocally = true)
                        return@withBookLock PushBookmarkOutcome.NoOp
                    }
                    SyncComparison.LOCAL_WINS -> Unit // fall through to PUT
                }
            }
        }
        transport.put(
            key = key,
            contentType = JSON_CONTENT_TYPE,
            body = json.encodeToString(
                HttpSyncBookmarkBlob.serializer(),
                localBookmark.toBlob().copy(rev = localRev),
            ).toByteArray(),
        )
        revisionStore.noteRemote(booksRoot, key, localRev, appliedLocally = true)
        PushBookmarkOutcome.Pushed
    }

    /**
     * Idempotent chat-entry PUT — re-pushes with the same content-addressable key write
     * identical bytes, so it is safe to retry.
     */
    suspend fun pushChat(
        transport: HttpSyncKvTransport,
        bookRoot: File,
        syncId: String,
        entry: AiChatEntry,
        chatKey: String,
    ): Boolean {
        // bookRoot is unused except as a future hook for the reader path; the chat key
        // already encodes everything the server needs.
        val blob = entry.toBlob()
        transport.put(
            key = chatKey,
            contentType = JSON_CONTENT_TYPE,
            body = json.encodeToString(HttpSyncChatEntryBlob.serializer(), blob).toByteArray(),
        )
        return true
    }

    /**
     * Metadata PUT. When [expectedRemote] (the blob the planner saw on the server) equals
     * [blob] ignoring `rev`, the PUT is skipped — identical bytes would only churn the
     * server's lastModified — but the revision store still fast-forwards so a later local
     * edit out-revisions the remote chain. Mirrors the v2 reconciler's content-equality
     * skip.
     *
     * Stale-rev recheck (under the per-key lock, like [pushBookmarkConditional]'s
     * recheck-under-lock): [blob]'s `rev` was computed from a snapshot taken at the start
     * of the sync. A deliberate local edit (shelf-move / delete hook bumping localRev) or
     * an observed deeper remote write (baseRev) may have advanced the key mid-sync;
     * PUTting the plan-time blob then would overwrite the newer state on the
     * content-blind, last-write-wins server. Re-read the revision store immediately
     * before the PUT and skip when it out-revisions the plan.
     */
    suspend fun pushMetadata(
        transport: HttpSyncKvTransport,
        syncId: String,
        blob: HttpSyncMetadataBlob,
        expectedRemote: HttpSyncMetadataBlob? = null,
    ): PushMetadataOutcome {
        val key = metadataKey(syncId)
        return bookLocks.withKeyLock(key) {
            val booksRoot = bookRepository.booksDirectory
            if (expectedRemote != null && expectedRemote.copy(rev = blob.rev) == blob) {
                revisionStore.noteRemote(booksRoot, key, blob.rev, appliedLocally = true)
                return@withKeyLock PushMetadataOutcome.SkippedIdentical
            }
            val current = revisionStore.current(booksRoot, key)
            val planRev = blob.rev ?: 0
            if (current.localRev > planRev || current.baseRev > planRev) {
                // Superseded mid-sync — the newer edit's own push (or the next sync's
                // plan) carries the deeper rev; pushing now would regress it.
                return@withKeyLock PushMetadataOutcome.SkippedStale
            }
            val response = transport.put(
                key = key,
                contentType = JSON_CONTENT_TYPE,
                body = json.encodeToString(HttpSyncMetadataBlob.serializer(), blob).toByteArray(),
            )
            revisionStore.noteRemote(booksRoot, key, blob.rev, appliedLocally = true)
            PushMetadataOutcome.Pushed(response)
        }
    }

    /**
     * Payload upload. Widened to allow EPUBs as well as Mokuro (the v3 gate); v2's
     * codec already supports the EPUB content type — the only thing that gated it
     * before was the caller's Mokuro-only `if`.
     *
     * Returns `true` if a new payload was uploaded, `false` if the server already had
     * a manifest (per [HttpSyncPayloadCodec.uploadIfChanged]'s policy).
     */
    suspend fun pushPayload(
        transport: HttpSyncKvTransport,
        bookRoot: File,
        syncId: String,
        title: String,
        format: HttpSyncContentType,
        onByteProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)? = null,
    ): Boolean {
        // v3 explicitly accepts both content types.
        require(format == HttpSyncContentType.Mokuro || format == HttpSyncContentType.Epub) {
            "Unsupported payload format: $format"
        }
        return payloadCodec.uploadIfChanged(
            transport = transport,
            syncId = syncId,
            bookRoot = bookRoot,
            originalName = title,
            format = format,
            onByteProgress = onByteProgress,
        )
    }

    /** Tombstone push: writes a revisioned metadata blob with `deletedAt` set. */
    suspend fun pushTombstone(
        transport: HttpSyncKvTransport,
        syncId: String,
        record: HttpSyncDeletedBookRecord,
    ): HttpSyncKvWriteResponse {
        val key = metadataKey(syncId)
        return bookLocks.withKeyLock(key) {
            val booksRoot = bookRepository.booksDirectory
            val localRev = revisionStore.current(booksRoot, key).localRev
            val response = transport.put(
                key = key,
                contentType = JSON_CONTENT_TYPE,
                body = json.encodeToString(
                    HttpSyncMetadataBlob.serializer(),
                    HttpSyncMetadataBlob(
                        title = record.title,
                        contentType = record.contentType,
                        deletedAt = record.deletedAt,
                        rev = localRev,
                    ),
                ).toByteArray(),
            )
            revisionStore.noteRemote(booksRoot, key, localRev, appliedLocally = true)
            response
        }
    }

    /** Global ChatGPT settings PUT. */
    suspend fun pushAiSettings(
        transport: HttpSyncKvTransport,
        local: AiChatSettings,
    ): HttpSyncKvWriteResponse {
        val stamp = local.lastEditedAt
            ?: throw IllegalStateException("Cannot push AI settings without a lastEditedAt stamp")
        val blob = HttpSyncAiChatSettingsBlob(
            model = local.model,
            promptText = local.promptText,
            imagePromptText = local.imagePromptText,
            lastModified = stamp,
        )
        return transport.put(
            key = AI_CHAT_SETTINGS_KEY,
            contentType = JSON_CONTENT_TYPE,
            body = json.encodeToString(HttpSyncAiChatSettingsBlob.serializer(), blob).toByteArray(),
        )
    }

    /**
     * Applies an inbound AI-settings blob into local storage via
     * [AiChatSettingsRepository.applyFromSync]. Returns `false` when no repository was
     * configured (test path) or when a concurrent local edit raced and the CAS rejected
     * the apply.
     */
    suspend fun applyAiSettings(remote: HttpSyncAiChatSettingsBlob): Boolean {
        val repo = aiSettingsRepository ?: return false
        return repo.applyFromSync(
            model = remote.model,
            promptText = remote.promptText,
            imagePromptText = remote.imagePromptText,
            remoteLastEditedAt = remote.lastModified,
        )
    }
}
