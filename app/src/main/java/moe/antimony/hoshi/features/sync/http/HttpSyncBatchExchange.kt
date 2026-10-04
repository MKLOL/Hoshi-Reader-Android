package moe.antimony.hoshi.features.sync.http

import moe.antimony.hoshi.storage.writeSidecarAtomically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.bookContentType
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** The two opaque maps live in the existing v1 KV store; no backend release is required. */
internal const val SYNC_MAP_PREFIX = "sync/maps/"
internal const val BOOKS_MAP_KEY = "sync/maps/books.json"
internal const val BOOKMARKS_MAP_KEY = "sync/maps/bookmarks.json"
internal const val BOOKMARKS_MAP_PREFIX = "sync/maps/bookmarks/"

@Serializable
private data class CachedMapState(
    val booksEtag: String? = null,
    val books: Map<String, String> = emptyMap(),
    val bookmarkEtags: Map<String, String> = emptyMap(),
    val bookmarkShards: Map<String, Map<String, HttpSyncBookmarkMapEntry>> = emptyMap(),
    val ownedBookmarks: Map<String, HttpSyncBookmarkMapEntry> = emptyMap(),
    val legacyEtags: Map<String, String> = emptyMap(),
    val initialized: Boolean = false,
)

@Serializable
private data class PendingBookmarkWrite(
    val key: String,
    val mutationId: String,
)

internal data class HttpSyncMapChanges(
    val needsBootstrap: Boolean = false,
    val booksChanged: Boolean = false,
    /** Keys only the full reconcile handles changed (chat, metadata, payloads, settings). */
    val otherChanged: Boolean = false,
    val uploadedBookmarks: Int = 0,
    val downloadedBookmarks: Int = 0,
    val observedLegacyEtags: Map<String, String> = emptyMap(),
    /** Statistics have work for [HttpSyncBatchState.runStatistics]; they never need the full reconcile. */
    val statisticsNeeded: Boolean = false,
    /** The metadata listing this pass judged by, for the statistics lane. */
    val listing: List<HttpSyncKvKeyMeta> = emptyList(),
    /** Bookmark keys whose queued local positions this pass published. */
    val uploadedBookmarkKeys: Set<String> = emptySet(),
)

/** Per-book reading statistics keys; the statistics lane owns them (see [HttpSyncStatisticsLane]). */
internal fun isStatisticsKey(key: String): Boolean = key.startsWith("books/") &&
    (key.endsWith("/statistics") || key.endsWith("/manga_statistics"))

/** Per-book bookmark keys of released clients; the bookmark maps carry positions now. */
private fun isLegacyBookmarkKey(key: String): Boolean = key.startsWith("books/") && key.endsWith("/bookmark")

/**
 * Keys whose change calls for the full reconcile. Map keys, statistics and per-book bookmark
 * keys each have their own exchange; letting their writes start full reconciles made two
 * devices trigger each other's full passes for as long as someone was reading.
 */
internal fun isReconcileKey(key: String): Boolean =
    !key.startsWith(SYNC_MAP_PREFIX) && !isStatisticsKey(key) && !isLegacyBookmarkKey(key)

/** Tracks exact legacy-key mutations authored by one full reconcile. */
private class HttpSyncWriteTrackingTransport(
    private val delegate: HttpSyncKvTransport,
) : HttpSyncKvTransport {
    override val cacheIdentity: String? get() = delegate.cacheIdentity
    private data class Mutation(val key: String, val etag: String?)
    private val mutations = mutableListOf<Mutation>()

    override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse =
        delegate.put(key, contentType, body).also { response -> record(key, response.etag) }

    override suspend fun putFile(
        key: String,
        contentType: String,
        file: File,
        onByteProgress: ((Long, Long) -> Unit)?,
    ): HttpSyncKvWriteResponse = delegate.putFile(key, contentType, file, onByteProgress).also { response ->
        record(key, response.etag)
    }

    override suspend fun get(key: String): HttpSyncKvFetched? = delegate.get(key)

    override suspend fun getBounded(key: String, maxBytes: Int): HttpSyncKvFetched? =
        delegate.getBounded(key, maxBytes)

    override suspend fun downloadToFile(
        key: String,
        targetFile: File,
        onByteProgress: ((Long, Long) -> Unit)?,
    ): HttpSyncKvFileFetched? = delegate.downloadToFile(key, targetFile, onByteProgress)

    override suspend fun list(
        prefix: String?,
        since: String?,
        cursor: String?,
        limit: Int?,
    ): HttpSyncKvList = delegate.list(prefix, since, cursor, limit)

    override suspend fun delete(key: String) {
        delegate.delete(key)
        record(key, null)
    }

    fun expectedLegacyEtags(after: Map<String, String>): Map<String, String> = synchronized(mutations) {
        after.toMutableMap().apply {
            for (mutation in mutations) {
                if (!isReconcileKey(mutation.key)) continue
                if (mutation.etag == null) remove(mutation.key) else this[mutation.key] = mutation.etag
            }
        }
    }

    private fun record(key: String, etag: String?) = synchronized(mutations) {
        mutations += Mutation(key, etag)
    }
}

/**
 * Durable logical two-map state and bookmark outbox. Bookmark storage is physically sharded by
 * installation because the generic KV API deliberately has no compare-and-swap operation.
 *
 * No-change is one metadata-list request. A normal page-turn pass adds one PUT containing every
 * dirty book. A changed remote shard adds one GET before the merge. Concurrent device PUTs target
 * different keys, so neither can erase the other's newer position.
 */
class HttpSyncBatchState(
    private val bookRepository: BookRepository,
    private val bookLocks: HttpSyncBookLocks = HttpSyncBookLocks(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val installationId: String? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** The streak goal and day reset the statistics lane keeps equal on every device. */
    statisticsPreferences: StatisticsPreferencesStore? = null,
    /** This device's usage log, exchanged with every other device's alongside the statistics. */
    usageLog: moe.antimony.hoshi.features.usage.UsageLog? = null,
) {
    internal val booksDirectory: java.io.File get() = bookRepository.booksDirectory

    private val revisionStore = HttpSyncRevisionStore(json)
    private val syncMutex = Mutex()
    private val metadataIndex = HttpSyncMetadataIndex(bookRepository.booksDirectory)
    private val statisticsLane = HttpSyncStatisticsLane(
        bookRepository, bookLocks, statisticsPreferences, usageLane = usageLog?.let { HttpSyncUsageLane(it) },
    )
    private val statisticsMutex = Mutex()
    private val booksRoot: File get() = bookRepository.booksDirectory

    /** How current other devices' reading history is here (see [HttpSyncStatisticsLane]). */
    val statisticsStatus: StateFlow<StatisticsSyncStatus> get() = statisticsLane.status
    private val _remoteBookmarkUpdates = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val remoteBookmarkUpdates: SharedFlow<String> = _remoteBookmarkUpdates.asSharedFlow()

    init {
        // A killed process may leave the live folder parked in a hidden swap backup.
        HttpSyncPayloadCodec().recoverInterruptedReplacements(booksRoot)
    }

    suspend fun queueBookmark(bookRoot: File, title: String?, persistedSyncId: String? = null) = withContext(ioDispatcher) {
        val bookmark = bookRepository.loadBookmark(bookRoot) ?: return@withContext
        val metadata = bookRepository.loadMetadata(bookRoot)
        val syncId = persistedSyncId
            ?: metadata?.let(::syncIdForMetadata)
            ?: deriveSyncId(title, bookRoot.name)
            ?: return@withContext
        val key = bookmarkKey(syncId)
        val rev = revisionStore.bumpForLocalEdit(booksRoot, key)
        val body = json.encodeToString(
            HttpSyncBookmarkBlob.serializer(),
            bookmark.toBlob().copy(rev = rev),
        ).toByteArray(Charsets.UTF_8)
        synchronized(stateLock) {
            val previousPending = loadPendingLocked()
            val pending = previousPending.associateBy { it.key }.toMutableMap()
            pending[key] = PendingBookmarkWrite(
                key = key,
                mutationId = UUID.randomUUID().toString(),
            )
            savePendingLocked(pending.values.sortedBy { it.key }, previousPending)
            val state = loadStateLocked()
            saveStateLocked(
                state.copy(
                    ownedBookmarks = state.ownedBookmarks + (
                        syncId to HttpSyncBookmarkMapEntry(
                            etag = body.sha256Etag(),
                            lastModified = bookmark.toBlob().lastModified,
                            value = bookmark.toBlob().copy(rev = rev),
                        )
                    ),
                ),
                previous = state,
            )
        }
    }

    internal fun hasPending(): Boolean = synchronized(stateLock) { loadPendingLocked().isNotEmpty() }

    /** Poll and merge both maps without calling a custom backend endpoint. */
    internal suspend fun syncMaps(
        transport: HttpSyncKvTransport,
        onProgress: suspend (HttpSyncProgress) -> Unit = {},
    ): HttpSyncMapChanges = withContext(ioDispatcher) {
        onProgress(HttpSyncProgress(messageResource = R.string.http_sync_checking_changes))
        syncMutex.withLock {
            val before = synchronized(stateLock) { loadStateLocked() }
            // The cached metadata index fetches only changed keys on routine checks. It also
            // notices writes from per-book clients and changes to chats/settings/translations
            // without fetching unchanged bodies.
            val listing = listAllMetadata(transport)
            val booksMeta = listing.keys.firstOrNull { it.key == BOOKS_MAP_KEY }
            val bookmarkMetas = listing.keys.filter {
                it.key == BOOKMARKS_MAP_KEY || it.key.startsWith(BOOKMARKS_MAP_PREFIX)
            }
            val legacyKeyEtags = listing.keys
                .filter { isReconcileKey(it.key) }
                .associate { it.key to it.etag }.toMutableMap()
            // Reading statistics have their own lane, decided per key against this listing, so a
            // statistics write never waits for (or starts) a full reconcile.
            suspend fun statisticsNeeded(entries: List<BookEntry>?): Boolean = try {
                statisticsLane.needsRun(transport, listing.keys, entries).also { if (!it) statisticsLane.markChecked() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                statisticsLane.markFailed(error)
                true
            }

            // Old accounts have per-book keys but no maps. Never publish local state over them:
            // do the legacy bidirectional reconciliation once and then publishMaps().
            if (booksMeta == null || bookmarkMetas.isEmpty()) {
                return@withLock HttpSyncMapChanges(
                    needsBootstrap = true,
                    observedLegacyEtags = legacyKeyEtags,
                    statisticsNeeded = statisticsNeeded(entries = null),
                    listing = listing.keys,
                )
            }

            val remoteBooks = if (!before.initialized || before.booksEtag != booksMeta.etag) {
                decodeBooksMap(transport.get(BOOKS_MAP_KEY))
            } else {
                before.books
            }
            val shardBodies = linkedMapOf<String, Map<String, HttpSyncBookmarkMapEntry>>()
            val ownShardKey = bookmarkMapKey(loadDeviceIdLocked())
            for (meta in bookmarkMetas) {
                shardBodies[meta.key] = if (!before.initialized || before.bookmarkEtags[meta.key] != meta.etag) {
                    // A failed request aborts this poll, so the shard is fetched again. Only
                    // another device's unreadable or just-deleted shard is passed over (until it
                    // changes), so it cannot stop every other device's positions from applying.
                    val fetched = transport.get(meta.key)
                    try {
                        // This installation's own shard deleted on the server: its owned
                        // positions are merged below and published again.
                        if (fetched == null && meta.key == ownShardKey) emptyMap() else decodeBookmarksMap(fetched)
                    } catch (error: HttpSyncException) {
                        if (meta.key == ownShardKey) throw error
                        before.bookmarkShards[meta.key].orEmpty()
                    }
                } else {
                    before.bookmarkShards[meta.key].orEmpty()
                }
            }
            val remoteBookmarks = mergeShards(shardBodies.values)
            val legacyFingerprint = legacyKeyEtags.toMap()
            // Older builds acknowledged statistics and per-book bookmark keys too; they are not
            // part of the comparison any more.
            val legacyChangedBeforeWrites = before.initialized &&
                before.legacyEtags.filterKeys(::isReconcileKey) != legacyFingerprint

            val pendingSnapshot = synchronized(stateLock) { loadPendingLocked() }
            onProgress(HttpSyncProgress(messageResource = R.string.http_sync_reading_local))
            val localEntries = bookRepository.loadBookEntries()
            val localBookmarks = readLocalBookmarks(localEntries)
            onProgress(HttpSyncProgress(messageResource = R.string.http_sync_updating_positions))
            val downloaded = applyRemoteWinners(remoteBookmarks, localBookmarks, localEntries)

            val deviceKey = bookmarkMapKey(loadDeviceIdLocked())
            val ownRemote = shardBodies[deviceKey].orEmpty()
            var owned = mergeBookmarkMaps(ownRemote, before.ownedBookmarks).toMutableMap()
            // Recover edits made before the map hooks existed (or just before a crash). Equal
            // remote values are not copied into this device's shard; only genuinely newer local
            // events become this device's responsibility.
            for ((syncId, local) in localBookmarks) {
                val remote = remoteBookmarks[syncId]
                if ((remote == null || compareBookmarkEntries(local, remote) > 0) &&
                    (owned[syncId] == null || compareBookmarkEntries(local, owned.getValue(syncId)) > 0)
                ) owned[syncId] = local
            }
            var uploadedKeys = emptySet<String>()
            var ownEtag = bookmarkMetas.firstOrNull { it.key == deviceKey }?.etag
            if (owned != ownRemote) {
                val body = json.encodeToString(
                    bookmarkMapSerializer,
                    owned.toSortedMap(),
                ).toByteArray(Charsets.UTF_8)
                ownEtag = transport.put(deviceKey, JSON_CONTENT_TYPE, body).etag
                uploadedKeys = pendingSnapshot.mapTo(mutableSetOf()) { it.key }
                shardBodies[deviceKey] = owned
            }
            // Remove only mutations captured by this pass. A page turn queued while the PUT was
            // in flight remains durable for the next five-second pass.
            val completed = pendingSnapshot.associate { it.key to it.mutationId }
            synchronized(stateLock) {
                val pending = loadPendingLocked()
                savePendingLocked(pending.filterNot { completed[it.key] == it.mutationId }, pending)
                val latest = loadStateLocked()
                saveStateLocked(
                    CachedMapState(
                        booksEtag = booksMeta.etag,
                        books = remoteBooks,
                        bookmarkEtags = bookmarkMetas.associate { it.key to it.etag }.toMutableMap().apply {
                            if (ownEtag != null) this[deviceKey] = ownEtag
                        },
                        bookmarkShards = shardBodies,
                        ownedBookmarks = mergeBookmarkMaps(owned, latest.ownedBookmarks),
                        // Do not acknowledge a changed legacy snapshot until the full reconcile
                        // succeeds; a crash between preflight and reconcile must retry it.
                        legacyEtags = if (legacyChangedBeforeWrites) {
                            before.legacyEtags
                        } else {
                            legacyKeyEtags
                        },
                        initialized = before.initialized,
                    ),
                    previous = latest,
                )
            }

            val localBookIds = localEntries.mapNotNull { syncIdForMetadata(it.metadata) }.toSet()
            val cachedLocalHashes = readCachedLocalBookHashes(localEntries)
            val localBookMismatch = localBookIds != remoteBooks.keys ||
                (localBookIds - cachedLocalHashes.keys).any { !HttpSyncActiveBooks.contains(it) } ||
                hasDirtyLocalPayload(localEntries) ||
                cachedLocalHashes.any { (id, sha) ->
                    !HttpSyncActiveBooks.contains(id) && remoteBooks[id] != sha
                }
            HttpSyncMapChanges(
                needsBootstrap = !before.initialized,
                booksChanged = localBookMismatch,
                otherChanged = legacyChangedBeforeWrites,
                uploadedBookmarks = uploadedKeys.size,
                downloadedBookmarks = downloaded,
                observedLegacyEtags = legacyKeyEtags,
                statisticsNeeded = statisticsNeeded(localEntries),
                listing = listing.keys,
                uploadedBookmarkKeys = uploadedKeys,
            )
        }
    }

    /**
     * One pass of the statistics lane on [listing] (normally the listing [syncMaps] just took).
     * Runs outside the map mutex, so a long first exchange never holds up page-turn uploads or a
     * reader opening; passes queue behind each other. [flush] sends local days at once.
     */
    internal suspend fun runStatistics(
        transport: HttpSyncKvTransport,
        listing: List<HttpSyncKvKeyMeta>,
        flush: Boolean = false,
        retryFailed: Boolean = false,
        onProgress: suspend (HttpSyncProgress) -> Unit = {},
    ): StatisticsLaneResult = withContext(ioDispatcher) {
        statisticsMutex.withLock {
            try {
                statisticsLane.run(transport, listing, flush, retryFailed, onProgress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                statisticsLane.markFailed(error)
                StatisticsLaneResult(errors = listOf("statistics: ${error.message ?: error.javaClass.simpleName}"))
            }
        }
    }

    /**
     * Lists the server and runs the statistics lane: leaving a reader, opening Statistics, the
     * background flush ([flush]), or a poll whose bookmark maps failed (only when it has work).
     */
    internal suspend fun syncStatisticsNow(
        transport: HttpSyncKvTransport,
        flush: Boolean = true,
        retryFailed: Boolean = false,
        onlyIfNeeded: Boolean = false,
    ): StatisticsLaneResult {
        val listing = try {
            withContext(ioDispatcher) { syncMutex.withLock { listAllMetadata(transport).keys } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            statisticsLane.markFailed(error)
            throw error
        }
        if (onlyIfNeeded && !withContext(ioDispatcher) { statisticsLane.needsRun(transport, listing) }) {
            statisticsLane.markChecked()
            return StatisticsLaneResult.NONE
        }
        return runStatistics(transport, listing, flush, retryFailed)
    }

    /** Whether page turns are queued for this installation's bookmark shard. */
    internal fun hasPendingBookmarks(): Boolean = hasPending()

    /** Every installed book's reading position, to count the books a sync actually moved. */
    internal suspend fun bookmarkPositions(): Map<String, Triple<Int, Double, Int>> = withContext(ioDispatcher) {
        buildMap {
            for (entry in bookRepository.loadBookEntries()) {
                val syncId = syncIdForMetadata(entry.metadata) ?: continue
                val bookmark = bookRepository.loadBookmark(entry.root) ?: continue
                put(syncId, Triple(bookmark.chapterIndex, bookmark.progress, bookmark.characterCount))
            }
        }
    }

    /** Publish maps only after the one-time/full book reconcile has succeeded. */
    internal suspend fun publishMaps(
        transport: HttpSyncKvTransport,
        acknowledgedLegacyEtags: Map<String, String> = emptyMap(),
    ): HttpSyncMapChanges = withContext(ioDispatcher) {
        syncMutex.withLock {
            val before = synchronized(stateLock) { loadStateLocked() }
            val pendingSnapshot = synchronized(stateLock) { loadPendingLocked() }
            val localEntries = bookRepository.loadBookEntries()
            // The preflight fetched every shard before the reconcile imported remote-only books,
            // so it could not place their positions yet. Apply the newest known position to the
            // books that exist now; otherwise a fresh install's first "Sync now" leaves each
            // imported book at the older legacy-key position until the next map pass.
            val applied = applyRemoteWinners(
                mergeShards(before.bookmarkShards.values),
                readLocalBookmarks(localEntries),
                localEntries,
            )
            val localBookmarks = readLocalBookmarks(localEntries)
            val owned = mergeBookmarkMaps(before.ownedBookmarks, localBookmarks)
            // A successful full reconcile has materialized a SHA sidecar for every live payload.
            // Use exactly that set so a deleted book does not remain in the server map forever.
            val books = readCachedLocalBookHashes(localEntries).toMutableMap().apply {
                // A concurrently changed open payload was deliberately deferred. Preserve the
                // server's new hash instead of publishing this reader's old local baseline.
                for ((id, remoteSha) in before.books) {
                    if (HttpSyncActiveBooks.contains(id) && id in this) this[id] = remoteSha
                }
            }

            val booksResponse = transport.put(
                BOOKS_MAP_KEY,
                JSON_CONTENT_TYPE,
                json.encodeToString(bookMapSerializer, books.toSortedMap()).toByteArray(Charsets.UTF_8),
            )
            val deviceKey = bookmarkMapKey(synchronized(stateLock) { loadDeviceIdLocked() })
            val bookmarksResponse = transport.put(
                deviceKey,
                JSON_CONTENT_TYPE,
                json.encodeToString(bookmarkMapSerializer, owned.toSortedMap())
                    .toByteArray(Charsets.UTF_8),
            )
            synchronized(stateLock) {
                val latest = loadStateLocked()
                saveStateLocked(
                    CachedMapState(
                        booksEtag = booksResponse.etag,
                        books = books,
                        bookmarkEtags = before.bookmarkEtags + (deviceKey to bookmarksResponse.etag),
                        bookmarkShards = before.bookmarkShards + (deviceKey to owned),
                        ownedBookmarks = mergeBookmarkMaps(owned, latest.ownedBookmarks),
                        // A write arriving after preflight was not reconciled. Acknowledge only
                        // that exact snapshot so the next five-second list notices the new ETag.
                        legacyEtags = acknowledgedLegacyEtags,
                        initialized = true,
                    ),
                    previous = latest,
                )
                // Preserve a page turn queued while either PUT was in flight.
                val completed = pendingSnapshot.associate { it.key to it.mutationId }
                val pending = loadPendingLocked()
                savePendingLocked(pending.filterNot { completed[it.key] == it.mutationId }, pending)
            }
            HttpSyncMapChanges(uploadedBookmarks = localBookmarks.size, downloadedBookmarks = applied)
        }
    }

    /** Snapshot after reconcile; a changed ETag requires one stabilizing pass before ack. */
    internal suspend fun observeLegacyEtags(transport: HttpSyncKvTransport): Map<String, String> = withContext(ioDispatcher) {
        syncMutex.withLock {
            listAllMetadata(transport).keys
                .filter { isReconcileKey(it.key) }
                .associate { it.key to it.etag }
        }
    }

    private suspend fun applyRemoteWinners(
        remote: Map<String, HttpSyncBookmarkMapEntry>,
        local: Map<String, HttpSyncBookmarkMapEntry>,
        localEntries: List<BookEntry>,
    ): Int {
        val roots = localEntries.mapNotNull { entry ->
            syncIdForMetadata(entry.metadata)?.let { it to entry.root }
        }.toMap()
        var applied = 0
        for ((syncId, remoteEntry) in remote) {
            val localEntry = local[syncId]
            if (localEntry != null && compareBookmarkEntries(remoteEntry, localEntry) <= 0) continue
            val blob = remoteEntry.value ?: continue
            val root = roots[syncId] ?: continue
            // A shard entry with an unparseable stamp is skipped, as on iOS; it must not abort
            // the whole pass (and it cannot be "newer" than anything real).
            val appliedStamp = runCatching { rfc3339ToAppleSecondsStrict(blob.lastModified) }.getOrNull() ?: continue
            bookLocks.withBookLock(root) {
                // Re-read under the lock: a page turn may have landed after readLocalBookmarks().
                val current = bookRepository.loadBookmark(root)
                if (current != null) {
                    val currentBlob = current.toBlob().copy(
                        rev = revisionStore.current(booksRoot, bookmarkKey(syncId)).localRev,
                    )
                    val currentBody = json.encodeToString(
                        HttpSyncBookmarkBlob.serializer(),
                        currentBlob,
                    ).toByteArray(Charsets.UTF_8)
                    val currentEntry = HttpSyncBookmarkMapEntry(
                        etag = currentBody.sha256Etag(),
                        lastModified = currentBlob.lastModified,
                        value = currentBlob,
                    )
                    if (compareBookmarkEntries(remoteEntry, currentEntry) <= 0) return@withBookLock
                }
                bookRepository.saveBookmark(
                    root,
                    Bookmark(
                        chapterIndex = blob.chapterIndex,
                        progress = blob.progress,
                        characterCount = blob.characterCount,
                        lastModified = appliedStamp,
                    ),
                )
                revisionStore.noteRemote(booksRoot, bookmarkKey(syncId), blob.rev, appliedLocally = true)
                // A newer stamp for the very same place only converges the stamps: the reader
                // stays where it is and nothing was "downloaded".
                val moved = current == null || current.chapterIndex != blob.chapterIndex ||
                    current.progress != blob.progress || current.characterCount != blob.characterCount
                if (moved) {
                    _remoteBookmarkUpdates.tryEmit(syncId)
                    applied += 1
                }
            }
        }
        return applied
    }

    private suspend fun readLocalBookmarks(
        entries: List<BookEntry>,
    ): Map<String, HttpSyncBookmarkMapEntry> {
        val result = linkedMapOf<String, HttpSyncBookmarkMapEntry>()
        for (entry in entries) {
            val syncId = syncIdForMetadata(entry.metadata) ?: continue
            val bookmark = bookRepository.loadBookmark(entry.root) ?: continue
            val key = bookmarkKey(syncId)
            val rev = revisionStore.current(booksRoot, key).localRev
            val blob = bookmark.toBlob().copy(rev = rev)
            val body = json.encodeToString(HttpSyncBookmarkBlob.serializer(), blob)
                .toByteArray(Charsets.UTF_8)
            result[syncId] = HttpSyncBookmarkMapEntry(
                etag = body.sha256Etag(),
                lastModified = blob.lastModified,
                value = blob,
            )
        }
        return result
    }

    private fun readCachedLocalBookHashes(entries: List<BookEntry>): Map<String, String> {
        val hashes = linkedMapOf<String, String>()
        for (entry in entries) {
            val syncId = syncIdForMetadata(entry.metadata) ?: continue
            val raw = runCatching {
                entry.root.resolve(PAYLOAD_SHA_CACHE_FILENAME).readText().trim()
            }.getOrNull()
            if (raw != null && SHA256_ETAG.matches(raw)) hashes[syncId] = raw
        }
        return hashes
    }

    private fun hasDirtyLocalPayload(entries: List<BookEntry>): Boolean = entries.any { entry ->
        val syncId = syncIdForMetadata(entry.metadata)
        syncId != null && !HttpSyncActiveBooks.contains(syncId) &&
            HttpSyncPayloadCodec().hasPayloadContentDirty(entry.root)
    }

    private fun mergeBookmarkMaps(
        remote: Map<String, HttpSyncBookmarkMapEntry>,
        local: Map<String, HttpSyncBookmarkMapEntry>,
    ): Map<String, HttpSyncBookmarkMapEntry> = remote.toMutableMap().apply {
        for ((syncId, localEntry) in local) {
            val remoteEntry = this[syncId]
            if (remoteEntry == null || compareBookmarkEntries(localEntry, remoteEntry) > 0) {
                this[syncId] = localEntry
            }
        }
    }

    private fun mergeShards(
        shards: Collection<Map<String, HttpSyncBookmarkMapEntry>>,
    ): Map<String, HttpSyncBookmarkMapEntry> {
        var merged: Map<String, HttpSyncBookmarkMapEntry> = emptyMap()
        for (shard in shards) merged = mergeBookmarkMaps(merged, shard)
        return merged
    }

    private fun loadDeviceIdLocked(): String {
        installationId?.let { if (DEVICE_ID.matches(it)) return it.lowercase() }
        val file = booksRoot.resolve(DEVICE_ID_FILE_NAME)
        val existing = runCatching { file.readText().trim() }.getOrNull()
        if (existing != null && DEVICE_ID.matches(existing)) return existing
        val created = UUID.randomUUID().toString().lowercase()
        booksRoot.mkdirs()
        writeSidecarAtomically(file, created)
        return created
    }

    private suspend fun listAllMetadata(transport: HttpSyncKvTransport): HttpSyncKvList {
        return HttpSyncKvList(keys = metadataIndex.list(transport))
    }

    private fun decodeBooksMap(fetched: HttpSyncKvFetched?): Map<String, String> {
        val body = fetched?.body ?: throw HttpSyncException("The server's book map disappeared during sync.")
        return runCatching {
            json.decodeFromString(bookMapSerializer, body.toString(Charsets.UTF_8))
        }.getOrElse { throw HttpSyncException("The server's book map is malformed (${it.message}).") }
    }

    private fun decodeBookmarksMap(fetched: HttpSyncKvFetched?): Map<String, HttpSyncBookmarkMapEntry> {
        val body = fetched?.body ?: throw HttpSyncException("The server's bookmark map disappeared during sync.")
        return runCatching {
            json.decodeFromString(bookmarkMapSerializer, body.toString(Charsets.UTF_8))
                .filterValues { entry ->
                    entry.value == null || runCatching {
                        rfc3339ToAppleSecondsStrict(entry.lastModified)
                    }.isSuccess
                }
        }.getOrElse { throw HttpSyncException("The server's bookmark map is malformed (${it.message}).") }
    }

    private fun loadStateLocked(): CachedMapState {
        val file = booksRoot.resolve(CACHE_FILE_NAME)
        if (!file.isFile) return CachedMapState()
        return runCatching { json.decodeFromString(CachedMapState.serializer(), file.readText()) }
            .getOrDefault(CachedMapState())
    }

    private fun saveStateLocked(state: CachedMapState, previous: CachedMapState) {
        if (state == previous) return
        booksRoot.mkdirs()
        writeSidecarAtomically(
            booksRoot.resolve(CACHE_FILE_NAME),
            json.encodeToString(CachedMapState.serializer(), state),
        )
    }

    private fun loadPendingLocked(): List<PendingBookmarkWrite> {
        val file = booksRoot.resolve(PENDING_FILE_NAME)
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString(pendingSerializer, file.readText()) }
            .getOrDefault(emptyList())
    }

    private fun savePendingLocked(pending: List<PendingBookmarkWrite>, previous: List<PendingBookmarkWrite>) {
        if (pending == previous) return
        booksRoot.mkdirs()
        writeSidecarAtomically(
            booksRoot.resolve(PENDING_FILE_NAME),
            json.encodeToString(pendingSerializer, pending),
        )
    }

    private companion object {
        const val CACHE_FILE_NAME = ".http_sync_exchange_cache.json"
        const val PENDING_FILE_NAME = ".http_sync_pending_bookmarks.json"
        const val DEVICE_ID_FILE_NAME = ".http_sync_device_id"
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        val SHA256_ETAG = Regex("^sha256:[0-9a-f]{64}$")
        val DEVICE_ID = Regex("^[0-9a-f-]{36}$")
        val stateLock = Any()
        val pendingSerializer = ListSerializer(PendingBookmarkWrite.serializer())
        val bookMapSerializer = MapSerializer(String.serializer(), String.serializer())
        val bookmarkMapSerializer = MapSerializer(
            String.serializer(),
            HttpSyncBookmarkMapEntry.serializer(),
        )
    }
}

/** Makes the manual button use the same O(1) map preflight as the reader hot path. */
class HttpSyncFastSync(
    private val state: HttpSyncBatchState,
    private val fullCycleRunner: HttpSyncFullCycleRunner,
    /** Production builds the real client; integration tests point it at a local server. */
    private val transportFactory: (HttpSyncSettings) -> HttpSyncKvTransport = { settings ->
        HttpSyncKvClient(settings.baseUrl, settings.bearerToken)
    },
) {
    suspend fun syncNow(
        settings: HttpSyncSettings,
        onProgress: suspend (HttpSyncProgress) -> Unit = {},
        fullSync: suspend (HttpSyncSettings, HttpSyncKvTransport, suspend (HttpSyncProgress) -> Unit) -> HttpSyncResult,
    ): HttpSyncResult {
        val client = HttpSyncWriteTrackingTransport(transportFactory(settings))
        // "N bookmarks down" means N books whose reading position this sync moved, however many
        // passes touched them (the map preflight, the reconcile, the map publication).
        val positionsBefore = state.bookmarkPositions()
        val maps = state.syncMaps(client, onProgress)
        var uploadedKeys = maps.uploadedBookmarkKeys
        // A tap sends this device's reading at once and takes every other device's, before and
        // independently of any book transfer.
        onProgress(HttpSyncProgress(messageResource = R.string.http_sync_reading_history))
        val statistics = state.runStatistics(client, maps.listing, flush = true, retryFailed = true, onProgress = onProgress)
        val result = if (!maps.needsBootstrap && !maps.booksChanged && !maps.otherChanged) {
            // Nothing left to download, so any remaining partial archive has no retry coming.
            HttpSyncDownloadSpool.pruneAfterSync(state.booksDirectory)
            emptyResult()
        } else {
            onProgress(HttpSyncProgress(messageResource = R.string.http_sync_waiting_for_sync))
            // An existing flight may have snapshotted the server before this tap. Wait for it,
            // then validate fresh state ourselves; joining an old result is not proof of sync.
            fullCycleRunner.run(onProgress, requireOwnPass = true) { report ->
                val work = state.syncMaps(client, report)
                uploadedKeys = uploadedKeys + work.uploadedBookmarkKeys
                if (!work.needsBootstrap && !work.booksChanged && !work.otherChanged) {
                    return@run emptyResult()
                }
                val reconciled = fullSync(work.reconcileSettings(settings), client, report)
                if (reconciled.errors.isEmpty()) {
                    report(HttpSyncProgress(messageResource = R.string.http_sync_finishing_maps))
                    val after = state.observeLegacyEtags(client)
                    val expected = client.expectedLegacyEtags(work.observedLegacyEtags)
                    if (after == expected) state.publishMaps(client, expected)
                }
                reconciled
            }
        }
        val positionsAfter = state.bookmarkPositions()
        return result.copy(
            uploadedBookmarks = uploadedKeys.size,
            downloadedBookmarks = positionsAfter.count { (syncId, position) -> positionsBefore[syncId] != position },
            uploadedStatistics = result.uploadedStatistics + statistics.uploadedBooks,
            downloadedStatistics = result.downloadedStatistics + statistics.downloadedBooks,
            errors = statistics.errors + result.errors,
        )
    }

    private fun emptyResult() = HttpSyncResult(
        uploadedBookmarks = 0,
        uploadedChatEntries = 0,
        uploadedMetadata = 0,
        downloadedBookmarks = 0,
        downloadedChatEntries = 0,
        remoteOnlyBooks = 0,
        errors = emptyList(),
    )
}

/**
 * The settings a full reconcile runs with: a deferred payload can predate the old engine's
 * incremental cursor, and only an account's bootstrap still exchanges per-book bookmark keys.
 */
internal fun HttpSyncMapChanges.reconcileSettings(settings: HttpSyncSettings): HttpSyncSettings = settings.copy(
    lastSyncedAt = if (needsBootstrap || booksChanged) null else settings.lastSyncedAt,
    exchangeLegacyBookmarks = needsBootstrap,
)

/** Polls every five seconds; the durable outbox coalesces all intervening page turns. */
class HttpSyncBookmarkScheduler(
    private val state: HttpSyncBatchState,
    private val currentSettings: suspend () -> HttpSyncSettings?,
    private val syncBooksNow: suspend (HttpSyncSettings, HttpSyncKvTransport, suspend (HttpSyncProgress) -> Unit) -> HttpSyncResult,
    private val fullCycleRunner: HttpSyncFullCycleRunner,
    private val scope: CoroutineScope,
    private val transportFactory: (HttpSyncSettings) -> HttpSyncKvTransport = { settings ->
        HttpSyncKvClient(settings.baseUrl, settings.bearerToken)
    },
    initiallyForeground: Boolean = true,
) {
    private val jobLock = Any()
    private var pollingJob: Job? = null
    private var mapFlight: Deferred<Unit>? = null
    private var backgroundFullJob: Job? = null
    private var statisticsFlight: Job? = null
    private var fullRetryDeferredForReader = false
    private var fullRetryAfterMillis = 0L

    /**
     * Whether any of the app's screens is started. The five-second poll pauses in the background
     * (a playing podcast keeps the process alive, not the need to poll); returning to the app
     * polls at once, so another device's reading shows as soon as the app is looked at.
     */
    private val foreground = MutableStateFlow(initiallyForeground)

    fun setForeground(visible: Boolean) {
        if (foreground.value == visible) return
        foreground.value = visible
        // Arriving: pull now. Leaving: send what is queued (page turns, reading time) now.
        refreshNow()
        if (!visible) refreshStatistics()
    }

    suspend fun onBookmarkChanged(bookRoot: File, title: String?, persistedSyncId: String? = null) {
        state.queueBookmark(bookRoot, title, persistedSyncId)
        start()
        // The poll waits while the app is not visible; a position saved just after leaving it
        // (a debounced page turn, audio still playing) goes up now instead of at the next visit.
        if (!foreground.value) publishInBackground()
    }

    /**
     * Publishes queued positions while no screen is visible: a pass already running may have
     * taken its snapshot before this page turn, so a second pass follows when one is left. No
     * full reconcile starts from here; the process may be frozen at any moment.
     */
    private fun publishInBackground() {
        scope.launch {
            runMaps(allowFullReconcile = false)
            if (state.hasPendingBookmarks()) runMaps(allowFullReconcile = false)
        }
    }

    fun start() = synchronized(jobLock) {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            // Started by a background job, the process does not poll (nor start a full
            // reconcile it could be frozen in) until a screen is shown.
            foreground.first { it }
            runMaps()
            while (isActive) {
                delay(BOOKMARK_SYNC_INTERVAL_MS)
                foreground.first { it }
                runMaps()
            }
        }
    }

    fun refreshNow() {
        scope.launch { runMaps() }
    }

    /** A reader just released a book: retry its deferred static mutation immediately. */
    fun refreshAfterReaderClosed() {
        synchronized(jobLock) {
            if (fullRetryDeferredForReader) {
                fullRetryDeferredForReader = false
                fullRetryAfterMillis = 0L
            }
        }
        refreshNow()
    }

    /**
     * Refreshes the bookmark maps before a reader opens, waiting at most [timeoutMillis] for
     * the network. A slow or offline connection must not keep the book on its loading
     * spinner: once the timeout passes the reader opens on the local bookmark while the
     * refresh keeps running in the background, and [HttpSyncBatchState.remoteBookmarkUpdates]
     * reloads the reader if that late refresh pulls a newer remote bookmark for the open book.
     *
     * @return `true` when the refresh finished within the timeout.
     */
    suspend fun refreshBeforeOpen(timeoutMillis: Long = REFRESH_BEFORE_OPEN_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMillis) { runMaps() } != null

    fun flushNow() = refreshNow()

    private suspend fun runMaps(allowFullReconcile: Boolean = true) {
        val flight = synchronized(jobLock) {
            mapFlight?.takeIf { it.isActive } ?: scope.async {
                runMapsOnce(allowFullReconcile)
            }.also { created ->
                mapFlight = created
                created.invokeOnCompletion {
                    synchronized(jobLock) {
                        if (mapFlight === created) mapFlight = null
                    }
                }
            }
        }
        flight.await()
    }

    /**
     * Exchanges reading statistics with every other device. [flush] sends local days at once
     * (leaving a reader, opening Statistics); without it local days follow their batching
     * cadence (the reader's periodic push). Null when sync is not set up.
     */
    suspend fun syncStatisticsNow(flush: Boolean = true): StatisticsLaneResult? {
        val settings = currentSettings() ?: return null
        if (!settings.isConfigured) return null
        return state.syncStatisticsNow(transportFactory(settings), flush = flush)
    }

    /**
     * What the background flush worker does: publishes queued page turns and sends this
     * device's reading time. Null when sync is not set up.
     */
    suspend fun flushInBackground(): StatisticsLaneResult? {
        val settings = currentSettings() ?: return null
        if (!settings.isConfigured) return null
        val client = transportFactory(settings)
        // Positions failing to go up must not keep the reading time from going up.
        val bookmarks = runCatching { if (state.hasPendingBookmarks()) state.syncMaps(client) }.exceptionOrNull()
        if (bookmarks is CancellationException) throw bookmarks
        // This device's own writes are retried at once: the in-app flush may have just failed.
        val statistics = state.syncStatisticsNow(client, flush = true, retryFailed = true)
        return if (bookmarks != null || state.hasPendingBookmarks()) {
            statistics.copy(errors = statistics.errors + "bookmarks: ${bookmarks?.message ?: "still queued"}")
        } else {
            statistics
        }
    }

    /** [syncStatisticsNow] without waiting; a failure shows on the Statistics screen. */
    fun refreshStatistics() {
        scope.launch {
            try {
                syncStatisticsNow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Recorded in HttpSyncBatchState.statisticsStatus.
            }
        }
    }

    private suspend fun runMapsOnce(allowFullReconcile: Boolean = true) {
        val settings = currentSettings() ?: return
        if (!settings.isConfigured) return
        val client = HttpSyncWriteTrackingTransport(transportFactory(settings))
        val changes = try {
            state.syncMaps(client)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The bookmark maps could not be read. Reading statistics do not depend on them, so
            // they still get their own pass (it lists the server again and reports a failure).
            launchStatistics(client, listing = null)
            return
        }
        if (changes.statisticsNeeded) launchStatistics(client, changes.listing)
        if (!allowFullReconcile) return
        if (changes.needsBootstrap || changes.booksChanged || changes.otherChanged) {
            scheduleFullReconcile(changes, changes.reconcileSettings(settings), client)
        } else {
            synchronized(jobLock) {
                fullRetryDeferredForReader = false
                fullRetryAfterMillis = 0L
            }
        }
    }

    /** One statistics pass at a time, never queued behind book transfers or their retry pause. */
    private fun launchStatistics(client: HttpSyncKvTransport, listing: List<HttpSyncKvKeyMeta>?) = synchronized(jobLock) {
        if (statisticsFlight?.isActive == true) return
        statisticsFlight = scope.launch {
            try {
                if (listing == null) state.syncStatisticsNow(client, flush = false, onlyIfNeeded = true) else state.runStatistics(client, listing)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Recorded in HttpSyncBatchState.statisticsStatus.
            }
        }
    }

    private fun scheduleFullReconcile(
        changes: HttpSyncMapChanges,
        settings: HttpSyncSettings,
        client: HttpSyncWriteTrackingTransport,
    ) = synchronized(jobLock) {
        if (backgroundFullJob?.isActive == true) return
        if (System.currentTimeMillis() < fullRetryAfterMillis) return
        if (fullRetryDeferredForReader) fullRetryDeferredForReader = false

        backgroundFullJob = scope.launch {
            val outcome = runCatching {
                fullCycleRunner.run { report ->
                    val result = syncBooksNow(settings, client, report)
                    if (result.errors.isEmpty()) {
                        report(HttpSyncProgress(messageResource = R.string.http_sync_finishing_maps))
                        val after = state.observeLegacyEtags(client)
                        val expected = client.expectedLegacyEtags(changes.observedLegacyEtags)
                        if (after == expected) {
                            state.publishMaps(client, expected)
                        }
                    }
                    result
                }
            }.getOrNull()
            val succeeded = outcome?.errors?.isEmpty() == true
            val readerDeletionDeferred = outcome?.errors?.any {
                it.contains("deletion deferred while this book is open", ignoreCase = true)
            } == true && HttpSyncActiveBooks.hasAny()

            synchronized(jobLock) {
                fullRetryDeferredForReader = readerDeletionDeferred
                fullRetryAfterMillis = if (!succeeded) {
                    System.currentTimeMillis() + FULL_SYNC_RETRY_BACKOFF_MS
                } else 0L
            }
        }.also { created ->
            created.invokeOnCompletion {
                synchronized(jobLock) {
                    if (backgroundFullJob === created) backgroundFullJob = null
                }
            }
        }
    }

    companion object {
        const val BOOKMARK_SYNC_INTERVAL_MS: Long = 5_000L

        /** Longest a reader waits on the pre-open map refresh before opening on the local bookmark. */
        const val REFRESH_BEFORE_OPEN_TIMEOUT_MS: Long = 1_500L
        private const val FULL_SYNC_RETRY_BACKOFF_MS: Long = 60_000L
    }
}

private fun compareBookmarkEntries(
    left: HttpSyncBookmarkMapEntry,
    right: HttpSyncBookmarkMapEntry,
): Int {
    val timestamp = compareRfc3339(left.lastModified, right.lastModified)
    if (timestamp != 0) return timestamp
    val revision = (left.value?.rev ?: 0).compareTo(right.value?.rev ?: 0)
    if (revision != 0) return revision
    return left.etag.compareTo(right.etag)
}

private fun rfc3339ToAppleSecondsStrict(value: String): Double {
    val converted = rfc3339ToAppleSeconds(value)
    if (converted == 0.0 && value != "2001-01-01T00:00:00Z" && value != "2001-01-01T00:00:00.000Z") {
        throw HttpSyncException("Invalid bookmark timestamp: $value")
    }
    return converted
}

private fun ByteArray.sha256Etag(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    return "sha256:" + digest.joinToString("") { "%02x".format(it) }
}

private fun bookmarkMapKey(deviceId: String): String = "$BOOKMARKS_MAP_PREFIX$deviceId.json"
