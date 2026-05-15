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
import moe.antimony.hoshi.epub.BookMetadata
import java.io.File
import java.util.UUID

/**
 * Full bidirectional reconciliation against the v2 KV server. Called from the manual
 * "Sync now" button in [HttpSyncSettingsView].
 *
 * Algorithm:
 *  1. **Inbound**. `GET /v1/kv?prefix=books/&since={cursor}`, paginate via `nextCursor`,
 *     and for each returned key fetch the body and apply locally if newer (LWW per key
 *     on bookmarks; set-union on chat entries).
 *  2. **Outbound**. Walk every local book, PUT bookmark + metadata, PUT each chat entry
 *     the server doesn't already have (idempotent: chat keys are content-addressable so
 *     re-pushes write identical bytes).
 *  3. Compute the new cursor as `max(inbound.lastModified, outbound.lastModified)`. Return
 *     it in [HttpSyncResult.newLastSyncedAt] for the caller to persist.
 *
 * The reader's fire-and-forget pushes (every-5-page-turns, on-leave, on-chat-reply) go
 * through [HttpSyncPusher] instead and bypass this entire flow — they are one PUT each
 * and need none of the listing or cursor logic.
 *
 * Per-book errors are collected into [HttpSyncResult.errors] so one corrupt book never
 * kills the whole pass.
 */
class HttpSyncReconciler(
    private val bookRepository: BookRepository,
    private val aiHistoryStore: AiChatHistoryStore = AiChatHistoryStore(),
    private val payloadCodec: HttpSyncPayloadCodec = HttpSyncPayloadCodec(),
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
     * One reconciliation pass. Inbound first so a newer server bookmark is not stomped
     * by our outbound push.
     */
    suspend fun syncOnce(settings: HttpSyncSettings): HttpSyncResult = withContext(ioDispatcher) {
        require(settings.isConfigured) { "HTTP sync is not configured." }
        val transport = transportFactory(settings)

        val inbound = pullChangedKeys(transport, settings.lastSyncedAt)
        val outbound = pushAllLocal(transport)

        val newCursor = maxRfc(
            maxRfc(settings.lastSyncedAt, inbound.maxLastModified),
            outbound.maxLastModified,
        )
        val cursorChanged = newCursor != null && newCursor != settings.lastSyncedAt

        HttpSyncResult(
            uploadedBookmarks = outbound.uploadedBookmarks,
            uploadedChatEntries = outbound.uploadedChatEntries,
            uploadedMetadata = outbound.uploadedMetadata,
            uploadedPayloads = outbound.uploadedPayloads,
            downloadedBookmarks = inbound.downloadedBookmarks,
            downloadedChatEntries = inbound.downloadedChatEntries,
            downloadedPayloads = inbound.downloadedPayloads,
            remoteOnlyBooks = inbound.remoteOnlyBooks,
            errors = inbound.errors + outbound.errors,
            newLastSyncedAt = newCursor.takeIf { cursorChanged },
        )
    }

    // ----- Inbound -----------------------------------------------------------------------

    private data class InboundResult(
        val downloadedBookmarks: Int,
        val downloadedChatEntries: Int,
        val downloadedPayloads: Int,
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
        var downloadedPayloads = 0
        var maxLastModified: String? = sinceCursor
        val errors = mutableListOf<String>()

        val localBookEntries = bookRepository.loadBookEntries()
        // Mutable so a payload download in this pass makes the new book visible for the
        // bookmark/chat handlers that come later (same pass, later keys).
        val rootsBySyncId: MutableMap<String, File> = mutableMapOf<String, File>().apply {
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
                runCatching {
                    when (parsed.kind) {
                        BookKeyKind.Bookmark -> {
                            val root = rootsBySyncId[parsed.syncId] ?: return@runCatching
                            if (applyBookmarkFromRemote(transport, root, meta)) downloadedBookmarks += 1
                        }
                        BookKeyKind.Chat -> {
                            val root = rootsBySyncId[parsed.syncId] ?: return@runCatching
                            if (applyChatEntryFromRemote(transport, root, meta)) downloadedChatEntries += 1
                        }
                        BookKeyKind.PayloadManifest -> {
                            // Only act on payload manifests for books we don't have locally.
                            // Updates to existing books are out of scope for v2.0 (very rare:
                            // would mean someone re-imported the same titled book with new pages).
                            if (parsed.syncId !in rootsBySyncId.keys) {
                                val imported = importRemoteOnlyBook(transport, parsed.syncId)
                                if (imported != null) {
                                    rootsBySyncId[parsed.syncId] = imported
                                    downloadedPayloads += 1
                                }
                            }
                        }
                        BookKeyKind.PayloadZip -> Unit // followed via the manifest
                        BookKeyKind.Metadata -> Unit // v2 doesn't act on metadata yet — reserved for tombstones
                    }
                }.onFailure { e ->
                    errors += "${parsed.kind.name.lowercase()} ${parsed.syncId}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            cursor = page.nextCursor
        } while (cursor != null && page.truncated)

        val remoteOnly = remoteSyncIds.count { it !in rootsBySyncId.keys }
        return InboundResult(
            downloadedBookmarks = downloadedBookmarks,
            downloadedChatEntries = downloadedChatEntries,
            downloadedPayloads = downloadedPayloads,
            remoteOnlyBooks = remoteOnly,
            maxLastModified = maxLastModified,
            errors = errors,
        )
    }

    private suspend fun applyBookmarkFromRemote(
        transport: HttpSyncKvTransport,
        bookRoot: File,
        meta: HttpSyncKvKeyMeta,
    ): Boolean {
        val fetched = transport.get(meta.key) ?: return false
        val blob = runCatching {
            json.decodeFromString(
                HttpSyncBookmarkBlob.serializer(),
                fetched.body.toString(Charsets.UTF_8),
            )
        }.getOrElse { error ->
            throw HttpSyncException("Bookmark at ${meta.key}: malformed JSON (${error.message ?: error.javaClass.simpleName})")
        }
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
        val blob = runCatching {
            json.decodeFromString(
                HttpSyncChatEntryBlob.serializer(),
                fetched.body.toString(Charsets.UTF_8),
            )
        }.getOrElse { error ->
            throw HttpSyncException("Chat entry at ${meta.key}: malformed JSON (${error.message ?: error.javaClass.simpleName})")
        }
        val existing = runCatching { aiHistoryStore.load(bookRoot).entries }.getOrDefault(emptyList())
        val incoming = AiChatEntry(
            bubbleText = blob.bubbleText,
            prompt = blob.prompt,
            model = blob.model,
            response = blob.response,
            timestampSeconds = blob.timestampSeconds,
        )
        if (existing.any { it.matchesEntry(incoming) }) return false
        aiHistoryStore.append(bookRoot, incoming)
        return true
    }

    // ----- Outbound ----------------------------------------------------------------------

    private data class OutboundResult(
        val uploadedBookmarks: Int,
        val uploadedChatEntries: Int,
        val uploadedMetadata: Int,
        val uploadedPayloads: Int,
        val maxLastModified: String?,
        val errors: List<String>,
    )

    private suspend fun pushAllLocal(transport: HttpSyncKvTransport): OutboundResult {
        var uploadedBookmarks = 0
        var uploadedChatEntries = 0
        var uploadedMetadata = 0
        var uploadedPayloads = 0
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
                        contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
                        body = json.encodeToString(HttpSyncBookmarkBlob.serializer(), bookmark.toBlob()).toByteArray(),
                    )
                    uploadedBookmarks += 1
                    maxLastModified = maxRfc(maxLastModified, response.lastModified)
                }

                val metadataResponse = transport.put(
                    key = metadataKey(syncId),
                    contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
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

                // Payload push: zip the book directory once, compare sha to remote manifest,
                // upload zip + manifest only if different. The codec caches nothing, so this
                // is roughly free on a second sync (it'll fetch the manifest, see the sha
                // matches, skip the zip entirely). Mokuro-only for v2.0; EPUB payload sync
                // can be added by widening the gate.
                val contentType = bookContentType(root)
                if (contentType == ContentType.Mokuro) {
                    val uploaded = payloadCodec.uploadIfChanged(
                        transport = transport,
                        syncId = syncId,
                        bookRoot = root,
                        originalName = title,
                        format = HttpSyncContentType.fromLocal(contentType),
                    )
                    if (uploaded) uploadedPayloads += 1
                }

                if (contentType == ContentType.Mokuro) {
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
                                contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
                                body = json.encodeToString(HttpSyncChatEntryBlob.serializer(), chatEntry.toBlob()).toByteArray(),
                            )
                            uploadedChatEntries += 1
                            maxLastModified = maxRfc(maxLastModified, response.lastModified)
                        }
                    }
                }
            } catch (e: HttpSyncException) {
                errors += "$title: ${e.message}"
            }
        }
        return OutboundResult(
            uploadedBookmarks = uploadedBookmarks,
            uploadedChatEntries = uploadedChatEntries,
            uploadedMetadata = uploadedMetadata,
            uploadedPayloads = uploadedPayloads,
            maxLastModified = maxLastModified,
            errors = errors,
        )
    }

    // ----- Key parsing --------------------------------------------------------------------

    private enum class BookKeyKind { Bookmark, Chat, Metadata, PayloadManifest, PayloadZip }

    private data class ParsedBookKey(val syncId: String, val kind: BookKeyKind)

    /**
     * Splits a `books/{syncId}/{...}` key into the parts the inbound handler needs. Unknown
     * shapes return null and are quietly skipped — forward-compat with future key types.
     * The `payload.zip` key is recognized but not acted on directly; the [PayloadManifest]
     * branch is what triggers the download (manifest carries the sha to verify against).
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
            suffix == "payload.manifest" -> BookKeyKind.PayloadManifest
            suffix == "payload.zip" -> BookKeyKind.PayloadZip
            suffix.startsWith("chat/") -> BookKeyKind.Chat
            else -> return null
        }
        return ParsedBookKey(syncId, kind)
    }

    /**
     * Fetches a payload manifest+zip for a [syncId] that has no local counterpart, unpacks
     * it into a freshly-created book directory, and writes a [BookMetadata] sidecar so the
     * book shows up on the shelf at the next [BookRepository.loadBookEntries] read.
     *
     * Returns the freshly-created book root, or `null` if the payload was unfetchable
     * (manifest 404, zip 404, sha256 mismatch). Per-key errors caught here surface as
     * entries in [HttpSyncResult.errors] via the caller.
     */
    private suspend fun importRemoteOnlyBook(
        transport: HttpSyncKvTransport,
        syncId: String,
    ): File? {
        val targetRoot = bookRepository.createBookDirectoryForImportedTitle(syncId)
        val manifest = try {
            payloadCodec.downloadAndUnpack(transport, syncId, targetRoot)
        } catch (e: HttpSyncException) {
            // Clean up the half-imported directory so a retry doesn't see stale partial state.
            targetRoot.deleteRecursively()
            throw e
        }
        // Write a minimal metadata sidecar — title comes from the manifest, id is fresh per
        // device (consistent with how local imports generate UUIDs).
        bookRepository.saveMetadata(
            targetRoot,
            BookMetadata(
                id = UUID.randomUUID().toString(),
                title = manifest.originalName,
                cover = null,
                folder = targetRoot.name,
                lastAccess = 0.0,
            ),
        )
        return targetRoot
    }
}

/**
 * Outcome of one [HttpSyncReconciler.syncOnce] pass. Granular counts so the UI can show a
 * one-line summary ("uploaded 3 bookmarks, 12 chat entries; downloaded 1 bookmark").
 */
data class HttpSyncResult(
    val uploadedBookmarks: Int,
    val uploadedChatEntries: Int,
    val uploadedMetadata: Int,
    val uploadedPayloads: Int = 0,
    val downloadedBookmarks: Int,
    val downloadedChatEntries: Int,
    val downloadedPayloads: Int = 0,
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
        if (uploadedPayloads > 0) parts += "$uploadedPayloads book payload${plural(uploadedPayloads)} up"
        if (downloadedBookmarks > 0) parts += "$downloadedBookmarks bookmark${plural(downloadedBookmarks)} down"
        if (downloadedChatEntries > 0) parts += "$downloadedChatEntries chat${plural(downloadedChatEntries)} down"
        if (downloadedPayloads > 0) parts += "$downloadedPayloads book payload${plural(downloadedPayloads)} down"
        if (remoteOnlyBooks > 0) parts += "$remoteOnlyBooks remote-only book${plural(remoteOnlyBooks)}"
        if (parts.isEmpty()) parts += "nothing to sync"
        return parts.joinToString(", ")
    }

    private fun plural(n: Int): String = if (n == 1) "" else "s"
}
