package moe.antimony.hoshi.features.sync.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.bookContentType
import moe.antimony.hoshi.epub.STATISTICS_SYNC_STATE_FILE_NAME
import moe.antimony.hoshi.epub.dayDeviceKey
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import moe.antimony.hoshi.mokuro.deduplicateMangaTextStatistics
import java.io.File
import java.security.MessageDigest

/** `books/{syncId}/statistics`: the shared per-day reading statistics (`statistics.json`). */
internal fun statisticsKey(syncId: String): String = "books/$syncId/statistics"

/** `books/{syncId}/manga_statistics`: per-day OCR characters read for a manga (`manga_statistics.json`). */
internal fun mangaStatisticsKey(syncId: String): String = "books/$syncId/manga_statistics"

/** A day is ~250 bytes, so this allows tens of years of daily reading before a blob is refused. */
internal const val MAX_STATISTICS_BLOB_BYTES: Int = 4 * 1024 * 1024

/**
 * Per-book, per-device record of the last statistics exchange, so a converged book costs no
 * request on later syncs. Excluded from the payload like every other per-device sidecar.
 */
internal const val STATISTICS_SYNC_STATE_FILENAME: String = STATISTICS_SYNC_STATE_FILE_NAME

@Serializable
data class HttpSyncStatisticsBlob(
    val version: Int = 2,
    val syncId: String = "",
    val entries: List<ReadingStatistics> = emptyList(),
) {
    companion object {
        const val SUPPORTED_VERSION: Int = 2
    }
}

@Serializable
data class HttpSyncMangaStatisticsBlob(
    val version: Int = 2,
    val syncId: String = "",
    val entries: List<MangaTextStatistic> = emptyList(),
) {
    companion object {
        const val SUPPORTED_VERSION: Int = 2
    }
}

enum class StatisticsSyncKind {
    Reading,
    MangaText;

    fun key(syncId: String): String = when (this) {
        Reading -> statisticsKey(syncId)
        MangaText -> mangaStatisticsKey(syncId)
    }
}

/** What the caller knows about the remote key before the exchange. */
sealed interface StatisticsRemoteListing {
    /** The key was in the listing with this body size and (when the listing carries it) modification stamp. */
    data class Listed(
        val size: Int,
        val lastModified: String? = null,
        val remoteEtag: String? = null,
    ) : StatisticsRemoteListing

    /** The listing did not contain the key. */
    data object Absent : StatisticsRemoteListing

    /** No listing was taken (the reader's push path); the key is fetched if local data changed. */
    data object Unknown : StatisticsRemoteListing
}

data class StatisticsSyncOutcome(val downloaded: Boolean, val uploaded: Boolean) {
    companion object {
        val NONE = StatisticsSyncOutcome(downloaded = false, uploaded = false)
    }
}

@Serializable
internal data class StatisticsSyncStateEntry(
    val localSha256: String,
    val remoteSize: Int,
    /** The server's stamp for the body we last read or wrote; a same-size edit elsewhere changes it. */
    val remoteLastModified: String? = null,
    /** Content identity also distinguishes same-size writes within one server timestamp tick. */
    val remoteEtag: String? = null,
)

@Serializable
internal data class StatisticsSyncState(
    val reading: StatisticsSyncStateEntry? = null,
    val mangaText: StatisticsSyncStateEntry? = null,
)

/**
 * Two-way merge of a book's statistics with the server, per day and device: the union of
 * (day, device) entries, and for one both sides know, the entry with the newest modification
 * stamp. That is the same rule the sidecars apply on every write, so the exchange is idempotent
 * and converges from any interleaving without revision counters. Entries without a device
 * (older clients) form their own bucket and are never re-attributed by the exchange.
 *
 * The exchange is `read remote -> merge -> save locally if new -> PUT if the server lacks
 * something`. A per-book state file remembers the sha of the local body and the size of the
 * remote body after the last exchange; when neither changed, the book costs no request, which
 * keeps a converged library at exactly one listing per sync.
 */
class HttpSyncStatisticsSync(
    private val bookRepository: BookRepository,
    private val bookLocks: HttpSyncBookLocks = HttpSyncBookLocks(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Local statistics are outside the book payload/map hash. A missed reader push must
     * still make a manual/background sync run the full exchange even if the server has not
     * changed. Compare the same canonical body as [sync], without making a network request.
     * Missing exchange state for a listed remote key and a missing sidecar with earlier
     * exchange state also need reconciliation: the full exchange can restore those days
     * from the server instead of calling an empty book synced.
     */
    suspend fun hasLocalChanges(
        bookRoot: File,
        syncId: String,
        remoteReadingPresent: Boolean = false,
        remoteMangaPresent: Boolean = false,
    ): Boolean {
        if (!bookRoot.isDirectory) return false
        val readingChanged = bookLocks.withKeyLock(statisticsKey(syncId)) {
            val entries = bookRepository.loadStatistics(bookRoot)
                .deduplicateReadingStatistics().sortedBy { dayDeviceKey(it.dateKey, it.deviceId) }
            val previous = loadState(bookRoot).reading
            if (previous == null) entries.isNotEmpty() || remoteReadingPresent
            else (remoteReadingPresent && previous.remoteEtag == null) ||
                previous.localSha256 != sha256(readingBody(syncId, entries))
        }
        if (readingChanged || bookContentType(bookRoot) != ContentType.Mokuro) return readingChanged
        return bookLocks.withKeyLock(mangaStatisticsKey(syncId)) {
            val entries = bookRepository.loadMangaTextStatistics(bookRoot)
                .deduplicateMangaTextStatistics().sortedBy { dayDeviceKey(it.dateKey, it.deviceId) }
            val previous = loadState(bookRoot).mangaText
            if (previous == null) entries.isNotEmpty() || remoteMangaPresent
            else (remoteMangaPresent && previous.remoteEtag == null) ||
                previous.localSha256 != sha256(mangaBody(syncId, entries))
        }
    }

    suspend fun sync(
        transport: HttpSyncKvTransport,
        bookRoot: File,
        syncId: String,
        kind: StatisticsSyncKind,
        remote: StatisticsRemoteListing,
    ): StatisticsSyncOutcome {
        if (!bookRoot.isDirectory) return StatisticsSyncOutcome.NONE
        if (kind == StatisticsSyncKind.MangaText && bookContentType(bookRoot) != ContentType.Mokuro) {
            return StatisticsSyncOutcome.NONE
        }
        val key = kind.key(syncId)
        return bookLocks.withKeyLock(key) {
            when (kind) {
                StatisticsSyncKind.Reading -> exchange(
                    transport = transport,
                    bookRoot = bookRoot,
                    key = key,
                    remote = remote,
                    loadLocal = { bookRepository.loadStatistics(bookRoot) },
                    saveLocal = { bookRepository.saveStatistics(bookRoot, it) },
                    merge = { it.deduplicateReadingStatistics() },
                    entryKey = { dayDeviceKey(it.dateKey, it.deviceId) },
                    stamp = { it.lastStatisticModified },
                    encode = { entries -> readingBody(syncId, entries) },
                    decode = { body ->
                        val blob = json.decodeFromString(HttpSyncStatisticsBlob.serializer(), body)
                        if (blob.version > HttpSyncStatisticsBlob.SUPPORTED_VERSION) {
                            throw HttpSyncException("Statistics at $key: unsupported version ${blob.version}.")
                        }
                        blob.entries
                    },
                    readState = { it.reading },
                    writeState = { state, entry -> state.copy(reading = entry) },
                )
                StatisticsSyncKind.MangaText -> exchange(
                    transport = transport,
                    bookRoot = bookRoot,
                    key = key,
                    remote = remote,
                    loadLocal = { bookRepository.loadMangaTextStatistics(bookRoot) },
                    saveLocal = { bookRepository.saveMangaTextStatistics(bookRoot, it) },
                    merge = { it.deduplicateMangaTextStatistics() },
                    entryKey = { dayDeviceKey(it.dateKey, it.deviceId) },
                    stamp = { it.lastModified },
                    encode = { entries -> mangaBody(syncId, entries) },
                    decode = { body ->
                        val blob = json.decodeFromString(HttpSyncMangaStatisticsBlob.serializer(), body)
                        if (blob.version > HttpSyncMangaStatisticsBlob.SUPPORTED_VERSION) {
                            throw HttpSyncException("Manga statistics at $key: unsupported version ${blob.version}.")
                        }
                        blob.entries
                    },
                    readState = { it.mangaText },
                    writeState = { state, entry -> state.copy(mangaText = entry) },
                )
            }
        }
    }

    private fun readingBody(syncId: String, entries: List<ReadingStatistics>): String =
        json.encodeToString(HttpSyncStatisticsBlob.serializer(), HttpSyncStatisticsBlob(syncId = syncId, entries = entries))

    private fun mangaBody(syncId: String, entries: List<MangaTextStatistic>): String =
        json.encodeToString(HttpSyncMangaStatisticsBlob.serializer(), HttpSyncMangaStatisticsBlob(syncId = syncId, entries = entries))

    private suspend fun <T> exchange(
        transport: HttpSyncKvTransport,
        bookRoot: File,
        key: String,
        remote: StatisticsRemoteListing,
        loadLocal: suspend () -> List<T>,
        saveLocal: suspend (List<T>) -> Unit,
        merge: (List<T>) -> List<T>,
        /** Sort key: day plus device, so bodies are byte-identical on every device. */
        entryKey: (T) -> String,
        stamp: (T) -> Long,
        encode: (List<T>) -> String,
        decode: (String) -> List<T>,
        readState: (StatisticsSyncState) -> StatisticsSyncStateEntry?,
        writeState: (StatisticsSyncState, StatisticsSyncStateEntry) -> StatisticsSyncState,
    ): StatisticsSyncOutcome {
        val local = merge(loadLocal()).sortedBy(entryKey)
        val localBody = encode(local)
        val localSha = sha256(localBody)
        val state = loadState(bookRoot)
        val last = readState(state)
        val unchangedLocally = last != null && last.localSha256 == localSha
        when (remote) {
            is StatisticsRemoteListing.Listed -> {
                val sameStamp = remote.lastModified == null || last?.remoteLastModified == remote.lastModified
                // Older state files have no ETag: revalidate the applied body even if its
                // size and timestamp happen to match. The content proof below can avoid a GET.
                val sameEtag = remote.remoteEtag == null || last?.remoteEtag == remote.remoteEtag
                if (unchangedLocally && last.remoteSize == remote.size && sameStamp && sameEtag) return StatisticsSyncOutcome.NONE
                // A content-addressed ETag can prove the canonical local bytes already match
                // the server. Upgrade the validator without downloading unchanged histories;
                // opaque ETags and changed bodies still take the normal GET + merge path.
                if (remote.remoteEtag == "sha256:$localSha" &&
                    remote.size == localBody.toByteArray(Charsets.UTF_8).size
                ) {
                    saveState(bookRoot, writeState(state, StatisticsSyncStateEntry(
                        localSha256 = localSha, remoteSize = remote.size,
                        remoteLastModified = remote.lastModified, remoteEtag = remote.remoteEtag,
                    )))
                    return StatisticsSyncOutcome.NONE
                }
            }
            // Nothing local and never exchanged: there is nothing to push, and a reconcile with a
            // listing pulls remote days, so the reader path must not fetch on every save.
            StatisticsRemoteListing.Unknown -> if (unchangedLocally || (last == null && local.isEmpty())) return StatisticsSyncOutcome.NONE
            // Absence is a listing snapshot, not a write lock. Another device can create
            // this key before our upload, so nonempty local data still gets a fresh merge.
            StatisticsRemoteListing.Absent -> if (local.isEmpty()) return StatisticsSyncOutcome.NONE
        }
        val fetched = transport.getBounded(key, MAX_STATISTICS_BLOB_BYTES)
            ?: return uploadWhole(transport, bookRoot, key, local, localBody, localSha, state, writeState)
        val remoteEntries = try {
            merge(decode(fetched.body.toString(Charsets.UTF_8))).sortedBy(entryKey)
        } catch (error: HttpSyncException) {
            throw error
        } catch (error: Exception) {
            throw HttpSyncException("Statistics at $key: malformed JSON (${error.message ?: error.javaClass.simpleName})")
        }
        // The dedupe keeps the first entry it sees for a day when stamps tie, so order the
        // candidates the same way on every device: newest stamp first, then by content.
        val candidates = (local + remoteEntries).sortedWith(compareByDescending<T> { stamp(it) }.thenByDescending { it.toString() })
        val merged = merge(candidates).sortedBy(entryKey)
        val downloaded = merged != local
        if (downloaded) {
            saveLocal(merged)
        }
        val uploaded = merged != remoteEntries
        val mergedBody = encode(merged)
        val remoteSize: Int
        val remoteStamp: String?
        val remoteEtag: String?
        if (uploaded) {
            val bytes = mergedBody.toByteArray(Charsets.UTF_8)
            val written = transport.put(key = key, contentType = JSON_CONTENT_TYPE, body = bytes)
            remoteStamp = written.lastModified
            remoteEtag = written.etag
            remoteSize = bytes.size
        } else {
            remoteSize = fetched.body.size
            remoteStamp = fetched.lastModified
            remoteEtag = fetched.etag
        }
        saveState(
            bookRoot,
            writeState(state, StatisticsSyncStateEntry(
                localSha256 = sha256(mergedBody), remoteSize = remoteSize,
                remoteLastModified = remoteStamp, remoteEtag = remoteEtag,
            )),
        )
        return StatisticsSyncOutcome(downloaded = downloaded, uploaded = uploaded)
    }

    private suspend fun <T> uploadWhole(
        transport: HttpSyncKvTransport,
        bookRoot: File,
        key: String,
        local: List<T>,
        localBody: String,
        localSha: String,
        state: StatisticsSyncState,
        writeState: (StatisticsSyncState, StatisticsSyncStateEntry) -> StatisticsSyncState,
    ): StatisticsSyncOutcome {
        if (local.isEmpty()) return StatisticsSyncOutcome.NONE
        val bytes = localBody.toByteArray(Charsets.UTF_8)
        val written = transport.put(key = key, contentType = JSON_CONTENT_TYPE, body = bytes)
        saveState(
            bookRoot,
            writeState(state, StatisticsSyncStateEntry(
                localSha256 = localSha, remoteSize = bytes.size,
                remoteLastModified = written.lastModified, remoteEtag = written.etag,
            )),
        )
        return StatisticsSyncOutcome(downloaded = false, uploaded = true)
    }

    private fun loadState(bookRoot: File): StatisticsSyncState {
        val file = File(bookRoot, STATISTICS_SYNC_STATE_FILENAME)
        if (!file.isFile) return StatisticsSyncState()
        return runCatching { json.decodeFromString(StatisticsSyncState.serializer(), file.readText()) }
            .getOrDefault(StatisticsSyncState())
    }

    private fun saveState(bookRoot: File, state: StatisticsSyncState) {
        if (!bookRoot.isDirectory) return
        val target = File(bookRoot, STATISTICS_SYNC_STATE_FILENAME)
        val temp = File(bookRoot, "$STATISTICS_SYNC_STATE_FILENAME.${System.nanoTime()}.tmp")
        temp.writeText(json.encodeToString(StatisticsSyncState.serializer(), state))
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
            }
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
    }
}
