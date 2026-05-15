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
import moe.antimony.hoshi.features.ai.AiChatSettings
import moe.antimony.hoshi.features.ai.AiChatSettingsRepository
import moe.antimony.hoshi.epub.BookMetadata
import kotlinx.coroutines.flow.first
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
    /**
     * Optional in tests that don't care about the AI-settings sync path. Production wiring
     * in [moe.antimony.hoshi.HoshiAppContainer] always supplies a real repo.
     */
    private val aiSettingsRepository: AiChatSettingsRepository? = null,
    /**
     * Shared with the reader's [HttpSyncPusher] so a manual Sync now can't race the reader's
     * per-page-turn push on the same bookmark file. Defaults to a fresh map in tests.
     */
    private val bookLocks: HttpSyncBookLocks = HttpSyncBookLocks(),
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
        val appSettings = syncAppSettings(transport)

        val newCursor = maxRfc(
            maxRfc(settings.lastSyncedAt, inbound.maxLastModified),
            maxRfc(outbound.maxLastModified, appSettings.maxLastModified),
        )
        val cursorChanged = newCursor != null && newCursor != settings.lastSyncedAt

        HttpSyncResult(
            uploadedBookmarks = outbound.uploadedBookmarks,
            uploadedChatEntries = outbound.uploadedChatEntries,
            uploadedMetadata = outbound.uploadedMetadata,
            uploadedPayloads = outbound.uploadedPayloads,
            uploadedAppSettings = appSettings.uploaded,
            downloadedBookmarks = inbound.downloadedBookmarks,
            downloadedChatEntries = inbound.downloadedChatEntries,
            downloadedPayloads = inbound.downloadedPayloads,
            downloadedAppSettings = appSettings.downloaded,
            remoteOnlyBooks = inbound.remoteOnlyBooks,
            errors = inbound.errors + outbound.errors + appSettings.errors,
            newLastSyncedAt = newCursor.takeIf { cursorChanged },
        )
    }

    // ----- App-level (non-per-book) settings sync -----------------------------------------

    private data class AppSettingsResult(
        val uploaded: Boolean,
        val downloaded: Boolean,
        val maxLastModified: String?,
        val errors: List<String>,
    )

    /**
     * Bidirectional LWW sync of the cross-device ChatGPT settings (`model` + `promptText`).
     *
     * Algorithm:
     *  1. Read local [AiChatSettings] and remote [HttpSyncAiChatSettingsBlob].
     *  2. If only one side has a value, that side wins. If both, the higher `lastModified`
     *     wins. Equal timestamps → no-op (presumed already in sync).
     *  3. The API key is **never** read or written here — that field stays per-device.
     *
     * Skipped silently if no [aiSettingsRepository] was supplied (test-only path).
     */
    private suspend fun syncAppSettings(transport: HttpSyncKvTransport): AppSettingsResult {
        val repo = aiSettingsRepository ?: return AppSettingsResult(
            uploaded = false, downloaded = false, maxLastModified = null, errors = emptyList(),
        )
        val errors = mutableListOf<String>()

        val local = runCatching { repo.settings.first() }.getOrElse {
            errors += "ai_chat_settings: ${it.message ?: it.javaClass.simpleName}"
            return AppSettingsResult(false, false, null, errors)
        }
        val remoteFetched = runCatching { transport.get(AI_CHAT_SETTINGS_KEY) }.getOrElse {
            errors += "ai_chat_settings GET: ${it.message ?: it.javaClass.simpleName}"
            return AppSettingsResult(false, false, null, errors)
        }
        val remoteBlob = remoteFetched?.let {
            runCatching {
                json.decodeFromString(
                    HttpSyncAiChatSettingsBlob.serializer(),
                    it.body.toString(Charsets.UTF_8),
                )
            }.getOrElse { e ->
                errors += "ai_chat_settings decode: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }

        val localStamp = local.lastEditedAt
        val remoteStamp = remoteBlob?.lastModified

        return when {
            remoteBlob == null && localStamp != null -> {
                // Server has nothing; we have something. Push.
                pushLocalAppSettings(transport, local, errors)?.let {
                    AppSettingsResult(uploaded = true, downloaded = false, maxLastModified = it.lastModified, errors = errors)
                } ?: AppSettingsResult(false, false, null, errors)
            }
            remoteBlob != null && localStamp == null -> {
                // We have nothing user-edited; pull. Stamp uses the remote's lastModified
                // (verbatim) so the next sync is a no-op rather than oscillating.
                runCatching { repo.applyFromSync(remoteBlob.model, remoteBlob.promptText, remoteBlob.lastModified) }
                    .onFailure { errors += "ai_chat_settings apply: ${it.message ?: it.javaClass.simpleName}" }
                AppSettingsResult(false, true, remoteStamp, errors)
            }
            remoteBlob != null && localStamp != null -> {
                val cmp = compareRfc3339(localStamp, remoteStamp)
                when {
                    cmp > 0 -> {
                        // Local newer → push.
                        pushLocalAppSettings(transport, local, errors)?.let {
                            AppSettingsResult(uploaded = true, downloaded = false, maxLastModified = it.lastModified, errors = errors)
                        } ?: AppSettingsResult(false, false, remoteStamp, errors)
                    }
                    cmp < 0 -> {
                        // Remote newer → apply.
                        // applyFromSync is CAS: if the user edited locally between our read
                        // (line above) and this write, it returns false and we leave the
                        // newer local state alone. The next sync will push it up.
                        runCatching { repo.applyFromSync(remoteBlob.model, remoteBlob.promptText, remoteBlob.lastModified) }
                            .onFailure { errors += "ai_chat_settings apply: ${it.message ?: it.javaClass.simpleName}" }
                        AppSettingsResult(false, true, remoteStamp, errors)
                    }
                    else -> AppSettingsResult(false, false, remoteStamp, errors)
                }
            }
            else -> AppSettingsResult(false, false, null, errors)
        }
    }

    private suspend fun pushLocalAppSettings(
        transport: HttpSyncKvTransport,
        local: AiChatSettings,
        errors: MutableList<String>,
    ): HttpSyncKvWriteResponse? {
        val stamp = local.lastEditedAt ?: return null
        val blob = HttpSyncAiChatSettingsBlob(
            model = local.model,
            promptText = local.promptText,
            lastModified = stamp,
        )
        return try {
            transport.put(
                key = AI_CHAT_SETTINGS_KEY,
                contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
                body = json.encodeToString(HttpSyncAiChatSettingsBlob.serializer(), blob).toByteArray(),
            )
        } catch (e: HttpSyncException) {
            errors += "ai_chat_settings PUT: ${e.message}"
            null
        }
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
        val rootsBySyncId: MutableMap<String, File> = mutableMapOf<String, File>().apply {
            for (entry in localBookEntries) {
                val syncId = deriveSyncId(entry.metadata.title) ?: continue
                put(syncId, entry.root)
            }
        }
        val remoteSyncIds = mutableSetOf<String>()

        // ── Pass 1: page through the entire listing and buffer keys by kind. We can't
        //    process bookmark/chat keys inline because they may arrive lex-before the
        //    payload manifest that imports the book they belong to (regression caught by
        //    `freshDeviceSyncDownloadsPayloadBookmarkAndChatInOnePass`).
        val payloadManifests = mutableListOf<HttpSyncKvKeyMeta>()
        val bookmarksAndChats = mutableListOf<Pair<ParsedBookKey, HttpSyncKvKeyMeta>>()
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
                when (parsed.kind) {
                    BookKeyKind.PayloadManifest -> payloadManifests += meta
                    BookKeyKind.Bookmark, BookKeyKind.Chat -> bookmarksAndChats += parsed to meta
                    BookKeyKind.PayloadZip -> Unit // followed via the manifest
                    BookKeyKind.Metadata -> Unit // v2 doesn't act on metadata yet — reserved for tombstones
                }
            }
            cursor = page.nextCursor
        } while (cursor != null && page.truncated)

        // ── Pass 2: import remote-only books by their payload manifests, BEFORE applying
        //    bookmarks/chats. This is what fixes the ordering bug: once this pass runs,
        //    every syncId on the server has a local root in `rootsBySyncId`.
        for (meta in payloadManifests) {
            val parsed = parseBookKey(meta.key) ?: continue
            if (parsed.syncId in rootsBySyncId.keys) continue
            runCatching {
                val imported = importRemoteOnlyBook(transport, parsed.syncId)
                if (imported != null) {
                    rootsBySyncId[parsed.syncId] = imported
                    downloadedPayloads += 1
                }
            }.onFailure { e ->
                errors += "payload ${parsed.syncId}: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        // ── Pass 3: bookmarks and chats now find their local roots and get applied.
        for ((parsed, meta) in bookmarksAndChats) {
            val root = rootsBySyncId[parsed.syncId] ?: continue
            runCatching {
                when (parsed.kind) {
                    BookKeyKind.Bookmark ->
                        if (applyBookmarkFromRemote(transport, root, meta)) downloadedBookmarks += 1
                    BookKeyKind.Chat ->
                        if (applyChatEntryFromRemote(transport, root, meta)) downloadedChatEntries += 1
                    else -> Unit
                }
            }.onFailure { e ->
                errors += "${parsed.kind.name.lowercase()} ${parsed.syncId}: ${e.message ?: e.javaClass.simpleName}"
            }
        }

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
        // Hold the per-book lock for the read-compare-write so a concurrent reader-side
        // push can't interleave with the apply.
        return bookLocks.withBookLock(bookRoot) {
            val fetched = transport.get(meta.key) ?: return@withBookLock false
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
                return@withBookLock false
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
            true
        }
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
                    // Don't overwrite a newer server bookmark — see the same guard in
                    // HttpSyncPusher.pushBookmark for the rationale. Inbound just ran (so in
                    // most cases local IS the freshest), but a concurrent push from another
                    // device between inbound and outbound is still possible.
                    val pushed = pushBookmarkIfLocalNewer(transport, syncId, bookmark, root)
                    if (pushed != null) {
                        uploadedBookmarks += 1
                        maxLastModified = maxRfc(maxLastModified, pushed.lastModified)
                    }
                }

                // Preserve the server's deletion tombstone — if another device flipped
                // `deletedAt` on the metadata, our push must not erase it.
                val remoteMetadataBlob = runCatching {
                    transport.get(metadataKey(syncId))
                        ?.body?.toString(Charsets.UTF_8)
                        ?.let { json.decodeFromString(HttpSyncMetadataBlob.serializer(), it) }
                }.getOrNull()
                val metadataResponse = transport.put(
                    key = metadataKey(syncId),
                    contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
                    body = json.encodeToString(
                        HttpSyncMetadataBlob.serializer(),
                        HttpSyncMetadataBlob(
                            title = title,
                            contentType = HttpSyncContentType.fromLocal(bookContentType(root)),
                            importedAt = remoteMetadataBlob?.importedAt,
                            deletedAt = remoteMetadataBlob?.deletedAt,
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
                        // Only push entries the server doesn't have. Walk every page of the
                        // chat-entry listing — a chat-heavy book can exceed the server's
                        // default page size (~500), and missing entries from page N would
                        // either re-upload duplicates or, worse, leave new local entries
                        // unpushed because we thought the server already had them.
                        val existing = mutableSetOf<String>()
                        var chatCursor: String? = null
                        do {
                            val page = transport.list(
                                prefix = chatPrefixForBook(syncId),
                                cursor = chatCursor,
                            )
                            for (meta in page.keys) existing += meta.key
                            chatCursor = page.nextCursor
                        } while (chatCursor != null && page.truncated)
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

    /**
     * Conditional bookmark PUT: fetches the remote bookmark, returns `null` and applies
     * locally if remote is newer; otherwise PUTs local and returns the write response.
     * Mirrors [HttpSyncPusher.pushBookmark]'s overwrite guard.
     */
    private suspend fun pushBookmarkIfLocalNewer(
        transport: HttpSyncKvTransport,
        syncId: String,
        local: moe.antimony.hoshi.epub.Bookmark,
        bookRoot: File,
    ): HttpSyncKvWriteResponse? = bookLocks.withBookLock(bookRoot) {
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
                val localStamp = local.lastModified?.let(::appleSecondsToRfc3339)
                if (compareRfc3339(remoteBlob.lastModified, localStamp) > 0) {
                    // Remote is newer — applyBookmarkFromRemote already ran during the
                    // inbound pass for this same key (or will on next sync if it slipped
                    // in between). Either way: don't downgrade. Just don't push.
                    return@withBookLock null
                }
            }
        }
        transport.put(
            key = key,
            contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
            body = json.encodeToString(HttpSyncBookmarkBlob.serializer(), local.toBlob()).toByteArray(),
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
    val uploadedAppSettings: Boolean = false,
    val downloadedBookmarks: Int,
    val downloadedChatEntries: Int,
    val downloadedPayloads: Int = 0,
    val downloadedAppSettings: Boolean = false,
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
        if (uploadedAppSettings) parts += "ChatGPT settings up"
        if (downloadedBookmarks > 0) parts += "$downloadedBookmarks bookmark${plural(downloadedBookmarks)} down"
        if (downloadedChatEntries > 0) parts += "$downloadedChatEntries chat${plural(downloadedChatEntries)} down"
        if (downloadedPayloads > 0) parts += "$downloadedPayloads book payload${plural(downloadedPayloads)} down"
        if (downloadedAppSettings) parts += "ChatGPT settings down"
        if (remoteOnlyBooks > 0) parts += "$remoteOnlyBooks remote-only book${plural(remoteOnlyBooks)}"
        if (parts.isEmpty()) parts += "nothing to sync"
        return parts.joinToString(", ")
    }

    private fun plural(n: Int): String = if (n == 1) "" else "s"
}
