package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ReadingPositionJournal
import moe.antimony.hoshi.storage.writeSidecarAtomically
import java.security.MessageDigest

internal const val READING_POSITION_PREFIX = "sync/maps/positions/v1/"

@Serializable
private data class PositionSyncIndex(
    val applied: Map<String, String> = emptyMap(),
    val published: Map<String, String> = emptyMap(),
    val legacy: Map<String, HttpSyncBookmarkBlob> = emptyMap(),
)

internal data class ReadingPositionExchange(val moved: Set<String>, val published: Set<String>, val errors: List<String>)

/** Independent single-writer histories plus a bridge for unchanged released bookmark formats. */
internal class ReadingPositionSync(private val repository: BookRepository) {
    private val store get() = repository.readingPositions
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val revisions = HttpSyncRevisionStore(json)
    private val indexFile get() = store.directory.resolve("sync-index.json")
    private val failures = mutableMapOf<String, Pair<String, Long>>()

    suspend fun exchange(transport: HttpSyncKvTransport, listing: List<HttpSyncKvKeyMeta>, entries: List<BookEntry>): ReadingPositionExchange {
        var index = if (indexFile.exists()) json.decodeFromString<PositionSyncIndex>(indexFile.readText()) else PositionSyncIndex()
        val roots = entries.mapNotNull { entry -> syncIdForMetadata(entry.metadata)?.let { it to entry.root } }.toMap()
        val moved = mutableSetOf<String>()
        val published = mutableSetOf<String>()
        val errors = mutableListOf<String>()
        val device = store.deviceId()
        val metas = listing.associateBy { it.key }

        suspend fun attempt(meta: HttpSyncKvKeyMeta?, key: String, action: suspend () -> Unit): Boolean {
            val failed = failures[key]
            if (failed != null && failed.first == meta?.etag.orEmpty() && System.currentTimeMillis() < failed.second) return false
            return try {
                action()
                failures.remove(key)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures[key] = meta?.etag.orEmpty() to (System.currentTimeMillis() + 30_000)
                errors += "$key: ${error.message ?: error.javaClass.simpleName}"
                false
            }
        }

        suspend fun receive(meta: HttpSyncKvKeyMeta, bookId: String, writer: String): Boolean = attempt(meta, meta.key) {
            val fetched = transport.getBounded(meta.key, 2 * 1024 * 1024) ?: error("Position history disappeared")
            val journal = json.decodeFromString<ReadingPositionJournal>(fetched.body.toString(Charsets.UTF_8))
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
        for ((meta, book, writer) in ownRemote) {
            if (index.applied[meta.key] != meta.etag || store.export(book) == null) {
                if (!receive(meta, book, writer)) blockedBooks += book
            }
        }
        for (state in store.snapshots()) {
            if (state.bookId in blockedBooks) continue
            val journal = store.export(state.bookId) ?: continue
            val key = "$READING_POSITION_PREFIX${state.bookId}/$device.json"
            val body = json.encodeToString(journal).toByteArray()
            val digest = digest(body)
            val meta = metas[key]
            if (meta?.etag == index.applied[key] && index.published[key] == digest) {
                store.acknowledge(state.bookId, (journal.events + journal.heads).mapTo(mutableSetOf()) { it.id })
                continue
            }
            attempt(meta, key) {
                val response = transport.put(key, "application/json", body)
                index = index.copy(applied = index.applied + (key to response.etag),
                    published = index.published + (key to digest))
                store.acknowledge(state.bookId, (journal.events + journal.heads).mapTo(mutableSetOf()) { it.id })
                published += state.bookId
            }
        }
        for (meta in listing) {
            val (book, writer) = parseKey(meta.key) ?: continue
            if (writer != device && index.applied[meta.key] != meta.etag) receive(meta, book, writer)
        }

        // Released pre-map clients only speak books/{id}/bookmark. Keep this bridge live after
        // bootstrap; preserve their original timestamp and never turn receiving into navigation.
        for ((book, root) in roots) {
            val key = bookmarkKey(book)
            val meta = metas[key]
            if (meta != null && index.applied[key] != meta.etag) {
                val received = attempt(meta, key) {
                    val fetched = transport.getBounded(key, 1024 * 1024) ?: error("Legacy bookmark disappeared")
                    val value = json.decodeFromString<HttpSyncBookmarkBlob>(fetched.body.toString(Charsets.UTF_8))
                    require(value.chapterIndex >= 0 && value.progress.isFinite() && value.progress in 0.0..1.0 && value.characterCount >= 0)
                    val stamp = rfc3339ToAppleSeconds(value.lastModified)
                    require(stamp != 0.0 || value.lastModified.startsWith("2001-01-01T00:00:00"))
                    val before = repository.loadBookmark(root)
                    val localBlob = before?.toBlob()?.copy(rev = revisions.current(repository.booksDirectory, key).localRev)
                    val localEntry = localBlob?.let { HttpSyncBookmarkMapEntry(
                        "sha256:${digest(json.encodeToString(it).toByteArray())}", it.lastModified, it,
                    ) }
                    val remoteEntry = HttpSyncBookmarkMapEntry(fetched.etag, value.lastModified, value)
                    if (localEntry == null || compareBookmarkEntries(remoteEntry, localEntry) > 0) {
                        repository.saveBookmark(root, Bookmark(value.chapterIndex, value.progress, value.characterCount, stamp))
                        revisions.noteRemote(repository.booksDirectory, key, value.rev, appliedLocally = true)
                    }
                    if (before?.let { Triple(it.chapterIndex, it.progress, it.characterCount) } !=
                        repository.loadBookmark(root)?.let { Triple(it.chapterIndex, it.progress, it.characterCount) }) moved += book
                    index = index.copy(applied = index.applied + (key to fetched.etag), legacy = index.legacy + (key to value))
                }
                if (!received) continue // Never replace a changed legacy value we could not read.
            }
            val local = repository.loadBookmark(root) ?: continue
            val remote = index.legacy[key]
            // Creating the compatibility key is needed for new navigation, not for an idle
            // migration. Existing legacy keys continue to receive updates in either case.
            val hasNavigation = store.export(book) != null
            val localBlob = local.toBlob().copy(rev = revisions.current(repository.booksDirectory, key).localRev)
            val localEntry = HttpSyncBookmarkMapEntry("sha256:${digest(json.encodeToString(localBlob).toByteArray())}", localBlob.lastModified, localBlob)
            val remoteEntry = remote?.let { HttpSyncBookmarkMapEntry(index.applied[key].orEmpty(), it.lastModified, it) }
            if ((meta != null || hasNavigation) && localBlob != remote &&
                (remoteEntry == null || compareBookmarkEntries(localEntry, remoteEntry) > 0)) {
                attempt(meta, key) {
                    val value = localBlob
                    val response = transport.put(key, "application/json", json.encodeToString(value).toByteArray())
                    index = index.copy(applied = index.applied + (key to response.etag), legacy = index.legacy + (key to value))
                }
            }
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
}
