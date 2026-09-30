package moe.antimony.hoshi.features.sync.http

import moe.antimony.hoshi.storage.writeSidecarAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.R
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * A remote metadata index, NOT an acknowledgement that its contents were applied locally.
 * The batch exchange separately remembers successfully reconciled ETags, so failed work is
 * retried even if the next delta is empty. No-change polling is one request independent of
 * library size; the server returns only recent writes instead of every book/chat/payload key.
 * Callers serialize access with the batch exchange mutex and run on its IO dispatcher.
 */
internal class HttpSyncMetadataIndex(
    booksRoot: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class Snapshot(
        val scope: String,
        val fullListedAt: Long,
        val watermark: String?,
        val keys: List<HttpSyncKvKeyMeta>,
    )

    private val file = booksRoot.resolve(".http_sync_metadata_index.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loaded = false
    private var cached: Snapshot? = null

    suspend fun list(transport: HttpSyncKvTransport): List<HttpSyncKvKeyMeta> {
        val scope = transport.cacheIdentity
        if (!loaded && scope != null) {
            cached = runCatching { json.decodeFromString<Snapshot>(file.readText()) }.getOrNull()
            loaded = true
        }
        val saved = cached?.takeIf { scope != null && it.scope == scope }
        val currentTime = now()
        // Application deletion uses metadata tombstones and is visible in deltas. A daily full
        // audit also detects raw DELETEs by legacy/external tools and namespace resets.
        val incremental = saved != null && saved.watermark != null &&
            currentTime >= saved.fullListedAt && currentTime - saved.fullListedAt < FULL_AUDIT_MS
        // Repeat the timestamp boundary to cover servers with second-resolution timestamps.
        val since = if (incremental) runCatching {
            Instant.parse(saved!!.watermark).minusSeconds(1).toString()
        }.getOrNull() else null
        val keys = if (since != null) saved!!.keys.associateBy { it.key }.toMutableMap() else linkedMapOf()
        // For a paginated scan we need a server-time boundary BEFORE scanning. The opaque
        // checkpoint is excluded from reconciliation like the other map keys. One fixed key
        // suffices for all devices: each uses its own PUT response, never the stored body.
        suspend fun checkpoint(): Instant = Instant.parse(transport.put(
            "${SYNC_MAP_PREFIX}checkpoint.json", "application/json; charset=utf-8",
            ("\"" + UUID.randomUUID().toString() + "\"").toByteArray(Charsets.UTF_8),
        ).lastModified)
        var scanBoundary = if (scope != null && since == null) checkpoint() else null
        var cursor: String? = null
        val cursors = mutableSetOf<String>()
        var firstPageStamp: Instant? = null
        do {
            val page = transport.list(since = since, cursor = cursor, limit = 2_000)
            if (cursor == null && page.truncated && scanBoundary == null && scope != null) {
                // Restart after establishing a boundary. Writes to a key passed on a later
                // page will be newer than it and therefore remain visible on the next poll.
                scanBoundary = checkpoint()
                continue
            }
            // Advance only to a timestamp observed in the FIRST page. A write into an already
            // scanned key during later pages must remain newer than our next polling floor.
            if (cursor == null) {
                firstPageStamp = page.keys.mapNotNull { runCatching { Instant.parse(it.lastModified) }.getOrNull() }.maxOrNull()
            }
            page.keys.forEach { keys[it.key] = it }
            if (!page.truncated) break
            cursor = page.nextCursor?.takeIf { cursors.add(it) }
                ?: throw HttpSyncException(R.string.http_sync_invalid_pagination)
        } while (true)
        val result = keys.values.toList()
        if (scope != null) {
            val previousStamp = if (since != null) runCatching { Instant.parse(saved!!.watermark) }.getOrNull() else null
            val watermark = listOfNotNull(previousStamp, scanBoundary ?: firstPageStamp).maxOrNull()?.toString()
            val snapshot = Snapshot(scope, if (since != null) saved!!.fullListedAt else currentTime, watermark, result)
            if (snapshot != cached) {
                file.parentFile?.mkdirs()
                writeSidecarAtomically(file, json.encodeToString(Snapshot.serializer(), snapshot))
                cached = snapshot
            }
        }
        return result
    }

    private companion object {
        const val FULL_AUDIT_MS = 24 * 60 * 60 * 1_000L
    }
}
