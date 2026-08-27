package moe.antimony.hoshi.features.sync.http

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Prevents sync from replacing static files underneath a live EPUB or manga reader. */
internal object HttpSyncActiveBooks {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val mutationGates = ConcurrentHashMap<String, Mutex>()

    suspend fun open(syncId: String?) {
        if (syncId == null) return
        gate(syncId).withLock {
            counts.computeIfAbsent(syncId) { AtomicInteger() }.incrementAndGet()
        }
    }

    suspend fun close(syncId: String?) {
        if (syncId == null) return
        gate(syncId).withLock {
            counts.computeIfPresent(syncId) { _, count ->
                if (count.decrementAndGet() <= 0) null else count
            }
        }
    }

    fun contains(syncId: String): Boolean = (counts[syncId]?.get() ?: 0) > 0

    fun hasAny(): Boolean = counts.values.any { it.get() > 0 }

    /** Reader admission and destructive filesystem mutations are atomic for one sync id. */
    suspend fun runIfInactive(syncId: String, mutation: suspend () -> Unit): Boolean =
        gate(syncId).withLock {
            if (contains(syncId)) return@withLock false
            mutation()
            true
        }

    class Lease {
        private val lock = Mutex()
        private var heldSyncId: String? = null

        suspend fun acquire(syncId: String?) = lock.withLock {
            if (syncId == null || heldSyncId == syncId) return@withLock
            heldSyncId?.let { HttpSyncActiveBooks.close(it) }
            HttpSyncActiveBooks.open(syncId)
            heldSyncId = syncId
        }

        suspend fun release() = lock.withLock {
            heldSyncId?.let { HttpSyncActiveBooks.close(it) }
            heldSyncId = null
        }
    }

    private fun gate(syncId: String): Mutex = mutationGates.computeIfAbsent(syncId) { Mutex() }
}
