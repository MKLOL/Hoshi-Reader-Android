package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import java.io.File
import java.util.Base64
import java.util.UUID

/** A transport that primes the two remote maps with one v2 exchange call. */
interface HttpSyncPreparedTransport {
    suspend fun prepare()
    suspend fun finish(success: Boolean) = Unit
}

@Serializable
internal data class CachedExchangeKey(
    val key: String,
    val lastModified: String,
    val etag: String,
    val size: Int,
    val contentType: String,
    val bodyBase64: String? = null,
)

@Serializable
private data class CachedExchangeState(
    val booksHash: String? = null,
    val bookmarksHash: String? = null,
    val books: Map<String, String> = emptyMap(),
    val keys: Map<String, CachedExchangeKey> = emptyMap(),
)

@Serializable
private data class PendingBookmarkWrite(
    val key: String,
    val baseEtag: String? = null,
    val mutationId: String,
    val contentType: String = HttpSyncPusher.JSON_CONTENT_TYPE,
    val bodyBase64: String,
)

internal data class HttpSyncMapChanges(
    val booksChanged: Boolean,
    val bookmarksChanged: Boolean,
)

/**
 * Persistent hashes for the two per-user maps plus a durable bookmark outbox.
 *
 * The common request contains two SHA-256 strings and every bookmark dirtied in
 * the last five seconds. A matching server map is omitted entirely. Book bytes
 * are never hashed here: their import/download SHA and server ETags are cached.
 */
class HttpSyncBatchState(
    private val bookRepository: BookRepository,
    private val bookLocks: HttpSyncBookLocks = HttpSyncBookLocks(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val revisionStore = HttpSyncRevisionStore(json)
    private val exchangeMutex = Mutex()
    private val booksRoot: File get() = bookRepository.booksDirectory

    suspend fun queueBookmark(bookRoot: File, title: String?, persistedSyncId: String? = null) {
        val bookmark = bookRepository.loadBookmark(bookRoot) ?: return
        val metadata = bookRepository.loadMetadata(bookRoot)
        val syncId = persistedSyncId
            ?: metadata?.let(::syncIdForMetadata)
            ?: deriveSyncId(title, bookRoot.name)
            ?: return
        val key = bookmarkKey(syncId)
        val rev = revisionStore.bumpForLocalEdit(booksRoot, key)
        val encodedBody = json.encodeToString(
            HttpSyncBookmarkBlob.serializer(),
            bookmark.toBlob().copy(rev = rev),
        ).toByteArray(Charsets.UTF_8).toBase64()

        synchronized(stateLock) {
            val pending = loadPendingLocked().associateBy { it.key }.toMutableMap()
            val existing = pending[key]
            val baseEtag = existing?.baseEtag ?: loadStateLocked().keys[key]?.etag
            pending[key] = PendingBookmarkWrite(
                key = key,
                baseEtag = baseEtag,
                mutationId = UUID.randomUUID().toString(),
                bodyBase64 = encodedBody,
            )
            savePendingLocked(pending.values.sortedBy { it.key })
        }
    }

    internal fun exchangeRequest(): HttpSyncExchangeRequest = synchronized(stateLock) {
        val state = loadStateLocked()
        HttpSyncExchangeRequest(
            booksHash = state.booksHash,
            bookmarksHash = state.bookmarksHash,
            writes = loadPendingLocked().map { pending ->
                HttpSyncExchangeWrite(
                    key = pending.key,
                    baseEtag = pending.baseEtag,
                    mutationId = pending.mutationId,
                    contentType = pending.contentType,
                    bodyBase64 = pending.bodyBase64,
                )
            },
        )
    }

    internal suspend fun exchange(client: HttpSyncKvClient): HttpSyncMapChanges =
        exchangeMutex.withLock {
            val response = client.exchange(exchangeRequest())
            applyExchange(response)
        }

    internal suspend fun applyExchange(response: HttpSyncExchangeResponse): HttpSyncMapChanges {
        val remoteBookmarks = response.bookmarks
        val changes = synchronized(stateLock) {
            val oldState = loadStateLocked()
            val nextKeys = oldState.keys.toMutableMap()

            response.bookKeys?.let { remoteKeys ->
                nextKeys.keys
                    .filter { it.isPerBookKey() && !it.isBookmarkKey() }
                    .forEach(nextKeys::remove)
                for (remote in remoteKeys) {
                    val old = oldState.keys[remote.key]
                    val retainedBody = when {
                        remote.bodyBase64 != null -> remote.bodyBase64
                        old?.etag == remote.etag -> old.bodyBase64
                        else -> null
                    }
                    nextKeys[remote.key] = CachedExchangeKey(
                        key = remote.key,
                        lastModified = remote.lastModified,
                        etag = remote.etag,
                        size = remote.size,
                        contentType = remote.contentType,
                        bodyBase64 = retainedBody,
                    )
                }
            }

            remoteBookmarks?.let { bookmarkMap ->
                nextKeys.keys.filter(String::isBookmarkKey).forEach(nextKeys::remove)
                for ((syncId, entry) in bookmarkMap) {
                    val value = entry.value ?: continue
                    val body = json.encodeToString(HttpSyncBookmarkBlob.serializer(), value)
                        .toByteArray(Charsets.UTF_8)
                    val key = bookmarkKey(syncId)
                    nextKeys[key] = CachedExchangeKey(
                        key = key,
                        lastModified = entry.lastModified,
                        etag = entry.etag,
                        size = body.size,
                        contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
                        bodyBase64 = body.toBase64(),
                    )
                }
            }

            saveStateLocked(
                CachedExchangeState(
                    booksHash = response.booksHash,
                    bookmarksHash = response.bookmarksHash,
                    books = response.books ?: oldState.books,
                    keys = nextKeys,
                ),
            )

            // Any acknowledgement resolves that exact outbox mutation. A rejected
            // stale value is replaced by the authoritative bookmark map above; retrying
            // it forever would both waste calls and risk a later rollback.
            val acknowledged = response.writeAcks.associateBy { it.mutationId }
            val remaining = loadPendingLocked().filter { pending ->
                acknowledged[pending.mutationId]?.key != pending.key
            }
            savePendingLocked(remaining)

            HttpSyncMapChanges(
                booksChanged = response.books != null && response.books != oldState.books,
                bookmarksChanged = remoteBookmarks != null,
            )
        }

        if (remoteBookmarks != null) applyRemoteBookmarkMap(remoteBookmarks)
        return changes
    }

    private suspend fun applyRemoteBookmarkMap(
        bookmarks: Map<String, HttpSyncBookmarkMapEntry>,
    ) {
        val roots = bookRepository.loadBookEntries().mapNotNull { entry ->
            syncIdForMetadata(entry.metadata)?.let { it to entry.root }
        }.toMap()
        for ((syncId, entry) in bookmarks) {
            val blob = entry.value ?: continue
            val root = roots[syncId] ?: continue
            val key = bookmarkKey(syncId)
            bookLocks.withBookLock(root) {
                val local = bookRepository.loadBookmark(root)
                val localStamp = local?.lastModified?.let(::appleSecondsToRfc3339)
                val localRev = revisionStore.current(booksRoot, key).localRev
                if (remoteBookmarkWins(
                        localRev = localRev,
                        remoteRev = blob.rev,
                        localStamp = localStamp,
                        remoteStamp = blob.lastModified,
                    )
                ) {
                    bookRepository.saveBookmark(
                        root,
                        Bookmark(
                            chapterIndex = blob.chapterIndex,
                            progress = blob.progress,
                            characterCount = blob.characterCount,
                            lastModified = rfc3339ToAppleSeconds(blob.lastModified),
                        ),
                    )
                    revisionStore.noteRemote(booksRoot, key, blob.rev, appliedLocally = true)
                } else {
                    revisionStore.noteRemote(booksRoot, key, blob.rev, appliedLocally = false)
                }
            }
        }
    }

    internal fun snapshot(): Map<String, CachedExchangeKey> =
        synchronized(stateLock) { loadStateLocked().keys }

    internal fun hasPending(key: String? = null): Boolean = synchronized(stateLock) {
        val pending = loadPendingLocked()
        if (key == null) pending.isNotEmpty() else pending.any { it.key == key }
    }

    internal fun resolveLegacyWrite(key: String, mutationId: String) = synchronized(stateLock) {
        savePendingLocked(
            loadPendingLocked().filterNot { it.key == key && it.mutationId == mutationId },
        )
    }

    internal fun cacheWrite(response: HttpSyncKvWriteResponse, body: ByteArray?) = synchronized(stateLock) {
        val state = loadStateLocked()
        val keys = state.keys.toMutableMap()
        keys[response.key] = CachedExchangeKey(
            key = response.key,
            lastModified = response.lastModified,
            etag = response.etag,
            size = response.size,
            contentType = response.contentType,
            bodyBase64 = body?.takeIf { it.size <= MAX_CACHED_BODY_BYTES }?.toBase64(),
        )
        saveStateLocked(
            state.copy(
                booksHash = state.booksHash.takeUnless {
                    response.key.isPerBookKey() && !response.key.isBookmarkKey()
                },
                bookmarksHash = state.bookmarksHash.takeUnless { response.key.isBookmarkKey() },
                keys = keys,
            ),
        )
    }

    internal fun cacheFetched(key: String, fetched: HttpSyncKvFetched) = synchronized(stateLock) {
        val state = loadStateLocked()
        val keys = state.keys.toMutableMap()
        keys[key] = CachedExchangeKey(
            key = key,
            lastModified = fetched.lastModified,
            etag = fetched.etag,
            size = fetched.body.size,
            contentType = fetched.contentType,
            bodyBase64 = fetched.body.takeIf { it.size <= MAX_CACHED_BODY_BYTES }?.toBase64(),
        )
        saveStateLocked(state.copy(keys = keys))
    }

    internal fun removeCached(key: String) = synchronized(stateLock) {
        val state = loadStateLocked()
        val keys = state.keys.toMutableMap()
        keys.remove(key)
        saveStateLocked(
            state.copy(
                booksHash = state.booksHash.takeUnless {
                    key.isPerBookKey() && !key.isBookmarkKey()
                },
                bookmarksHash = state.bookmarksHash.takeUnless { key.isBookmarkKey() },
                keys = keys,
            ),
        )
    }

    private fun loadStateLocked(): CachedExchangeState {
        val file = booksRoot.resolve(CACHE_FILE_NAME)
        if (!file.isFile) return CachedExchangeState()
        val raw = runCatching { file.readText() }.getOrNull() ?: return CachedExchangeState()
        val isNewShape = runCatching {
            json.parseToJsonElement(raw).jsonObject.containsKey("keys")
        }.getOrDefault(false)
        if (isNewShape) {
            return runCatching { json.decodeFromString(CachedExchangeState.serializer(), raw) }
                .getOrDefault(CachedExchangeState())
        }
        val legacy = runCatching { json.decodeFromString(legacyCacheSerializer, raw) }
            .getOrDefault(emptyMap())
        return CachedExchangeState(keys = legacy)
    }

    private fun saveStateLocked(state: CachedExchangeState) {
        val file = booksRoot.resolve(CACHE_FILE_NAME)
        file.parentFile?.mkdirs()
        writeSidecarAtomically(
            file,
            json.encodeToString(CachedExchangeState.serializer(), state.copy(keys = state.keys.toSortedMap())),
        )
    }

    private fun loadPendingLocked(): List<PendingBookmarkWrite> {
        val file = booksRoot.resolve(PENDING_FILE_NAME)
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString(pendingSerializer, file.readText()) }
            .getOrDefault(emptyList())
    }

    private fun savePendingLocked(pending: List<PendingBookmarkWrite>) {
        val file = booksRoot.resolve(PENDING_FILE_NAME)
        file.parentFile?.mkdirs()
        writeSidecarAtomically(file, json.encodeToString(pendingSerializer, pending))
    }

    private companion object {
        const val CACHE_FILE_NAME = ".http_sync_exchange_cache.json"
        const val PENDING_FILE_NAME = ".http_sync_pending_bookmarks.json"
        const val MAX_CACHED_BODY_BYTES = 512 * 1024
        val stateLock = Any()
        val legacyCacheSerializer = MapSerializer(String.serializer(), CachedExchangeKey.serializer())
        val pendingSerializer = ListSerializer(PendingBookmarkWrite.serializer())
    }
}

class HttpSyncBatchKvTransport(
    settings: HttpSyncSettings,
    private val state: HttpSyncBatchState,
    private val delegate: HttpSyncKvClient = HttpSyncKvClient(settings.baseUrl, settings.bearerToken),
) : HttpSyncKvTransport, HttpSyncPreparedTransport {
    private var prepared = false
    private var legacyFallback = false
    private var legacyMutations: Map<String, String> = emptyMap()

    override suspend fun prepare() {
        if (prepared) return
        try {
            state.exchange(delegate)
        } catch (error: HttpSyncException) {
            if (error.httpCode != 404 && error.httpCode != 405) throw error
            // Rolling deploy compatibility: keep the released app fully usable
            // until this server gains /v2/exchange, then use maps automatically.
            legacyFallback = true
            legacyMutations = state.exchangeRequest().writes.associate { it.key to it.mutationId }
        }
        prepared = true
    }

    override suspend fun finish(success: Boolean) {
        if (!legacyFallback || !success) return
        for ((key, mutationId) in legacyMutations) {
            state.resolveLegacyWrite(key, mutationId)
        }
    }

    private suspend fun ensurePrepared() = prepare()

    override suspend fun list(prefix: String?, since: String?, cursor: String?, limit: Int?): HttpSyncKvList {
        ensurePrepared()
        if (legacyFallback) return delegate.list(prefix, since, cursor, limit)
        if (prefix != null && !prefix.startsWith("books/")) {
            return delegate.list(prefix, since, cursor, limit)
        }
        val filtered = state.snapshot().values.asSequence()
            .filter { prefix == null || it.key.startsWith(prefix) }
            .filter { since == null || it.lastModified > since }
            .filter { cursor == null || it.key > cursor }
            .sortedBy { it.key }
            .map { HttpSyncKvKeyMeta(it.key, it.lastModified, it.etag, it.size, it.contentType) }
            .toList()
        return HttpSyncKvList(keys = filtered, truncated = false, nextCursor = null)
    }

    override suspend fun get(key: String): HttpSyncKvFetched? {
        ensurePrepared()
        if (legacyFallback) return delegate.get(key)
        if (!key.isPerBookKey()) return delegate.get(key)
        val cached = state.snapshot()[key] ?: return null
        val body = cached.bodyBase64?.fromBase64()
        if (body != null) {
            return HttpSyncKvFetched(body, cached.contentType, cached.lastModified, cached.etag)
        }
        return delegate.get(key)?.also { state.cacheFetched(key, it) }
    }

    override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
        ensurePrepared()
        if (!legacyFallback && key.isBookmarkKey() && state.hasPending(key)) {
            throw HttpSyncException("A newer local bookmark is still awaiting conflict-safe acknowledgement.")
        }
        return delegate.put(key, contentType, body).also {
            state.cacheWrite(it, body)
            legacyMutations[key]?.let { mutationId ->
                if (legacyFallback && key.isBookmarkKey()) {
                    state.resolveLegacyWrite(key, mutationId)
                }
            }
        }
    }

    override suspend fun putFile(
        key: String,
        contentType: String,
        file: File,
        onByteProgress: ((Long, Long) -> Unit)?,
    ): HttpSyncKvWriteResponse {
        ensurePrepared()
        return delegate.putFile(key, contentType, file, onByteProgress)
            .also { state.cacheWrite(it, body = null) }
    }

    override suspend fun downloadToFile(
        key: String,
        targetFile: File,
        onByteProgress: ((Long, Long) -> Unit)?,
    ): HttpSyncKvFileFetched? = delegate.downloadToFile(key, targetFile, onByteProgress)

    override suspend fun delete(key: String) {
        ensurePrepared()
        delegate.delete(key)
        state.removeCached(key)
    }
}

/** Coalesces page turns into one map exchange every five seconds. */
class HttpSyncBookmarkScheduler(
    private val state: HttpSyncBatchState,
    private val currentSettings: suspend () -> HttpSyncSettings?,
    private val syncBooksNow: suspend (HttpSyncSettings) -> Unit,
    private val scope: CoroutineScope,
) {
    private val jobLock = Any()
    private var scheduledJob: Job? = null

    suspend fun onBookmarkChanged(bookRoot: File, title: String?, persistedSyncId: String? = null) {
        state.queueBookmark(bookRoot, title, persistedSyncId)
        schedule()
    }

    fun start() {
        scope.launch {
            runExchange()
            if (state.hasPending()) schedule()
        }
    }

    fun flushNow() {
        scope.launch { runExchange() }
    }

    private suspend fun runExchange() {
        val settings = currentSettings() ?: return
        if (!settings.isConfigured) return
        val changes = try {
            state.exchange(HttpSyncKvClient(settings.baseUrl, settings.bearerToken))
        } catch (error: HttpSyncException) {
            if (error.httpCode != 404 && error.httpCode != 405) return
            // Old server during a rolling deployment: the full engine's batch
            // transport falls back to v1 and still flushes the durable bookmark.
            runCatching { syncBooksNow(settings) }
            return
        } catch (_: Exception) {
            return
        }
        // Discovery stays in the same one-call hot path. Only a changed/new book
        // starts the larger reconciler, and it runs in this background scope.
        if (changes.booksChanged) runCatching { syncBooksNow(settings) }
    }

    private fun schedule() = synchronized(jobLock) {
        if (scheduledJob?.isActive == true) return
        scheduledJob = scope.launch {
            do {
                delay(BOOKMARK_SYNC_INTERVAL_MS)
                runExchange()
            } while (state.hasPending())
        }
    }

    companion object {
        const val BOOKMARK_SYNC_INTERVAL_MS: Long = 5_000L
    }
}

private fun String.isPerBookKey(): Boolean = startsWith("books/")
private fun String.isBookmarkKey(): Boolean = isPerBookKey() && endsWith("/bookmark")
private fun remoteBookmarkWins(
    localRev: Int?,
    remoteRev: Int?,
    localStamp: String?,
    remoteStamp: String?,
): Boolean {
    val stampOrder = compareRfc3339(remoteStamp, localStamp)
    if (stampOrder != 0) return stampOrder > 0
    return (remoteRev ?: 0) > (localRev ?: 0)
}
private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)
private fun String.fromBase64(): ByteArray? = runCatching { Base64.getDecoder().decode(this) }.getOrNull()
