package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.usage.UsageLog
import moe.antimony.hoshi.storage.writeSidecarAtomically
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * `sync/maps/usage/{deviceId}/{yyyy-MM-dd}.ndjson.gz`: one device's usage log for one of its
 * calendar days; once that month is over, `…/{yyyy-MM}.ndjson.gz` holds the whole month instead.
 */
internal const val USAGE_PREFIX: String = "${SYNC_MAP_PREFIX}usage/"
private const val USAGE_SUFFIX = ".ndjson.gz"
private val USAGE_DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")
private val USAGE_MONTH = Regex("^\\d{4}-\\d{2}$")

internal fun usageDayKey(deviceId: String, date: LocalDate): String = "$USAGE_PREFIX$deviceId/$date$USAGE_SUFFIX"

internal fun usageMonthKey(deviceId: String, month: YearMonth): String = "$USAGE_PREFIX$deviceId/$month$USAGE_SUFFIX"

/** One device's day ([date]) or, for a month that is over, its whole [month]. */
internal data class UsageSyncKey(val deviceId: String, val date: LocalDate?, val month: YearMonth)

internal fun parseUsageKey(key: String): UsageSyncKey? {
    if (!key.startsWith(USAGE_PREFIX) || !key.endsWith(USAGE_SUFFIX)) return null
    val parts = key.removePrefix(USAGE_PREFIX).removeSuffix(USAGE_SUFFIX).split('/')
    if (parts.size != 2 || !isValidSyncId(parts[0]) || parts[0].trim('.').isEmpty()) return null
    return when {
        USAGE_DAY.matches(parts[1]) -> runCatching { LocalDate.parse(parts[1]) }.getOrNull()
            ?.let { UsageSyncKey(parts[0], it, YearMonth.from(it)) }
        USAGE_MONTH.matches(parts[1]) -> runCatching { YearMonth.parse(parts[1]) }.getOrNull()
            ?.let { UsageSyncKey(parts[0], null, it) }
        else -> null
    }
}

/**
 * Sends this device's usage log (lookups, bubbles, translations, reading spans) and keeps every
 * other device's, so Today, the timeline and the lookup trends count every device. Each device
 * writes (and removes) only its own keys: a gzipped file per day of the current month, and one
 * per month once it is over, after which that month's day keys are removed so the listing every
 * poll reads stays small. This device's own copy on the server is read back and merged before
 * it is ever replaced (a reinstall starts with an empty log). Today's file goes up at most every
 * [PUSH_INTERVAL_MS] while it keeps changing, and at once on a flush; other devices' files are
 * fetched whenever their ETag changes.
 */
class HttpSyncUsageLane(
    private val log: UsageLog,
    private val now: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Key -> the ETag that failed (null for this device's own writes), when to retry, attempts. */
    private val failures = ConcurrentHashMap<String, Failure>()

    /** This process's own writes -> the body's SHA-256, for listings taken before the write. */
    private val sent = ConcurrentHashMap<String, String>()

    @Volatile private var lastPushAtMillis = 0L
    @Volatile private var cached: Index? = null

    private data class Failure(val etag: String?, val retryAtMillis: Long, val attempts: Int)

    @Serializable
    private data class Index(
        val scope: String = "",
        /** Other devices' keys -> the ETag stored here. */
        val applied: Map<String, String> = emptyMap(),
        /** This device's keys -> the server ETag of a body it wrote or merged. */
        val published: Map<String, String> = emptyMap(),
        /** This device's keys -> SHA-256 of the body it wrote, for servers whose ETag is not one. */
        val publishedSha: Map<String, String> = emptyMap(),
        /** This device's keys -> the stamp of the day files last sent under them. */
        val sentStamps: Map<String, String> = emptyMap(),
    )

    /** What this device sends under one key: a day of the current month, or a whole month that is over. */
    private class OwnUpload(val key: String, val files: List<File>, val finished: Boolean) {
        val stamp: String = files.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
    }

    /** Whether [run] has work: another device's day changed, or one of this device's needs sending. */
    suspend fun needsRun(transport: HttpSyncKvTransport, listing: List<HttpSyncKvKeyMeta>, deviceId: String): Boolean {
        val index = loadIndex(transport)
        val listed = usageKeys(listing)
        val current = YearMonth.from(today())
        for ((key, meta) in listed) {
            val parsed = parseUsageKey(key) ?: continue
            if (blocked(key, meta.etag)) continue
            if (parsed.deviceId != deviceId) {
                if (index.applied[key] != meta.etag) return true
                continue
            }
            if (index.published[key] != meta.etag) return true
            // A day of a month that is over, still listed once its month went up: remove it.
            if (parsed.date != null && parsed.month < current && index.published[usageMonthKey(deviceId, parsed.month)] != null) return true
        }
        val pushDue = now() - lastPushAtMillis >= PUSH_INTERVAL_MS
        for (unit in ownUnits(deviceId)) {
            if (blocked(unit.key, null)) continue
            val missing = unit.key !in listed && sent[unit.key] == null
            if ((index.sentStamps[unit.key] != unit.stamp || missing) && (unit.finished || pushDue)) return true
        }
        return false
    }

    /** Merges other devices' changed days and sends this device's ([flush]: today's too, at once). */
    suspend fun run(
        transport: HttpSyncKvTransport,
        listing: List<HttpSyncKvKeyMeta>,
        deviceId: String,
        flush: Boolean,
        retryFailed: Boolean,
    ): List<String> {
        var index = loadIndex(transport)
        val errors = mutableListOf<String>()
        val listed = usageKeys(listing)
        fun skipped(key: String, etag: String?) = !retryFailed && blocked(key, etag)

        // 1. Other devices' days and months, and this device's own when the server holds a copy
        //    this install never merged (a reinstall): store or merge what changed.
        for ((key, meta) in listed) {
            val parsed = parseUsageKey(key) ?: continue
            val own = parsed.deviceId == deviceId
            val known = if (own) index.published[key] else index.applied[key]
            if (known == meta.etag || skipped(key, meta.etag)) continue
            try {
                if (meta.size > MAX_GZIP_BYTES) throw HttpSyncException("Usage at $key exceeds $MAX_GZIP_BYTES bytes.")
                val fetched = transport.getBounded(key, MAX_GZIP_BYTES)
                if (fetched == null) {
                    // Gone since the listing: paused quietly until the key changes or the pause ends.
                    pause(key, meta.etag)
                    continue
                }
                val text = gunzip(fetched.body).toString(Charsets.UTF_8)
                val days = if (parsed.date != null) mapOf(parsed.date to text) else splitByDay(text)
                if (own) {
                    days.forEach { (date, lines) -> log.mergeOwnDay(date, lines) }
                    index = index.copy(
                        published = index.published + (key to fetched.etag),
                        publishedSha = index.publishedSha + (key to sha256(fetched.body)),
                    )
                } else {
                    days.forEach { (date, lines) -> log.storeDeviceDay(parsed.deviceId, date, lines) }
                    index = index.copy(applied = index.applied + (key to fetched.etag))
                }
                failures.remove(key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(key, meta.etag, errors, error)
            }
        }

        // 2. This device's own days: past ones whenever they changed, today's on the push cadence.
        val pushDue = flush || now() - lastPushAtMillis >= PUSH_INTERVAL_MS
        var pushed = false
        val units = ownUnits(deviceId)
        for (unit in units) {
            if (!unit.finished && !pushDue) continue
            val onServer = listed[unit.key]
            // A server copy this install has not merged yet is never overwritten unread.
            if (onServer != null && index.published[unit.key] != onServer.etag) continue
            if (skipped(unit.key, null)) continue
            if (index.sentStamps[unit.key] == unit.stamp && (onServer != null || sent[unit.key] != null)) continue
            try {
                val body = gzip(unit.files.joinToString("") { file ->
                    file.readText().let { if (it.isEmpty() || it.endsWith("\n")) it else it + "\n" }
                }.toByteArray(Charsets.UTF_8))
                val sha = sha256(body)
                val current = (onServer != null && (onServer.etag == "sha256:$sha" || index.publishedSha[unit.key] == sha)) ||
                    (onServer == null && sent[unit.key] == sha)
                if (!current) {
                    val written = transport.put(unit.key, GZIP_CONTENT_TYPE, body)
                    index = index.copy(
                        published = index.published + (unit.key to written.etag),
                        publishedSha = index.publishedSha + (unit.key to sha),
                    )
                    sent[unit.key] = sha
                    pushed = true
                }
                // Recorded only once the server holds it.
                index = index.copy(sentStamps = index.sentStamps + (unit.key to unit.stamp))
                failures.remove(unit.key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(unit.key, null, errors, error)
            }
        }

        // 3. Day keys of a month that is over, once the month's own key holds them: removed.
        val current = YearMonth.from(today())
        for ((key, meta) in listed) {
            val parsed = parseUsageKey(key) ?: continue
            if (parsed.deviceId != deviceId || parsed.date == null || parsed.month >= current) continue
            val monthKey = usageMonthKey(deviceId, parsed.month)
            if (index.published[key] != meta.etag || (monthKey !in listed && sent[monthKey] == null)) continue
            if (index.sentStamps[monthKey] == null) continue
            try {
                transport.delete(key)
                index = index.copy(published = index.published - key, publishedSha = index.publishedSha - key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(key, meta.etag, errors, error)
            }
        }

        val live = listed.keys + units.map { it.key } + sent.keys
        index = index.copy(
            applied = index.applied.filterKeys { it in live },
            published = index.published.filterKeys { it in live },
            publishedSha = index.publishedSha.filterKeys { it in live },
            sentStamps = index.sentStamps.filterKeys { it in live },
        )
        if (pushed) lastPushAtMillis = now()
        saveIndex(index)
        return errors
    }

    /** This device's day files as what it sends: each day of the current month, each month before as one. */
    private suspend fun ownUnits(deviceId: String): List<OwnUpload> {
        val current = YearMonth.from(today())
        val days = log.dayFiles().mapNotNull { file -> dateOf(file.name)?.let { it to file } }
        return days.groupBy { (date, _) -> YearMonth.from(date) }.flatMap { (month, files) ->
            if (month < current) {
                listOf(OwnUpload(usageMonthKey(deviceId, month), files.sortedBy { it.first }.map { it.second }, finished = true))
            } else {
                files.map { (date, file) -> OwnUpload(usageDayKey(deviceId, date), listOf(file), finished = date < today()) }
            }
        }
    }

    /** A month's lines by the calendar day of the device that wrote them (its offset at that moment). */
    private fun splitByDay(text: String): Map<LocalDate, String> {
        val days = linkedMapOf<LocalDate, StringBuilder>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val stamp = runCatching { json.decodeFromString(LineStamp.serializer(), line) }.getOrNull() ?: continue
            val offset = runCatching { ZoneOffset.of(stamp.utcOffset) }.getOrNull() ?: ZoneOffset.UTC
            val date = Instant.ofEpochMilli(stamp.at).atOffset(offset).toLocalDate()
            days.getOrPut(date) { StringBuilder() }.append(line).append('\n')
        }
        return days.mapValues { it.value.toString() }
    }

    @Serializable
    private data class LineStamp(val at: Long, val utcOffset: String = "Z")

    private fun usageKeys(listing: List<HttpSyncKvKeyMeta>): Map<String, HttpSyncKvKeyMeta> =
        listing.filter { it.key.startsWith(USAGE_PREFIX) && parseUsageKey(it.key) != null }.associateBy { it.key }

    private fun dateOf(name: String): LocalDate? =
        name.removeSuffix(UsageLog.FILE_SUFFIX).takeIf { USAGE_DAY.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun blocked(key: String, etag: String?): Boolean =
        failures[key]?.let { it.etag == etag && now() < it.retryAtMillis } == true

    private fun pause(key: String, etag: String?): Failure {
        val previous = failures[key]
        val attempts = if (previous != null && previous.etag == etag) previous.attempts + 1 else 1
        val pause = (FIRST_RETRY_MS shl (attempts - 1).coerceAtMost(8)).coerceAtMost(MAX_RETRY_MS)
        return Failure(etag, now() + pause, attempts).also { failures[key] = it }
    }

    private fun fail(key: String, etag: String?, errors: MutableList<String>, error: Exception) {
        pause(key, etag)
        errors += "$key: ${error.message ?: error.javaClass.simpleName}"
    }

    private suspend fun loadIndex(transport: HttpSyncKvTransport): Index {
        val scope = transport.cacheIdentity.orEmpty()
        cached?.takeIf { it.scope == scope }?.let { return it }
        val stored = runCatching { json.decodeFromString(Index.serializer(), log.syncIndexFile.readText()) }.getOrNull()
        if (stored?.scope == scope) return stored.also { cached = it }
        // Another account (or server): its devices' days are not this account's.
        if (stored != null || cached != null) log.clearDeviceDays()
        sent.clear()
        return Index(scope = scope).also { cached = it }
    }

    private fun saveIndex(index: Index) {
        if (cached == index && log.syncIndexFile.isFile) return
        cached = index
        log.syncIndexFile.parentFile?.mkdirs()
        writeSidecarAtomically(log.syncIndexFile, json.encodeToString(Index.serializer(), index))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** While a day keeps changing, it goes up at most this often (and at once on a flush). */
        const val PUSH_INTERVAL_MS: Long = 2 * 60_000L
        private const val FIRST_RETRY_MS: Long = 30_000L
        private const val MAX_RETRY_MS: Long = 10 * 60_000L
        private const val MAX_GZIP_BYTES: Int = 16 * 1024 * 1024
        private const val MAX_TEXT_BYTES: Int = 64 * 1024 * 1024
        private const val GZIP_CONTENT_TYPE = "application/gzip"

        /** Gzip without a timestamp, so the same day always has the same body and ETag. */
        internal fun gzip(bytes: ByteArray): ByteArray =
            ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

        internal fun gunzip(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_TEXT_BYTES) throw HttpSyncException("Usage day expands beyond $MAX_TEXT_BYTES bytes.")
                }
            }
            return out.toByteArray()
        }
    }
}
