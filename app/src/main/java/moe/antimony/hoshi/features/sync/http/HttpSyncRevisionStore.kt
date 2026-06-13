package moe.antimony.hoshi.features.sync.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-KV-key edit-depth (Lamport) revision sidecar, persisted as
 * `.http_sync_revisions.json` in the Books directory. Mirrors iOS's
 * `HttpSyncRevisionStore` — the wire `rev` field on bookmark/metadata blobs is produced
 * and consumed through this store.
 *
 * Model per key:
 *  - [HttpSyncRevisionRecord.localRev]: the edit depth of the local copy. Bumped to
 *    `max(localRev, baseRev) + 1` by every deliberate local edit (page-turn batch persisted,
 *    shelf move, delete, import).
 *  - [HttpSyncRevisionRecord.baseRev]: the highest remote rev this device has observed
 *    (pushed or pulled). A push is allowed only when `localRev` out-revisions the remote
 *    blob; otherwise remote wins and is applied locally.
 *
 * Concurrency: like the deleted-book sidecar, this file is mutated from multiple in-process
 * call sites (reader-hook pusher, auto-push hooks, v2 reconciler, v3 executor), so every
 * read-modify-write goes through a process-wide [lock] — see
 * [HttpSyncDeletedBookStateStore] for the rationale. Reads/writes re-read the file each
 * time: calls are rare (one per user-visible edit / per pushed key), so simplicity beats
 * caching.
 */
@Serializable
data class HttpSyncRevisionRecord(
    val localRev: Int = 0,
    val baseRev: Int = 0,
)

class HttpSyncRevisionStore(
    private val json: Json,
) {
    fun load(booksRoot: File?): Map<String, HttpSyncRevisionRecord> =
        synchronized(lock) { loadLocked(booksRoot) }

    /** The record for [key] (zeros when never seen). */
    fun current(booksRoot: File?, key: String): HttpSyncRevisionRecord =
        load(booksRoot)[key] ?: HttpSyncRevisionRecord()

    /**
     * Registers a deliberate local edit of [key] and returns the new local rev:
     * `max(localRev, baseRev) + 1`. Monotonic even if the subsequent push fails.
     */
    fun bumpForLocalEdit(booksRoot: File?, key: String): Int = synchronized(lock) {
        val state = loadLocked(booksRoot).toMutableMap()
        val record = state[key] ?: HttpSyncRevisionRecord()
        val bumped = record.copy(localRev = maxOf(record.localRev, record.baseRev) + 1)
        state[key] = bumped
        saveLocked(booksRoot, state)
        bumped.localRev
    }

    /**
     * Records that the remote copy of [key] is at [rev]. When [appliedLocally] (we pulled
     * the remote state into local files, or our own PUT landed), the local rev is
     * fast-forwarded too, so a later local edit builds on top of it.
     */
    fun noteRemote(booksRoot: File?, key: String, rev: Int?, appliedLocally: Boolean) {
        val rr = rev ?: 0
        synchronized(lock) {
            val state = loadLocked(booksRoot).toMutableMap()
            val record = state[key] ?: HttpSyncRevisionRecord()
            state[key] = record.copy(
                baseRev = maxOf(record.baseRev, rr),
                localRev = if (appliedLocally) maxOf(record.localRev, rr) else record.localRev,
            )
            saveLocked(booksRoot, state)
        }
    }

    private fun loadLocked(booksRoot: File?): Map<String, HttpSyncRevisionRecord> {
        val file = stateFile(booksRoot) ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString(serializer, file.readText())
        }.getOrDefault(emptyMap())
    }

    private fun saveLocked(booksRoot: File?, state: Map<String, HttpSyncRevisionRecord>) {
        val file = stateFile(booksRoot) ?: return
        file.parentFile?.mkdirs()
        // Atomic (tmp + rename): a torn write of this sidecar would reset every key's rev
        // to 0, silently demoting every deliberate local edit to legacy-LWW.
        writeSidecarAtomically(file, json.encodeToString(serializer, state.toSortedMap()))
    }

    private fun stateFile(booksRoot: File?): File? =
        booksRoot?.resolve(STATE_FILE_NAME)

    private companion object {
        const val STATE_FILE_NAME = ".http_sync_revisions.json"
        val serializer = MapSerializer(String.serializer(), HttpSyncRevisionRecord.serializer())

        /**
         * Process-wide lock: multiple store instances (pusher, auto-push, reconciler, v3)
         * operate on the same file, so an instance-level lock would not serialize them.
         */
        val lock = Any()
    }
}
