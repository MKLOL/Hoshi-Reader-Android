package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookRepository
import java.io.File
import java.util.Base64
import java.util.UUID

/** A transport that primes the complete remote index with one v2 exchange call. */
interface HttpSyncPreparedTransport {
    suspend fun prepare()
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
private data class PendingBookmarkWrite(
    val key: String,
    val baseEtag: String? = null,
    val mutationId: String,
    val contentType: String = HttpSyncPusher.JSON_CONTENT_TYPE,
    val bodyBase64: String,
)

/**
 * Durable cache + outbox shared by manual sync and reader page-turn sync.
 *
 * A bookmark is written to this outbox before network IO. Accepted mutations are
 * removed only when the server acknowledges the exact mutation id, so process death
 * or a page turn racing an in-flight request cannot lose progress.
 */
class HttpSyncBatchState(
    private val bookRepository: BookRepository,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val revisionStore = HttpSyncRevisionStore(json)
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
            val baseEtag = existing?.baseEtag ?: loadCacheLocked()[key]?.etag
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
        HttpSyncExchangeRequest(
            knownEtags = loadCacheLocked().mapValues { it.value.etag },
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

    internal fun applyExchange(response: HttpSyncExchangeResponse) = synchronized(stateLock) {
        val oldCache = loadCacheLocked()
        val nextCache = oldCache.toMutableMap()
        response.removedKeys.forEach(nextCache::remove)
        for (remote in response.keys) {
            val old = oldCache[remote.key]
            val retainedBody = when {
                remote.bodyBase64 != null -> remote.bodyBase64
                old?.etag == remote.etag -> old.bodyBase64
                else -> null
            }
            nextCache[remote.key] = CachedExchangeKey(
                key = remote.key,
                lastModified = remote.lastModified,
                etag = remote.etag,
                size = remote.size,
                contentType = remote.contentType,
                bodyBase64 = retainedBody,
            )
        }
        saveCacheLocked(nextCache)

        val acceptedByMutation = response.writeAcks
            .filter { it.accepted }
            .associateBy { it.mutationId }
        val remaining = loadPendingLocked().filter { pending ->
            acceptedByMutation[pending.mutationId]?.key != pending.key
        }
        savePendingLocked(remaining)
    }

    internal fun snapshot(): Map<String, CachedExchangeKey> =
        synchronized(stateLock) { loadCacheLocked() }

    internal fun hasPending(key: String? = null): Boolean = synchronized(stateLock) {
        val pending = loadPendingLocked()
        if (key == null) pending.isNotEmpty() else pending.any { it.key == key }
    }

    internal fun cacheWrite(response: HttpSyncKvWriteResponse, body: ByteArray?) = synchronized(stateLock) {
        val cache = loadCacheLocked().toMutableMap()
        cache[response.key] = CachedExchangeKey(
            key = response.key,
            lastModified = response.lastModified,
            etag = response.etag,
            size = response.size,
            contentType = response.contentType,
            bodyBase64 = body?.takeIf { it.size <= MAX_CACHED_BODY_BYTES }?.toBase64(),
        )
        saveCacheLocked(cache)
    }

    internal fun cacheFetched(key: String, fetched: HttpSyncKvFetched) = synchronized(stateLock) {
        val cache = loadCacheLocked().toMutableMap()
        cache[key] = CachedExchangeKey(
            key = key,
            lastModified = fetched.lastModified,
            etag = fetched.etag,
            size = fetched.body.size,
            contentType = fetched.contentType,
            bodyBase64 = fetched.body.takeIf { it.size <= MAX_CACHED_BODY_BYTES }?.toBase64(),
        )
        saveCacheLocked(cache)
    }

    internal fun removeCached(key: String) = synchronized(stateLock) {
        val cache = loadCacheLocked().toMutableMap()
        cache.remove(key)
        saveCacheLocked(cache)
    }

    private fun loadCacheLocked(): Map<String, CachedExchangeKey> {
        val file = booksRoot.resolve(CACHE_FILE_NAME)
        if (!file.isFile) return emptyMap()
        return runCatching { json.decodeFromString(cacheSerializer, file.readText()) }
            .getOrDefault(emptyMap())
    }

    private fun saveCacheLocked(cache: Map<String, CachedExchangeKey>) {
        val file = booksRoot.resolve(CACHE_FILE_NAME)
        file.parentFile?.mkdirs()
        writeSidecarAtomically(file, json.encodeToString(cacheSerializer, cache.toSortedMap()))
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
        val cacheSerializer = MapSerializer(String.serializer(), CachedExchangeKey.serializer())
        val pendingSerializer = ListSerializer(PendingBookmarkWrite.serializer())
    }
}

class HttpSyncBatchKvTransport(
    settings: HttpSyncSettings,
    private val state: HttpSyncBatchState,
    private val delegate: HttpSyncKvClient = HttpSyncKvClient(settings.baseUrl, settings.bearerToken),
) : HttpSyncKvTransport, HttpSyncPreparedTransport {
    private var prepared = false

    override suspend fun prepare() {
        if (prepared) return
        val response = delegate.exchange(state.exchangeRequest())
        state.applyExchange(response)
        prepared = true
    }

    private suspend fun ensurePrepared() = prepare()

    override suspend fun list(prefix: String?, since: String?, cursor: String?, limit: Int?): HttpSyncKvList {
        ensurePrepared()
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
        val cached = state.snapshot()[key] ?: return null
        val body = cached.bodyBase64?.fromBase64()
        if (body != null) {
            return HttpSyncKvFetched(body, cached.contentType, cached.lastModified, cached.etag)
        }
        return delegate.get(key)?.also { state.cacheFetched(key, it) }
    }

    override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
        ensurePrepared()
        if (key.endsWith("/bookmark") && state.hasPending(key)) {
            throw HttpSyncException("A newer local bookmark is still awaiting conflict-safe acknowledgement.")
        }
        return delegate.put(key, contentType, body).also { state.cacheWrite(it, body) }
    }

    override suspend fun putFile(
        key: String,
        contentType: String,
        file: File,
        onByteProgress: ((Long, Long) -> Unit)?,
    ): HttpSyncKvWriteResponse = delegate.putFile(key, contentType, file, onByteProgress)
        .also { state.cacheWrite(it, body = null) }

    override suspend fun downloadToFile(
        key: String,
        targetFile: File,
        onByteProgress: ((Long, Long) -> Unit)?,
    ): HttpSyncKvFileFetched? = delegate.downloadToFile(key, targetFile, onByteProgress)

    override suspend fun delete(key: String) {
        delegate.delete(key)
        state.removeCached(key)
    }
}

/** Coalesces all page turns in a five-second window into one full exchange. */
class HttpSyncBookmarkScheduler(
    private val state: HttpSyncBatchState,
    private val currentSettings: suspend () -> HttpSyncSettings?,
    private val syncNow: suspend (HttpSyncSettings) -> Unit,
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
            val settings = currentSettings() ?: return@launch
            if (settings.isConfigured) runCatching { syncNow(settings) }
            if (state.hasPending()) schedule()
        }
    }

    fun flushNow() {
        scope.launch {
            val settings = currentSettings() ?: return@launch
            if (settings.isConfigured) runCatching { syncNow(settings) }
        }
    }

    private fun schedule() = synchronized(jobLock) {
        if (scheduledJob?.isActive == true) return
        scheduledJob = scope.launch {
            do {
                delay(BOOKMARK_SYNC_INTERVAL_MS)
                val settings = currentSettings()
                if (settings?.isConfigured == true) runCatching { syncNow(settings) }
            } while (state.hasPending())
        }
    }

    companion object {
        const val BOOKMARK_SYNC_INTERVAL_MS: Long = 5_000L
    }
}

private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)
private fun String.fromBase64(): ByteArray? = runCatching { Base64.getDecoder().decode(this) }.getOrNull()
