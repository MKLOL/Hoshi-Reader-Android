package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.bookContentType
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.ai.AiChatLog
import java.io.File

/**
 * Reconciles local book state with the configured v2 KV sync server (see
 * `docs/HTTP_SYNC_KV.md`). Entry points:
 *
 *  - [syncOnce] — full reconciliation, called from the "Sync now" button in
 *    [HttpSyncSettingsView]. Pulls everything newer than the local `lastSyncedAt`
 *    cursor, then pushes every local book's bookmark + missing chat entries +
 *    metadata. Updates the cursor and returns a [HttpSyncResult] summary.
 *  - [pushBookmark] — single-PUT fire-and-forget, called every N page-turn saves
 *    and on reader leave (see [HttpSyncReaderHooks]). Skips the listing roundtrip.
 *  - [pushChatEntry] — single-PUT fire-and-forget, called when a new ChatGPT
 *    response is appended to the local log. The KV key is content-addressable
 *    ([chatEntryKeySuffix]), so re-pushes are idempotent.
 *
 * All schema work happens here; the network layer ([HttpSyncKvTransport]) only
 * knows about bytes. The class is unit-testable via a `FakeKvTransport`; see
 * `HttpSyncTest`.
 */
class HttpSyncManager(
    private val bookRepository: BookRepository,
    private val aiHistoryStore: AiChatHistoryStore = AiChatHistoryStore(),
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
     * One reconciliation pass. Inbound first (so a newer bookmark on the server is not
     * stomped by our outbound push), then outbound. The new cursor is returned in
     * [HttpSyncResult.newLastSyncedAt] for the caller to persist; per-book errors are
     * collected so a single corrupt entry doesn't kill the whole sync.
     */
    suspend fun syncOnce(settings: HttpSyncSettings): HttpSyncResult = withContext(ioDispatcher) {
        require(settings.isConfigured) { "HTTP sync is not configured." }
        val transport = transportFactory(settings)

        val inbound = pullChangedKeys(transport, settings.lastSyncedAt)
        val outbound = pushAllLocal(transport)

        // Cursor advances to the newest timestamp we saw across either direction. A miss
        // here would resurface the same keys on the next sync — wasteful but not wrong.
        val newCursor = listOf(settings.lastSyncedAt, inbound.maxLastModified, outbound.maxLastModified)
            .filterNotNull()
            .maxByOrNull { it } // lexicographic on RFC 3339 is chronological
        val cursorChanged = newCursor != null && newCursor != settings.lastSyncedAt

        HttpSyncResult(
            uploadedBookmarks = outbound.uploadedBookmarks,
            uploadedChatEntries = outbound.uploadedChatEntries,
            uploadedMetadata = outbound.uploadedMetadata,
            downloadedBookmarks = inbound.downloadedBookmarks,
            downloadedChatEntries = inbound.downloadedChatEntries,
            remoteOnlyBooks = inbound.remoteOnlyBooks,
            errors = inbound.errors + outbound.errors,
            newLastSyncedAt = newCursor.takeIf { cursorChanged },
        )
    }

    /**
     * PUTs the current local bookmark for [bookRoot] under `books/{syncId}/bookmark`. Used
     * by the reader's every-N-page-turn + on-leave hooks. Returns silently on success;
     * throws [HttpSyncException] on failure so the caller can log without stopping the
     * reader. The server is overwrite-by-default, so this is just one PUT.
     */
    suspend fun pushBookmark(bookRoot: File, title: String, settings: HttpSyncSettings) {
        require(settings.isConfigured) { "HTTP sync is not configured." }
        val syncId = deriveSyncId(title)
            ?: throw HttpSyncException("Book has no title to derive a syncId from.")
        val bookmark = bookRepository.loadBookmark(bookRoot)
            ?: throw HttpSyncException("No local bookmark to push for $title.")
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
     * content-addressable, so calling this twice for the same entry is a no-op on the
     * server (it overwrites with identical bytes and stamps a new lastModified).
     */
    suspend fun pushChatEntry(title: String, entry: AiChatEntry, settings: HttpSyncSettings) {
        require(settings.isConfigured) { "HTTP sync is not configured." }
        val syncId = deriveSyncId(title)
            ?: throw HttpSyncException("Book has no title to derive a syncId from.")
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

    // ----- Inbound -----------------------------------------------------------------------

    private data class InboundResult(
        val downloadedBookmarks: Int,
        val downloadedChatEntries: Int,
        val remoteOnlyBooks: Int,
        val maxLastModified: String?,
        val errors: List<String>,
    )

    private suspend fun pullChangedKeys(
        transport: HttpSyncKvTransport,
        sinceCursor: String?,
    ): InboundResult {
        var downloadedBookmarks = 0
        var downloadedChatEntries = 0
        var maxLastModified: String? = sinceCursor
        val errors = mutableListOf<String>()

        val localBookEntries = bookRepository.loadBookEntries()
        val rootsBySyncId: Map<String, File> = buildMap {
            for (entry in localBookEntries) {
                val syncId = deriveSyncId(entry.metadata.title) ?: continue
                put(syncId, entry.root)
            }
        }
        val remoteSyncIds = mutableSetOf<String>()

        var cursor: String? = null
        do {
            val page = transport.list(
                prefix = ALL_BOOKS_PREFIX,
                since = sinceCursor,
                cursor = cursor,
            )
            for (meta in page.keys) {
                if (compareRfc3339(meta.lastModified, maxLastModified) > 0) {
                    maxLastModified = meta.lastModified
                }
                val parsed = parseBookKey(meta.key) ?: continue
                remoteSyncIds += parsed.syncId
                val localRoot = rootsBySyncId[parsed.syncId] ?: continue
                runCatching {
                    when (parsed.kind) {
                        BookKeyKind.Bookmark -> if (applyBookmarkFromRemote(transport, localRoot, meta)) downloadedBookmarks += 1
                        BookKeyKind.Chat -> if (applyChatEntryFromRemote(transport, localRoot, meta)) downloadedChatEntries += 1
                        BookKeyKind.Metadata -> Unit // v2 doesn't act on metadata yet — reserved for tombstones
                    }
                }.onFailure { e ->
                    errors += "${parsed.kind.name.lowercase()} ${parsed.syncId}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            cursor = page.nextCursor
        } while (cursor != null && page.truncated)

        val remoteOnly = remoteSyncIds.count { it !in rootsBySyncId.keys }
        return InboundResult(downloadedBookmarks, downloadedChatEntries, remoteOnly, maxLastModified, errors)
    }

    private suspend fun applyBookmarkFromRemote(
        transport: HttpSyncKvTransport,
        bookRoot: File,
        meta: HttpSyncKvKeyMeta,
    ): Boolean {
        val fetched = transport.get(meta.key) ?: return false
        val blob = json.decodeFromString(
            HttpSyncBookmarkBlob.serializer(),
            fetched.body.toString(Charsets.UTF_8),
        )
        val local = bookRepository.loadBookmark(bookRoot)
        val localModified = local?.lastModified?.let(::appleSecondsToRfc3339)
        if (compareRfc3339(blob.lastModified, localModified) <= 0) {
            // Local is at least as fresh — don't downgrade.
            return false
        }
        bookRepository.saveBookmark(
            bookRoot,
            Bookmark(
                chapterIndex = blob.chapterIndex,
                progress = blob.progress,
                characterCount = blob.characterCount,
                lastModified = rfc3339ToAppleSeconds(blob.lastModified),
            ),
        )
        return true
    }

    private suspend fun applyChatEntryFromRemote(
        transport: HttpSyncKvTransport,
        bookRoot: File,
        meta: HttpSyncKvKeyMeta,
    ): Boolean {
        if (bookContentType(bookRoot) != ContentType.Mokuro) return false
        val fetched = transport.get(meta.key) ?: return false
        val blob = json.decodeFromString(
            HttpSyncChatEntryBlob.serializer(),
            fetched.body.toString(Charsets.UTF_8),
        )
        val existing = runCatching { aiHistoryStore.load(bookRoot).entries }.getOrDefault(emptyList())
        val incoming = AiChatEntry(
            bubbleText = blob.bubbleText,
            prompt = blob.prompt,
            model = blob.model,
            response = blob.response,
            timestampSeconds = blob.timestampSeconds,
        )
        if (existing.any { it.matches(incoming) }) return false
        aiHistoryStore.append(bookRoot, incoming)
        return true
    }

    // ----- Outbound ----------------------------------------------------------------------

    private data class OutboundResult(
        val uploadedBookmarks: Int,
        val uploadedChatEntries: Int,
        val uploadedMetadata: Int,
        val maxLastModified: String?,
        val errors: List<String>,
    )

    private suspend fun pushAllLocal(transport: HttpSyncKvTransport): OutboundResult {
        var uploadedBookmarks = 0
        var uploadedChatEntries = 0
        var uploadedMetadata = 0
        var maxLastModified: String? = null
        val errors = mutableListOf<String>()

        val localBooks = bookRepository.loadBookEntries()
        for (entry in localBooks) {
            val title = entry.metadata.title.orEmpty().ifBlank { continue }
            val syncId = deriveSyncId(title) ?: continue
            val root = entry.root
            try {
                val bookmark = bookRepository.loadBookmark(root)
                if (bookmark != null) {
                    val response = transport.put(
                        key = bookmarkKey(syncId),
                        contentType = JSON_CONTENT_TYPE,
                        body = json.encodeToString(HttpSyncBookmarkBlob.serializer(), bookmark.toBlob()).toByteArray(),
                    )
                    uploadedBookmarks += 1
                    maxLastModified = maxRfc(maxLastModified, response.lastModified)
                }

                val metadataResponse = transport.put(
                    key = metadataKey(syncId),
                    contentType = JSON_CONTENT_TYPE,
                    body = json.encodeToString(
                        HttpSyncMetadataBlob.serializer(),
                        HttpSyncMetadataBlob(
                            title = title,
                            contentType = HttpSyncContentType.fromLocal(bookContentType(root)),
                        ),
                    ).toByteArray(),
                )
                uploadedMetadata += 1
                maxLastModified = maxRfc(maxLastModified, metadataResponse.lastModified)

                if (bookContentType(root) == ContentType.Mokuro) {
                    val chatEntries = runCatching { aiHistoryStore.load(root).entries }
                        .getOrDefault(emptyList())
                    if (chatEntries.isNotEmpty()) {
                        // Only push entries the server doesn't have. Listing once is cheaper
                        // than a PUT per entry on a chat-heavy book.
                        val existing = transport.list(prefix = chatPrefixForBook(syncId))
                            .keys.map { it.key }.toSet()
                        for (chatEntry in chatEntries) {
                            val suffix = chatEntryKeySuffix(chatEntry.timestampSeconds, chatEntry.bubbleText, chatEntry.response)
                            val key = chatKey(syncId, suffix)
                            if (key in existing) continue
                            val response = transport.put(
                                key = key,
                                contentType = JSON_CONTENT_TYPE,
                                body = json.encodeToString(HttpSyncChatEntryBlob.serializer(), chatEntry.toBlob()).toByteArray(),
                            )
                            uploadedChatEntries += 1
                            maxLastModified = maxRfc(maxLastModified, response.lastModified)
                        }
                    }
                }
            } catch (e: HttpSyncException) {
                errors += "${title}: ${e.message}"
            }
        }
        return OutboundResult(uploadedBookmarks, uploadedChatEntries, uploadedMetadata, maxLastModified, errors)
    }

    // ----- Conversions --------------------------------------------------------------------

    private fun Bookmark.toBlob(): HttpSyncBookmarkBlob = HttpSyncBookmarkBlob(
        chapterIndex = chapterIndex,
        progress = progress,
        characterCount = characterCount,
        lastModified = lastModified?.let(::appleSecondsToRfc3339)
            ?: appleSecondsToRfc3339(0.0),
    )

    private fun AiChatEntry.toBlob(): HttpSyncChatEntryBlob = HttpSyncChatEntryBlob(
        bubbleText = bubbleText,
        prompt = prompt,
        model = model,
        response = response,
        timestampSeconds = timestampSeconds,
    )

    /** Same-entry detection for inbound dedup: bubble + timestamp uniquely identifies a chat. */
    private fun AiChatEntry.matches(other: AiChatEntry): Boolean =
        bubbleText == other.bubbleText && timestampSeconds == other.timestampSeconds

    // ----- Key parsing --------------------------------------------------------------------

    private enum class BookKeyKind { Bookmark, Chat, Metadata }

    private data class ParsedBookKey(val syncId: String, val kind: BookKeyKind)

    /**
     * Splits a `books/{syncId}/{...}` key into the parts the inbound handler needs. Unknown
     * shapes (e.g. v2.1 payload keys) return null and are quietly skipped — forward-compat.
     */
    private fun parseBookKey(key: String): ParsedBookKey? {
        if (!key.startsWith(ALL_BOOKS_PREFIX)) return null
        val rest = key.removePrefix(ALL_BOOKS_PREFIX)
        val firstSlash = rest.indexOf('/')
        if (firstSlash <= 0) return null
        val syncId = rest.substring(0, firstSlash)
        val suffix = rest.substring(firstSlash + 1)
        val kind = when {
            suffix == "bookmark" -> BookKeyKind.Bookmark
            suffix == "metadata" -> BookKeyKind.Metadata
            suffix.startsWith("chat/") -> BookKeyKind.Chat
            else -> return null
        }
        return ParsedBookKey(syncId, kind)
    }

    private fun maxRfc(left: String?, right: String?): String? = when {
        left == null -> right
        right == null -> left
        compareRfc3339(left, right) >= 0 -> left
        else -> right
    }

    private companion object {
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
    }
}

/**
 * Outcome of one [HttpSyncManager.syncOnce] pass. Granular counts so the UI can show a
 * one-line summary ("uploaded 3 bookmarks, 12 chat entries; downloaded 1 bookmark").
 */
data class HttpSyncResult(
    val uploadedBookmarks: Int,
    val uploadedChatEntries: Int,
    val uploadedMetadata: Int,
    val downloadedBookmarks: Int,
    val downloadedChatEntries: Int,
    val remoteOnlyBooks: Int,
    val errors: List<String>,
    /**
     * New cursor for [HttpSyncSettings.lastSyncedAt] — `null` means "no change, leave the
     * stored cursor alone." The caller is responsible for persisting this; see
     * [HttpSyncSettingsView] for the canonical wiring.
     */
    val newLastSyncedAt: String? = null,
) {
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (uploadedBookmarks > 0) parts += "$uploadedBookmarks bookmark${plural(uploadedBookmarks)} up"
        if (uploadedChatEntries > 0) parts += "$uploadedChatEntries chat${plural(uploadedChatEntries)} up"
        if (downloadedBookmarks > 0) parts += "$downloadedBookmarks bookmark${plural(downloadedBookmarks)} down"
        if (downloadedChatEntries > 0) parts += "$downloadedChatEntries chat${plural(downloadedChatEntries)} down"
        if (remoteOnlyBooks > 0) parts += "$remoteOnlyBooks remote-only book${plural(remoteOnlyBooks)}"
        if (parts.isEmpty()) parts += "nothing to sync"
        return parts.joinToString(", ")
    }

    private fun plural(n: Int): String = if (n == 1) "" else "s"
}
