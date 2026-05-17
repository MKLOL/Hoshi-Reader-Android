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
import moe.antimony.hoshi.features.sync.http.AI_CHAT_SETTINGS_KEY
import moe.antimony.hoshi.features.sync.http.appleSecondsToRfc3339
import moe.antimony.hoshi.features.sync.http.bookmarkKey
import moe.antimony.hoshi.features.sync.http.compareRfc3339
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
    internal companion object {
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
    }

    /**
     * Conditional bookmark PUT. Holds the per-book lock for the read-then-write so a
     * concurrent reader-hook push cannot interleave with apply or push.
     */
    suspend fun pushBookmarkConditional(
        transport: HttpSyncKvTransport,
        bookRoot: File,
        syncId: String,
        localBookmark: Bookmark,
    ): PushBookmarkOutcome = bookLocks.withBookLock(bookRoot) {
        val key = bookmarkKey(syncId)
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
                val cmp = compareRfc3339(remoteBlob.lastModified, localStamp)
                when {
                    cmp > 0 -> {
                        // Remote strictly newer — apply locally instead of pushing.
                        bookRepository.saveBookmark(
                            bookRoot,
                            Bookmark(
                                chapterIndex = remoteBlob.chapterIndex,
                                progress = remoteBlob.progress,
                                characterCount = remoteBlob.characterCount,
                                lastModified = rfc3339ToAppleSeconds(remoteBlob.lastModified),
                            ),
                        )
                        return@withBookLock PushBookmarkOutcome.AppliedRemote
                    }
                    cmp == 0 -> return@withBookLock PushBookmarkOutcome.NoOp
                    else -> Unit // local strictly newer → fall through to PUT
                }
            }
        }
        transport.put(
            key = key,
            contentType = JSON_CONTENT_TYPE,
            body = json.encodeToString(HttpSyncBookmarkBlob.serializer(), localBookmark.toBlob())
                .toByteArray(),
        )
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

    suspend fun pushMetadata(
        transport: HttpSyncKvTransport,
        syncId: String,
        blob: HttpSyncMetadataBlob,
    ): HttpSyncKvWriteResponse = transport.put(
        key = metadataKey(syncId),
        contentType = JSON_CONTENT_TYPE,
        body = json.encodeToString(HttpSyncMetadataBlob.serializer(), blob).toByteArray(),
    )

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
        )
    }

    /** Tombstone push: writes a metadata blob with `deletedAt` set. */
    suspend fun pushTombstone(
        transport: HttpSyncKvTransport,
        syncId: String,
        record: HttpSyncDeletedBookRecord,
    ): HttpSyncKvWriteResponse = transport.put(
        key = metadataKey(syncId),
        contentType = JSON_CONTENT_TYPE,
        body = json.encodeToString(
            HttpSyncMetadataBlob.serializer(),
            HttpSyncMetadataBlob(
                title = record.title,
                contentType = record.contentType,
                deletedAt = record.deletedAt,
            ),
        ).toByteArray(),
    )

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
