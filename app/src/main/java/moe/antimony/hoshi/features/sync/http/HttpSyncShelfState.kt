package moe.antimony.hoshi.features.sync.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HttpSyncShelfPlacementRecord(
    val shelfName: String? = null,
    val updatedAt: String,
)

/**
 * Stores the per-book shelf-placement sidecar (`.http_sync_shelf_state.json`).
 *
 * Concurrency: the sidecar is mutated from several in-process call sites — both sync
 * engines (the v2 reconciler and the v3 executor) do a whole-map load at the start of a
 * (potentially long) sync and save at the end, while
 * [HttpSyncAutoPush.onShelfPlacementChanged] records single placements the moment the user
 * moves a book. Same shape as the deleted-book sidecar's Bug 3: an unlocked
 * load-modify-save in any one of them can clobber a concurrent write from another.
 *
 * Two-part fix (mirrors iOS `HttpSyncShelfStateStore`):
 *  - every read/write is serialized under a process-wide [lock], and single-key mutations
 *    go through the atomic [recordPlacement] helper (same pattern as
 *    [HttpSyncDeletedBookStateStore.recordDeletedBook]);
 *  - [save] merges per key against the latest disk state — see its doc — so the engines'
 *    end-of-sync whole-map saves can't silently lose a shelf move recorded mid-sync.
 */
class HttpSyncShelfStateStore(
    private val json: Json,
) {
    fun load(booksRoot: File?): Map<String, HttpSyncShelfPlacementRecord> =
        synchronized(lock) { loadLocked(booksRoot) }

    /**
     * Merge-on-save: a fire-and-forget shelf hook can write a fresher record while a long
     * sync holds an in-memory copy of the whole map — a blind overwrite here would silently
     * lose that move (and the stale snapshot could then revert it via LWW). Per key, the
     * newer `updatedAt` wins ([compareRfc3339]); disk-only keys are kept (a hook may have
     * created them mid-sync). The cost is that records removed for tombstoned books can
     * linger as harmless orphans until the book's syncId is reused. Mirrors iOS
     * `HttpSyncShelfStateStore.save`.
     */
    fun save(booksRoot: File?, state: Map<String, HttpSyncShelfPlacementRecord>) {
        synchronized(lock) {
            val merged = state.toMutableMap()
            for ((key, diskRecord) in loadLocked(booksRoot)) {
                val memory = merged[key]
                if (memory == null || compareRfc3339(diskRecord.updatedAt, memory.updatedAt) > 0) {
                    merged[key] = diskRecord
                }
            }
            saveLocked(booksRoot, merged)
        }
    }

    /**
     * Atomically add (or replace) the placement record for [syncId]. Re-reads the latest
     * disk state under the lock before writing, so a concurrent whole-map [save] or
     * another `recordPlacement` cannot lose either side's update.
     */
    fun recordPlacement(booksRoot: File?, syncId: String, record: HttpSyncShelfPlacementRecord) {
        synchronized(lock) {
            val updated = loadLocked(booksRoot).toMutableMap()
            updated[syncId] = record
            saveLocked(booksRoot, updated)
        }
    }

    private fun loadLocked(booksRoot: File?): Map<String, HttpSyncShelfPlacementRecord> {
        val file = stateFile(booksRoot) ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString(serializer, file.readText())
        }.getOrDefault(emptyMap())
    }

    private fun saveLocked(booksRoot: File?, state: Map<String, HttpSyncShelfPlacementRecord>) {
        val file = stateFile(booksRoot) ?: return
        file.parentFile?.mkdirs()
        writeSidecarAtomically(file, json.encodeToString(serializer, state.toSortedMap()))
    }

    private fun stateFile(booksRoot: File?): File? =
        booksRoot?.resolve(STATE_FILE_NAME)

    private companion object {
        const val STATE_FILE_NAME = ".http_sync_shelf_state.json"
        val serializer = MapSerializer(String.serializer(), HttpSyncShelfPlacementRecord.serializer())

        /**
         * Process-wide lock — multiple store instances (auto-push hooks, v2 reconciler,
         * v3 executor/local-state) operate on the same file, so an instance-level lock
         * would not serialize them. See [HttpSyncDeletedBookStateStore.lock].
         */
        val lock = Any()
    }
}

/**
 * Atomic sidecar write: write to a `.tmp` sibling, then rename over the target. A bare
 * `writeText` truncates the file before writing, so a crash mid-write leaves a torn (or
 * empty) sidecar — for the revision sidecar that silently resets every rev to 0, for the
 * shelf sidecar it wipes every placement record. POSIX `rename` within one directory is
 * atomic; mirrors iOS's `Data.write(options: .atomic)`.
 */
internal fun writeSidecarAtomically(file: File, text: String) {
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        // Rename can fail on exotic filesystems; fall back to delete + rename, then to a
        // plain write (no worse than the previous behavior) as the last resort.
        file.delete()
        if (!tmp.renameTo(file)) {
            tmp.delete()
            file.writeText(text)
        }
    }
}

@Serializable
data class HttpSyncDeletedBookRecord(
    val title: String,
    val contentType: HttpSyncContentType,
    val deletedAt: String,
)

/**
 * Stores the local "deleted-book" tombstone sidecar
 * (`.http_sync_deleted_books.json`).
 *
 * Concurrency: the sidecar is mutated from at least three call sites in-process
 * — `BookshelfRepository.recordHttpSyncTombstone` (user-initiated delete),
 * `V3Executor` (push/clear tombstones during sync), and `V3LocalState` (stale
 * tombstone pruning on snapshot). A naive load-modify-save in any one of them
 * can clobber a concurrent write from another. Bug 3 happened because
 * V3Executor took a snapshot at the start of sync, mutated the snapshot, and
 * wrote the WHOLE map back at the end — so a concurrent
 * `BookshelfRepository.recordHttpSyncTombstone` mid-sync was silently lost.
 *
 * Fix: every mutation goes through one of the atomic helpers below
 * (`recordDeletedBook` / `removeDeletedBook`) and the read-modify-write is
 * protected by a process-wide [lock] so concurrent mutators serialize. Plain
 * [load] / [save] remain for callers that genuinely want a full-map view, but
 * callers that only want to add or remove a single key MUST use the atomic
 * helpers — otherwise they re-introduce the same race.
 */
class HttpSyncDeletedBookStateStore(
    private val json: Json,
) {
    fun load(booksRoot: File?): Map<String, HttpSyncDeletedBookRecord> {
        val file = stateFile(booksRoot) ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        return synchronized(lock) {
            runCatching {
                json.decodeFromString(serializer, file.readText())
            }.getOrDefault(emptyMap())
        }
    }

    fun save(booksRoot: File?, state: Map<String, HttpSyncDeletedBookRecord>) {
        val file = stateFile(booksRoot) ?: return
        synchronized(lock) {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer, state.toSortedMap()))
        }
    }

    /**
     * Atomically add (or replace) a tombstone for [syncId]. Re-reads the latest
     * disk state under the lock before writing, so two concurrent
     * `recordDeletedBook` calls — or a `recordDeletedBook` racing with a
     * `removeDeletedBook` — cannot lose either side's update.
     */
    fun recordDeletedBook(booksRoot: File?, syncId: String, record: HttpSyncDeletedBookRecord) {
        synchronized(lock) {
            val updated = loadLocked(booksRoot).toMutableMap()
            updated[syncId] = record
            saveLocked(booksRoot, updated)
        }
    }

    /**
     * Atomically remove the tombstone for [syncId]. Re-reads the latest disk
     * state under the lock, so a concurrent [recordDeletedBook] for a different
     * key racing this call is preserved (whichever side runs second sees the
     * other's write in its load).
     *
     * No-op when [syncId] is not present.
     */
    fun removeDeletedBook(booksRoot: File?, syncId: String) {
        synchronized(lock) {
            val current = loadLocked(booksRoot)
            if (!current.containsKey(syncId)) return
            val updated = current.toMutableMap().apply { remove(syncId) }
            saveLocked(booksRoot, updated)
        }
    }

    private fun loadLocked(booksRoot: File?): Map<String, HttpSyncDeletedBookRecord> {
        val file = stateFile(booksRoot) ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString(serializer, file.readText())
        }.getOrDefault(emptyMap())
    }

    private fun saveLocked(booksRoot: File?, state: Map<String, HttpSyncDeletedBookRecord>) {
        val file = stateFile(booksRoot) ?: return
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(serializer, state.toSortedMap()))
    }

    private fun stateFile(booksRoot: File?): File? =
        booksRoot?.resolve(STATE_FILE_NAME)

    private companion object {
        const val STATE_FILE_NAME = ".http_sync_deleted_books.json"
        val serializer = MapSerializer(String.serializer(), HttpSyncDeletedBookRecord.serializer())

        /**
         * Process-wide lock for in-memory serialization of sidecar mutations.
         * The sidecar file itself is per-app-process (single sync engine + single
         * UI repository per process) so a JVM-level lock is sufficient. We avoid
         * making this an instance-level lock because multiple
         * `HttpSyncDeletedBookStateStore` instances are constructed across the
         * app (one in V3Executor, one in V3LocalState, one in BookshelfRepository,
         * etc.) and all of them operate on the same file.
         */
        val lock = Any()
    }
}
