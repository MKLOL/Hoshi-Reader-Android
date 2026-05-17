package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookShelf
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
import java.time.Instant
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
    private val shelfStateStore = HttpSyncShelfStateStore(json)
    private val deletedBookStateStore = HttpSyncDeletedBookStateStore(json)

    /**
     * One reconciliation pass. Inbound first so a newer server bookmark is not stomped
     * by our outbound push.
     */
    suspend fun syncOnce(
        settings: HttpSyncSettings,
        onProgress: suspend (HttpSyncProgress) -> Unit = {},
    ): HttpSyncResult = withContext(ioDispatcher) {
        require(settings.isConfigured) { "HTTP sync is not configured." }
        val transport = transportFactory(settings)

        onProgress(HttpSyncProgress(message = "Preparing sync", detail = "Connecting to the HTTP sync server."))
        val inbound = pullChangedKeys(transport, settings.lastSyncedAt, onProgress)
        val outbound = pushAllLocal(transport, onProgress)
        val appSettings = syncAppSettings(transport, onProgress)

        val newCursor = safeNewCursor(currentCursor = settings.lastSyncedAt, inbound = inbound)
        val cursorChanged = newCursor != null && newCursor != settings.lastSyncedAt
        onProgress(HttpSyncProgress(message = "Finishing sync", detail = "Saving the sync cursor."))

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

    private data class RemoteAiChatSettings(
        val blob: HttpSyncAiChatSettingsBlob,
        val hasImagePromptText: Boolean,
    )

    private data class RemoteBookMetadata(
        val blob: HttpSyncMetadataBlob,
        val hasShelfName: Boolean,
    )

    private data class RemoteBookMetadataFetched(
        val blob: HttpSyncMetadataBlob,
        val hasShelfName: Boolean,
        val lastModified: String,
    )

    private data class ShelfSnapshot(
        val namesByBookId: Map<String, String>,
        val recordsBySyncId: Map<String, HttpSyncShelfPlacementRecord>,
        val shelvesUpdatedAt: String?,
    )

    private enum class MetadataApplyResult { Applied, Deleted, MissingLocal, Noop }

    /**
     * Bidirectional LWW sync of the cross-device ChatGPT settings (`model` + prompts).
     *
     * Algorithm:
     *  1. Read local [AiChatSettings] and remote [HttpSyncAiChatSettingsBlob].
     *  2. If only one side has a value, that side wins. If both, the higher `lastModified`
     *     wins. Equal timestamps → no-op (presumed already in sync).
     *  3. The API key is **never** read or written here — that field stays per-device.
     *
     * Skipped silently if no [aiSettingsRepository] was supplied (test-only path).
     */
    private suspend fun syncAppSettings(
        transport: HttpSyncKvTransport,
        onProgress: suspend (HttpSyncProgress) -> Unit,
    ): AppSettingsResult {
        val repo = aiSettingsRepository ?: return AppSettingsResult(
            uploaded = false, downloaded = false, maxLastModified = null, errors = emptyList(),
        )
        val errors = mutableListOf<String>()
        onProgress(HttpSyncProgress(message = "Syncing ChatGPT settings", detail = "Checking shared model and prompt settings."))

        val local = runCatching { repo.settings.first() }.getOrElse {
            errors += "ai_chat_settings: ${it.message ?: it.javaClass.simpleName}"
            return AppSettingsResult(false, false, null, errors)
        }
        val remoteFetched = runCatching { transport.get(AI_CHAT_SETTINGS_KEY) }.getOrElse {
            errors += "ai_chat_settings GET: ${it.message ?: it.javaClass.simpleName}"
            return AppSettingsResult(false, false, null, errors)
        }
        val remoteSettings = remoteFetched?.let {
            runCatching {
                val body = it.body.toString(Charsets.UTF_8)
                json.decodeFromString(
                    HttpSyncAiChatSettingsBlob.serializer(),
                    body,
                ).let { blob ->
                    RemoteAiChatSettings(
                        blob = blob,
                        hasImagePromptText = runCatching {
                            json.parseToJsonElement(body).jsonObject.containsKey("imagePromptText")
                        }.getOrDefault(false),
                    )
                }
            }.getOrElse { e ->
                errors += "ai_chat_settings decode: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }
        val remoteBlob = remoteSettings?.blob

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
                val applied = runCatching {
                    repo.applyFromSync(
                        model = remoteBlob.model,
                        promptText = remoteBlob.promptText,
                        imagePromptText = remoteImagePromptText(remoteSettings, local),
                        remoteLastEditedAt = remoteBlob.lastModified,
                    )
                }
                    .onFailure { errors += "ai_chat_settings apply: ${it.message ?: it.javaClass.simpleName}" }
                    .getOrDefault(false)
                val backfill = if (applied && remoteSettings?.hasImagePromptText == false) {
                    // Older clients can upload a newer settings blob without the image prompt
                    // field. After preserving our local/default image prompt, immediately
                    // write a full blob back so other new clients do not keep seeing a
                    // legacy/default image prompt forever.
                    pushLocalAppSettings(transport, repo.settings.first(), errors)
                } else {
                    null
                }
                AppSettingsResult(
                    uploaded = backfill != null,
                    downloaded = true,
                    maxLastModified = backfill?.lastModified ?: remoteStamp,
                    errors = errors,
                )
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
                        val applied = runCatching {
                            repo.applyFromSync(
                                model = remoteBlob.model,
                                promptText = remoteBlob.promptText,
                                imagePromptText = remoteImagePromptText(remoteSettings, local),
                                remoteLastEditedAt = remoteBlob.lastModified,
                            )
                        }
                            .onFailure { errors += "ai_chat_settings apply: ${it.message ?: it.javaClass.simpleName}" }
                            .getOrDefault(false)
                        val backfill = if (applied && remoteSettings?.hasImagePromptText == false) {
                            pushLocalAppSettings(transport, repo.settings.first(), errors)
                        } else {
                            null
                        }
                        AppSettingsResult(
                            uploaded = backfill != null,
                            downloaded = true,
                            maxLastModified = backfill?.lastModified ?: remoteStamp,
                            errors = errors,
                        )
                    }
                    else -> {
                        val backfill = if (remoteSettings?.hasImagePromptText == false) {
                            pushLocalAppSettings(transport, local, errors)
                        } else {
                            null
                        }
                        AppSettingsResult(
                            uploaded = backfill != null,
                            downloaded = false,
                            maxLastModified = backfill?.lastModified ?: remoteStamp,
                            errors = errors,
                        )
                    }
                }
            }
            else -> AppSettingsResult(false, false, null, errors)
        }
    }

    private fun remoteImagePromptText(
        remoteSettings: RemoteAiChatSettings?,
        local: AiChatSettings,
    ): String {
        val remote = remoteSettings ?: return local.imagePromptText
        return if (remote.hasImagePromptText) {
            remote.blob.imagePromptText
        } else {
            local.imagePromptText
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
            imagePromptText = local.imagePromptText,
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
        val maxHandledLastModified: String?,
        val minUnhandledLastModified: String?,
        val errors: List<String>,
    )

    private fun safeNewCursor(
        currentCursor: String?,
        inbound: InboundResult,
    ): String? {
        val candidates = listOfNotNull(
            currentCursor,
            inbound.maxHandledLastModified,
        )
        val firstUnhandled = inbound.minUnhandledLastModified
        val safeCandidates = if (firstUnhandled == null) {
            candidates
        } else {
            candidates.filter { compareRfc3339(it, firstUnhandled) < 0 }
        }
        return safeCandidates.fold<String, String?>(null) { acc, timestamp ->
            maxRfc(acc, timestamp)
        }
    }

    private suspend fun pullChangedKeys(
        transport: HttpSyncKvTransport,
        sinceCursor: String?,
        onProgress: suspend (HttpSyncProgress) -> Unit,
    ): InboundResult {
        var downloadedBookmarks = 0
        var downloadedChatEntries = 0
        var downloadedPayloads = 0
        var maxHandledLastModified: String? = null
        var minUnhandledLastModified: String? = null
        val errors = mutableListOf<String>()

        fun markHandled(meta: HttpSyncKvKeyMeta) {
            maxHandledLastModified = maxRfc(maxHandledLastModified, meta.lastModified)
        }

        fun markUnhandled(meta: HttpSyncKvKeyMeta) {
            if (compareRfc3339(meta.lastModified, sinceCursor) <= 0) return
            minUnhandledLastModified = when {
                minUnhandledLastModified == null -> meta.lastModified
                compareRfc3339(meta.lastModified, minUnhandledLastModified) < 0 -> meta.lastModified
                else -> minUnhandledLastModified
            }
        }

        onProgress(HttpSyncProgress(message = "Scanning local books", detail = "Preparing to match local and remote sync IDs."))
        val localBookEntries = bookRepository.loadBookEntries()
        val rootsBySyncId: MutableMap<String, File> = mutableMapOf<String, File>().apply {
            for (entry in localBookEntries) {
                val syncId = deriveSyncId(entry.metadata.title) ?: continue
                put(syncId, entry.root)
            }
        }
        // Per-syncId `BookMetadata.importedAt` so Pass 2 (tombstone application) can compare
        // local import stamps against remote `deletedAt`. A strictly-newer local stamp means
        // the user re-imported the book after the tombstone was published — we keep the
        // local copy and let the outbound pass push fresh `deletedAt = null` metadata.
        val localImportedAtBySyncId: Map<String, String?> = buildMap {
            for (entry in localBookEntries) {
                val syncId = deriveSyncId(entry.metadata.title) ?: continue
                put(syncId, entry.metadata.importedAt)
            }
        }
        // Snapshot pending tombstones up front so Pass 3 (remote-only import) can refuse to
        // re-create a book the user just deleted. Without this, the inbound-first ordering
        // re-imports the remote payload, the subsequent push then sees a live local book and
        // computes an empty `tombstonesToPush`, and the user's delete is silently lost on
        // their other devices. The outbound pass owns the actual tombstone write — it's the
        // one that clears the sidecar atomically — so we only READ the map here.
        val pendingTombstoneSyncIds = deletedBookStateStore.load(bookRepository.booksDirectory).keys
        val remoteSyncIds = mutableSetOf<String>()

        // ── Pass 1: page through the entire listing and buffer keys by kind. We can't
        //    process bookmark/chat keys inline because they may arrive lex-before the
        //    payload manifest that imports the book they belong to (regression caught by
        //    `freshDeviceSyncDownloadsPayloadBookmarkAndChatInOnePass`).
        val payloadManifests = mutableListOf<HttpSyncKvKeyMeta>()
        val metadataKeys = mutableListOf<Pair<ParsedBookKey, HttpSyncKvKeyMeta>>()
        val bookmarksAndChats = mutableListOf<Pair<ParsedBookKey, HttpSyncKvKeyMeta>>()
        // Manual `Sync now` always does a full pull. The incremental `since=` filter was
        // hiding payload manifests for books that were uploaded from another device while
        // this device's cursor pointed at a later wallclock — server clock skew, a partially-
        // completed prior sync that markHandled keys it didn't actually apply, or a future-
        // stamped cursor from an older buggy build would all silently swallow new books.
        // Listing is cheap (a few hundred bytes per key, paginated), and the per-key GETs
        // are still short-circuited by the local-vs-remote LWW checks below, so the extra
        // work is one list pass — not a re-download of every book.
        val listSinceCursor: String? = null
        var cursor: String? = null
        var listedPages = 0
        do {
            onProgress(
                HttpSyncProgress(
                    message = "Listing remote changes",
                    detail = if (listedPages == 0) {
                        "Asking the server what changed."
                    } else {
                        "Read $listedPages remote page${plural(listedPages)} so far."
                    },
                ),
            )
            val page = transport.list(
                prefix = ALL_BOOKS_PREFIX,
                since = listSinceCursor,
                cursor = cursor,
            )
            listedPages += 1
            for (meta in page.keys) {
                val parsed = parseBookKey(meta.key)
                if (parsed == null) {
                    markHandled(meta)
                    continue
                }
                remoteSyncIds += parsed.syncId
                when (parsed.kind) {
                    BookKeyKind.PayloadManifest -> payloadManifests += meta
                    BookKeyKind.Bookmark, BookKeyKind.Chat -> bookmarksAndChats += parsed to meta
                    BookKeyKind.PayloadZip -> markHandled(meta) // followed via the manifest
                    BookKeyKind.Metadata -> metadataKeys += parsed to meta
                }
            }
            cursor = page.nextCursor
        } while (cursor != null && page.truncated)

        // ── Pass 2: apply metadata tombstones before payload import so a deleted remote
        //    book never causes a fresh device to download a payload just to delete it.
        val shelfSnapshotBeforeMetadata = loadShelfSnapshot()
        val updatedShelfState = shelfSnapshotBeforeMetadata.recordsBySyncId.toMutableMap()
        val deletedSyncIds = mutableSetOf<String>()
        val placementMetadataKeys = mutableListOf<Pair<ParsedBookKey, HttpSyncKvKeyMeta>>()
        for ((parsed, meta) in metadataKeys) {
            runCatching {
                val remote = fetchRemoteMetadata(transport, meta.key)
                    ?: throw HttpSyncException("Metadata at ${meta.key}: missing.")
                val remoteDeletedAt = remote.blob.deletedAt
                if (remoteDeletedAt != null) {
                    val localImportedAt = localImportedAtBySyncId[parsed.syncId]
                    if (localImportedAtOverridesRemoteDeletion(localImportedAt, remoteDeletedAt)) {
                        // Re-import after tombstone: the user imported this book AFTER the
                        // remote `deletedAt` was published, so the live local copy wins.
                        // Keep the book, leave it as an outbound-pass candidate that will
                        // re-publish metadata with `deletedAt = null`. We mark the metadata
                        // as unhandled so this tombstone-bearing key is re-fetched (and the
                        // cursor is not advanced past it) until the outbound push lands.
                        markUnhandled(meta)
                    } else {
                        rootsBySyncId[parsed.syncId]?.let { bookRepository.deleteBook(it) }
                        rootsBySyncId.remove(parsed.syncId)
                        updatedShelfState.remove(parsed.syncId)
                        deletedSyncIds += parsed.syncId
                        markHandled(meta)
                    }
                } else {
                    placementMetadataKeys += parsed to meta
                }
            }.onFailure { e ->
                errors += "metadata ${parsed.syncId}: ${e.message ?: e.javaClass.simpleName}"
                markUnhandled(meta)
            }
        }

        // ── Pass 3: import remote-only books by their payload manifests, BEFORE applying
        //    bookmarks/chats. This is what fixes the ordering bug: once this pass runs,
        //    every non-deleted syncId on the server has a local root in `rootsBySyncId`.
        for ((index, meta) in payloadManifests.withIndex()) {
            val parsed = parseBookKey(meta.key) ?: continue
            onProgress(
                HttpSyncProgress(
                    message = "Checking remote book payloads",
                    detail = "Book ${index + 1} of ${payloadManifests.size}: ${parsed.syncId}",
                    completed = index,
                    total = payloadManifests.size,
                ),
            )
            if (parsed.syncId in deletedSyncIds) {
                markHandled(meta)
                continue
            }
            if (parsed.syncId in rootsBySyncId.keys) {
                markHandled(meta)
                continue
            }
            if (parsed.syncId in pendingTombstoneSyncIds) {
                // User staged a delete locally but we haven't pushed the tombstone yet.
                // Importing here would re-create the book, the subsequent push would see
                // a "live" local book for this syncId, filter the tombstone out of
                // `tombstonesToPush`, clear it from the sidecar, and then PUT plain
                // metadata — overwriting the server with `deletedAt = null`. Skip the
                // import; the outbound pass in this same `syncOnce` will turn the remote
                // metadata into a tombstone.
                markHandled(meta)
                continue
            }
            runCatching {
                val imported = importRemoteOnlyBook(transport, parsed.syncId)
                if (imported != null) {
                    rootsBySyncId[parsed.syncId] = imported
                    downloadedPayloads += 1
                    markHandled(meta)
                } else {
                    markUnhandled(meta)
                }
            }.onFailure { e ->
                errors += "payload ${parsed.syncId}: ${e.message ?: e.javaClass.simpleName}"
                markUnhandled(meta)
            }
        }

        // ── Pass 4: metadata shelf placement now has local roots too.
        for ((index, pair) in placementMetadataKeys.withIndex()) {
            val (parsed, meta) = pair
            onProgress(
                HttpSyncProgress(
                    message = "Applying bookshelf folders",
                    detail = "Book ${index + 1} of ${placementMetadataKeys.size}: ${parsed.syncId}",
                    completed = index,
                    total = placementMetadataKeys.size,
                ),
            )
            runCatching {
                when (applyMetadataFromRemote(
                    transport = transport,
                    syncId = parsed.syncId,
                    bookRoot = rootsBySyncId[parsed.syncId],
                    meta = meta,
                    shelfSnapshot = shelfSnapshotBeforeMetadata,
                    updatedShelfState = updatedShelfState,
                )) {
                    MetadataApplyResult.MissingLocal -> {
                        markUnhandled(meta)
                        return@runCatching
                    }
                    else -> markHandled(meta)
                }
            }.onFailure { e ->
                errors += "metadata ${parsed.syncId}: ${e.message ?: e.javaClass.simpleName}"
                markUnhandled(meta)
            }
        }
        if (updatedShelfState != shelfSnapshotBeforeMetadata.recordsBySyncId) {
            shelfStateStore.save(bookRepository.booksDirectory, updatedShelfState)
        }

        // ── Pass 5: bookmarks and chats now find their local roots and get applied.
        for ((index, pair) in bookmarksAndChats.withIndex()) {
            val (parsed, meta) = pair
            onProgress(
                HttpSyncProgress(
                    message = "Applying remote reading data",
                    detail = "Item ${index + 1} of ${bookmarksAndChats.size}: ${parsed.kind.name.lowercase()} for ${parsed.syncId}",
                    completed = index,
                    total = bookmarksAndChats.size,
                ),
            )
            val root = rootsBySyncId[parsed.syncId]
            if (parsed.syncId in deletedSyncIds) {
                markHandled(meta)
                continue
            }
            if (root == null) {
                markUnhandled(meta)
                continue
            }
            runCatching {
                when (parsed.kind) {
                    BookKeyKind.Bookmark ->
                        if (applyBookmarkFromRemote(transport, root, meta)) downloadedBookmarks += 1
                    BookKeyKind.Chat ->
                        if (applyChatEntryFromRemote(transport, root, meta)) downloadedChatEntries += 1
                    else -> Unit
                }
                markHandled(meta)
            }.onFailure { e ->
                errors += "${parsed.kind.name.lowercase()} ${parsed.syncId}: ${e.message ?: e.javaClass.simpleName}"
                markUnhandled(meta)
            }
        }

        // A previous Android build could advance lastSyncedAt past chat keys that were
        // listed before their book payload/root was available. Once that happens, an
        // incremental `since` list will never show those older chat keys again. Backfill
        // chat prefixes for local mokuro books so manual Sync now can recover those skipped
        // ChatGPT entries without forcing a full payload rescan.
        if (sinceCursor != null) {
            val rootEntries = rootsBySyncId.entries
                .filter { (_, root) -> bookContentType(root) == ContentType.Mokuro }
            for ((index, entry) in rootEntries.withIndex()) {
                val (syncId, root) = entry
                onProgress(
                    HttpSyncProgress(
                        message = "Backfilling manga chats",
                        detail = "Book ${index + 1} of ${rootEntries.size}: $syncId",
                        completed = index,
                        total = rootEntries.size,
                    ),
                )
                val knownChatKeys = runCatching {
                    aiHistoryStore.load(root).entries
                        .map { entry ->
                            chatKey(
                                syncId,
                                chatEntryKeySuffix(
                                    entry.timestampSeconds,
                                    entry.bubbleText,
                                    entry.response,
                                ),
                            )
                        }
                        .toMutableSet()
                }.getOrElse { e ->
                    errors += "chat backfill $syncId: ${e.message ?: e.javaClass.simpleName}"
                    mutableSetOf()
                }
                var chatCursor: String? = null
                do {
                    val page = try {
                        transport.list(
                            prefix = chatPrefixForBook(syncId),
                            cursor = chatCursor,
                        )
                    } catch (e: HttpSyncException) {
                        errors += "chat backfill $syncId: ${e.message}"
                        break
                    } catch (e: Exception) {
                        errors += "chat backfill $syncId: ${e.message ?: e.javaClass.simpleName}"
                        break
                    }
                    for (meta in page.keys) {
                        if (meta.key in knownChatKeys) {
                            continue
                        }
                        runCatching {
                            if (applyChatEntryFromRemote(transport, root, meta)) {
                                downloadedChatEntries += 1
                            }
                            knownChatKeys += meta.key
                        }.onFailure { e ->
                            errors += "chat backfill $syncId: ${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                    chatCursor = page.nextCursor
                } while (chatCursor != null && page.truncated)
            }
        }

        val remoteOnly = remoteSyncIds.count { it !in rootsBySyncId.keys && it !in deletedSyncIds }
        return InboundResult(
            downloadedBookmarks = downloadedBookmarks,
            downloadedChatEntries = downloadedChatEntries,
            downloadedPayloads = downloadedPayloads,
            remoteOnlyBooks = remoteOnly,
            maxHandledLastModified = maxHandledLastModified,
            minUnhandledLastModified = minUnhandledLastModified,
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

    private suspend fun applyMetadataFromRemote(
        transport: HttpSyncKvTransport,
        syncId: String,
        bookRoot: File?,
        meta: HttpSyncKvKeyMeta,
        shelfSnapshot: ShelfSnapshot,
        updatedShelfState: MutableMap<String, HttpSyncShelfPlacementRecord>,
    ): MetadataApplyResult {
        val remote = fetchRemoteMetadata(transport, meta.key)
            ?: throw HttpSyncException("Metadata at ${meta.key}: missing.")
        if (remote.blob.deletedAt != null) {
            if (bookRoot != null) {
                bookRepository.deleteBook(bookRoot)
            }
            updatedShelfState.remove(syncId)
            return MetadataApplyResult.Deleted
        }
        if (bookRoot == null) return MetadataApplyResult.MissingLocal
        if (remote.hasShelfName) {
            val bookId = bookRepository.loadMetadata(bookRoot)?.id
            val localShelfName = bookId?.let { shelfSnapshot.namesByBookId[it] }
            val localUpdatedAt = localShelfUpdatedAtForBook(
                syncId = syncId,
                shelfName = localShelfName,
                snapshot = shelfSnapshot,
            )
            if (shouldApplyRemoteShelfPlacement(remote.blob.shelfUpdatedAt, localUpdatedAt)) {
                val normalizedShelf = normalizeShelfName(remote.blob.shelfName)
                applyShelfPlacement(bookRoot, normalizedShelf)
                updatedShelfState[syncId] = HttpSyncShelfPlacementRecord(
                    shelfName = normalizedShelf,
                    updatedAt = remote.blob.shelfUpdatedAt ?: meta.lastModified,
                )
                return MetadataApplyResult.Applied
            }
        }
        return MetadataApplyResult.Noop
    }

    private suspend fun localShelvesUpdatedAt(): String? =
        bookRepository.shelvesLastModifiedMillis()?.let { Instant.ofEpochMilli(it).toString() }

    private suspend fun loadShelfSnapshot(): ShelfSnapshot {
        val namesByBookId = linkedMapOf<String, String>()
        for (shelf in bookRepository.loadShelves()) {
            for (bookId in shelf.bookIds) namesByBookId.putIfAbsent(bookId, shelf.name)
        }
        return ShelfSnapshot(
            namesByBookId = namesByBookId,
            recordsBySyncId = shelfStateStore.load(bookRepository.booksDirectory),
            shelvesUpdatedAt = localShelvesUpdatedAt(),
        )
    }

    private fun localShelfUpdatedAtForBook(
        syncId: String,
        shelfName: String?,
        snapshot: ShelfSnapshot,
    ): String? {
        val record = snapshot.recordsBySyncId[syncId]
        if (record != null && record.shelfName == shelfName) return record.updatedAt
        if (record != null || shelfName != null) {
            return snapshot.shelvesUpdatedAt ?: Instant.now().toString()
        }
        return null
    }

    private fun shouldApplyRemoteShelfPlacement(
        remoteShelfUpdatedAt: String?,
        localShelvesUpdatedAt: String?,
    ): Boolean {
        if (localShelvesUpdatedAt == null) return true
        if (remoteShelfUpdatedAt == null) return false
        return compareRfc3339(remoteShelfUpdatedAt, localShelvesUpdatedAt) >= 0
    }

    private suspend fun fetchRemoteMetadata(
        transport: HttpSyncKvTransport,
        key: String,
    ): RemoteBookMetadata? {
        val fetched = transport.get(key) ?: return null
        val body = fetched.body.toString(Charsets.UTF_8)
        return runCatching {
            RemoteBookMetadata(
                blob = json.decodeFromString(HttpSyncMetadataBlob.serializer(), body),
                hasShelfName = json.parseToJsonElement(body).jsonObject.containsKey("shelfName"),
            )
        }.getOrElse { error ->
            throw HttpSyncException("Metadata at $key: malformed JSON (${error.message ?: error.javaClass.simpleName})")
        }
    }

    private suspend fun fetchRemoteMetadataForUpload(
        transport: HttpSyncKvTransport,
        key: String,
    ): RemoteBookMetadataFetched? {
        val fetched = try {
            transport.get(key)
        } catch (e: HttpSyncException) {
            throw HttpSyncException("metadata GET: ${e.message}")
        }
        fetched ?: return null
        val body = fetched.body.toString(Charsets.UTF_8)
        return runCatching {
            RemoteBookMetadataFetched(
                blob = json.decodeFromString(HttpSyncMetadataBlob.serializer(), body),
                hasShelfName = json.parseToJsonElement(body).jsonObject.containsKey("shelfName"),
                lastModified = fetched.lastModified,
            )
        }.getOrElse { error ->
            throw HttpSyncException("metadata decode: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private suspend fun applyShelfPlacement(bookRoot: File, shelfName: String?) {
        val bookId = bookRepository.loadMetadata(bookRoot)?.id ?: return
        val normalizedShelf = normalizeShelfName(shelfName)
        val shelves = bookRepository.loadShelves()
        var foundTargetShelf = false
        val updated = shelves.map { shelf ->
            val withoutBook = shelf.bookIds.filterNot { it == bookId }
            if (normalizedShelf != null && shelf.name == normalizedShelf) {
                foundTargetShelf = true
                shelf.copy(bookIds = (withoutBook + bookId).distinct())
            } else {
                shelf.copy(bookIds = withoutBook)
            }
        }
        val finalShelves = if (normalizedShelf != null && !foundTargetShelf) {
            updated + BookShelf(normalizedShelf, listOf(bookId))
        } else {
            updated
        }
        if (finalShelves != shelves) {
            bookRepository.saveShelves(finalShelves)
        }
    }

    private fun normalizeShelfName(shelfName: String?): String? =
        shelfName?.trim()?.takeIf { it.isNotEmpty() }

    // ----- Outbound ----------------------------------------------------------------------

    private data class OutboundResult(
        val uploadedBookmarks: Int,
        val uploadedChatEntries: Int,
        val uploadedMetadata: Int,
        val uploadedPayloads: Int,
        val maxLastModified: String?,
        val errors: List<String>,
    )

    private data class LocalSyncBook(
        val bookId: String,
        val title: String,
        val syncId: String,
        val root: File,
        val contentType: ContentType,
        val shelfName: String?,
        /**
         * RFC 3339 UTC stamp from `BookMetadata.importedAt`. Read once when we build the
         * local snapshot; used by both Pass 2 of [pullChangedKeys] and the outbound pass
         * in [pushAllLocal] to compare against `remote.metadata.deletedAt` so a re-import
         * after a server-side tombstone is not wiped on the next sync.
         */
        val importedAt: String?,
    )

    private data class LocalChatUpload(
        val entry: AiChatEntry,
        val key: String,
    )

    private suspend fun pushAllLocal(
        transport: HttpSyncKvTransport,
        onProgress: suspend (HttpSyncProgress) -> Unit,
    ): OutboundResult {
        var uploadedBookmarks = 0
        var uploadedChatEntries = 0
        var uploadedMetadata = 0
        var uploadedPayloads = 0
        var maxLastModified: String? = null
        val errors = mutableListOf<String>()

        val entries = bookRepository.loadBookEntries()
        val shelfSnapshot = loadShelfSnapshot()
        val updatedShelfState = shelfSnapshot.recordsBySyncId.toMutableMap()
        // Snapshot the deleted-book sidecar ONLY to drive iteration. Every
        // mutation of the sidecar below goes through the store's atomic per-key
        // helpers (`recordDeletedBook` / `removeDeletedBook`) so a concurrent
        // `BookshelfRepository.recordHttpSyncTombstone` mid-sync survives —
        // same Bug 3 fix the v3 executor applies.
        val pendingDeletedBooks = deletedBookStateStore.load(bookRepository.booksDirectory)
        val localBooks = entries.mapNotNull { entry ->
            val title = entry.metadata.title.orEmpty().ifBlank { return@mapNotNull null }
            val syncId = deriveSyncId(title) ?: return@mapNotNull null
            LocalSyncBook(
                bookId = entry.metadata.id,
                title = title,
                syncId = syncId,
                root = entry.root,
                contentType = bookContentType(entry.root),
                shelfName = shelfSnapshot.namesByBookId[entry.metadata.id],
                importedAt = entry.metadata.importedAt,
            )
        }
        // Prune stale tombstones (a tombstone whose syncId now matches a live
        // local book) one key at a time through the atomic remove helper.
        val liveLocalSyncIds = localBooks.map { it.syncId }.toSet()
        val tombstonesToPush = pendingDeletedBooks.filterKeys { it !in liveLocalSyncIds }
        for (syncId in pendingDeletedBooks.keys) {
            if (syncId in liveLocalSyncIds) {
                deletedBookStateStore.removeDeletedBook(bookRepository.booksDirectory, syncId)
            }
        }
        val payloadBookCount = localBooks.count { it.contentType == ContentType.Mokuro }
        var payloadBookIndex = 0
        for ((syncId, deleted) in tombstonesToPush) {
            try {
                onProgress(
                    HttpSyncProgress(
                        message = "Uploading deleted book markers",
                        detail = deleted.title,
                    ),
                )
                val response = transport.put(
                    key = metadataKey(syncId),
                    contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
                    body = json.encodeToString(
                        HttpSyncMetadataBlob.serializer(),
                        HttpSyncMetadataBlob(
                            title = deleted.title,
                            contentType = deleted.contentType,
                            deletedAt = deleted.deletedAt,
                        ),
                    ).toByteArray(),
                )
                uploadedMetadata += 1
                maxLastModified = maxRfc(maxLastModified, response.lastModified)
                // Atomic per-key clear: re-reads disk under the store's lock so
                // a concurrent recordDeletedBook for a different syncId during
                // sync (Bug 3) survives. Critically, we ONLY remove the
                // tombstone after the remote PUT succeeded — matching the
                // previous behavior where `pendingDeletedBooks.remove(syncId)`
                // sat inside the same try block.
                deletedBookStateStore.removeDeletedBook(bookRepository.booksDirectory, syncId)
                updatedShelfState.remove(syncId)
            } catch (e: HttpSyncException) {
                errors += "${deleted.title}: ${e.message}"
            }
        }
        for ((index, book) in localBooks.withIndex()) {
            val title = book.title
            val syncId = book.syncId
            val root = book.root
            val contentType = book.contentType
            val shelfName = book.shelfName
            try {
                onProgress(
                    HttpSyncProgress(
                        message = "Uploading local book state",
                        detail = "Book ${index + 1} of ${localBooks.size}: $title",
                        completed = index,
                        total = localBooks.size,
                    ),
                )
                val remoteMetadata = fetchRemoteMetadataForUpload(transport, metadataKey(syncId))
                val remoteDeletedAt = remoteMetadata?.blob?.deletedAt
                val tombstoneOverridden = remoteDeletedAt != null &&
                    localImportedAtOverridesRemoteDeletion(book.importedAt, remoteDeletedAt)
                if (remoteDeletedAt != null && !tombstoneOverridden) {
                    bookRepository.deleteBook(root)
                    updatedShelfState.remove(syncId)
                    continue
                }
                var uploadShelfName = shelfName
                var uploadShelfUpdatedAt = localShelfUpdatedAtForBook(
                    syncId = syncId,
                    shelfName = uploadShelfName,
                    snapshot = shelfSnapshot,
                )
                if (
                    remoteMetadata?.hasShelfName == true &&
                    shouldApplyRemoteShelfPlacement(remoteMetadata.blob.shelfUpdatedAt, uploadShelfUpdatedAt)
                ) {
                    uploadShelfName = normalizeShelfName(remoteMetadata.blob.shelfName)
                    uploadShelfUpdatedAt = remoteMetadata.blob.shelfUpdatedAt ?: remoteMetadata.lastModified
                    applyShelfPlacement(root, uploadShelfName)
                    updatedShelfState[syncId] = HttpSyncShelfPlacementRecord(
                        shelfName = uploadShelfName,
                        updatedAt = uploadShelfUpdatedAt,
                    )
                }
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

                // If our local importedAt overrides the remote tombstone, publish the local
                // stamp + `deletedAt = null` so every other device pulling next will see the
                // book come back to life. Otherwise preserve whatever importedAt the server
                // already had (or this device's stamp if the server's is null/older) so the
                // freshly re-imported state propagates correctly.
                val uploadImportedAt = if (tombstoneOverridden) {
                    book.importedAt
                } else {
                    maxRfc(remoteMetadata?.blob?.importedAt, book.importedAt)
                }
                val uploadDeletedAt = if (tombstoneOverridden) null else remoteMetadata?.blob?.deletedAt
                val metadataResponse = transport.put(
                    key = metadataKey(syncId),
                    contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
                    body = json.encodeToString(
                        HttpSyncMetadataBlob.serializer(),
                        HttpSyncMetadataBlob(
                            title = title,
                            contentType = HttpSyncContentType.fromLocal(contentType),
                            shelfName = uploadShelfName,
                            shelfUpdatedAt = uploadShelfUpdatedAt,
                            importedAt = uploadImportedAt,
                            deletedAt = uploadDeletedAt,
                        ),
                    ).toByteArray(),
                )
                uploadedMetadata += 1
                maxLastModified = maxRfc(maxLastModified, metadataResponse.lastModified)
                if (uploadShelfUpdatedAt != null) {
                    updatedShelfState[syncId] = HttpSyncShelfPlacementRecord(
                        shelfName = uploadShelfName,
                        updatedAt = uploadShelfUpdatedAt,
                    )
                } else {
                    updatedShelfState.remove(syncId)
                }

                // Payload push: zip the book directory once, compare sha to remote manifest,
                // upload zip + manifest only if different. The codec caches nothing, so this
                // is roughly free on a second sync (it'll fetch the manifest, see the sha
                // matches, skip the zip entirely). Mokuro-only for v2.0; EPUB payload sync
                // can be added by widening the gate.
                if (contentType == ContentType.Mokuro) {
                    val currentPayloadIndex = payloadBookIndex
                    payloadBookIndex += 1
                    onProgress(
                        HttpSyncProgress(
                            message = "Checking manga payload upload",
                            detail = "Book ${currentPayloadIndex + 1} of $payloadBookCount: $title",
                            completed = currentPayloadIndex,
                            total = payloadBookCount,
                        ),
                    )
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
                        val missingChatUploads = chatEntries.mapNotNull { chatEntry ->
                            val suffix = chatEntryKeySuffix(chatEntry.timestampSeconds, chatEntry.bubbleText, chatEntry.response)
                            val key = chatKey(syncId, suffix)
                            if (key in existing) null else LocalChatUpload(chatEntry, key)
                        }
                        for ((chatIndex, upload) in missingChatUploads.withIndex()) {
                            onProgress(
                                HttpSyncProgress(
                                    message = "Uploading manga chat history",
                                    detail = "$title: chat ${chatIndex + 1} of ${missingChatUploads.size}",
                                    completed = chatIndex,
                                    total = missingChatUploads.size,
                                ),
                            )
                            val response = transport.put(
                                key = upload.key,
                                contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
                                body = json.encodeToString(
                                    HttpSyncChatEntryBlob.serializer(),
                                    upload.entry.toBlob(),
                                ).toByteArray(),
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
        if (updatedShelfState != shelfSnapshot.recordsBySyncId) {
            shelfStateStore.save(bookRepository.booksDirectory, updatedShelfState)
        }
        // No final full-map save for the deleted-books sidecar — every mutation
        // above went through atomic per-key helpers under the store's lock so a
        // concurrent `BookshelfRepository.recordHttpSyncTombstone` can't be
        // clobbered. See the Bug 3 comment at the top of `pushAllLocal`.
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
                    // Remote is newer. Inbound normally already applied this exact key, but
                    // a concurrent push from another device can appear between inbound and
                    // outbound. Apply it here before refusing to upload, so this sync pass
                    // converges locally instead of waiting for another manual sync.
                    bookRepository.saveBookmark(
                        bookRoot,
                        Bookmark(
                            chapterIndex = remoteBlob.chapterIndex,
                            progress = remoteBlob.progress,
                            characterCount = remoteBlob.characterCount,
                            lastModified = rfc3339ToAppleSeconds(remoteBlob.lastModified),
                        ),
                    )
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
        // Mirror the user-side import path: parse the freshly-unzipped book and resolve a
        // cover path so the bookshelf can render a thumbnail before the user opens it. Without
        // this, `metadata.cover` stayed null until the first open triggered the parser via
        // `BookshelfRepository.openBook`, and the bookshelf showed a blank cover slot.
        val coverPath = resolveSyncImportedCoverPath(bookRepository, targetRoot)
        // Write a minimal metadata sidecar — title comes from the manifest, id is fresh per
        // device (consistent with how local imports generate UUIDs).
        bookRepository.saveMetadata(
            targetRoot,
            BookMetadata(
                id = UUID.randomUUID().toString(),
                title = manifest.originalName,
                cover = coverPath,
                folder = targetRoot.name,
                lastAccess = 0.0,
                // Stamp the import so this device participates in the re-import-after-tombstone
                // protocol (see `compareRfc3339(local.importedAt, remote.deletedAt)` callers below).
                importedAt = Instant.now().toString(),
            ),
        )
        return targetRoot
    }
}

data class HttpSyncProgress(
    val message: String,
    val detail: String? = null,
    val completed: Int? = null,
    val total: Int? = null,
) {
    val fraction: Float?
        get() {
            val done = completed ?: return null
            val count = total ?: return null
            if (count <= 0) return null
            return ((done + 1).toFloat() / count.toFloat()).coerceIn(0f, 1f)
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

private fun plural(n: Int): String = if (n == 1) "" else "s"
