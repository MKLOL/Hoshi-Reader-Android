package moe.antimony.hoshi.features.sync.v3

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.sync.http.HttpSyncKvFetched
import moe.antimony.hoshi.features.sync.http.HttpSyncKvKeyMeta
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Small planner inputs, reused only after the current server listing verifies their version. */
internal class V3RemoteBodyCache(private val directory: File?) {
    @Serializable
    private data class Entry(
        val etag: String,
        val lastModified: String,
        val contentType: String,
        val body: String,
    )

    @Serializable
    private data class Snapshot(val identity: String, val entries: Map<String, Entry>)

    private val json = Json { ignoreUnknownKeys = true }
    private var identity: String? = null
    private var entries = mutableMapOf<String, Entry>()
    private var dirty = false

    fun begin(serverIdentity: String?, keys: Set<String>) {
        // A transport without a stable account identity cannot safely share a persisted cache.
        // Production transports provide a hash; no URL credentials or bearer token is stored.
        if (serverIdentity == null || identity != serverIdentity) {
            identity = serverIdentity
            entries = if (serverIdentity != null) {
                runCatching {
                    val snapshot = json.decodeFromString<Snapshot>(cacheFile().readText())
                    snapshot.entries.takeIf { snapshot.identity == serverIdentity }
                }.getOrNull().orEmpty().toMutableMap()
            } else {
                mutableMapOf()
            }
            dirty = false
        }
        if (entries.keys.retainAll(keys)) dirty = true
    }

    fun get(meta: HttpSyncKvKeyMeta): HttpSyncKvFetched? {
        val entry = entries[meta.key] ?: return null
        if (entry.etag != meta.etag || entry.lastModified != meta.lastModified) return null
        val bytes = entry.body.toByteArray(Charsets.UTF_8)
        if (bytes.size != meta.size) return null
        return HttpSyncKvFetched(bytes, entry.contentType, entry.lastModified, entry.etag)
    }

    fun put(meta: HttpSyncKvKeyMeta, fetched: HttpSyncKvFetched?) {
        // If a writer raced the list, use the fresh GET this pass but never label it with
        // the older listing version. The next pass will resolve the new version normally.
        val value = fetched?.takeIf {
            it.etag.isNotEmpty() && it.etag == meta.etag &&
                it.lastModified == meta.lastModified && it.body.size == meta.size &&
                it.body.size <= MAX_CACHED_BODY_BYTES
        }?.let { Entry(it.etag, it.lastModified, it.contentType, it.body.toString(Charsets.UTF_8)) }
        if (value == null) {
            if (entries.remove(meta.key) != null) dirty = true
        } else if (entries.put(meta.key, value) != value) {
            dirty = true
        }
    }

    fun save() {
        val serverIdentity = identity ?: return
        if (directory == null || !dirty) return
        // Losing this cache only costs downloads; it must never fail a successful sync.
        runCatching {
            val target = cacheFile()
            val temp = File(directory, "$CACHE_FILENAME.tmp")
            temp.writeText(json.encodeToString(Snapshot.serializer(), Snapshot(serverIdentity, entries)))
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            dirty = false
        }
    }

    private fun cacheFile(): File = File(requireNotNull(directory), CACHE_FILENAME)

    private companion object {
        const val CACHE_FILENAME = ".http_sync_v3_remote_cache.json"
        const val MAX_CACHED_BODY_BYTES = 512 * 1024
    }
}
