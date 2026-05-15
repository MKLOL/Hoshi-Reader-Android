package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.Bookmark
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
    private val bookLocks: HttpSyncBookLocks = HttpSyncBookLocks(),
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
     * PUTs the current local bookmark for [bookRoot] under `books/{syncId}/bookmark` — but
     * **only** if the local bookmark is strictly newer than what the server already has.
     *
     * Overwrite protection: device A reading page 50 (stamped at T1) while device B is at
     * page 100 (stamped at T2 > T1) used to clobber B's progress when A's reader hook fired.
     * Now we fetch the remote bookmark first; if it's newer, we apply it to the local file
     * and return without pushing. If it's older or absent, we push our local copy.
     */
    suspend fun pushBookmark(bookRoot: File, title: String, settings: HttpSyncSettings) {
        require(settings.isConfigured) { "HTTP sync is not configured." }
        val syncId = deriveSyncId(title)
            ?: throw HttpSyncException("Book '$title' has no title to derive a syncId from.")
        val transport = transportFactory(settings)
        val key = bookmarkKey(syncId)
        // Hold the per-book mutex for the entire fetch + compare + write/PUT cycle so the
        // reconciler can't race us on this same key. Without it the second writer's stale
        // local copy could clobber the first's freshly-pulled-or-pushed value.
        bookLocks.withBookLock(bookRoot) {
            val local = bookRepository.loadBookmark(bookRoot)
                ?: throw HttpSyncException("No local bookmark to push for '$title'.")
            withContext(ioDispatcher) {
                // Fetch + compare. The extra GET adds ~50ms to a typical push, well worth
                // it to never lose a reading position because of a stale push.
                val remote = transport.get(key)
            if (remote != null) {
                val remoteBlob = runCatching {
                    json.decodeFromString(
                        HttpSyncBookmarkBlob.serializer(),
                        remote.body.toString(Charsets.UTF_8),
                    )
                }.getOrNull()
                if (remoteBlob != null) {
                    val localStamp = local.lastModified?.let(::appleSecondsToRfc3339)
                    if (compareRfc3339(remoteBlob.lastModified, localStamp) > 0) {
                        // Server has a newer bookmark; pull it into the local file and bail
                        // on the push. Caller treats success as "the local state is now in
                        // sync with the server", which is true either way.
                        bookRepository.saveBookmark(
                            bookRoot,
                            Bookmark(
                                chapterIndex = remoteBlob.chapterIndex,
                                progress = remoteBlob.progress,
                                characterCount = remoteBlob.characterCount,
                                lastModified = rfc3339ToAppleSeconds(remoteBlob.lastModified),
                            ),
                        )
                        return@withContext
                    }
                }
            }
            transport.put(
                key = key,
                contentType = JSON_CONTENT_TYPE,
                body = json.encodeToString(HttpSyncBookmarkBlob.serializer(), local.toBlob()).toByteArray(),
            )
            }
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
