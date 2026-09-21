package moe.antimony.hoshi.features.sync.v3

import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.sync.http.AI_CHAT_SETTINGS_KEY
import moe.antimony.hoshi.features.sync.http.ALL_BOOKS_PREFIX
import moe.antimony.hoshi.features.sync.http.HttpSyncAiChatSettingsBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncBookmarkBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncMetadataBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadManifest
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadKeys
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType

/**
 * Step 2 of the v3 algorithm. Reads everything an immediate sync would need to know
 * about the remote KV server into a [V3RemoteSnapshot].
 *
 * Implementation guidance: see `docs/SYNC_V3_SPEC.md` § Step 2.
 *
 * Decoding errors on individual blobs are accumulated as `V3Error` entries in the
 * returned [V3RemoteSnapshotResult.errors] list — the engine surfaces them in the
 * final [V3SyncResult].
 */
class V3RemoteState {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun read(
        transport: HttpSyncKvTransport,
        onProgress: suspend (V3Progress) -> Unit,
    ): V3RemoteSnapshotResult {
        val errors = mutableListOf<V3Error>()

        // ── Pass A: paginated `books/` LIST, collect per-key metadata only.
        data class RemoteKey(
            val syncId: String,
            val kind: BookKind,
            val key: String,
            val lastModified: String,
            /** Byte size from the listing; lets the planner skip an unchanged blob download. */
            val size: Int,
        )
        val keys = mutableListOf<RemoteKey>()
        var cursor: String? = null
        var pages = 0
        do {
            onProgress(
                V3Progress(
                    phase = V3Phase.ListingRemote,
                    message = "Listing remote state",
                    detail = if (pages == 0) {
                        "Asking the server for the current key set."
                    } else {
                        "Read $pages remote page(s) so far."
                    },
                ),
            )
            val page = transport.list(prefix = ALL_BOOKS_PREFIX, cursor = cursor)
            pages += 1
            for (meta in page.keys) {
                val parsed = parseBookKey(meta.key) ?: continue
                keys += RemoteKey(parsed.first, parsed.second, meta.key, meta.lastModified, meta.size)
            }
            cursor = page.nextCursor
        } while (cursor != null && page.truncated)

        // ── Pass B: group by syncId, build V3RemoteBook by fetching needed blob bodies.
        val grouped: MutableMap<String, MutableList<RemoteKey>> = linkedMapOf()
        for (k in keys) grouped.getOrPut(k.syncId) { mutableListOf() } += k

        val books = linkedMapOf<String, V3RemoteBook>()
        val syncIdsSorted = grouped.keys.sorted()
        for ((index, syncId) in syncIdsSorted.withIndex()) {
            onProgress(
                V3Progress(
                    phase = V3Phase.ListingRemote,
                    message = "Reading remote book state",
                    detail = "Book ${index + 1} of ${syncIdsSorted.size}: $syncId",
                    completed = index,
                    total = syncIdsSorted.size,
                ),
            )
            var metadata: HttpSyncMetadataBlob? = null
            var metadataLastModified: String? = null
            var metadataMalformed = false
            var legacyManifestKey: RemoteKey? = null
            var epubManifestKey: RemoteKey? = null
            var bookmark: HttpSyncBookmarkBlob? = null
            var bookmarkLastModified: String? = null
            var bookmarkMalformed = false
            val chatKeys = mutableSetOf<String>()
            var pretranslationsKey: String? = null
            var pretranslationsSize: Int? = null
            var sentencesKey: String? = null
            var sentencesSize: Int? = null
            var statisticsKey: String? = null
            var statisticsSize: Int? = null
            var statisticsLastModified: String? = null
            var mangaStatisticsKey: String? = null
            var mangaStatisticsSize: Int? = null
            var mangaStatisticsLastModified: String? = null
            for (k in grouped.getValue(syncId)) {
                when (k.kind) {
                    BookKind.Metadata -> {
                        try {
                            val fetched = transport.get(k.key)
                            if (fetched != null) {
                                metadata = json.decodeFromString(
                                    HttpSyncMetadataBlob.serializer(),
                                    fetched.body.toString(Charsets.UTF_8),
                                )
                                metadataLastModified = fetched.lastModified
                            }
                        } catch (e: Exception) {
                            // Bug 5: surface the decode error AND mark the field as
                            // malformed so the planner refuses to overwrite the corrupt
                            // remote bytes with our local copy.
                            metadataMalformed = true
                            errors += V3Error(
                                syncId = syncId,
                                action = "ReadRemoteMetadata",
                                message = "metadata ${syncId}: ${e.message ?: e.javaClass.simpleName}",
                            )
                        }
                    }
                    BookKind.LegacyManifest -> legacyManifestKey = k
                    BookKind.EpubManifest -> epubManifestKey = k
                    BookKind.Bookmark -> {
                        try {
                            val fetched = transport.get(k.key)
                            if (fetched != null) {
                                bookmark = json.decodeFromString(
                                    HttpSyncBookmarkBlob.serializer(),
                                    fetched.body.toString(Charsets.UTF_8),
                                )
                                bookmarkLastModified = fetched.lastModified
                            }
                        } catch (e: Exception) {
                            bookmarkMalformed = true
                            errors += V3Error(
                                syncId = syncId,
                                action = "ReadRemoteBookmark",
                                message = "bookmark ${syncId}: ${e.message ?: e.javaClass.simpleName}",
                            )
                        }
                    }
                    BookKind.Chat -> chatKeys += k.key
                    BookKind.Pretranslations -> {
                        // Body is NOT fetched here: it is ~750 KB per book and only
                        // needed when the planner decides it actually changed.
                        pretranslationsKey = k.key
                        pretranslationsSize = k.size
                    }
                    BookKind.Sentences -> {
                        sentencesKey = k.key
                        sentencesSize = k.size
                    }
                    BookKind.Statistics -> {
                        statisticsKey = k.key
                        statisticsSize = k.size
                        statisticsLastModified = k.lastModified
                    }
                    BookKind.MangaStatistics -> {
                        mangaStatisticsKey = k.key
                        mangaStatisticsSize = k.size
                        mangaStatisticsLastModified = k.lastModified
                    }
                    BookKind.PayloadZip, BookKind.EpubZip -> Unit // body not fetched here
                }
            }
            data class ManifestCandidate(
                val remoteKey: RemoteKey,
                val keys: HttpSyncPayloadKeys,
                val expectedFormat: HttpSyncContentType?,
            )
            var selectionError: String? = null
            val candidate = when (metadata?.contentType) {
                HttpSyncContentType.Epub -> when {
                    epubManifestKey != null -> ManifestCandidate(
                        epubManifestKey!!,
                        HttpSyncPayloadKeys.forFormat(HttpSyncContentType.Epub, syncId),
                        HttpSyncContentType.Epub,
                    )
                    legacyManifestKey != null -> ManifestCandidate(
                        legacyManifestKey!!,
                        HttpSyncPayloadKeys.legacy(syncId),
                        HttpSyncContentType.Epub,
                    )
                    else -> null
                }
                HttpSyncContentType.Mokuro -> when {
                    legacyManifestKey != null -> ManifestCandidate(
                        legacyManifestKey!!,
                        HttpSyncPayloadKeys.legacy(syncId),
                        HttpSyncContentType.Mokuro,
                    )
                    else -> null
                }
                null -> when {
                    epubManifestKey != null && legacyManifestKey != null -> {
                        selectionError = "both EPUB and payload manifest families exist without metadata"
                        null
                    }
                    epubManifestKey != null -> ManifestCandidate(
                        epubManifestKey!!,
                        HttpSyncPayloadKeys.forFormat(HttpSyncContentType.Epub, syncId),
                        HttpSyncContentType.Epub,
                    )
                    legacyManifestKey != null -> ManifestCandidate(
                        legacyManifestKey!!,
                        HttpSyncPayloadKeys.legacy(syncId),
                        null,
                    )
                    else -> null
                }
            }
            if (selectionError != null) {
                errors += V3Error(syncId, "SelectRemoteManifest", selectionError!!)
            }
            var manifest: HttpSyncPayloadManifest? = null
            var manifestLastModified: String? = null
            var payloadKeys: HttpSyncPayloadKeys? = null
            var manifestMalformed = selectionError != null
            if (candidate != null) {
                try {
                    val fetched = transport.get(candidate.remoteKey.key)
                    if (fetched != null) {
                        val decoded = json.decodeFromString(
                            HttpSyncPayloadManifest.serializer(),
                            fetched.body.toString(Charsets.UTF_8),
                        )
                        if (candidate.expectedFormat != null && decoded.format != candidate.expectedFormat) {
                            throw IllegalArgumentException(
                                "${candidate.remoteKey.kind} declares ${decoded.format}, expected ${candidate.expectedFormat}",
                            )
                        }
                        manifest = decoded
                        manifestLastModified = fetched.lastModified
                        payloadKeys = candidate.keys
                    }
                } catch (e: Exception) {
                    manifestMalformed = true
                    errors += V3Error(
                        syncId = syncId,
                        action = "ReadRemoteManifest",
                        message = "manifest ${syncId}: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
            books[syncId] = V3RemoteBook(
                syncId = syncId,
                metadata = metadata,
                metadataLastModified = metadataLastModified,
                manifest = manifest,
                manifestLastModified = manifestLastModified,
                payloadKeys = payloadKeys,
                bookmark = bookmark,
                bookmarkLastModified = bookmarkLastModified,
                chatKeys = chatKeys,
                pretranslationsKey = pretranslationsKey,
                pretranslationsSize = pretranslationsSize,
                sentencesKey = sentencesKey,
                sentencesSize = sentencesSize,
                statisticsKey = statisticsKey,
                statisticsSize = statisticsSize,
                statisticsLastModified = statisticsLastModified,
                mangaStatisticsKey = mangaStatisticsKey,
                mangaStatisticsSize = mangaStatisticsSize,
                mangaStatisticsLastModified = mangaStatisticsLastModified,
                metadataMalformed = metadataMalformed,
                manifestMalformed = manifestMalformed,
                bookmarkMalformed = bookmarkMalformed,
            )
        }

        // ── Pass C: app/ai_chat_settings (single tiny blob).
        onProgress(V3Progress(V3Phase.ListingRemote, "Reading remote app settings"))
        var aiSettings: HttpSyncAiChatSettingsBlob? = null
        var aiSettingsLastModified: String? = null
        var aiSettingsMalformed = false
        try {
            val fetched = transport.get(AI_CHAT_SETTINGS_KEY)
            if (fetched != null) {
                aiSettings = json.decodeFromString(
                    HttpSyncAiChatSettingsBlob.serializer(),
                    fetched.body.toString(Charsets.UTF_8),
                )
                aiSettingsLastModified = fetched.lastModified
            }
        } catch (e: Exception) {
            // Bug 5: same as the per-book fields — surface AND mark as malformed so the
            // planner doesn't push our local AI settings over the corrupt remote.
            aiSettingsMalformed = true
            errors += V3Error(
                syncId = null,
                action = "ReadRemoteAiSettings",
                message = "ai_chat_settings: ${e.message ?: e.javaClass.simpleName}",
            )
        }

        return V3RemoteSnapshotResult(
            snapshot = V3RemoteSnapshot(
                books = books,
                aiSettings = aiSettings,
                aiSettingsLastModified = aiSettingsLastModified,
                aiSettingsMalformed = aiSettingsMalformed,
            ),
            errors = errors,
        )
    }

    private enum class BookKind {
        Metadata,
        LegacyManifest,
        EpubManifest,
        Bookmark,
        Chat,
        PayloadZip,
        EpubZip,
        Pretranslations,
        Sentences,
        Statistics,
        MangaStatistics,
    }

    /**
     * Parses a `books/{syncId}/{...}` key into `(syncId, kind)`. Returns null for keys
     * we don't recognize — forward-compat with future kinds the server may add.
     */
    private fun parseBookKey(key: String): Pair<String, BookKind>? {
        if (!key.startsWith(ALL_BOOKS_PREFIX)) return null
        val rest = key.removePrefix(ALL_BOOKS_PREFIX)
        val firstSlash = rest.indexOf('/')
        if (firstSlash <= 0) return null
        val syncId = rest.substring(0, firstSlash)
        val suffix = rest.substring(firstSlash + 1)
        val kind = when {
            suffix == "bookmark" -> BookKind.Bookmark
            suffix == "pretranslations" -> BookKind.Pretranslations
            suffix == "metadata" -> BookKind.Metadata
            suffix == "payload.manifest" -> BookKind.LegacyManifest
            suffix == "payload.zip" -> BookKind.PayloadZip
            suffix == "epub.manifest" -> BookKind.EpubManifest
            suffix == "epub.zip" -> BookKind.EpubZip
            suffix == "sentences" -> BookKind.Sentences
            suffix == "statistics" -> BookKind.Statistics
            suffix == "manga_statistics" -> BookKind.MangaStatistics
            suffix.startsWith("chat/") && suffix.length > 5 -> BookKind.Chat
            else -> return null
        }
        return syncId to kind
    }
}

/** Pair of snapshot + per-key errors. See [V3RemoteState.read]. */
data class V3RemoteSnapshotResult(
    val snapshot: V3RemoteSnapshot,
    val errors: List<V3Error>,
)
