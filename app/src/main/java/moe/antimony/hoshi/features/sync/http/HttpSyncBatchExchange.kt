package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import java.io.File
import java.security.MessageDigest
import java.util.Base64
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
    val bodyBase64: String,
)

internal data class HttpSyncMapChanges(
    val needsBootstrap: Boolean = false,
    val booksChanged: Boolean = false,
    val otherChanged: Boolean = false,
    val uploadedBookmarks: Int = 0,
    val downloadedBookmarks: Int = 0,
    val observedLegacyEtags: Map<String, String> = emptyMap(),
)

/** Tracks exact legacy-key mutations authored by one full reconcile. */
private class HttpSyncWriteTrackingTransport(
    private val delegate: HttpSyncKvTransport,
) : HttpSyncKvTransport {
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
                if (mutation.key.startsWith(SYNC_MAP_PREFIX)) continue
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
) {
    private val revisionStore = HttpSyncRevisionStore(json)
    private val syncMutex = Mutex()
    private val booksRoot: File get() = bookRepository.booksDirectory
    private val _remoteBookmarkUpdates = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val remoteBookmarkUpdates: SharedFlow<String> = _remoteBookmarkUpdates.asSharedFlow()

    init {
        // A killed process may leave the live folder parked in a hidden swap backup.
        HttpSyncPayloadCodec().recoverInterruptedReplacements(booksRoot)
    }

    suspend fun queueBookmark(bookRoot: File, title: String?, persistedSyncId: String? = null) {
        val bookmark = bookRepository.loadBookmark(bookRoot) ?: return
        val metadata = bookRepository.loadMetadata(bookRoot)
        val syncId = persistedSyncId
            ?: metadata?.let(::syncIdForMetadata)
            ?: deriveSyncId(title, bookRoot.name)
            ?: return
        val key = bookmarkKey(syncId)
        val rev = revisionStore.bumpForLocalEdit(booksRoot, key)
        val body = json.encodeToString(
            HttpSyncBookmarkBlob.serializer(),
            bookmark.toBlob().copy(rev = rev),
        ).toByteArray(Charsets.UTF_8)
        synchronized(stateLock) {
            val pending = loadPendingLocked().associateBy { it.key }.toMutableMap()
            pending[key] = PendingBookmarkWrite(
                key = key,
                mutationId = UUID.randomUUID().toString(),
                bodyBase64 = body.toBase64(),
            )
            savePendingLocked(pending.values.sortedBy { it.key })
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
            )
        }
    }

    internal fun hasPending(): Boolean = synchronized(stateLock) { loadPendingLocked().isNotEmpty() }

    /** Poll and merge both maps without calling a custom backend endpoint. */
    internal suspend fun syncMaps(transport: HttpSyncKvTransport): HttpSyncMapChanges =
        syncMutex.withLock {
            val before = synchronized(stateLock) { loadStateLocked() }
            // Listing all metadata is still one small request for normal libraries. Besides the
            // two fast maps it lets us notice writes from released per-book clients and changes
            // to chats/settings/translations without fetching unchanged bodies.
            val listing = listAllMetadata(transport)
            val booksMeta = listing.keys.firstOrNull { it.key == BOOKS_MAP_KEY }
            val bookmarkMetas = listing.keys.filter {
                it.key == BOOKMARKS_MAP_KEY || it.key.startsWith(BOOKMARKS_MAP_PREFIX)
            }
            val legacyKeyEtags = listing.keys
                .filterNot { it.key.startsWith(SYNC_MAP_PREFIX) }
                .associate { it.key to it.etag }.toMutableMap()

            // Old accounts have per-book keys but no maps. Never publish local state over them:
            // do the legacy bidirectional reconciliation once and then publishMaps().
            if (booksMeta == null || bookmarkMetas.isEmpty()) {
                return@withLock HttpSyncMapChanges(
                    needsBootstrap = true,
                    observedLegacyEtags = legacyKeyEtags,
                )
            }

            val remoteBooks = if (!before.initialized || before.booksEtag != booksMeta.etag) {
                decodeBooksMap(transport.get(BOOKS_MAP_KEY))
            } else {
                before.books
            }
            val shardBodies = linkedMapOf<String, Map<String, HttpSyncBookmarkMapEntry>>()
            for (meta in bookmarkMetas) {
                shardBodies[meta.key] = if (!before.initialized || before.bookmarkEtags[meta.key] != meta.etag) {
                    decodeBookmarksMap(transport.get(meta.key))
                } else {
                    before.bookmarkShards[meta.key].orEmpty()
                }
            }
            val remoteBookmarks = mergeShards(shardBodies.values)
            val legacyFingerprint = legacyKeyEtags.toMap()
            val legacyChangedBeforeWrites = before.initialized &&
                before.legacyEtags != legacyFingerprint

            val pendingSnapshot = synchronized(stateLock) { loadPendingLocked() }
            val localEntries = bookRepository.loadBookEntries()
            val localBookmarks = readLocalBookmarks(localEntries)
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
            var uploaded = 0
            var ownEtag = bookmarkMetas.firstOrNull { it.key == deviceKey }?.etag
            if (owned != ownRemote) {
                val body = json.encodeToString(
                    bookmarkMapSerializer,
                    owned.toSortedMap(),
                ).toByteArray(Charsets.UTF_8)
                ownEtag = transport.put(deviceKey, JSON_CONTENT_TYPE, body).etag
                uploaded = pendingSnapshot.size
                shardBodies[deviceKey] = owned
            }
            // Remove only mutations captured by this pass. A page turn queued while the PUT was
            // in flight remains durable for the next five-second pass.
            val completed = pendingSnapshot.associate { it.key to it.mutationId }
            synchronized(stateLock) {
                savePendingLocked(loadPendingLocked().filterNot { completed[it.key] == it.mutationId })
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
                uploadedBookmarks = uploaded,
                downloadedBookmarks = downloaded,
                observedLegacyEtags = legacyKeyEtags,
            )
        }

    /** Publish maps only after the one-time/full book reconcile has succeeded. */
    internal suspend fun publishMaps(
        transport: HttpSyncKvTransport,
        acknowledgedLegacyEtags: Map<String, String> = emptyMap(),
    ): HttpSyncMapChanges =
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
                saveStateLocked(
                    CachedMapState(
                        booksEtag = booksResponse.etag,
                        books = books,
                        bookmarkEtags = before.bookmarkEtags + (deviceKey to bookmarksResponse.etag),
                        bookmarkShards = before.bookmarkShards + (deviceKey to owned),
                        ownedBookmarks = owned,
                        // A write arriving after preflight was not reconciled. Acknowledge only
                        // that exact snapshot so the next five-second list notices the new ETag.
                        legacyEtags = acknowledgedLegacyEtags,
                        initialized = true,
                    ),
                )
                // Preserve a page turn queued while either PUT was in flight.
                val completed = pendingSnapshot.associate { it.key to it.mutationId }
                savePendingLocked(loadPendingLocked().filterNot { completed[it.key] == it.mutationId })
            }
            HttpSyncMapChanges(uploadedBookmarks = localBookmarks.size, downloadedBookmarks = applied)
        }

    /** Snapshot after reconcile; a changed ETag requires one stabilizing pass before ack. */
    internal suspend fun observeLegacyEtags(transport: HttpSyncKvTransport): Map<String, String> =
        syncMutex.withLock {
            listAllMetadata(transport).keys
                .filterNot { it.key.startsWith(SYNC_MAP_PREFIX) }
                .associate { it.key to it.etag }
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
                _remoteBookmarkUpdates.tryEmit(syncId)
                applied += 1
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
        val keys = mutableListOf<HttpSyncKvKeyMeta>()
        var cursor: String? = null
        do {
            val page = transport.list(cursor = cursor, limit = METADATA_PAGE_LIMIT)
            keys += page.keys
            if (!page.truncated) break
            cursor = page.nextCursor
                ?: throw HttpSyncException("The server truncated its metadata list without a cursor.")
        } while (true)
        return HttpSyncKvList(keys = keys)
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

    private fun saveStateLocked(state: CachedMapState) {
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

    private fun savePendingLocked(pending: List<PendingBookmarkWrite>) {
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
        const val METADATA_PAGE_LIMIT = 2_000
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

/** Coalesces rare full scans across the manual button and the five-second background lane. */
class HttpSyncFullCycleRunner(private val scope: CoroutineScope) {
    private val lock = Any()
    private var flight: Deferred<HttpSyncResult>? = null

    suspend fun run(block: suspend () -> HttpSyncResult): HttpSyncResult {
        val selected = synchronized(lock) {
            flight?.takeIf { it.isActive } ?: scope.async { block() }.also { created ->
                flight = created
                created.invokeOnCompletion {
                    synchronized(lock) {
                        if (flight === created) flight = null
                    }
                }
            }
        }
        return selected.await()
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
        fullSync: suspend (HttpSyncSettings, HttpSyncKvTransport) -> HttpSyncResult,
    ): HttpSyncResult {
        val client = HttpSyncWriteTrackingTransport(transportFactory(settings))
        val maps = state.syncMaps(client)
        if (!maps.needsBootstrap && !maps.booksChanged && !maps.otherChanged) {
            return emptyResult(
                uploadedBookmarks = maps.uploadedBookmarks,
                downloadedBookmarks = maps.downloadedBookmarks,
            )
        }

        // A books-map mismatch may refer to a manifest older than the incremental cursor (for
        // example, replacement was deferred while its reader was open). Force a complete key
        // listing for that rare path so the payload cannot be skipped forever.
        val reconcileSettings = if (maps.needsBootstrap || maps.booksChanged) {
            settings.copy(lastSyncedAt = null)
        } else {
            settings
        }
        val result = fullCycleRunner.run {
            val reconciled = fullSync(reconcileSettings, client)
            if (reconciled.errors.isEmpty()) {
                val after = state.observeLegacyEtags(client)
                val expected = client.expectedLegacyEtags(maps.observedLegacyEtags)
                if (after == expected) {
                    val published = state.publishMaps(client, expected)
                    return@run reconciled.copy(
                        downloadedBookmarks = reconciled.downloadedBookmarks + published.downloadedBookmarks,
                    )
                }
            }
            reconciled
        }
        return result.copy(
            uploadedBookmarks = result.uploadedBookmarks + maps.uploadedBookmarks,
            downloadedBookmarks = result.downloadedBookmarks + maps.downloadedBookmarks,
        )
    }

    private fun emptyResult(uploadedBookmarks: Int, downloadedBookmarks: Int) = HttpSyncResult(
        uploadedBookmarks = uploadedBookmarks,
        uploadedChatEntries = 0,
        uploadedMetadata = 0,
        downloadedBookmarks = downloadedBookmarks,
        downloadedChatEntries = 0,
        remoteOnlyBooks = 0,
        errors = emptyList(),
    )
}

/** Polls every five seconds; the durable outbox coalesces all intervening page turns. */
class HttpSyncBookmarkScheduler(
    private val state: HttpSyncBatchState,
    private val currentSettings: suspend () -> HttpSyncSettings?,
    private val syncBooksNow: suspend (HttpSyncSettings, HttpSyncKvTransport) -> HttpSyncResult,
    private val fullCycleRunner: HttpSyncFullCycleRunner,
    private val scope: CoroutineScope,
) {
    private val jobLock = Any()
    private var pollingJob: Job? = null
    private var mapFlight: Deferred<Unit>? = null
    private var backgroundFullJob: Job? = null
    private var fullRetryDeferredForReader = false
    private var fullRetryAfterMillis = 0L

    suspend fun onBookmarkChanged(bookRoot: File, title: String?, persistedSyncId: String? = null) {
        state.queueBookmark(bookRoot, title, persistedSyncId)
        start()
    }

    fun start() = synchronized(jobLock) {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            runMaps()
            while (isActive) {
                delay(BOOKMARK_SYNC_INTERVAL_MS)
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

    suspend fun refreshBeforeOpen() {
        runMaps()
    }

    fun flushNow() = refreshNow()

    private suspend fun runMaps() {
        val flight = synchronized(jobLock) {
            mapFlight?.takeIf { it.isActive } ?: scope.async {
                runMapsOnce()
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

    private suspend fun runMapsOnce() {
        val settings = currentSettings() ?: return
        if (!settings.isConfigured) return
        val client = HttpSyncWriteTrackingTransport(
            HttpSyncKvClient(settings.baseUrl, settings.bearerToken),
        )
        val changes = runCatching { state.syncMaps(client) }.getOrNull() ?: return
        if (changes.needsBootstrap || changes.booksChanged || changes.otherChanged) {
            val reconcileSettings = if (changes.needsBootstrap || changes.booksChanged) {
                settings.copy(lastSyncedAt = null)
            } else {
                settings
            }
            scheduleFullReconcile(changes, reconcileSettings, client)
        } else {
            fullRetryDeferredForReader = false
            fullRetryAfterMillis = 0L
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
                fullCycleRunner.run {
                    val result = syncBooksNow(settings, client)
                    if (result.errors.isEmpty()) {
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

private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

private fun bookmarkMapKey(deviceId: String): String = "$BOOKMARKS_MAP_PREFIX$deviceId.json"
