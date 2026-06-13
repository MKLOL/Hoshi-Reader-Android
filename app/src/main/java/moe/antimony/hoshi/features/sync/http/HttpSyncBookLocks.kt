package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-book mutex used to serialize bookmark reads and writes between the reader's
 * fire-and-forget [HttpSyncPusher.pushBookmark] and the manual-sync
 * [HttpSyncReconciler.pushAllLocal]. Without it, both paths could fetch + compare +
 * PUT the same bookmark key concurrently, with the second writer's stale local copy
 * clobbering the first's freshly-pulled-or-pushed value.
 *
 * Granularity is per-book — different books never contend, which matters because the
 * reconciler iterates all books while a reader hook is hot on exactly one.
 *
 * Thread-safety: backed by a [ConcurrentHashMap] so `mutexFor` itself is safe to call
 * from any coroutine. The mutex is created lazily on first request.
 *
 * Lifetime: instances are held for the lifetime of [HttpSyncBookLocks] (one per
 * [moe.antimony.hoshi.HoshiAppContainer]). The map will accumulate one entry per book the
 * user has ever opened during the session — tiny memory footprint, never a leak in
 * practice (books are tens, not thousands).
 */
class HttpSyncBookLocks {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    fun mutexFor(bookRoot: File): Mutex =
        mutexes.computeIfAbsent(bookRoot.canonicalPath) { Mutex() }

    suspend fun <T> withBookLock(bookRoot: File, block: suspend () -> T): T =
        mutexFor(bookRoot).withLock { block() }

    /**
     * Same per-key serialization for writers that have no on-disk root to lock on — e.g.
     * the v3 metadata PUT, whose action carries only a syncId. Namespaced with a `key:`
     * prefix so a KV key can never collide with a book root's canonical path.
     */
    fun mutexForKey(key: String): Mutex =
        mutexes.computeIfAbsent("key:$key") { Mutex() }

    suspend fun <T> withKeyLock(key: String, block: suspend () -> T): T =
        mutexForKey(key).withLock { block() }
}
