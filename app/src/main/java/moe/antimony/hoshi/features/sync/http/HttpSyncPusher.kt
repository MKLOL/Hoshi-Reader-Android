package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.ai.AiChatEntry
import java.io.File

/**
 * Reader-hot, fire-and-forget single-PUT hooks. One class with one job: push the local
 * state for **one** book up to the server. Nothing reads from the server here; nothing
 * paginates; nothing reconciles. Cursor advancement is the [HttpSyncReconciler]'s job.
 *
 * Both methods throw [HttpSyncException] on failure. Callers (the reader hooks) wrap
 * each call in `runCatching { }` so a 401 / offline / 5xx never affects the reader UI.
 *
 * Why split from [HttpSyncReconciler]: the reader's failure surface shrinks to one class,
 * and the reconciler's listing/cursor logic gets to be the heavy one. See the audit at
 * commit 42b2f17 for the rationale.
 */
class HttpSyncPusher(
    private val bookRepository: BookRepository,
    private val transportFactory: (HttpSyncSettings) -> HttpSyncKvTransport = { settings ->
        HttpSyncKvClient(settings.baseUrl, settings.bearerToken)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * PUTs the current local bookmark for [bookRoot] under `books/{syncId}/bookmark`. Used
     * by the every-N-page-turn + on-leave hooks ([HttpSyncReaderHooks]). One round-trip,
     * no listing.
     */
    suspend fun pushBookmark(bookRoot: File, title: String, settings: HttpSyncSettings) {
        require(settings.isConfigured) { "HTTP sync is not configured." }
        val syncId = deriveSyncId(title)
            ?: throw HttpSyncException("Book '$title' has no title to derive a syncId from.")
        val bookmark = bookRepository.loadBookmark(bookRoot)
            ?: throw HttpSyncException("No local bookmark to push for '$title'.")
        val blob = bookmark.toBlob()
        withContext(ioDispatcher) {
            transportFactory(settings).put(
                key = bookmarkKey(syncId),
                contentType = JSON_CONTENT_TYPE,
                body = json.encodeToString(HttpSyncBookmarkBlob.serializer(), blob).toByteArray(),
            )
        }
    }

    /**
     * PUTs a single chat entry at `books/{syncId}/chat/{ts}-{contenthash}`. The key is
     * content-addressable, so re-pushes are idempotent on the server.
     */
    suspend fun pushChatEntry(title: String, entry: AiChatEntry, settings: HttpSyncSettings) {
        require(settings.isConfigured) { "HTTP sync is not configured." }
        val syncId = deriveSyncId(title)
            ?: throw HttpSyncException("Book '$title' has no title to derive a syncId from.")
        val blob = entry.toBlob()
        val suffix = chatEntryKeySuffix(entry.timestampSeconds, entry.bubbleText, entry.response)
        withContext(ioDispatcher) {
            transportFactory(settings).put(
                key = chatKey(syncId, suffix),
                contentType = JSON_CONTENT_TYPE,
                body = json.encodeToString(HttpSyncChatEntryBlob.serializer(), blob).toByteArray(),
            )
        }
    }

    internal companion object {
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
    }
}
