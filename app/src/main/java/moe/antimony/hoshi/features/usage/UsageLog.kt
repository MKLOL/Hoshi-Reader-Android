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
import java.util.concurrent.Executors

/**
 * A local, append-only record of what the reader did and when: reading spans, page turns,
 * words looked up, bubble and screenshot translations. It is detailed enough to rebuild a
 * timeline of any day, which the daily statistics (one total per book per day) cannot.
 *
 * Each local calendar day is one file, `yyyy-MM-dd.ndjson`, with one JSON [UsageEvent] per
 * line. Every write and read runs on one background thread in the order it was requested, so
 * events never interleave or reorder and a read always sees every event recorded before it.
 * Nothing here leaves the device.
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
                file.appendText(json.encodeToString(UsageEvent.serializer(), event) + "\n")
                true
            } catch (_: IOException) {
                // A full or read-only disk loses this event; it must never break reading.
                false
            }
            if (written) writes.update { it + 1 }
        }
    }

    /** Every event recorded on the local calendar day [date], oldest first. */
    suspend fun eventsOn(date: LocalDate): List<UsageEvent> = withContext(io) {
        val file = fileFor(date)
        if (!file.isFile) return@withContext emptyList()
        file.useLines { lines ->
            lines.mapNotNull { line -> line.takeIf { it.isNotBlank() }?.let(::decodeOrNull) }
                .sortedBy { it.at }
                .toList()
        }
    }

    /** The day files on disk, oldest first, for exporting the raw log. */
    suspend fun dayFiles(): List<File> = withContext(io) {
        directory.listFiles { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    /** The local calendar day an epoch-millisecond instant falls on. */
    fun dateOf(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone()).toLocalDate()

    private fun fileFor(date: LocalDate): File =
        File(directory, DateTimeFormatter.ISO_LOCAL_DATE.format(date) + FILE_SUFFIX)

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
        private const val FILE_SUFFIX = ".ndjson"

        private val json = Json {
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}
