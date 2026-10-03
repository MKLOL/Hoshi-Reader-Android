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
    /** The values before anyone chooses; a device still on them has made no choice. */
    val defaults: StatisticsPreferences

    suspend fun load(): StatisticsPreferences
    suspend fun save(preferences: StatisticsPreferences)

    /** [preferences] as the app would store them (out-of-range values brought in range). */
    fun normalize(preferences: StatisticsPreferences): StatisticsPreferences = preferences
}

/** Why other devices' reading may be missing here, shown as a localized line on Statistics. */
enum class StatisticsSyncProblem {
    /** The sync server could not be reached. */
    Offline,

    /** The server answered with an error. */
    Server,

    /** Statistics another device wrote could not be read. */
    Unreadable,
}

/** What the Statistics screen tells the reader about how current other devices' reading is. */
data class StatisticsSyncStatus(
    /** Epoch milliseconds when every device's statistics were last checked without a problem. */
    val lastSuccessAtMillis: Long? = null,
    /** What keeps some reading out right now; null when nothing does. */
    val problem: StatisticsSyncProblem? = null,
)

/** A statistics body this build cannot use. */
internal open class StatisticsDataException(message: String) : Exception(message)

/** Written by a newer app version, or gone from the server: skipped quietly and checked again later. */
internal class StatisticsSkippedException(message: String) : StatisticsDataException(message)

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
 * state for book keys, [LaneIndex] for shards and settings), never through a global "seen"
 * snapshot. This device's own keys are read back before they are written, so a reinstall or a
 * restore never overwrites days the server still holds. Statistics of a book without a folder
 * here go to its history folder ([BookRepository.statisticsHistoryRoot]), so Today, the streak
 * and every total count all reading, whichever device it happened on. A key that fails is tried
 * again when it changes, or after a pause that doubles up to [MAX_RETRY_MS]; only a manual sync
 * retries at once. Callers serialize runs.
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

    /**
     * Last time local days went into the per-book keys. Builds up to 0.11.21 run a full
     * reconcile for every change of such a key, so while reading they are written less often
     * than the shards (and at once on leaving the reader); updated devices read the shards.
     */
    @Volatile private var lastLegacyPushAtMillis = 0L
    private val failures = ConcurrentHashMap<String, Failure>()
    private val ownEntriesCache = ConcurrentHashMap<String, OwnEntriesSnapshot>()

    /** [problem] is null for a quiet skip (a newer version's body, a vanished key). */
    private data class Failure(
        val etag: String?,
        val retryAtMillis: Long,
        val attempts: Int,
        val problem: StatisticsSyncProblem?,
    )

    @Serializable
    private data class LaneIndex(
        val scope: String = "",
        /** Other devices' shard and settings keys -> the ETag whose content was merged here. */
        val applied: Map<String, String> = emptyMap(),
        /** This device's keys -> the server ETag of a body this install wrote or merged. */
        val published: Map<String, String> = emptyMap(),
        /** This device's keys -> SHA-256 of the body it wrote, for servers whose ETag is not one. */
        val publishedSha: Map<String, String> = emptyMap(),
        /** Settings keys -> the value read or written, so an unchanged key is never fetched again. */
        val preferences: Map<String, HttpSyncStatisticsPreferences> = emptyMap(),
        /** Set after the first complete pass; each failed key retries on its own afterwards. */
        val bootstrapped: Boolean = false,
        /** Own months a pass could not publish yet (batched, unmerged or failed). */
        val ownPending: Boolean = false,
    )

    private data class StatisticsRoot(
        val syncId: String,
        val root: File,
        val title: String?,
        val contentType: ContentType,
        /** A history folder (no book folder here) rather than an installed book. */
        val isHistory: Boolean,
        /** False while a history folder only guesses its type. */
        val typeKnown: Boolean,
    )

    private data class LegacyKeys(
        val reading: HttpSyncKvKeyMeta? = null,
        val manga: HttpSyncKvKeyMeta? = null,
        val metadata: HttpSyncKvKeyMeta? = null,
        val epubManifest: HttpSyncKvKeyMeta? = null,
        val payloadManifest: HttpSyncKvKeyMeta? = null,
    ) {
        val statistics: List<HttpSyncKvKeyMeta> get() = listOfNotNull(reading, manga)
    }

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
        if (roots.absorb.any { (history, _) -> !ownBlocked(absorbKey(history.syncId)) }) return true
        val localDue = flushDue()
        val legacyDue = legacyDue()
        for (root in roots.all) {
            val remote = legacy[root.syncId]
            when (statisticsSync.pendingExchange(root.root, root.syncId, remote?.reading?.etag, remote?.manga?.etag)) {
                StatisticsExchangeNeed.Remote -> if (remoteChanges(root, remote).any { !blocked(it) }) return true
                // Local days wait for the per-book cadence, unless this device's shard has not
                // seen them yet (then the shard cadence applies).
                StatisticsExchangeNeed.Local -> if (!ownBlocked(statisticsKey(root.syncId)) &&
                    (legacyDue || (localDue && ownEntriesStale(root)))
                ) return true
                StatisticsExchangeNeed.None -> Unit
            }
        }
        val rooted = roots.all.mapTo(HashSet()) { it.syncId }
        if (legacy.any { (syncId, keys) -> syncId !in rooted && keys.statistics.any { !blocked(it) } }) return true
        if (index.ownPending && localDue && !ownBlocked(OWN_SHARDS)) return true
        val listed = HashSet<String>()
        for (meta in listing) {
            val key = parseStatisticsLaneKey(meta.key) ?: continue
            listed += meta.key
            val known = if (key.deviceId == deviceId) index.published[meta.key] else index.applied[meta.key]
            if (known != meta.etag && !blocked(meta)) return true
        }
        if (index.published.keys.any { it !in listed && !ownBlocked(it) }) return true
        return preferencesDirty(index, deviceId, listing)
    }

    /**
     * Brings this device's statistics and every other device's in line. [flush] sends local
     * days at once instead of batching them (the reader was just left, Statistics opened, a
     * manual sync). [retryFailed] retries failed keys without waiting for their pause (a manual
     * sync). [onProgress] hears about each book that needs an exchange.
     */
    suspend fun run(
        transport: HttpSyncKvTransport,
        listing: List<HttpSyncKvKeyMeta>,
        flush: Boolean = false,
        retryFailed: Boolean = false,
        onProgress: suspend (HttpSyncProgress) -> Unit = {},
    ): StatisticsLaneResult {
        val deviceId = ownDeviceId()
        var index = loadIndex(transport)
        val errors = mutableListOf<String>()
        val downloaded = mutableSetOf<String>()
        val uploaded = mutableSetOf<String>()
        val localDue = flush || !index.bootstrapped || flushDue()
        val legacyDue = flush || !index.bootstrapped || legacyDue()
        var pushedLocal = false
        var pushedLegacy = false
        fun skipped(meta: HttpSyncKvKeyMeta?) = !retryFailed && blocked(meta)
        fun skippedOwn(key: String) = !retryFailed && ownBlocked(key)

        val entries = bookRepository.loadBookEntries()
        var roots = statisticsRoots(entries, bookRepository.loadStatisticsHistory())
        // A book installed again takes back the history kept while it was gone.
        for ((history, root) in roots.absorb) {
            if (skippedOwn(absorbKey(history.syncId))) continue
            try {
                bookRepository.absorbStatisticsHistory(history, root)
                failures.remove(absorbKey(history.syncId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(absorbKey(history.syncId), null, errors, error)
            }
        }
        if (roots.absorb.isNotEmpty()) {
            roots = statisticsRoots(entries, bookRepository.loadStatisticsHistory())
        }
        val rootsBySyncId = roots.all.associateBy { it.syncId }.toMutableMap()

        /** The root days of [syncId] go to; better evidence fills in what a history folder lacked. */
        suspend fun rootFor(syncId: String, title: String?, contentType: ContentType?): StatisticsRoot? {
            val existing = rootsBySyncId[syncId]
            if (existing != null && (!existing.isHistory ||
                    ((title == null || existing.title != null) && (contentType == null || existing.typeKnown)))
            ) return existing
            val folder = bookRepository.statisticsHistoryRoot(syncId, title, contentType) ?: return existing
            val info = readStatisticsHistoryInfo(folder)
            return StatisticsRoot(
                syncId = syncId,
                root = folder,
                title = info?.title?.takeIf { it.isNotBlank() },
                contentType = statisticsContentType(folder),
                isHistory = true,
                typeKnown = info?.contentType != null,
            ).also { rootsBySyncId[syncId] = it }
        }

        // 1. Other devices' shards, and this device's own when the server holds a version this
        //    install never merged (a reinstall or a restore): merge what changed.
        for (meta in listing) {
            val key = parseStatisticsLaneKey(meta.key) as? StatisticsLaneKey.Shard ?: continue
            val own = key.deviceId == deviceId
            val known = if (own) index.published[meta.key] else index.applied[meta.key]
            if (known == meta.etag || skipped(meta)) continue
            try {
                if (meta.size > MAX_STATISTICS_BLOB_BYTES) {
                    throw StatisticsDataException("Statistics at ${meta.key} exceed the $MAX_STATISTICS_BLOB_BYTES-byte limit.")
                }
                val fetched = transport.getBounded(meta.key, MAX_STATISTICS_BLOB_BYTES)
                    ?: throw StatisticsSkippedException("Statistics at ${meta.key} are no longer on the server.")
                val shard = decodeShard(meta.key, key, fetched.body)
                for ((syncId, book) in shard.books.toSortedMap()) {
                    if (!isValidSyncId(syncId)) continue
                    // A shard speaks only for its own device and month.
                    val reading = book.reading.filter { it.deviceId == key.deviceId && it.dateKey.startsWith(key.month) }
                    val mangaText = book.mangaText.filter { it.deviceId == key.deviceId && it.dateKey.startsWith(key.month) }
                    if (reading.isEmpty() && mangaText.isEmpty()) continue
                    val title = book.title?.takeIf { it.isNotBlank() } ?: reading.firstOrNull { it.title.isNotBlank() }?.title
                    val root = rootFor(syncId, title, book.contentType) ?: continue
                    if (merge(root, reading, mangaText)) downloaded += syncId
                }
                index = if (own) {
                    index.copy(
                        published = index.published + (meta.key to fetched.etag),
                        publishedSha = index.publishedSha + (meta.key to sha256(fetched.body)),
                    )
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
        val candidates = (rootsBySyncId.keys + legacy.filterValues { it.statistics.isNotEmpty() }.keys).toSortedSet()
        val work = candidates.filter { syncId ->
            val remote = legacy[syncId]
            val root = rootsBySyncId[syncId]
            if (root == null) {
                remote != null && remote.statistics.any { !skipped(it) }
            } else {
                when (statisticsSync.pendingExchange(root.root, syncId, remote?.reading?.etag, remote?.manga?.etag)) {
                    StatisticsExchangeNeed.None -> false
                    StatisticsExchangeNeed.Local -> legacyDue && !skippedOwn(statisticsKey(syncId))
                    StatisticsExchangeNeed.Remote -> remoteChanges(root, remote).any { !skipped(it) } ||
                        (legacyDue && !skippedOwn(statisticsKey(syncId)))
                }
            }
        }
        for ((position, syncId) in work.withIndex()) {
            val remote = legacy[syncId]
            val existing = rootsBySyncId[syncId]
            onProgress(HttpSyncProgress(
                messageResource = moe.antimony.hoshi.R.string.http_sync_reading_history,
                detail = existing?.title ?: syncId, completed = position, total = work.size,
            ))
            try {
                val root = when {
                    existing == null -> describeRemoteBook(transport, remote).let { (title, type) -> rootFor(syncId, title, type) }
                    // OCR statistics on the server settle a history folder's guessed type.
                    existing.isHistory && !existing.typeKnown && remote?.manga != null -> rootFor(syncId, null, ContentType.Mokuro)
                    else -> existing
                } ?: continue
                val need = statisticsSync.pendingExchange(root.root, syncId, remote?.reading?.etag, remote?.manga?.etag)
                if (need == StatisticsExchangeNeed.None) continue
                val kinds = if (root.contentType == ContentType.Mokuro) StatisticsSyncKind.entries else listOf(StatisticsSyncKind.Reading)
                var sentLocal = false
                for (kind in kinds) {
                    val meta = if (kind == StatisticsSyncKind.Reading) remote?.reading else remote?.manga
                    if (skipped(meta) || (meta == null && skippedOwn(kind.key(syncId)))) continue
                    try {
                        if (meta != null && meta.size > MAX_STATISTICS_BLOB_BYTES) {
                            throw StatisticsDataException("Statistics at ${meta.key} exceed the $MAX_STATISTICS_BLOB_BYTES-byte limit.")
                        }
                        val listed = meta?.let { StatisticsRemoteListing.Listed(it.size, it.lastModified, it.etag) }
                            ?: StatisticsRemoteListing.Absent
                        val outcome = statisticsSync.sync(transport, root.root, syncId, kind, listed)
                        if (outcome.downloaded) downloaded += syncId
                        if (outcome.uploaded) {
                            uploaded += syncId
                            sentLocal = true
                        }
                        failures.remove(meta?.key ?: kind.key(syncId))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        fail(meta?.key ?: kind.key(syncId), meta?.etag, errors, error)
                    }
                }
                if (sentLocal) pushedLegacy = true
                if (root.isHistory && root.title == null) nameFromEntries(root)
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
            var pending = false
            for ((month, body) in months) {
                val key = statisticsShardKey(deviceId, month)
                val sha = sha256(body)
                val onServer = listed[key]
                if (onServer != null &&
                    (onServer.etag == "sha256:$sha" || (onServer.etag == index.published[key] && index.publishedSha[key] == sha))
                ) {
                    index = index.copy(published = index.published + (key to onServer.etag), publishedSha = index.publishedSha + (key to sha))
                    continue
                }
                // A server copy this install has not merged yet is never overwritten unread; its
                // listing brings the pass back once it can be read.
                if (onServer != null && index.published[key] != onServer.etag) continue
                if (!localDue || skippedOwn(key)) {
                    pending = true
                    continue
                }
                try {
                    val written = transport.put(key, JSON_CONTENT_TYPE, body)
                    index = index.copy(
                        published = index.published + (key to written.etag),
                        publishedSha = index.publishedSha + (key to sha),
                    )
                    failures.remove(key)
                    failures.remove(OWN_SHARDS)
                    pushedLocal = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    fail(key, null, errors, error)
                    fail(OWN_SHARDS, null, mutableListOf(), error)
                    pending = true
                }
            }
            // A month this install no longer has days for is not republished; forget it.
            val generated = months.keys.mapTo(HashSet()) { statisticsShardKey(deviceId, it) }
            fun keep(key: String) = (parseStatisticsLaneKey(key) as? StatisticsLaneKey.Shard)?.deviceId != deviceId ||
                key in generated || key in listed
            index = index.copy(
                published = index.published.filterKeys(::keep),
                publishedSha = index.publishedSha.filterKeys(::keep),
                ownPending = pending,
            )
            if (!pending) failures.remove(OWN_SHARDS)
        }

        // 4. The streak goal and day reset: the newest choice of any device applies everywhere.
        if (deviceId != null && preferencesStore != null) {
            index = syncPreferences(transport, listing, index, deviceId, errors, retryFailed)
        }

        if (pushedLocal) lastLocalPushAtMillis = now()
        if (pushedLegacy) lastLegacyPushAtMillis = now()
        index = index.copy(bootstrapped = true)
        saveIndex(index)
        publishStatus()
        return StatisticsLaneResult(downloaded.size, uploaded.size, errors)
    }

    /** A listing pass with nothing to do proves this device is current, unless a key is failing. */
    fun markChecked() {
        publishStatus()
    }

    /** The listing could not be read, so nothing about other devices is known. */
    fun markFailed(error: Throwable) {
        _status.value = _status.value.copy(problem = problemOf(error) ?: StatisticsSyncProblem.Server)
    }

    private fun publishStatus() {
        val outstanding = failures.values.mapNotNull { it.problem }.firstOrNull()
        val current = _status.value
        if (outstanding != null) {
            if (current.problem != outstanding) _status.value = current.copy(problem = outstanding)
            return
        }
        val time = now()
        // A new value (and a recomposition of Statistics) at most every half minute.
        val recent = current.lastSuccessAtMillis?.let { time - it in 0 until STATUS_REFRESH_MS } == true
        if (current.problem == null && recent) return
        _status.value = StatisticsSyncStatus(lastSuccessAtMillis = time)
    }

    private fun flushDue(): Boolean = now() - lastLocalPushAtMillis >= localPushIntervalMs

    private fun legacyDue(): Boolean = now() - lastLegacyPushAtMillis >= LEGACY_PUSH_INTERVAL_MS

    /** Whether [root]'s files changed since this device's shards were last built from them. */
    private fun ownEntriesStale(root: StatisticsRoot): Boolean =
        ownEntriesCache[root.root.absolutePath]?.stamp != ownFilesStamp(root.root)

    private fun ownFilesStamp(root: File): List<Long> =
        listOf("statistics.json", "manga_statistics.json").map { root.resolve(it) }.flatMap { listOf(it.lastModified(), it.length()) }

    private fun absorbKey(syncId: String) = "absorb:$syncId"

    /** A remote key that failed with this very content is not fetched again before its pause ends. */
    private fun blocked(meta: HttpSyncKvKeyMeta?): Boolean {
        meta ?: return false
        val failure = failures[meta.key] ?: return false
        return failure.etag == meta.etag && now() < failure.retryAtMillis
    }

    /** A write of this device's own key that failed waits out its pause. */
    private fun ownBlocked(key: String): Boolean = failures[key]?.let { now() < it.retryAtMillis } == true

    private fun fail(key: String, etag: String?, errors: MutableList<String>, error: Exception) {
        val problem = problemOf(error)
        val previous = failures[key]
        val attempts = if (previous != null && previous.etag == etag) previous.attempts + 1 else 1
        val pause = (FIRST_RETRY_MS shl (attempts - 1).coerceAtMost(8)).coerceAtMost(MAX_RETRY_MS)
        failures[key] = Failure(etag, now() + pause, attempts, problem)
        if (problem != null) errors += "$key: ${error.message ?: error.javaClass.simpleName}"
    }

    private fun problemOf(error: Throwable): StatisticsSyncProblem? = when (error) {
        is StatisticsSkippedException -> null
        is StatisticsDataException -> StatisticsSyncProblem.Unreadable
        is HttpSyncException -> when {
            error.httpCode != null || HTTP_STATUS.containsMatchIn(error.message.orEmpty()) -> StatisticsSyncProblem.Server
            error.message.orEmpty().contains("malformed", ignoreCase = true) -> StatisticsSyncProblem.Unreadable
            else -> StatisticsSyncProblem.Offline
        }
        is java.io.IOException -> StatisticsSyncProblem.Offline
        else -> StatisticsSyncProblem.Server
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
                StatisticsRoot(syncId, entry.root, entry.displayTitle.ifBlank { null }, bookContentType(entry.root), isHistory = false, typeKnown = true),
            )
        }
        val absorb = history.mapNotNull { kept -> installed[kept.syncId]?.let { kept to it.root } }
        val kept = history.filter { it.syncId !in installed }.map { entry ->
            val info = readStatisticsHistoryInfo(entry.root)
            StatisticsRoot(
                syncId = entry.syncId,
                root = entry.root,
                title = info?.title?.takeIf { it.isNotBlank() },
                contentType = entry.contentType,
                isHistory = true,
                typeKnown = info?.contentType != null,
            )
        }
        return Roots(installed.values.toList() + kept, absorb)
    }

    /** The listed per-book keys of [root] whose content [root] has not merged. */
    private suspend fun remoteChanges(root: StatisticsRoot, remote: LegacyKeys?): List<HttpSyncKvKeyMeta> = listOfNotNull(
        remote?.reading?.takeIf { statisticsSync.remoteDiffers(root.root, StatisticsSyncKind.Reading, it.etag) },
        remote?.manga?.takeIf {
            root.contentType == ContentType.Mokuro && statisticsSync.remoteDiffers(root.root, StatisticsSyncKind.MangaText, it.etag)
        },
    )

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
                "epub.manifest" -> current.copy(epubManifest = meta)
                "payload.manifest" -> current.copy(payloadManifest = meta)
                else -> continue
            }
        }
        return keys
    }

    /**
     * Title and type of a book known only from the server. The listing usually tells the type
     * (OCR statistics or a manifest), and the days carry the title; the book's metadata is
     * fetched only when the type is still open.
     */
    private suspend fun describeRemoteBook(transport: HttpSyncKvTransport, remote: LegacyKeys?): Pair<String?, ContentType?> {
        val listedType = when {
            remote?.manga != null -> ContentType.Mokuro
            remote?.epubManifest != null -> ContentType.Epub
            else -> null
        }
        val metadata = remote?.metadata
        if (listedType != null || metadata == null) return null to listedType
        val blob = try {
            transport.getBounded(metadata.key, MAX_METADATA_BYTES)?.let {
                json.decodeFromString(HttpSyncMetadataBlob.serializer(), it.body.toString(Charsets.UTF_8))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return blob?.title?.takeIf { it.isNotBlank() } to blob?.contentType?.toLocal()
    }

    /** A history folder that only knew its sync id takes the title its days carry. */
    private suspend fun nameFromEntries(root: StatisticsRoot) {
        if (readStatisticsHistoryInfo(root.root)?.title?.isNotBlank() == true) return
        val title = bookRepository.loadStatistics(root.root).firstOrNull { it.title.isNotBlank() }?.title ?: return
        bookRepository.statisticsHistoryRoot(root.syncId, title, null)
    }

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
        val live = HashSet<String>()
        for (root in roots) {
            live += root.root.absolutePath
            val own = ownEntries(root, deviceId)
            val readingByMonth = own.reading.groupBy { it.dateKey.take(7) }
            val mangaByMonth = own.mangaText.groupBy { it.dateKey.take(7) }
            for (month in (readingByMonth.keys + mangaByMonth.keys)) {
                books.getOrPut(month) { sortedMapOf() }[root.syncId] = HttpSyncStatisticsShardBook(
                    title = root.title ?: readingByMonth[month]?.firstOrNull { it.title.isNotBlank() }?.title,
                    // A history folder's guessed type is not passed on as fact.
                    contentType = root.contentType.takeIf { root.typeKnown },
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
        // Stamped before reading: a save landing during the read is read again next time.
        val stamp = ownFilesStamp(root.root)
        val path = root.root.absolutePath
        ownEntriesCache[path]?.takeIf { it.stamp == stamp }?.let { return it }
        val reading = bookRepository.loadStatistics(root.root)
            .filter { it.deviceId == deviceId && DATE_KEY.matches(it.dateKey) }
        val mangaText = if (root.contentType == ContentType.Mokuro) {
            bookRepository.loadMangaTextStatistics(root.root).filter { it.deviceId == deviceId && DATE_KEY.matches(it.dateKey) }
        } else {
            emptyList()
        }
        return OwnEntriesSnapshot(stamp, reading, mangaText).also { ownEntriesCache[path] = it }
    }

    private fun decodeShard(key: String, parsed: StatisticsLaneKey.Shard, body: ByteArray): HttpSyncStatisticsShard {
        val version = runCatching {
            json.decodeFromString(VersionProbe.serializer(), body.toString(Charsets.UTF_8)).version
        }.getOrNull()
        if (version != null && version > HttpSyncStatisticsShard.SUPPORTED_VERSION) {
            throw StatisticsSkippedException("Statistics at $key: written by a newer version ($version).")
        }
        val shard = try {
            json.decodeFromString(HttpSyncStatisticsShard.serializer(), body.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw StatisticsDataException("Statistics at $key: malformed JSON (${error.message ?: error.javaClass.simpleName})")
        }
        if (shard.deviceId != parsed.deviceId || shard.month != parsed.month) {
            throw StatisticsDataException("Statistics at $key: written for another device or month.")
        }
        return shard
    }

    @Serializable
    private data class VersionProbe(val version: Int = 1)

    private suspend fun preferencesDirty(index: LaneIndex, deviceId: String?, listing: List<HttpSyncKvKeyMeta>): Boolean {
        val store = preferencesStore ?: return false
        deviceId ?: return false
        val local = effectiveLocalPreferences(store.load(), store)
        val ownKey = statisticsPreferencesKey(deviceId)
        if (local.updatedAt > 0L && !ownBlocked(ownKey) &&
            !ownPreferencesCurrent(index, listing.firstOrNull { it.key == ownKey }, local)
        ) return true
        val newest = newestPreferences(index) ?: return false
        return wins(store.normalize(newest.second.toPreferences()), newest.first, local, deviceId)
    }

    private suspend fun syncPreferences(
        transport: HttpSyncKvTransport,
        listing: List<HttpSyncKvKeyMeta>,
        start: LaneIndex,
        deviceId: String,
        errors: MutableList<String>,
        retryFailed: Boolean,
    ): LaneIndex {
        val store = preferencesStore ?: return start
        var index = start
        // Every settings key with a new ETag, this device's own included: a reinstall finds its
        // earlier choice there.
        for (meta in listing) {
            val key = parseStatisticsLaneKey(meta.key) as? StatisticsLaneKey.Preferences ?: continue
            val own = key.deviceId == deviceId
            val known = if (own) index.published[meta.key] else index.applied[meta.key]
            if (known == meta.etag || (!retryFailed && blocked(meta))) continue
            try {
                val fetched = transport.getBounded(meta.key, MAX_METADATA_BYTES)
                    ?: throw StatisticsSkippedException("Settings at ${meta.key} are no longer on the server.")
                val text = fetched.body.toString(Charsets.UTF_8)
                val version = runCatching { json.decodeFromString(VersionProbe.serializer(), text).version }.getOrNull()
                if (version != null && version > 1) throw StatisticsSkippedException("Settings at ${meta.key}: written by a newer version.")
                val value = try {
                    json.decodeFromString(HttpSyncStatisticsPreferences.serializer(), text)
                } catch (error: Exception) {
                    throw StatisticsDataException("Settings at ${meta.key}: malformed JSON (${error.message ?: error.javaClass.simpleName})")
                }
                index = index.copy(
                    preferences = index.preferences + (meta.key to value),
                    applied = if (own) index.applied else index.applied + (meta.key to fetched.etag),
                    published = if (own) index.published + (meta.key to fetched.etag) else index.published,
                    publishedSha = if (own) index.publishedSha + (meta.key to sha256(fetched.body)) else index.publishedSha,
                )
                failures.remove(meta.key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(meta.key, meta.etag, errors, error)
            }
        }
        val stored = store.load()
        var local = effectiveLocalPreferences(stored, store)
        if (local != stored) store.save(local)
        newestPreferences(index)?.let { (device, remote) ->
            val candidate = store.normalize(remote.toPreferences())
            if (wins(candidate, device, local, deviceId)) {
                store.save(candidate)
                local = candidate
            }
        }
        val ownKey = statisticsPreferencesKey(deviceId)
        val onServer = listing.firstOrNull { it.key == ownKey }
        if (local.updatedAt > 0L && !ownPreferencesCurrent(index, onServer, local)) {
            // This device's own server copy is read (above) before it is ever replaced.
            val unread = onServer != null && index.published[ownKey] != onServer.etag
            if (!unread && !(!retryFailed && ownBlocked(ownKey))) {
                try {
                    val blob = local.toBlob()
                    val body = json.encodeToString(HttpSyncStatisticsPreferences.serializer(), blob).toByteArray(Charsets.UTF_8)
                    val written = transport.put(ownKey, JSON_CONTENT_TYPE, body)
                    index = index.copy(
                        published = index.published + (ownKey to written.etag),
                        publishedSha = index.publishedSha + (ownKey to sha256(body)),
                        preferences = index.preferences + (ownKey to blob),
                    )
                    failures.remove(ownKey)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    fail(ownKey, null, errors, error)
                }
            }
        }
        return index
    }

    /** Whether the server holds exactly [local] under this device's settings key. */
    private fun ownPreferencesCurrent(index: LaneIndex, onServer: HttpSyncKvKeyMeta?, local: StatisticsPreferences): Boolean {
        onServer ?: return false
        val body = json.encodeToString(HttpSyncStatisticsPreferences.serializer(), local.toBlob()).toByteArray(Charsets.UTF_8)
        val sha = sha256(body)
        return onServer.etag == "sha256:$sha" || (onServer.etag == index.published[onServer.key] && index.publishedSha[onServer.key] == sha)
    }

    /** A goal or reset changed before choices were synced counts as chosen, older than any synced choice. */
    private fun effectiveLocalPreferences(local: StatisticsPreferences, store: StatisticsPreferencesStore): StatisticsPreferences {
        val defaults = store.defaults
        val unchosen = local.streakMinimumMinutes == defaults.streakMinimumMinutes && local.dayResetHour == defaults.dayResetHour
        return if (local.updatedAt == 0L && !unchosen) local.copy(updatedAt = LEGACY_CHOICE_STAMP) else local
    }

    /** The newest choice on the server, this device's own included; the larger device id breaks a tie. */
    private fun newestPreferences(index: LaneIndex): Pair<String, HttpSyncStatisticsPreferences>? =
        index.preferences.mapNotNull { (key, value) ->
            val device = (parseStatisticsLaneKey(key) as? StatisticsLaneKey.Preferences)?.deviceId ?: return@mapNotNull null
            device to value
        }.maxWithOrNull(compareBy<Pair<String, HttpSyncStatisticsPreferences>>({ it.second.updatedAt }, { it.first }))

    /** Whether [candidate] should replace [local]: newer, or as new from a larger device id, and different. */
    private fun wins(candidate: StatisticsPreferences, candidateDevice: String, local: StatisticsPreferences, deviceId: String): Boolean {
        if (candidate == local) return false
        return candidate.updatedAt > local.updatedAt ||
            (candidate.updatedAt == local.updatedAt && candidateDevice > deviceId)
    }

    private fun HttpSyncStatisticsPreferences.toPreferences() = StatisticsPreferences(streakMinimumMinutes, dayResetHour, updatedAt)

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

        /** The same for the per-book keys older builds read (see [lastLegacyPushAtMillis]). */
        const val LEGACY_PUSH_INTERVAL_MS: Long = 3 * 60_000L
        private const val FIRST_RETRY_MS: Long = 30_000L
        const val MAX_RETRY_MS: Long = 10 * 60_000L
        private const val STATUS_REFRESH_MS: Long = 30_000L
        private const val MAX_METADATA_BYTES: Int = 64 * 1024
        private const val INDEX_FILE_NAME = ".http_sync_statistics_lane.json"
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private const val LEGACY_CHOICE_STAMP = 1L

        /** Failure slot for "some own month could not be published". */
        private const val OWN_SHARDS = "own-shards"

        /** How the sync client words an answer with an HTTP error status. */
        private val HTTP_STATUS = Regex("HTTP \\d{3}")
    }
}
