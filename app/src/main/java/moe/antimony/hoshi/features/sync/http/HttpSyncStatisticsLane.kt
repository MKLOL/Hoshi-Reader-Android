package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.StatisticsHistoryEntry
import moe.antimony.hoshi.epub.bookContentType
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.epub.readStatisticsHistoryInfo
import moe.antimony.hoshi.epub.statisticsContentType
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import moe.antimony.hoshi.mokuro.deduplicateMangaTextStatistics
import moe.antimony.hoshi.storage.writeSidecarAtomically
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-device statistics live under the map prefix. Every installed build (0.11.15+) skips
 * `sync/maps/` keys it does not know when it looks for changes, so these keys never make an
 * older device run a full reconcile.
 */
internal const val STATISTICS_SHARD_PREFIX: String = "${SYNC_MAP_PREFIX}stats/"
private const val PREFERENCES_FILE_NAME = "settings.json"
private val SHARD_MONTH = Regex("^\\d{4}-\\d{2}$")
private val DATE_KEY = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/** `sync/maps/stats/{deviceId}/{yyyy-MM}.json`: one device's own statistics for one month. */
internal fun statisticsShardKey(deviceId: String, month: String): String =
    "$STATISTICS_SHARD_PREFIX$deviceId/$month.json"

/** `sync/maps/stats/{deviceId}/settings.json`: the streak goal and day reset that device last chose. */
internal fun statisticsPreferencesKey(deviceId: String): String =
    "$STATISTICS_SHARD_PREFIX$deviceId/$PREFERENCES_FILE_NAME"

internal sealed interface StatisticsLaneKey {
    val deviceId: String

    data class Shard(override val deviceId: String, val month: String) : StatisticsLaneKey
    data class Preferences(override val deviceId: String) : StatisticsLaneKey
}

internal fun parseStatisticsLaneKey(key: String): StatisticsLaneKey? {
    if (!key.startsWith(STATISTICS_SHARD_PREFIX)) return null
    val parts = key.removePrefix(STATISTICS_SHARD_PREFIX).split('/')
    if (parts.size != 2 || !isUsableDeviceId(parts[0])) return null
    val (deviceId, file) = parts
    if (file == PREFERENCES_FILE_NAME) return StatisticsLaneKey.Preferences(deviceId)
    val month = file.removeSuffix(".json").takeIf { file.endsWith(".json") && SHARD_MONTH.matches(it) }
        ?: return null
    return StatisticsLaneKey.Shard(deviceId, month)
}

private fun isUsableDeviceId(deviceId: String): Boolean = isValidSyncId(deviceId) && deviceId.trim('.').isNotEmpty()

/**
 * One device's own reading for one month, every book. Only that device ever writes the key, so
 * a plain PUT can never drop another device's days — the generic KV store has no
 * compare-and-swap, and this is how statistics stay correct without one.
 */
@Serializable
data class HttpSyncStatisticsShard(
    val version: Int = SUPPORTED_VERSION,
    val deviceId: String = "",
    val month: String = "",
    /** By sync id, so a day survives the book being renamed, re-imported, deleted or never downloaded. */
    val books: Map<String, HttpSyncStatisticsShardBook> = emptyMap(),
) {
    companion object {
        const val SUPPORTED_VERSION: Int = 1
    }
}

@Serializable
data class HttpSyncStatisticsShardBook(
    val title: String? = null,
    val contentType: ContentType? = null,
    val reading: List<ReadingStatistics> = emptyList(),
    val mangaText: List<MangaTextStatistic> = emptyList(),
)

/** The streak goal and day reset a device chose; the newest choice of any device applies everywhere. */
@Serializable
data class HttpSyncStatisticsPreferences(
    val version: Int = 1,
    val streakMinimumMinutes: Int,
    val dayResetHour: Int,
    /** Epoch milliseconds of the choice; 1 marks a value chosen before choices were synced. */
    val updatedAt: Long,
)

/** The statistics settings as the app stores them, with when they were last chosen. */
data class StatisticsPreferences(
    val streakMinimumMinutes: Int,
    val dayResetHour: Int,
    /** Epoch milliseconds of the user's last change; 0 when never changed. */
    val updatedAt: Long,
)

/** Where [HttpSyncStatisticsLane] reads and applies the synced statistics settings. */
interface StatisticsPreferencesStore {
    suspend fun load(): StatisticsPreferences
    suspend fun save(preferences: StatisticsPreferences)
}

/** What the Statistics screen tells the reader about how current other devices' reading is. */
data class StatisticsSyncStatus(
    /** Epoch milliseconds of the last pass that checked every device's statistics without error. */
    val lastSuccessAtMillis: Long? = null,
    val lastError: String? = null,
)

data class StatisticsLaneResult(
    /** Books whose statistics here gained days from other devices. */
    val downloadedBooks: Int = 0,
    /** Books whose statistics this device sent up. */
    val uploadedBooks: Int = 0,
    val errors: List<String> = emptyList(),
) {
    companion object {
        val NONE = StatisticsLaneResult()
    }
}

/**
 * The statistics sync lane: keeps every device's reading history identical, independently of
 * which books each device has installed and of book downloads.
 *
 * Two channels carry the same per-day, per-device entries:
 *  - **Per-device monthly shards** (`sync/maps/stats/{deviceId}/{yyyy-MM}.json`), written only
 *    by their own device: no update is ever lost, a changed month costs one PUT, and other
 *    devices GET only shards whose ETag changed.
 *  - **The per-book keys** (`books/{syncId}/statistics`, `…/manga_statistics`) that every
 *    released build reads and writes. They keep older installs working in both directions, so
 *    devices can be updated one at a time.
 *
 * Every key is acknowledged on its own, only after its content was merged (per-root exchange
 * state for book keys, [LaneIndex] for shards), never through a global "seen" snapshot.
 * Statistics of a book without a folder here go to its history folder
 * ([BookRepository.statisticsHistoryRoot]), so Today, the streak and every total count all
 * reading, whichever device it happened on. Callers serialize runs (the batch exchange mutex).
 */
class HttpSyncStatisticsLane(
    private val bookRepository: BookRepository,
    bookLocks: HttpSyncBookLocks,
    private val preferencesStore: StatisticsPreferencesStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val localPushIntervalMs: Long = LOCAL_PUSH_INTERVAL_MS,
) {
    private val statisticsSync = HttpSyncStatisticsSync(bookRepository, bookLocks)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val indexFile: File get() = bookRepository.booksDirectory.resolve(INDEX_FILE_NAME)
    private val _status = MutableStateFlow(StatisticsSyncStatus())
    val status: StateFlow<StatisticsSyncStatus> = _status.asStateFlow()

    /** Last time this device sent local days up; batches them while someone keeps reading. */
    @Volatile private var lastLocalPushAtMillis = 0L

    private val failures = ConcurrentHashMap<String, Failure>()
    private val ownEntriesCache = mutableMapOf<String, OwnEntriesSnapshot>()

    private data class Failure(val etag: String?, val retryAtMillis: Long)

    @Serializable
    private data class LaneIndex(
        val scope: String = "",
        /** Other devices' shard and settings keys -> the ETag whose content was merged here. */
        val applied: Map<String, String> = emptyMap(),
        /** This device's keys -> the ETag it wrote or confirmed. */
        val published: Map<String, String> = emptyMap(),
        /** Settings keys -> the value read or written, so an unchanged key is never fetched again. */
        val preferences: Map<String, HttpSyncStatisticsPreferences> = emptyMap(),
        /** Set after the first complete pass; each failed key retries on its own afterwards. */
        val bootstrapped: Boolean = false,
        /** Own months a pass could not publish yet (batched or failed); kept across restarts. */
        val ownPending: Boolean = false,
    )

    private data class StatisticsRoot(
        val syncId: String,
        val root: File,
        val title: String?,
        val contentType: ContentType,
    )

    private data class LegacyKeys(
        val reading: HttpSyncKvKeyMeta? = null,
        val manga: HttpSyncKvKeyMeta? = null,
        val metadata: HttpSyncKvKeyMeta? = null,
    )

    private data class OwnEntriesSnapshot(
        val stamp: List<Long>,
        val reading: List<ReadingStatistics>,
        val mangaText: List<MangaTextStatistic>,
    )

    /**
     * Whether a [run] has work: a key whose ETag this device has not merged, local days not yet
     * sent (once the batching interval has passed), a book without a history folder, or the
     * first pass. Reads only local files the validation cache has not already vouched for.
     */
    suspend fun needsRun(
        transport: HttpSyncKvTransport,
        listing: List<HttpSyncKvKeyMeta>,
        /** The library as the caller just read it, to avoid reading it twice per poll. */
        entries: List<BookEntry>? = null,
    ): Boolean {
        val index = loadIndex(transport)
        if (!index.bootstrapped) return true
        val deviceId = ownDeviceId()
        val legacy = legacyKeys(listing)
        val roots = statisticsRoots(entries ?: bookRepository.loadBookEntries(), bookRepository.loadStatisticsHistory())
        if (roots.absorb.isNotEmpty()) return true
        val localDue = flushDue()
        for (root in roots.all) {
            val remote = legacy[root.syncId]
            when (statisticsSync.pendingExchange(root.root, root.syncId, remote?.reading?.etag, remote?.manga?.etag)) {
                StatisticsExchangeNeed.Remote ->
                    if (listOfNotNull(remote?.reading, remote?.manga).any { !blocked(it) }) return true
                StatisticsExchangeNeed.Local -> if (localDue) return true
                StatisticsExchangeNeed.None -> Unit
            }
        }
        val rooted = roots.all.mapTo(mutableSetOf()) { it.syncId }
        if (legacy.any { (syncId, keys) -> syncId !in rooted && (keys.reading ?: keys.manga) != null && !blocked(keys.reading ?: keys.manga) }) {
            return true
        }
        if (index.ownPending && localDue) return true
        for (meta in listing) {
            val key = parseStatisticsLaneKey(meta.key) ?: continue
            val known = if (key.deviceId == deviceId) index.published[meta.key] else index.applied[meta.key]
            if (known != meta.etag && !blocked(meta)) return true
        }
        val listed = listing.mapTo(mutableSetOf()) { it.key }
        if (index.published.keys.any { it !in listed }) return true
        return preferencesDirty(index, deviceId)
    }

    /**
     * Brings this device's statistics and every other device's in line. [flush] sends local
     * days at once instead of batching them (the reader was just left, a manual sync, the
     * Statistics screen opened).
     */
    suspend fun run(
        transport: HttpSyncKvTransport,
        listing: List<HttpSyncKvKeyMeta>,
        flush: Boolean = false,
    ): StatisticsLaneResult {
        val deviceId = ownDeviceId()
        var index = loadIndex(transport)
        val errors = mutableListOf<String>()
        val downloaded = mutableSetOf<String>()
        val uploaded = mutableSetOf<String>()
        val localDue = flush || !index.bootstrapped || flushDue()
        var pushedLocal = false

        val entries = bookRepository.loadBookEntries()
        var roots = statisticsRoots(entries, bookRepository.loadStatisticsHistory())
        // A book installed again takes back the history kept while it was gone.
        for ((history, root) in roots.absorb) {
            try {
                bookRepository.absorbStatisticsHistory(history, root)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errors += "statistics ${history.syncId}: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        if (roots.absorb.isNotEmpty()) {
            roots = statisticsRoots(entries, bookRepository.loadStatisticsHistory())
        }
        val rootsBySyncId = roots.all.associateBy { it.syncId }.toMutableMap()

        suspend fun rootFor(syncId: String, title: String?, contentType: ContentType?): StatisticsRoot? {
            rootsBySyncId[syncId]?.let { return it }
            val created = bookRepository.statisticsHistoryRoot(syncId, title, contentType) ?: return null
            return StatisticsRoot(syncId, created, title, statisticsContentType(created)).also { rootsBySyncId[syncId] = it }
        }

        // 1. Other devices' shards (and this device's own, if a reinstall or restore left the
        //    server with days this install lost): merge what changed into the books' statistics.
        for (meta in listing) {
            val key = parseStatisticsLaneKey(meta.key) as? StatisticsLaneKey.Shard ?: continue
            val own = key.deviceId == deviceId
            val known = if (own) index.published[meta.key] else index.applied[meta.key]
            if (known == meta.etag || (!flush && blocked(meta))) continue
            try {
                if (meta.size > MAX_STATISTICS_BLOB_BYTES) {
                    throw HttpSyncException("Statistics at ${meta.key} exceed the $MAX_STATISTICS_BLOB_BYTES-byte limit.")
                }
                val fetched = transport.getBounded(meta.key, MAX_STATISTICS_BLOB_BYTES) ?: continue
                val shard = decodeShard(meta.key, key, fetched.body)
                for ((syncId, book) in shard.books.toSortedMap()) {
                    if (!isValidSyncId(syncId)) continue
                    // A shard speaks only for its own device and month.
                    val reading = book.reading.filter { it.deviceId == key.deviceId && it.dateKey.startsWith(key.month) }
                    val mangaText = book.mangaText.filter { it.deviceId == key.deviceId && it.dateKey.startsWith(key.month) }
                    if (reading.isEmpty() && mangaText.isEmpty()) continue
                    val root = rootFor(syncId, book.title ?: reading.firstOrNull()?.title, book.contentType) ?: continue
                    if (merge(root, reading, mangaText)) downloaded += syncId
                }
                index = if (own) {
                    index.copy(published = index.published + (meta.key to fetched.etag))
                } else {
                    index.copy(applied = index.applied + (meta.key to fetched.etag))
                }
                failures.remove(meta.key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(meta.key, meta.etag, errors, error)
            }
        }

        // 2. The per-book keys of released builds: every kept history, plus books only the
        //    server knows about (not downloaded here, or deleted before history was kept).
        val legacy = legacyKeys(listing)
        val syncIds = (rootsBySyncId.keys + legacy.filterValues { (it.reading ?: it.manga) != null }.keys).toSortedSet()
        for (syncId in syncIds) {
            val remote = legacy[syncId]
            try {
                val existing = rootsBySyncId[syncId]
                if (existing == null && !flush && blocked(remote?.reading ?: remote?.manga)) continue
                val root = existing ?: describeRemoteBook(transport, syncId, remote).let { (title, type) ->
                    rootFor(syncId, title, type)
                } ?: continue
                val need = statisticsSync.pendingExchange(root.root, syncId, remote?.reading?.etag, remote?.manga?.etag)
                if (need == StatisticsExchangeNeed.None) continue
                if (need == StatisticsExchangeNeed.Local && !localDue) continue
                val kinds = if (root.contentType == ContentType.Mokuro) StatisticsSyncKind.entries else listOf(StatisticsSyncKind.Reading)
                for (kind in kinds) {
                    val meta = if (kind == StatisticsSyncKind.Reading) remote?.reading else remote?.manga
                    if (meta != null && !flush && need == StatisticsExchangeNeed.Remote && blocked(meta)) continue
                    try {
                        if (meta != null && meta.size > MAX_STATISTICS_BLOB_BYTES) {
                            throw HttpSyncException("Statistics at ${meta.key} exceed the $MAX_STATISTICS_BLOB_BYTES-byte limit.")
                        }
                        val listed = meta?.let { StatisticsRemoteListing.Listed(it.size, it.lastModified, it.etag) }
                            ?: StatisticsRemoteListing.Absent
                        val outcome = statisticsSync.sync(transport, root.root, syncId, kind, listed)
                        if (outcome.downloaded) downloaded += syncId
                        if (outcome.uploaded) uploaded += syncId
                        meta?.let { failures.remove(it.key) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        fail(meta?.key ?: kind.key(syncId), meta?.etag, errors, error)
                    }
                }
                if (need == StatisticsExchangeNeed.Local) pushedLocal = true
                if (existing == null || existing.title == null) nameFromEntries(root)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(remote?.reading?.key ?: statisticsKey(syncId), remote?.reading?.etag, errors, error)
            }
        }

        // 3. This device's own shards, rebuilt from every book and history folder it keeps.
        if (deviceId != null) {
            val listed = listing.associateBy { it.key }
            val months = ownMonths(deviceId, rootsBySyncId.values.toList())
            var skipped = false
            for ((month, body) in months) {
                val key = statisticsShardKey(deviceId, month)
                val etag = "sha256:" + sha256(body)
                if (listed[key]?.etag == etag) {
                    if (index.published[key] != etag) index = index.copy(published = index.published + (key to etag))
                    continue
                }
                if (!localDue) {
                    skipped = true
                    continue
                }
                try {
                    val written = transport.put(key, JSON_CONTENT_TYPE, body)
                    index = index.copy(published = index.published + (key to written.etag))
                    pushedLocal = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    fail(key, null, errors, error)
                    skipped = true
                }
            }
            index = index.copy(ownPending = skipped)
        }

        // 4. The streak goal and day reset: the newest choice of any device applies everywhere.
        if (deviceId != null && preferencesStore != null) {
            index = syncPreferences(transport, listing, index, deviceId, errors)
        }

        if (pushedLocal) lastLocalPushAtMillis = now()
        val cleanPass = errors.isEmpty()
        index = index.copy(bootstrapped = true)
        saveIndex(index)
        _status.value = if (cleanPass) {
            StatisticsSyncStatus(lastSuccessAtMillis = now())
        } else {
            _status.value.copy(lastError = errors.first())
        }
        return StatisticsLaneResult(downloaded.size, uploaded.size, errors)
    }

    /** A listing pass with nothing to do still proves this device is current. */
    fun markChecked() {
        _status.value = StatisticsSyncStatus(lastSuccessAtMillis = now())
    }

    /** The listing could not be read, so nothing about other devices is known. */
    fun markFailed(error: Throwable) {
        _status.value = _status.value.copy(lastError = error.message ?: error.javaClass.simpleName)
    }

    private fun flushDue(): Boolean = now() - lastLocalPushAtMillis >= localPushIntervalMs

    private fun blocked(meta: HttpSyncKvKeyMeta?): Boolean {
        meta ?: return false
        val failure = failures[meta.key] ?: return false
        return failure.etag == meta.etag && now() < failure.retryAtMillis
    }

    /** A failing key is retried when it changes, or after a pause; it never fails every poll. */
    private fun fail(key: String, etag: String?, errors: MutableList<String>, error: Exception) {
        failures[key] = Failure(etag, now() + FAILURE_RETRY_MS)
        errors += "$key: ${error.message ?: error.javaClass.simpleName}"
    }

    private fun ownDeviceId(): String? = bookRepository.statisticsDevice?.id?.takeIf(::isUsableDeviceId)

    private data class Roots(
        val all: List<StatisticsRoot>,
        /** History folders of books installed again, with the book they belong to. */
        val absorb: List<Pair<StatisticsHistoryEntry, File>>,
    )

    private fun statisticsRoots(entries: List<BookEntry>, history: List<StatisticsHistoryEntry>): Roots {
        val installed = linkedMapOf<String, StatisticsRoot>()
        for (entry in entries) {
            val syncId = syncIdForMetadata(entry.metadata) ?: continue
            installed.putIfAbsent(
                syncId,
                StatisticsRoot(syncId, entry.root, entry.displayTitle.ifBlank { null }, bookContentType(entry.root)),
            )
        }
        val absorb = history.mapNotNull { kept -> installed[kept.syncId]?.let { kept to it.root } }
        val kept = history.filter { it.syncId !in installed }
            .map { StatisticsRoot(it.syncId, it.root, it.title.takeIf { title -> title != it.syncId }, it.contentType) }
        return Roots(installed.values.toList() + kept, absorb)
    }

    private fun legacyKeys(listing: List<HttpSyncKvKeyMeta>): Map<String, LegacyKeys> {
        val keys = mutableMapOf<String, LegacyKeys>()
        for (meta in listing) {
            val parts = meta.key.split('/')
            if (parts.size != 3 || parts[0] != "books" || !isValidSyncId(parts[1])) continue
            val current = keys[parts[1]] ?: LegacyKeys()
            keys[parts[1]] = when (parts[2]) {
                "statistics" -> current.copy(reading = meta)
                "manga_statistics" -> current.copy(manga = meta)
                "metadata" -> current.copy(metadata = meta)
                else -> continue
            }
        }
        return keys
    }

    /** Title and type of a book known only from the server, from its metadata when listed. */
    private suspend fun describeRemoteBook(
        transport: HttpSyncKvTransport,
        syncId: String,
        remote: LegacyKeys?,
    ): Pair<String?, ContentType?> {
        val guessedType = if (remote?.manga != null) ContentType.Mokuro else null
        val metadata = remote?.metadata ?: return null to guessedType
        val blob = try {
            transport.getBounded(metadata.key, MAX_METADATA_BYTES)?.let {
                json.decodeFromString(HttpSyncMetadataBlob.serializer(), it.body.toString(Charsets.UTF_8))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return blob?.title?.takeIf { it.isNotBlank() } to (blob?.contentType?.toLocal() ?: guessedType)
    }

    /** A history folder that only knew its sync id takes the title its days carry. */
    private suspend fun nameFromEntries(root: StatisticsRoot) {
        if (root.title != null || readTitle(root.root) != null) return
        val title = bookRepository.loadStatistics(root.root).firstOrNull { it.title.isNotBlank() }?.title ?: return
        bookRepository.statisticsHistoryRoot(root.syncId, title, null)
    }

    private fun readTitle(root: File): String? = readStatisticsHistoryInfo(root)?.title?.takeIf { it.isNotBlank() }

    /** Merges another device's days into [root]; true when anything there changed. */
    private suspend fun merge(root: StatisticsRoot, reading: List<ReadingStatistics>, mangaText: List<MangaTextStatistic>): Boolean {
        var changed = false
        if (reading.isNotEmpty()) {
            val current = bookRepository.loadStatistics(root.root)
            if ((reading + current).deduplicateReadingStatistics().toSet() != current.toSet()) {
                bookRepository.saveStatistics(root.root, reading)
                changed = true
            }
        }
        if (mangaText.isNotEmpty()) {
            val current = bookRepository.loadMangaTextStatistics(root.root)
            if ((mangaText + current).deduplicateMangaTextStatistics().toSet() != current.toSet()) {
                bookRepository.saveMangaTextStatistics(root.root, mangaText)
                changed = true
            }
        }
        return changed
    }

    /** This device's own days in every book it keeps, as one canonical shard body per month. */
    private suspend fun ownMonths(deviceId: String, roots: List<StatisticsRoot>): Map<String, ByteArray> {
        val books = sortedMapOf<String, MutableMap<String, HttpSyncStatisticsShardBook>>()
        val live = mutableSetOf<String>()
        for (root in roots) {
            val path = root.root.absolutePath
            live += path
            val own = ownEntries(root, deviceId)
            val readingByMonth = own.reading.groupBy { it.dateKey.take(7) }
            val mangaByMonth = own.mangaText.groupBy { it.dateKey.take(7) }
            for (month in (readingByMonth.keys + mangaByMonth.keys)) {
                books.getOrPut(month) { sortedMapOf() }[root.syncId] = HttpSyncStatisticsShardBook(
                    title = root.title ?: readingByMonth[month]?.firstOrNull()?.title,
                    contentType = root.contentType,
                    reading = readingByMonth[month].orEmpty().sortedBy { it.dateKey },
                    mangaText = mangaByMonth[month].orEmpty().sortedBy { it.dateKey },
                )
            }
        }
        ownEntriesCache.keys.retainAll(live)
        return books.mapValues { (month, byBook) ->
            json.encodeToString(
                HttpSyncStatisticsShard.serializer(),
                HttpSyncStatisticsShard(deviceId = deviceId, month = month, books = byBook.toSortedMap()),
            ).toByteArray(Charsets.UTF_8)
        }
    }

    private suspend fun ownEntries(root: StatisticsRoot, deviceId: String): OwnEntriesSnapshot {
        val files = listOf("statistics.json", "manga_statistics.json").map { root.root.resolve(it) }
        val stamp = files.flatMap { listOf(it.lastModified(), it.length()) }
        val path = root.root.absolutePath
        ownEntriesCache[path]?.takeIf { it.stamp == stamp }?.let { return it }
        val reading = bookRepository.loadStatistics(root.root)
            .filter { it.deviceId == deviceId && DATE_KEY.matches(it.dateKey) }
        val mangaText = if (root.contentType == ContentType.Mokuro) {
            bookRepository.loadMangaTextStatistics(root.root).filter { it.deviceId == deviceId && DATE_KEY.matches(it.dateKey) }
        } else {
            emptyList()
        }
        // Loading may rewrite a file once (legacy attribution); stamp what is there now.
        val settled = files.flatMap { listOf(it.lastModified(), it.length()) }
        return OwnEntriesSnapshot(settled, reading, mangaText).also { ownEntriesCache[path] = it }
    }

    private fun decodeShard(key: String, parsed: StatisticsLaneKey.Shard, body: ByteArray): HttpSyncStatisticsShard {
        val shard = try {
            json.decodeFromString(HttpSyncStatisticsShard.serializer(), body.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw HttpSyncException("Statistics at $key: malformed JSON (${error.message ?: error.javaClass.simpleName})")
        }
        if (shard.version > HttpSyncStatisticsShard.SUPPORTED_VERSION) {
            throw HttpSyncException("Statistics at $key: unsupported version ${shard.version}.")
        }
        if (shard.deviceId != parsed.deviceId || shard.month != parsed.month) {
            throw HttpSyncException("Statistics at $key: written for another device or month.")
        }
        return shard
    }

    private suspend fun preferencesDirty(index: LaneIndex, deviceId: String?): Boolean {
        val store = preferencesStore ?: return false
        deviceId ?: return false
        val local = effectiveLocalPreferences(store.load())
        val own = index.preferences[statisticsPreferencesKey(deviceId)]
        val newest = newestRemotePreferences(index, deviceId)
        return (local.updatedAt > 0L && own != local.toBlob()) ||
            (newest != null && wins(newest.second, newest.first, local, deviceId))
    }

    private suspend fun syncPreferences(
        transport: HttpSyncKvTransport,
        listing: List<HttpSyncKvKeyMeta>,
        start: LaneIndex,
        deviceId: String,
        errors: MutableList<String>,
    ): LaneIndex {
        val store = preferencesStore ?: return start
        var index = start
        for (meta in listing) {
            val key = parseStatisticsLaneKey(meta.key) as? StatisticsLaneKey.Preferences ?: continue
            if (key.deviceId == deviceId || index.applied[meta.key] == meta.etag) continue
            try {
                val fetched = transport.getBounded(meta.key, MAX_METADATA_BYTES) ?: continue
                val value = json.decodeFromString(HttpSyncStatisticsPreferences.serializer(), fetched.body.toString(Charsets.UTF_8))
                index = index.copy(
                    applied = index.applied + (meta.key to fetched.etag),
                    preferences = index.preferences + (meta.key to value),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(meta.key, meta.etag, errors, error)
            }
        }
        val stored = store.load()
        var local = effectiveLocalPreferences(stored)
        if (local != stored) store.save(local)
        newestRemotePreferences(index, deviceId)?.let { (remoteDevice, remote) ->
            if (wins(remote, remoteDevice, local, deviceId)) {
                local = StatisticsPreferences(
                    streakMinimumMinutes = remote.streakMinimumMinutes.coerceIn(1, 600),
                    dayResetHour = remote.dayResetHour.coerceIn(0, 23),
                    updatedAt = remote.updatedAt,
                )
                store.save(local)
            }
        }
        val ownKey = statisticsPreferencesKey(deviceId)
        if (local.updatedAt > 0L && index.preferences[ownKey] != local.toBlob()) {
            try {
                val blob = local.toBlob()
                val written = transport.put(ownKey, JSON_CONTENT_TYPE, json.encodeToString(HttpSyncStatisticsPreferences.serializer(), blob).toByteArray(Charsets.UTF_8))
                index = index.copy(
                    published = index.published + (ownKey to written.etag),
                    preferences = index.preferences + (ownKey to blob),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(ownKey, null, errors, error)
            }
        }
        return index
    }

    /** A goal or reset changed before choices were synced counts as chosen, older than any synced choice. */
    private fun effectiveLocalPreferences(local: StatisticsPreferences): StatisticsPreferences =
        if (local.updatedAt == 0L && (local.streakMinimumMinutes != DEFAULT_MINIMUM_MINUTES || local.dayResetHour != DEFAULT_RESET_HOUR)) {
            local.copy(updatedAt = LEGACY_CHOICE_STAMP)
        } else {
            local
        }

    private fun newestRemotePreferences(index: LaneIndex, deviceId: String): Pair<String, HttpSyncStatisticsPreferences>? =
        index.preferences.mapNotNull { (key, value) ->
            val device = (parseStatisticsLaneKey(key) as? StatisticsLaneKey.Preferences)?.deviceId ?: return@mapNotNull null
            if (device == deviceId || value.version > 1) null else device to value
        }.maxWithOrNull(compareBy<Pair<String, HttpSyncStatisticsPreferences>>({ it.second.updatedAt }, { it.first }))

    /** Newest choice wins; the larger device id breaks a tie, so every device picks the same one. */
    private fun wins(remote: HttpSyncStatisticsPreferences, remoteDevice: String, local: StatisticsPreferences, deviceId: String): Boolean {
        if (remote.streakMinimumMinutes == local.streakMinimumMinutes && remote.dayResetHour == local.dayResetHour) return false
        return remote.updatedAt > local.updatedAt || (remote.updatedAt == local.updatedAt && remoteDevice > deviceId)
    }

    private fun StatisticsPreferences.toBlob() = HttpSyncStatisticsPreferences(
        streakMinimumMinutes = streakMinimumMinutes,
        dayResetHour = dayResetHour,
        updatedAt = updatedAt,
    )

    private fun loadIndex(transport: HttpSyncKvTransport): LaneIndex {
        val scope = transport.cacheIdentity.orEmpty()
        val stored = runCatching { json.decodeFromString(LaneIndex.serializer(), indexFile.readText()) }.getOrNull()
        return stored?.takeIf { it.scope == scope } ?: LaneIndex(scope = scope)
    }

    private fun saveIndex(index: LaneIndex) {
        val text = json.encodeToString(LaneIndex.serializer(), index)
        if (runCatching { indexFile.readText() }.getOrNull() == text) return
        indexFile.parentFile?.mkdirs()
        writeSidecarAtomically(indexFile, text)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** While someone keeps reading, local days go up at most this often (and at once on leaving). */
        const val LOCAL_PUSH_INTERVAL_MS: Long = 30_000L
        private const val FAILURE_RETRY_MS: Long = 10 * 60_000L
        private const val MAX_METADATA_BYTES: Int = 64 * 1024
        private const val INDEX_FILE_NAME = ".http_sync_statistics_lane.json"
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private const val DEFAULT_MINIMUM_MINUTES = 10
        private const val DEFAULT_RESET_HOUR = 3
        private const val LEGACY_CHOICE_STAMP = 1L
    }
}
