package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
import moe.antimony.hoshi.features.ai.PRETRANSLATIONS_FILENAME
import moe.antimony.hoshi.features.ai.PretranslationStore
import moe.antimony.hoshi.features.ai.EPUB_TRANSLATIONS_FILENAME
import moe.antimony.hoshi.features.ai.EpubTranslationStore
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.BookMetadata
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * Reader bookmarks use the durable five-second map exchange and bypass this full flow.
 * Chat replies still use [HttpSyncPusher] as a content-addressed one-key PUT.
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
    private val revisionStore = HttpSyncRevisionStore(json)

    /**
     * One reconciliation pass. Inbound first so a newer server bookmark is not stomped
     * by our outbound push.
     */
    suspend fun syncOnce(
        settings: HttpSyncSettings,
        transportOverride: HttpSyncKvTransport? = null,
        onProgress: suspend (HttpSyncProgress) -> Unit = {},
    ): HttpSyncResult = withContext(ioDispatcher) {
        require(settings.isConfigured) { "HTTP sync is not configured." }
        val transport = transportOverride ?: transportFactory(settings)

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
            downloadedPretranslations = inbound.downloadedPretranslations,
            downloadedSentenceTranslations = inbound.downloadedSentenceTranslations,
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

    /**
     * Runs ONLY the bidirectional app-settings (ChatGPT model + prompts) LWW sync — used by
     * [HttpSyncAutoPush.onAiSettingsChanged] after its debounce. Returns the error strings
     * (empty = success). Mirrors iOS, where `HttpSyncManager.onAiSettingsChanged` calls
     * `HttpSyncReconciler.syncAppSettings` directly.
     */
    suspend fun syncAppSettingsOnly(settings: HttpSyncSettings): List<String> = withContext(ioDispatcher) {
        require(settings.isConfigured) { "HTTP sync is not configured." }
        syncAppSettings(transportFactory(settings)) { }.errors
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
        /** Books whose offline pre-translation blob was pulled this pass. */
        val downloadedPretranslations: Int,
        val downloadedSentenceTranslations: Int,
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
        var downloadedPretranslations = 0
        var downloadedSentenceTranslations = 0
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
        for (entry in localBookEntries) {
            val resolved = syncIdForMetadata(entry.metadata) ?: continue
            if (entry.metadata.syncId != resolved) {
                bookRepository.saveMetadata(entry.root, entry.metadata.copy(syncId = resolved))
            }
        }
        val rootsBySyncId: MutableMap<String, File> = mutableMapOf<String, File>().apply {
            for (entry in localBookEntries) {
                val syncId = syncIdForMetadata(entry.metadata) ?: continue
                put(syncId, entry.root)
            }
        }
        // Per-syncId `BookMetadata.importedAt` so Pass 2 (tombstone application) can compare
        // local import stamps against remote `deletedAt`. A strictly-newer local stamp means
        // the user re-imported the book after the tombstone was published — we keep the
        // local copy and let the outbound pass push fresh `deletedAt = null` metadata.
        val localImportedAtBySyncId: Map<String, String?> = buildMap {
            for (entry in localBookEntries) {
                val syncId = syncIdForMetadata(entry.metadata) ?: continue
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
        val payloadManifests = mutableListOf<Pair<ParsedBookKey, HttpSyncKvKeyMeta>>()
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
                    BookKeyKind.PayloadManifest, BookKeyKind.EpubManifest -> payloadManifests += parsed to meta
                    BookKeyKind.Bookmark, BookKeyKind.Chat, BookKeyKind.Pretranslations, BookKeyKind.Sentences ->
                        bookmarksAndChats += parsed to meta
                    BookKeyKind.PayloadZip, BookKeyKind.EpubZip -> markHandled(meta) // followed via the manifest
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
        val remoteContentTypeBySyncId = mutableMapOf<String, HttpSyncContentType>()
        val placementMetadataKeys = mutableListOf<Pair<ParsedBookKey, HttpSyncKvKeyMeta>>()
        for ((parsed, meta) in metadataKeys) {
            runCatching {
                val remote = fetchRemoteMetadata(transport, meta.key)
                    ?: throw HttpSyncException("Metadata at ${meta.key}: missing.")
                remoteContentTypeBySyncId[parsed.syncId] = remote.blob.contentType
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
                        val targetRoot = rootsBySyncId[parsed.syncId]
                        val removed = targetRoot == null || HttpSyncActiveBooks.runIfInactive(parsed.syncId) {
                            bookRepository.deleteBook(targetRoot)
                        }
                        if (!removed) {
                            errors += "metadata ${parsed.syncId}: deletion deferred while this book is open"
                            markUnhandled(meta)
                        } else {
                            rootsBySyncId.remove(parsed.syncId)
                            updatedShelfState.remove(parsed.syncId)
                            deletedSyncIds += parsed.syncId
                            markHandled(meta)
                        }
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
        // Resolve the exact manifest family once per book. Metadata is authoritative; without it,
        // two families are ambiguous and fail closed. EPUB prefers the canonical iOS pair and only
        // falls back to Android 0.11's payload.* shape when epub.manifest is absent.
        val selectedPayloadManifests = mutableListOf<Pair<ParsedBookKey, HttpSyncKvKeyMeta>>()
        for ((syncId, candidates) in payloadManifests.groupBy { it.first.syncId }) {
            val canonical = candidates.firstOrNull { it.first.kind == BookKeyKind.EpubManifest }
            val legacy = candidates.firstOrNull { it.first.kind == BookKeyKind.PayloadManifest }
            val selected = when (remoteContentTypeBySyncId[syncId]) {
                HttpSyncContentType.Epub -> canonical ?: legacy
                HttpSyncContentType.Mokuro -> legacy
                null -> when {
                    canonical != null && legacy != null -> {
                        errors += "payload $syncId: both EPUB and payload manifest families exist without metadata"
                        canonical.second.let(::markUnhandled)
                        legacy.second.let(::markUnhandled)
                        null
                    }
                    else -> canonical ?: legacy
                }
            }
            if (selected != null) selectedPayloadManifests += selected
            for (candidate in candidates) {
                if (candidate != selected) markHandled(candidate.second)
            }
        }
        for ((index, pair) in selectedPayloadManifests.withIndex()) {
            val (parsed, meta) = pair
            onProgress(
                HttpSyncProgress(
                    message = "Checking remote book payloads",
                    detail = "Book ${index + 1} of ${selectedPayloadManifests.size}: ${parsed.syncId}",
                    completed = index,
                    total = selectedPayloadManifests.size,
                ),
            )
            if (parsed.syncId in deletedSyncIds) {
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
            val existingRoot = rootsBySyncId[parsed.syncId]
            if (existingRoot != null) {
                runCatching {
                    val keys = when (parsed.kind) {
                        BookKeyKind.EpubManifest -> HttpSyncPayloadKeys.forFormat(HttpSyncContentType.Epub, parsed.syncId)
                        else -> HttpSyncPayloadKeys.legacy(parsed.syncId)
                    }
                    val expectedFormat = if (parsed.kind == BookKeyKind.EpubManifest) {
                        HttpSyncContentType.Epub
                    } else {
                        remoteContentTypeBySyncId[parsed.syncId]
                    }
                    if (refreshExistingBookPayload(
                            transport = transport,
                            syncId = parsed.syncId,
                            bookRoot = existingRoot,
                            keys = keys,
                            expectedFormat = expectedFormat,
                            onProgress = onProgress,
                        )
                    ) {
                        downloadedPayloads += 1
                    }
                    markHandled(meta)
                }.onFailure { e ->
                    errors += "payload ${parsed.syncId}: ${e.message ?: e.javaClass.simpleName}"
                    markUnhandled(meta)
                }
                continue
            }
            runCatching {
                val keys = when (parsed.kind) {
                    BookKeyKind.EpubManifest -> HttpSyncPayloadKeys.forFormat(HttpSyncContentType.Epub, parsed.syncId)
                    else -> HttpSyncPayloadKeys.legacy(parsed.syncId)
                }
                val expectedFormat = if (parsed.kind == BookKeyKind.EpubManifest) {
                    HttpSyncContentType.Epub
                } else {
                    remoteContentTypeBySyncId[parsed.syncId]
                }
                val imported = importRemoteOnlyBook(
                    transport = transport,
                    syncId = parsed.syncId,
                    keys = keys,
                    expectedFormat = expectedFormat,
                    onProgress = onProgress,
                )
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
                    BookKeyKind.Pretranslations ->
                        if (applyPretranslationsFromRemote(transport, root, meta)) {
                            downloadedPretranslations += 1
                        }
                    BookKeyKind.Sentences ->
                        if (applySentencesFromRemote(transport, parsed.syncId, root, meta)) {
                            downloadedSentenceTranslations += 1
                        }
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
            downloadedPretranslations = downloadedPretranslations,
            downloadedSentenceTranslations = downloadedSentenceTranslations,
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
            val localRev = revisionStore.current(bookRepository.booksDirectory, meta.key).localRev
            // Don't downgrade: apply only when the remote edit chain is deeper (timestamps
            // break ties; legacy blobs without rev keep the old pure-timestamp behavior).
            if (compareRevisioned(
                    localRev = localRev,
                    remoteRev = blob.rev,
                    localStamp = localModified,
                    remoteStamp = blob.lastModified,
                ) != SyncComparison.REMOTE_WINS
            ) {
                revisionStore.noteRemote(bookRepository.booksDirectory, meta.key, blob.rev, appliedLocally = false)
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
            revisionStore.noteRemote(bookRepository.booksDirectory, meta.key, blob.rev, appliedLocally = true)
            true
        }
    }

    /**
     * Pulls a volume's offline pre-translation blob to `pretranslations.json` in the book dir.
     *
     * Download-only by design: these are produced by the desktop tool and never edited on device,
     * so there is no push side and no conflict resolution — the server copy always wins. The bytes
     * are validated by decoding them first, so a truncated download cannot replace a good local
     * cache, then written verbatim to avoid re-encoding a file with thousands of entries.
     */
    private suspend fun applyPretranslationsFromRemote(
        transport: HttpSyncKvTransport,
        bookRoot: File,
        meta: HttpSyncKvKeyMeta,
    ): Boolean {
        if (bookContentType(bookRoot) != ContentType.Mokuro) return false
        val target = File(bookRoot, PRETRANSLATIONS_FILENAME)
        // Skip the transfer when the local copy already matches what the server lists. A manual
        // sync lists every key with no cursor, so without this a 30-volume shelf would re-download
        // and rewrite every blob on every sync and report them all as freshly downloaded.
        val remoteSize = meta.size
        if (target.isFile && target.length() == remoteSize.toLong()) {
            return false
        }
        val fetched = transport.get(meta.key)
            ?: throw HttpSyncException("Offline translations at ${meta.key}: listed key is missing.")
        val body = fetched.body.toString(Charsets.UTF_8)
        val blob = runCatching {
            json.decodeFromString(PretranslationsBlob.serializer(), body)
        }.getOrElse { error ->
            throw HttpSyncException(
                "Offline translations at ${meta.key}: malformed JSON " +
                    "(${error.message ?: error.javaClass.simpleName})",
            )
        }
        // "Valid JSON" alone is far too weak: every field decodes leniently, so `{}` or a proxy
        // error page returned with HTTP 200 would decode to an empty blob and wipe a good cache.
        if (blob.version > PretranslationStore.SUPPORTED_BLOB_VERSION) {
            throw HttpSyncException("Offline translations at ${meta.key}: unsupported version ${blob.version}.")
        }
        if (blob.entries.isEmpty()) {
            throw HttpSyncException("Offline translations at ${meta.key}: no entries.")
        }
        // Temp + rename, matching AiChatHistoryStore: a direct writeText would leave a truncated
        // file behind if the process died mid-write, silently losing every offline translation.
        val temp = File(bookRoot, "$PRETRANSLATIONS_FILENAME.tmp")
        temp.writeText(body, Charsets.UTF_8)
        replaceSyncSidecar(temp, target, meta.key)
        PretranslationStore.invalidate(bookRoot)
        return true
    }

    /** Pulls and atomically installs an iOS-compatible EPUB sentence translation blob. */
    private suspend fun applySentencesFromRemote(
        transport: HttpSyncKvTransport,
        syncId: String,
        bookRoot: File,
        meta: HttpSyncKvKeyMeta,
    ): Boolean {
        if (bookContentType(bookRoot) != ContentType.Epub) return false
        val target = File(bookRoot, EPUB_TRANSLATIONS_FILENAME)
        if (meta.size > MAX_EPUB_SENTENCES_BLOB_BYTES) {
            throw HttpSyncException("Sentence translations at ${meta.key}: blob is too large (${meta.size} bytes).")
        }
        val fetched = transport.getBounded(meta.key, MAX_EPUB_SENTENCES_BLOB_BYTES)
            ?: throw HttpSyncException("Sentence translations at ${meta.key}: listed key is missing.")
        val alreadyInstalled = target.isFile &&
            target.length() == fetched.body.size.toLong() &&
            target.length() <= MAX_EPUB_SENTENCES_BLOB_BYTES &&
            target.readBytes().contentEquals(fetched.body)
        val body = fetched.body.toString(Charsets.UTF_8)
        val spineCount = runCatching {
            EpubBookParser().parse(
                root = bookRoot,
                cachedBookInfo = bookRepository.loadBookInfo(bookRoot),
            ).spineCount
        }.getOrElse { error ->
            throw HttpSyncException(
                "Sentence translations at ${meta.key}: could not parse EPUB " +
                    "(${error.message ?: error.javaClass.simpleName})",
            )
        }
        runCatching {
            EpubTranslationStore.decodeAndValidate(body, syncId, spineCount)
        }.getOrElse { error ->
            throw HttpSyncException(
                "Sentence translations at ${meta.key}: ${error.message ?: error.javaClass.simpleName}",
            )
        }
        if (alreadyInstalled) return false
        val temp = File(bookRoot, "$EPUB_TRANSLATIONS_FILENAME.tmp")
        temp.writeText(body, Charsets.UTF_8)
        replaceSyncSidecar(temp, target, meta.key)
        EpubTranslationStore.invalidate(bookRoot)
        return true
    }

    private fun replaceSyncSidecar(source: File, target: File, key: String) {
        try {
            try {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            source.delete()
            throw HttpSyncException("Translations at $key: could not replace $target (${error.message}).")
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
            screenshotImage = blob.screenshotImage,
            dictionaryLookup = blob.dictionaryLookup,
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
                val removed = HttpSyncActiveBooks.runIfInactive(syncId) {
                    bookRepository.deleteBook(bookRoot)
                }
                if (!removed) throw HttpSyncException("Deletion deferred while this book is open.")
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

    // shouldApplyRemoteShelfPlacement moved to HttpSyncBlobs.kt (shared spec function,
    // also used by the v3 planner and the fire-and-forget pushMetadata; mirrors iOS SyncCore).

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
            val syncId = syncIdForMetadata(entry.metadata) ?: return@mapNotNull null
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
        val payloadBookCount = localBooks.size
        var payloadBookIndex = 0
        for ((syncId, deleted) in tombstonesToPush) {
            try {
                onProgress(
                    HttpSyncProgress(
                        message = "Uploading deleted book markers",
                        detail = deleted.title,
                    ),
                )
                val mKey = metadataKey(syncId)
                val response = bookLocks.withKeyLock(mKey) {
                    val localRev = revisionStore.current(bookRepository.booksDirectory, mKey).localRev
                    val written = transport.put(
                        key = mKey,
                        contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
                        body = json.encodeToString(
                            HttpSyncMetadataBlob.serializer(),
                            HttpSyncMetadataBlob(
                                title = deleted.title,
                                contentType = deleted.contentType,
                                deletedAt = deleted.deletedAt,
                                rev = localRev,
                            ),
                        ).toByteArray(),
                    )
                    revisionStore.noteRemote(bookRepository.booksDirectory, mKey, localRev, appliedLocally = true)
                    written
                }
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
                    val removed = HttpSyncActiveBooks.runIfInactive(syncId) {
                        bookRepository.deleteBook(root)
                    }
                    if (!removed) {
                        errors += "$title: deletion deferred while this book is open"
                        continue
                    }
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
                val mKey = metadataKey(syncId)
                val localRev = revisionStore.current(bookRepository.booksDirectory, mKey).localRev
                // Reconcile pushes merged state, not a new edit: carry the max of both revs
                // forward without bumping (only deliberate edits bump, via the hooks).
                val uploadRev = maxOf(localRev, remoteMetadata?.blob?.rev ?: 0)
                val metaBlob = HttpSyncMetadataBlob(
                    title = title,
                    contentType = HttpSyncContentType.fromLocal(contentType),
                    shelfName = uploadShelfName,
                    shelfUpdatedAt = uploadShelfUpdatedAt,
                    importedAt = uploadImportedAt,
                    deletedAt = uploadDeletedAt,
                    rev = uploadRev,
                )
                val metadataResponse = bookLocks.withKeyLock(mKey) {
                    if (remoteMetadata?.blob?.copy(rev = metaBlob.rev) == metaBlob) {
                        // Content identical to the server's — skip the PUT entirely.
                        revisionStore.noteRemote(bookRepository.booksDirectory, mKey, uploadRev, appliedLocally = true)
                        null
                    } else {
                        val current = revisionStore.current(bookRepository.booksDirectory, mKey)
                        if (current.localRev > uploadRev || current.baseRev > uploadRev) {
                            null
                        } else {
                            val written = transport.put(
                                key = mKey,
                                contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
                                body = json.encodeToString(HttpSyncMetadataBlob.serializer(), metaBlob).toByteArray(),
                            )
                            revisionStore.noteRemote(bookRepository.booksDirectory, mKey, uploadRev, appliedLocally = true)
                            written
                        }
                    }
                }
                if (metadataResponse != null) {
                    uploadedMetadata += 1
                    maxLastModified = maxRfc(maxLastModified, metadataResponse.lastModified)
                }
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
                // matches, skip the zip entirely). Mokuro uses payload.*; EPUB uses the iOS
                // epub.* pair selected by HttpSyncPayloadCodec.
                if (contentType == ContentType.Mokuro || contentType == ContentType.Epub) {
                    val currentPayloadIndex = payloadBookIndex
                    payloadBookIndex += 1
                    onProgress(
                        HttpSyncProgress(
                            message = "Checking book payload upload",
                            detail = "Book ${currentPayloadIndex + 1} of $payloadBookCount: $title",
                            completed = currentPayloadIndex,
                            total = payloadBookCount,
                        ),
                    )
                    val uploaded = withByteProgress(
                        onProgress = onProgress,
                        makeProgress = { transferred, total ->
                            byteProgressOf("Uploading", title, transferred, total)
                        },
                    ) { onByteProgress ->
                        payloadCodec.uploadIfChanged(
                            transport = transport,
                            syncId = syncId,
                            bookRoot = root,
                            originalName = title,
                            format = HttpSyncContentType.fromLocal(contentType),
                            onByteProgress = onByteProgress,
                        )
                    }
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
        val localRev = revisionStore.current(bookRepository.booksDirectory, key).localRev
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
                // The actual edit timestamp wins; revision only makes equal-time decisions stable.
                when (compareRevisioned(
                    localRev = localRev,
                    remoteRev = remoteBlob.rev,
                    localStamp = localStamp,
                    remoteStamp = remoteBlob.lastModified,
                )) {
                    SyncComparison.REMOTE_WINS -> {
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
                        revisionStore.noteRemote(bookRepository.booksDirectory, key, remoteBlob.rev, appliedLocally = true)
                        return@withBookLock null
                    }
                    SyncComparison.TIE -> {
                        // Same depth, same stamp: nothing to push (avoids ping-pong PUTs).
                        revisionStore.noteRemote(bookRepository.booksDirectory, key, remoteBlob.rev, appliedLocally = true)
                        return@withBookLock null
                    }
                    SyncComparison.LOCAL_WINS -> Unit
                }
            }
        }
        val response = transport.put(
            key = key,
            contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
            body = json.encodeToString(
                HttpSyncBookmarkBlob.serializer(),
                local.toBlob().copy(rev = localRev),
            ).toByteArray(),
        )
        revisionStore.noteRemote(bookRepository.booksDirectory, key, localRev, appliedLocally = true)
        response
    }

    /**
     * Runs [block] while bridging its non-suspend byte-progress callback to the suspend
     * [onProgress] channel. A conflated [Channel] decouples the two: the byte callback only
     * ever does a non-blocking `trySend` (safe to call from the IO upload/download loop),
     * and a collector coroutine drains the latest value and emits an [HttpSyncProgress] with
     * byte-level `completed`/`total` so `fraction` reflects the current file's transfer.
     *
     * [makeProgress] turns a `(bytesTransferred, totalBytes)` pair into the progress to emit;
     * callers supply the per-file message/detail. When `totalBytes` is non-positive (e.g. a
     * chunked download with no Content-Length) the pair is still forwarded — `makeProgress`
     * decides whether to populate `completed`/`total`.
     */
    private suspend fun <T> withByteProgress(
        onProgress: suspend (HttpSyncProgress) -> Unit,
        makeProgress: (bytesTransferred: Long, totalBytes: Long) -> HttpSyncProgress,
        block: suspend (onByteProgress: (Long, Long) -> Unit) -> T,
    ): T = coroutineScope {
        // Conflated: a slow UI consumer just sees the most recent byte count, never a
        // backlog. The transfer loop is never throttled by progress emission.
        val channel = Channel<Pair<Long, Long>>(Channel.CONFLATED)
        val collector = launch {
            for ((transferred, total) in channel) {
                onProgress(makeProgress(transferred, total))
            }
        }
        try {
            block { transferred, total -> channel.trySend(transferred to total) }
        } finally {
            channel.close()
            collector.join()
        }
    }

    /**
     * Bytes-to-`HttpSyncProgress` mapper shared by the upload and download paths. Uses byte
     * counts as `completed`/`total` so [HttpSyncProgress.fraction] tracks the file transfer;
     * a non-positive `totalBytes` (chunked transfer, unknown length) leaves them null so the
     * indicator falls back to indeterminate.
     */
    private fun byteProgressOf(
        message: String,
        title: String,
        transferred: Long,
        total: Long,
    ): HttpSyncProgress {
        val haveTotal = total > 0L
        // completed/total are Int; a 100 MB payload (~1e8) fits well within Int.MAX_VALUE,
        // but coerce defensively so a hypothetical >2 GB payload can't overflow.
        return HttpSyncProgress(
            message = "$message $title",
            detail = if (haveTotal) {
                "${megabytes(transferred)} MB / ${megabytes(total)} MB"
            } else {
                "${megabytes(transferred)} MB"
            },
            completed = if (haveTotal) {
                transferred.coerceAtMost(total).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else {
                null
            },
            total = if (haveTotal) total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null,
        )
    }

    // ----- Key parsing --------------------------------------------------------------------

    private enum class BookKeyKind {
        Bookmark,
        Chat,
        Metadata,
        PayloadManifest,
        PayloadZip,
        EpubManifest,
        EpubZip,
        Pretranslations,
        Sentences,
    }

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
            suffix == "epub.manifest" -> BookKeyKind.EpubManifest
            suffix == "epub.zip" -> BookKeyKind.EpubZip
            suffix == "pretranslations" -> BookKeyKind.Pretranslations
            suffix == "sentences" -> BookKeyKind.Sentences
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
        keys: HttpSyncPayloadKeys = HttpSyncPayloadKeys.legacy(syncId),
        expectedFormat: HttpSyncContentType? = null,
        onProgress: suspend (HttpSyncProgress) -> Unit = {},
    ): File? {
        val stagingRoot = createSyncImportStagingDirectory(bookRepository.booksDirectory)
        var publishedRoot: File? = null
        return try {
            val manifest = withByteProgress(
                onProgress = onProgress,
                makeProgress = { transferred, total ->
                    byteProgressOf("Downloading", syncId, transferred, total)
                },
            ) { onByteProgress ->
                payloadCodec.downloadAndUnpack(
                    transport = transport,
                    syncId = syncId,
                    targetDir = stagingRoot,
                    onByteProgress = onByteProgress,
                    keys = keys,
                    expectedFormat = expectedFormat,
                )
            }
            val actualContentType = bookContentType(stagingRoot)
            if (actualContentType != manifest.format.toLocal()) {
                throw HttpSyncException(
                    "Payload for $syncId declares ${manifest.format} but unpacked as $actualContentType.",
                )
            }
            val parsedEpub = validateSyncImportedBook(stagingRoot)
            val targetRoot = publishSyncImportDirectory(stagingRoot, bookRepository.booksDirectory)
            publishedRoot = targetRoot
            val coverPath = if (parsedEpub != null) {
                bookRepository.metadataCoverPath(targetRoot, parsedEpub.coverHref)
            } else {
                resolveSyncImportedCoverPath(bookRepository, targetRoot)
            }
            parsedEpub?.let { bookRepository.saveBookInfo(targetRoot, it.bookInfo) }
            bookRepository.saveMetadata(
                targetRoot,
                BookMetadata(
                    id = UUID.randomUUID().toString(),
                    title = manifest.originalName,
                    cover = coverPath,
                    folder = targetRoot.name,
                    lastAccess = 0.0,
                    syncId = syncId,
                    importedAt = Instant.now().toString(),
                ),
            )
            targetRoot
        } catch (e: Exception) {
            // Download, type detection, parse/cover generation, and metadata registration form one
            // transaction. A malformed remote EPUB must not leave a ghost shelf directory.
            (publishedRoot ?: stagingRoot).deleteRecursively()
            throw e
        }
    }

    /** Replace static book bytes only after a previously-verified SHA changes. */
    private suspend fun refreshExistingBookPayload(
        transport: HttpSyncKvTransport,
        syncId: String,
        bookRoot: File,
        keys: HttpSyncPayloadKeys,
        expectedFormat: HttpSyncContentType?,
        onProgress: suspend (HttpSyncProgress) -> Unit,
    ): Boolean {
        // Reader saves do not participate in the sync book lock. Never rename its directory
        // underneath a live reader; the route triggers another map pass as soon as it closes.
        if (HttpSyncActiveBooks.contains(syncId)) return false
        val remote = payloadCodec.fetchManifest(transport, syncId, keys) ?: return false
        if (payloadCodec.hasPayloadContentDirty(bookRoot)) return false
        val localSha = payloadCodec.cachedPayloadSha(bookRoot)
            ?: payloadCodec.ensurePayloadContentSha(bookRoot)
        if (remote.contentSha256 != null && localSha == remote.contentSha256) return false

        val stagingRoot = createSyncImportStagingDirectory(bookRepository.booksDirectory)
        try {
            val manifest = withByteProgress(
                onProgress = onProgress,
                makeProgress = { transferred, total ->
                    byteProgressOf("Updating", syncId, transferred, total)
                },
            ) { onByteProgress ->
                payloadCodec.downloadAndUnpack(
                    transport = transport,
                    syncId = syncId,
                    targetDir = stagingRoot,
                    onByteProgress = onByteProgress,
                    keys = keys,
                    expectedFormat = expectedFormat,
                )
            }
            val actualContentType = bookContentType(stagingRoot)
            if (actualContentType != manifest.format.toLocal()) {
                throw HttpSyncException(
                    "Payload for $syncId declares ${manifest.format} but unpacked as $actualContentType.",
                )
            }
            val parsedEpub = validateSyncImportedBook(stagingRoot)
            val verifiedRemoteSha = requireNotNull(manifest.contentSha256)
            if (remote.contentSha256 == null) {
                payloadCodec.publishVerifiedContentSha(transport, keys, manifest)
            }
            if (localSha == verifiedRemoteSha) return false
            return bookLocks.withBookLock(bookRoot) {
                // A local same-title import may have completed while this payload downloaded.
                // Its dirty generation is authoritative and must not be overwritten.
                if (payloadCodec.hasPayloadContentDirty(bookRoot)) return@withBookLock false
                HttpSyncActiveBooks.runIfInactive(syncId) {
                    payloadCodec.installReplacement(
                        bookRoot,
                        stagingRoot,
                        verifiedRemoteSha,
                    )
                    parsedEpub?.let { bookRepository.saveBookInfo(bookRoot, it.bookInfo) }
                }
            }
        } finally {
            if (stagingRoot.exists()) stagingRoot.deleteRecursively()
        }
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
    /** Books whose offline pre-translation blob was pulled this pass. */
    val downloadedPretranslations: Int = 0,
    /** EPUBs whose sentence translation blob was pulled this pass. */
    val downloadedSentenceTranslations: Int = 0,
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
        if (downloadedPretranslations > 0) {
            parts += "$downloadedPretranslations offline translation set${plural(downloadedPretranslations)} down"
        }
        if (downloadedSentenceTranslations > 0) {
            parts += "$downloadedSentenceTranslations EPUB translation set${plural(downloadedSentenceTranslations)} down"
        }
        if (downloadedAppSettings) parts += "ChatGPT settings down"
        if (remoteOnlyBooks > 0) parts += "$remoteOnlyBooks remote-only book${plural(remoteOnlyBooks)}"
        if (parts.isEmpty()) parts += "nothing to sync"
        return parts.joinToString(", ")
    }

    private fun plural(n: Int): String = if (n == 1) "" else "s"
}

private fun plural(n: Int): String = if (n == 1) "" else "s"

/** Formats a byte count as a one-decimal MB string for per-file transfer progress. */
private fun megabytes(bytes: Long): String =
    "%.1f".format(bytes.coerceAtLeast(0L) / (1024.0 * 1024.0))
