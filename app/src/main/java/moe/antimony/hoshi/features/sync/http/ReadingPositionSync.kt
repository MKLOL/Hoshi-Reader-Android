package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ReadingPositionJournal
import java.security.MessageDigest

internal const val READING_POSITION_PREFIX = "sync/maps/positions/v1/"

@Serializable
private data class PositionSyncIndex(
    val applied: Map<String, String> = emptyMap(),
    val published: Map<String, String> = emptyMap(),
    /**
     * This device's own keys it cannot use (undecodable, invalid or from a newer build), by
     * ETag. Only this device writes them, so they are replaced, never fetched again unchanged.
     */
    val unusable: Map<String, String> = emptyMap(),
)

@Serializable
private data class PositionJournalVersion(val version: Int = 1)

private data class PositionKeyFailure(val etag: String, val retryAt: Long, val pauseMillis: Long)

internal data class ReadingPositionExchange(val moved: Set<String>, val published: Set<String>, val errors: List<String>)

/**
 * Independent single-writer histories. Released builds keep exchanging positions through the
 * bookmark maps; per-book bookmark keys stay bootstrap-only (see docs/HTTP_SYNC_KV.md).
 *
 * @param maxReadsPerPass bounds the histories one pass fetches, so a first sync (a new install,
 *   many books arriving at once) never holds up the bookmark maps behind them; the rest follow
 *   on the next polls.
 */
internal class ReadingPositionSync(
    private val repository: BookRepository,
    private val maxReadsPerPass: Int = MAX_READS_PER_PASS,
) {
    private val store get() = repository.readingPositions
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val failures = mutableMapOf<String, PositionKeyFailure>()

    suspend fun exchange(transport: HttpSyncKvTransport, listing: List<HttpSyncKvKeyMeta>, entries: List<BookEntry>): ReadingPositionExchange {
        // An unreadable index only costs one re-read of each history.
        val (stored, generation) = store.loadSyncIndex()
        var index = stored?.let { runCatching { json.decodeFromString<PositionSyncIndex>(it) }.getOrNull() }
            ?: PositionSyncIndex()
        val roots = entries.mapNotNull { entry -> syncIdForMetadata(entry.metadata)?.let { it to entry.root } }.toMap()
        val moved = mutableSetOf<String>()
        val published = mutableSetOf<String>()
        val errors = mutableListOf<String>()
        val device = store.deviceId()
        val metas = listing.associateBy { it.key }
        var reads = 0

        // Kept as each phase ends: an interrupted first pass does not fetch everything again.
        var savedText = stored
        suspend fun checkpoint() {
            val text = json.encodeToString(index)
            if (text == savedText) return
            store.saveSyncIndex(text, generation)
            savedText = text
        }

        fun backedOff(meta: HttpSyncKvKeyMeta?, key: String): Boolean {
            val failed = failures[key]?.takeIf { it.etag == meta?.etag.orEmpty() } ?: return false
            return System.currentTimeMillis() < failed.retryAt
        }

        suspend fun attempt(meta: HttpSyncKvKeyMeta?, key: String, action: suspend () -> Unit): Boolean {
            if (backedOff(meta, key)) return false
            val failed = failures[key]?.takeIf { it.etag == meta?.etag.orEmpty() }
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
                failures[key] = PositionKeyFailure(meta?.etag.orEmpty(), System.currentTimeMillis() + pause, pause)
                errors += "$key: ${error.message ?: error.javaClass.simpleName}"
                false
            }
        }

        fun markUnusable(key: String, etag: String) {
            index = index.copy(applied = index.applied - key, unusable = index.unusable + (key to etag))
        }

        suspend fun receive(meta: HttpSyncKvKeyMeta, bookId: String, writer: String): Boolean = attempt(meta, meta.key) {
            reads += 1
            val own = writer == device
            try {
                val fetched = transport.getBounded(meta.key, MAX_HISTORY_BYTES) ?: error("Position history disappeared")
                val text = fetched.body.toString(Charsets.UTF_8)
                if (json.decodeFromString<PositionJournalVersion>(text).version > 1) {
                    // A newer build's history is skipped quietly until it changes.
                    if (own) markUnusable(meta.key, fetched.etag)
                    else index = index.copy(applied = index.applied + (meta.key to fetched.etag))
                    return@attempt
                }
                val journal = json.decodeFromString<ReadingPositionJournal>(text)
                require(journal.bookId == bookId && journal.deviceId == writer) { "Position history writer/book mismatch" }
                if (store.merge(journal, roots[bookId])) moved += bookId
                // A listing ETag is not an acknowledgement: only the successfully merged body is.
                index = index.copy(
                    applied = index.applied + (meta.key to fetched.etag),
                    unusable = index.unusable - meta.key,
                )
            } catch (invalid: IllegalArgumentException) {
                // Undecodable or invalid (SerializationException is one). Another device's body
                // waits for that device; this device's own is replaced by the next publish.
                if (!own) throw invalid
                markUnusable(meta.key, meta.etag)
            } catch (tooLarge: HttpSyncTooLargeException) {
                if (!own) throw tooLarge
                markUnusable(meta.key, meta.etag)
            }
        }

        // An open book's histories go first when a pass cannot read everything.
        fun activeFirst(book: String) = if (HttpSyncActiveBooks.contains(book)) 0 else 1

        // Restore this writer's remote history before replacing it. Other broken writers cannot
        // prevent independent local events from being sent.
        val ownRemote = listing.mapNotNull { meta -> parseKey(meta.key)?.let { (book, writer) ->
            if (writer == device) Triple(meta, book, writer) else null
        } }.sortedBy { activeFirst(it.second) }
        val blockedBooks = mutableSetOf<String>()
        val ownBefore = store.ownHistories()
        for ((meta, book, writer) in ownRemote) {
            if (index.unusable[meta.key] == meta.etag) continue
            if (index.applied[meta.key] == meta.etag && book in ownBefore) continue
            // Nothing here to publish over it and no folder to show it in: read it once the
            // book is on this device (its key stays unacknowledged until then).
            if (book !in ownBefore && book !in roots) continue
            if (backedOff(meta, meta.key) || reads >= maxReadsPerPass) {
                blockedBooks += book
                continue
            }
            if (!receive(meta, book, writer)) blockedBooks += book
        }
        checkpoint()
        for ((book, pending) in store.ownHistories()) {
            if (book in blockedBooks) continue
            val key = "$READING_POSITION_PREFIX$book/$device.json"
            val meta = metas[key]
            // Nothing new since this history was published: no journal is read on such a poll.
            if (pending.isEmpty() && meta != null && meta.etag == index.applied[key] && index.published[key] != null) continue
            attempt(meta, key) {
                val journal = store.export(book) ?: return@attempt
                val body = json.encodeToString(journal).toByteArray()
                val digest = digest(body)
                val acknowledged = (journal.events + journal.heads).mapTo(mutableSetOf()) { it.id }
                // The server's ETag is the body's hash, which also spares re-uploads after a lost index.
                if (meta != null && (meta.etag == "sha256:$digest" ||
                        (meta.etag == index.applied[key] && index.published[key] == digest))) {
                    index = index.copy(applied = index.applied + (key to meta.etag),
                        published = index.published + (key to digest), unusable = index.unusable - key)
                    store.acknowledge(book, acknowledged)
                    return@attempt
                }
                val response = transport.put(key, "application/json", body)
                index = index.copy(applied = index.applied + (key to response.etag),
                    published = index.published + (key to digest), unusable = index.unusable - key)
                store.acknowledge(book, acknowledged)
                published += book
            }
        }
        checkpoint()
        // Another device's history is read only for a book on this device; a book installed
        // later finds its keys still unacknowledged and reads them then.
        val foreign = listing.mapNotNull { meta -> parseKey(meta.key)?.let { (book, writer) ->
            Triple(meta, book, writer).takeIf {
                writer != device && book in roots && index.applied[meta.key] != meta.etag && !backedOff(meta, meta.key)
            }
        } }.sortedBy { activeFirst(it.second) }
        for ((meta, book, writer) in foreign) {
            if (reads >= maxReadsPerPass) break
            receive(meta, book, writer)
        }

        checkpoint()
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
        const val MAX_HISTORY_BYTES = 2 * 1024 * 1024
        const val MAX_READS_PER_PASS = 32
    }
}
