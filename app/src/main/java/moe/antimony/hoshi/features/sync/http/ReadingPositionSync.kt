package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ReadingPositionJournal
import moe.antimony.hoshi.storage.writeSidecarAtomically
import java.security.MessageDigest

internal const val READING_POSITION_PREFIX = "sync/maps/positions/v1/"

@Serializable
private data class PositionSyncIndex(
    val applied: Map<String, String> = emptyMap(),
    val published: Map<String, String> = emptyMap(),
)

@Serializable
private data class PositionJournalVersion(val version: Int = 1)

private data class PositionKeyFailure(val etag: String, val retryAt: Long, val pauseMillis: Long)

internal data class ReadingPositionExchange(val moved: Set<String>, val published: Set<String>, val errors: List<String>)

/**
 * Independent single-writer histories. Released builds keep exchanging positions through the
 * bookmark maps; per-book bookmark keys stay bootstrap-only (see docs/HTTP_SYNC_KV.md).
 */
internal class ReadingPositionSync(private val repository: BookRepository) {
    private val store get() = repository.readingPositions
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val indexFile get() = store.directory.resolve("sync-index.json")
    private val failures = mutableMapOf<String, PositionKeyFailure>()

    suspend fun exchange(transport: HttpSyncKvTransport, listing: List<HttpSyncKvKeyMeta>, entries: List<BookEntry>): ReadingPositionExchange {
        // An unreadable index only costs one re-read of each history.
        var index = indexFile.takeIf { it.exists() }
            ?.let { runCatching { json.decodeFromString<PositionSyncIndex>(it.readText()) }.getOrNull() }
            ?: PositionSyncIndex()
        val roots = entries.mapNotNull { entry -> syncIdForMetadata(entry.metadata)?.let { it to entry.root } }.toMap()
        val moved = mutableSetOf<String>()
        val published = mutableSetOf<String>()
        val errors = mutableListOf<String>()
        val device = store.deviceId()
        val metas = listing.associateBy { it.key }

        suspend fun attempt(meta: HttpSyncKvKeyMeta?, key: String, action: suspend () -> Unit): Boolean {
            val etag = meta?.etag.orEmpty()
            val failed = failures[key]?.takeIf { it.etag == etag }
            if (failed != null && System.currentTimeMillis() < failed.retryAt) return false
            return try {
                action()
                failures.remove(key)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // As in the statistics lane: tried again when the ETag changes, or after a pause
                // that starts at 30 s and doubles up to ten minutes.
                val pause = failed?.let { (it.pauseMillis * 2).coerceAtMost(MAX_RETRY_MILLIS) } ?: FIRST_RETRY_MILLIS
                failures[key] = PositionKeyFailure(etag, System.currentTimeMillis() + pause, pause)
                errors += "$key: ${error.message ?: error.javaClass.simpleName}"
                false
            }
        }

        suspend fun receive(meta: HttpSyncKvKeyMeta, bookId: String, writer: String): Boolean = attempt(meta, meta.key) {
            val fetched = transport.getBounded(meta.key, 2 * 1024 * 1024) ?: error("Position history disappeared")
            val text = fetched.body.toString(Charsets.UTF_8)
            if (json.decodeFromString<PositionJournalVersion>(text).version > 1) {
                // A newer build's history is skipped quietly until it changes.
                index = index.copy(applied = index.applied + (meta.key to fetched.etag))
                return@attempt
            }
            val journal = json.decodeFromString<ReadingPositionJournal>(text)
            require(journal.bookId == bookId && journal.deviceId == writer) { "Position history writer/book mismatch" }
            if (store.merge(journal, roots[bookId])) moved += bookId
            // A listing ETag is not an acknowledgement: only the successfully merged body is.
            index = index.copy(applied = index.applied + (meta.key to fetched.etag))
        }

        // Restore this writer's remote history before replacing it. Other broken writers cannot
        // prevent independent local events from being sent.
        val ownRemote = listing.mapNotNull { meta -> parseKey(meta.key)?.let { (book, writer) ->
            if (writer == device) Triple(meta, book, writer) else null
        } }
        val blockedBooks = mutableSetOf<String>()
        val ownBefore = store.ownHistories()
        for ((meta, book, writer) in ownRemote) {
            if (index.applied[meta.key] != meta.etag || book !in ownBefore) {
                if (!receive(meta, book, writer)) blockedBooks += book
            }
        }
        for ((book, pending) in store.ownHistories()) {
            if (book in blockedBooks) continue
            val key = "$READING_POSITION_PREFIX$book/$device.json"
            val meta = metas[key]
            // Nothing new since this history was published: no journal is read on such a poll.
            if (pending.isEmpty() && meta != null && meta.etag == index.applied[key] && index.published[key] != null) continue
            val journal = store.export(book) ?: continue
            val body = json.encodeToString(journal).toByteArray()
            val digest = digest(body)
            if (meta?.etag == index.applied[key] && index.published[key] == digest) {
                store.acknowledge(book, (journal.events + journal.heads).mapTo(mutableSetOf()) { it.id })
                continue
            }
            attempt(meta, key) {
                val response = transport.put(key, "application/json", body)
                index = index.copy(applied = index.applied + (key to response.etag),
                    published = index.published + (key to digest))
                store.acknowledge(book, (journal.events + journal.heads).mapTo(mutableSetOf()) { it.id })
                published += book
            }
        }
        for (meta in listing) {
            val (book, writer) = parseKey(meta.key) ?: continue
            if (writer != device && index.applied[meta.key] != meta.etag) receive(meta, book, writer)
        }

        store.directory.mkdirs()
        val text = json.encodeToString(index)
        if (!indexFile.exists() || indexFile.readText() != text) writeSidecarAtomically(indexFile, text)
        return ReadingPositionExchange(moved, published, errors)
    }

    private fun parseKey(key: String): Pair<String, String>? {
        if (!key.startsWith(READING_POSITION_PREFIX)) return null
        val parts = key.removePrefix(READING_POSITION_PREFIX).split('/')
        if (parts.size != 2 || !parts[1].endsWith(".json")) return null
        val book = parts[0]
        val device = parts[1].removeSuffix(".json")
        return if (isValidSyncId(book) && isValidSyncId(device)) book to device else null
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val FIRST_RETRY_MILLIS = 30_000L
        const val MAX_RETRY_MILLIS = 10 * 60_000L
    }
}
