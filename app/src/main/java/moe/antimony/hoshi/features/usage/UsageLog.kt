package moe.antimony.hoshi.features.usage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A local, append-only record of what the reader did and when: reading spans, page turns,
 * words looked up, bubble and screenshot translations. It is detailed enough to rebuild a
 * timeline of any day, which the daily statistics (one total per book per day) cannot.
 *
 * Each local calendar day is one file, `yyyy-MM-dd.ndjson`, with one JSON [UsageEvent] per
 * line. Every write and read runs on one background thread in the order it was requested, so
 * events never interleave or reorder and a read always sees every event recorded before it.
 * HTTP sync sends this device's day files to its own keys and keeps other devices' copies in
 * `devices/{deviceId}/` ([storeDeviceDay]); reads include them, so every device's lookups,
 * bubbles and reading spans count. The folder is excluded from Android backup and transfer.
 * Use [forDirectory] in the app so every activity shares one writer.
 */
class UsageLog(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    dispatcher: CoroutineDispatcher? = null,
) {
    private val io: CoroutineDispatcher = dispatcher
        ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hoshi-usage-log").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val writes = MutableStateFlow(0L)

    /** Counts written events; collect it to refresh anything that shows the log. */
    val changes: StateFlow<Long> = writes.asStateFlow()

    /**
     * Totals of reading days that are over, keyed by day, reset hour and zone: their files no
     * longer change, so they are read once per reset hour and zone.
     */
    internal val finishedDayCounts: MutableMap<UsageDayKey, UsageDayCounts> = ConcurrentHashMap()

    /** Changes whenever a finished day's files change; a load only caches what it read under one value. */
    @Volatile internal var cacheGeneration: Long = 0L
        private set

    /** Forgets the finished days a change to the files named [date] can affect (their reading days, either side). */
    private fun invalidateAround(date: LocalDate) {
        cacheGeneration += 1
        finishedDayCounts.keys.removeAll { it.date in date.minusDays(2)..date.plusDays(2) }
    }

    /** A new event of [type] stamped with the current time and UTC offset. */
    fun newEvent(type: UsageEventType): UsageEvent {
        val at = clock()
        return UsageEvent(at = at, utcOffset = offsetAt(at), type = type)
    }

    /** Appends [event] to its day's file without blocking the caller. */
    fun append(event: UsageEvent) {
        scope.launch {
            val written = try {
                val file = fileFor(dateOf(event.at))
                file.parentFile?.mkdirs()
                // A line cut short by a crash or a full disk has no newline; start a fresh line
                // so this event is not glued onto the broken one and lost with it.
                val prefix = if (file.length() > 0 && !file.endsWithNewline()) "\n" else ""
                file.appendText(prefix + json.encodeToString(UsageEvent.serializer(), event) + "\n")
                true
            } catch (_: Exception) {
                // Whatever goes wrong loses this one event; logging must never break reading.
                false
            }
            if (written) writes.update { it + 1 }
        }
    }

    /**
     * Every event in the day files named [date] (this device's calendar day, and each other
     * device's own), oldest first; an unreadable file adds nothing.
     */
    suspend fun eventsOn(date: LocalDate): List<UsageEvent> = withContext(io) {
        val name = DateTimeFormatter.ISO_LOCAL_DATE.format(date) + FILE_SUFFIX
        (listOf(fileFor(date)) + deviceFolders().map { File(it, name) })
            .flatMap(::readEvents)
            .sortedBy { it.at }
    }

    /**
     * When logging began on any device, in epoch milliseconds: the start of this device's first
     * day file, and the first event in each other device's (named by that device's own calendar,
     * whose zone this device does not know); null when there is none.
     */
    suspend fun firstLoggedAt(): Long? = withContext(io) {
        fun firstFile(folder: File) = dayFilesIn(folder)
            .mapNotNull { file -> runCatching { LocalDate.parse(file.name.removeSuffix(FILE_SUFFIX)) }.getOrNull()?.let { it to file } }
            .minByOrNull { it.first }
        val own = firstFile(directory)?.let { (date, _) -> startOf(date) }
        val others = deviceFolders().mapNotNull { folder -> firstFile(folder)?.second?.let(::readEvents)?.minOfOrNull { it.at } }
        (listOfNotNull(own) + others).minOrNull()
    }

    /** This device's own day file for [date] as stored, or null when there is none. */
    suspend fun ownDayBytes(date: LocalDate): ByteArray? = withContext(io) {
        fileFor(date).takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
    }

    /**
     * Adds the lines of [text] this device's own file for [date] does not hold yet: what an
     * earlier install of this device sent before it was reinstalled. True when it changed.
     */
    suspend fun mergeOwnDay(date: LocalDate, text: String): Boolean = withContext(io) {
        val file = fileFor(date)
        val existing = if (file.isFile) file.readLines().filter { it.isNotBlank() }.toHashSet() else HashSet()
        // Every line is kept, even one this build cannot read (a newer build's event): reads skip those.
        val missing = text.lineSequence().filter { it.isNotBlank() && it !in existing }.distinct().toList()
        if (missing.isEmpty()) return@withContext false
        file.parentFile?.mkdirs()
        val prefix = if (file.length() > 0 && !file.endsWithNewline()) "\n" else ""
        file.appendText(prefix + missing.joinToString("\n", postfix = "\n"))
        invalidateAround(date)
        writes.update { it + 1 }
        true
    }

    /** Stores another device's own day file for [date] as it sent it (replacing an older copy). */
    suspend fun storeDeviceDay(deviceId: String, date: LocalDate, text: String) = withContext(io) {
        val folder = File(File(directory, DEVICES_DIRECTORY), deviceId)
        folder.mkdirs()
        val target = File(folder, DateTimeFormatter.ISO_LOCAL_DATE.format(date) + FILE_SUFFIX)
        val temporary = File(folder, target.name + ".tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(target)) {
            target.delete()
            check(temporary.renameTo(target)) { "Could not store $target" }
        }
        invalidateAround(date)
        writes.update { it + 1 }
    }

    /** Drops every other device's day files (another sync account's). */
    suspend fun clearDeviceDays() = withContext(io) {
        File(directory, DEVICES_DIRECTORY).deleteRecursively()
        cacheGeneration += 1
        finishedDayCounts.clear()
        writes.update { it + 1 }
    }

    /** Where HTTP sync remembers which day files it sent and received for this log. */
    val syncIndexFile: File get() = File(directory, SYNC_INDEX_FILE_NAME)

    private fun deviceFolders(): List<File> =
        runCatching { File(directory, DEVICES_DIRECTORY).listFiles { file -> file.isDirectory }?.toList() }.getOrNull().orEmpty()

    private fun dayFilesIn(folder: File): List<File> =
        runCatching { folder.listFiles { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }?.toList() }.getOrNull().orEmpty()

    private fun readEvents(file: File): List<UsageEvent> {
        if (!file.isFile) return emptyList()
        return try {
            file.useLines { lines -> lines.mapNotNull { line -> line.takeIf { it.isNotBlank() }?.let(::decodeOrNull) }.toList() }
        } catch (_: IOException) {
            emptyList()
        }
    }

    /** This device's own day files, oldest first, for exporting the raw log and for sync. */
    suspend fun dayFiles(): List<File> = withContext(io) {
        try {
            directory.listFiles { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }
                ?.sortedBy { it.name }
                .orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /** The local calendar day an epoch-millisecond instant falls on. */
    fun dateOf(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone()).toLocalDate()

    /** The first instant of the local calendar day [date], whose events [eventsOn] reads. */
    fun startOf(date: LocalDate): Long = date.atStartOfDay(zone()).toInstant().toEpochMilli()

    private fun fileFor(date: LocalDate): File =
        File(directory, DateTimeFormatter.ISO_LOCAL_DATE.format(date) + FILE_SUFFIX)

    private fun File.endsWithNewline(): Boolean =
        java.io.RandomAccessFile(this, "r").use { file ->
            file.seek(file.length() - 1)
            file.read() == '\n'.code
        }

    private fun offsetAt(epochMillis: Long): String =
        zone().rules.getOffset(Instant.ofEpochMilli(epochMillis)).id.let { if (it == "Z") "+00:00" else it }

    private fun decodeOrNull(line: String): UsageEvent? =
        try {
            json.decodeFromString(UsageEvent.serializer(), line)
        } catch (_: SerializationException) {
            // A line cut short by a crash mid-write is skipped, not fatal to the whole day.
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    companion object {
        const val DIRECTORY_NAME: String = "usage-log"
        internal const val FILE_SUFFIX = ".ndjson"

        /** Other devices' day files, by device id. */
        private const val DEVICES_DIRECTORY = "devices"
        private const val SYNC_INDEX_FILE_NAME = ".http_sync_usage.json"

        private val shared = ConcurrentHashMap<String, UsageLog>()

        /**
         * The process-wide log for [directory]. Each activity builds its own app container,
         * and two logs on one folder would each run a writer thread appending to the same files.
         */
        fun forDirectory(directory: File): UsageLog =
            shared.computeIfAbsent(directory.absolutePath) { UsageLog(directory) }

        private val json = Json {
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}
