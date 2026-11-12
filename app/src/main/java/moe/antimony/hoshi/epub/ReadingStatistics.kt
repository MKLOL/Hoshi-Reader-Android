package moe.antimony.hoshi.epub

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReadingStatistics(
    val title: String,
    val dateKey: String,
    val charactersRead: Int = 0,
    val readingTime: Double = 0.0,
    val minReadingSpeed: Int = 0,
    val altMinReadingSpeed: Int = 0,
    val lastReadingSpeed: Int = 0,
    val maxReadingSpeed: Int = 0,
    val lastStatisticModified: Long = 0,
    /**
     * The device this day was read on (see [DeviceIdentity]). Every device keeps its own entry
     * per day, so totals are sums across devices and the Statistics screens can show how much
     * was read on which device. `null` only in files written before devices were tracked;
     * [BookRepository] attributes those to the device that wrote them. Left out of the JSON
     * while null so a device-less file keeps the exact shape iOS and ッツ write.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val deviceId: String? = null,
    /** The device's name when this day was recorded; the newest entry's name is shown. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val deviceName: String? = null,
    /** Counted seconds by local ISO date/hour, with a partial hour at the travel cutoff.
     * Missing time is legacy history with unknown time of day. Calendar totals stay compatible
     * with iOS; a tick across midnight can contain an hour from the preceding date.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val readingTimeByHour: Map<String, Double> = emptyMap(),
    /**
     * The recording device's time zone for a uniform row. Older records without a zone
     * use US Eastern. A row that spans zones names them in [readingTimeByZone].
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val timeZone: String? = null,
    /** Collapsed TTU history has one shared origin, even when imported on several devices. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val importedBaseline: ImportedReadingBaseline? = null,
    /**
     * Completed manga pages by active reading duration, rounded to seconds. A histogram keeps
     * the median reproducible across openings/devices without retaining a record per turn.
     * Like the other counters, this is cumulative for this (calendar day, device) only.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val mangaPageSeconds: Map<Int, Int> = emptyMap(),
    /** Local hourly buckets split by time zone when this same device/date spans zones. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val readingTimeByZone: Map<String, Map<String, Double>> = emptyMap(),
)

/** The device counters already included in one external day total. Never used by a tracker. */
@Serializable
data class ImportedReadingCheckpoint(
    val charactersRead: Int,
    val readingTime: Double,
    val lastStatisticModified: Long,
    val readingTimeByHour: Map<String, Double> = emptyMap(),
    val timeZone: String? = null,
)

@Serializable
data class ImportedReadingBaseline(
    val charactersRead: Int,
    val readingTime: Double,
    val sourceModified: Long,
    val readingTimeByHour: Map<String, Double> = emptyMap(),
    val timeZone: String? = null,
    val includedDevices: Map<String, ImportedReadingCheckpoint> = emptyMap(),
)

/** A stable shared bucket: importing the same TTU day on a second device is not new reading. */
const val TTU_STATISTICS_DEVICE_ID: String = "hoshi:ttu"

internal fun ReadingStatistics.withImportedResidual(): ReadingStatistics {
    val baseline = importedBaseline ?: return this
    val seconds = (baseline.readingTime - baseline.includedDevices.values.sumOf { it.readingTime }).coerceAtLeast(0.0)
    val characters = (baseline.charactersRead - baseline.includedDevices.values.sumOf { it.charactersRead }).coerceAtLeast(0)
    val hours = if (baseline.readingTimeByHour.isNotEmpty() && baseline.includedDevices.values.all {
        kotlin.math.abs(it.readingTime - it.readingTimeByHour.values.sum()) < 0.001 &&
            (it.timeZone == null || baseline.timeZone == null || it.timeZone == baseline.timeZone)
    }) {
        val includedHours = baseline.includedDevices.values.map { it.readingTimeByHour }.sumReadingHours()
        baseline.readingTimeByHour.mapValues { (hour, value) -> (value - (includedHours[hour] ?: 0.0)).coerceAtLeast(0.0) }
            .filterValues { it > 0.0 }.takeIf { it.values.sum() <= seconds + 0.001 }.orEmpty()
    } else emptyMap()
    return copy(
        readingTime = seconds,
        charactersRead = characters,
        lastReadingSpeed = if (seconds > 0.0) (characters / seconds * 3600.0).toInt() else 0,
        readingTimeByHour = hours,
        timeZone = baseline.timeZone.takeIf { hours.isNotEmpty() },
        readingTimeByZone = emptyMap(),
    )
}

private fun mergeImportedBaseline(first: ReadingStatistics, second: ReadingStatistics): ReadingStatistics? {
    val left = first.importedBaseline ?: return null
    val right = second.importedBaseline ?: return null
    if (left.copy(includedDevices = emptyMap()) != right.copy(includedDevices = emptyMap())) return null
    val included = (left.includedDevices.keys + right.includedDevices.keys).sorted().associateWith { device ->
        val a = left.includedDevices[device]
        val b = right.includedDevices[device]
        if (a == null) b!! else if (b == null) a else {
            // Same-source imports on two devices may know different device counters. Keep
            // the newest checkpoint for each origin, independent of merge order.
            listOf(a, b).maxWith(compareBy<ImportedReadingCheckpoint> { it.lastStatisticModified }
                .thenBy { it.readingTime }.thenBy { it.charactersRead })
        }
    }
    return first.copy(
        lastStatisticModified = maxOf(first.lastStatisticModified, second.lastStatisticModified),
        importedBaseline = left.copy(includedDevices = included),
    ).withImportedResidual()
}

/** Entries recorded before devices were tracked belong to [device], the device that wrote them. */
fun List<ReadingStatistics>.attributedTo(device: DeviceIdentity): List<ReadingStatistics> =
    map { if (it.deviceId == null) it.copy(deviceId = device.id, deviceName = device.name) else it }

/**
 * One entry per day with every device's time and characters added up: the device-less view
 * ッツ Reader statistics files expect. Speed fields are recomputed from the sums.
 */
fun List<ReadingStatistics>.collapsedByDay(): List<ReadingStatistics> =
    deduplicateReadingStatistics()
        .groupBy { it.dateKey }
        .map { (_, entries) ->
            val newest = entries.maxBy { it.lastStatisticModified }
            val readingTime = entries.sumOf { it.readingTime }
            val charactersRead = entries.sumOf { it.charactersRead }
            newest.copy(
                readingTime = readingTime,
                charactersRead = charactersRead,
                lastReadingSpeed = if (readingTime > 0.0) (charactersRead / readingTime * 3600.0).toInt() else 0,
                minReadingSpeed = entries.filter { it.minReadingSpeed > 0 }.minOfOrNull { it.minReadingSpeed } ?: 0,
                altMinReadingSpeed = entries.filter { it.altMinReadingSpeed > 0 }.minOfOrNull { it.altMinReadingSpeed } ?: 0,
                maxReadingSpeed = entries.maxOf { it.maxReadingSpeed },
                deviceId = null,
                deviceName = null,
                readingTimeByHour = entries.map { it.readingTimeByHour }.sumReadingHours(),
                timeZone = entries.map { it.timeZone }.distinct().singleOrNull(),
                readingTimeByZone = emptyMap(),
                importedBaseline = null,
                // TTU's shared imported baseline cannot own a device's page samples.
                mangaPageSeconds = emptyMap(),
            )
        }

/**
 * All-time totals of a book's per-day statistics. Every screen that shows a book's reading
 * time or amount read derives it from this one function, so the reader's Statistics sheet
 * and the Statistics screens can never disagree about the same `statistics.json`.
 */
data class ReadingTotals(
    val readingTime: Double,
    val charactersRead: Int,
) {
    /** Characters (or manga pages) per hour over the whole recorded time; 0 when nothing was read. */
    val readingSpeed: Int
        get() = if (readingTime > 0.0) (charactersRead.toDouble() / readingTime * 3600.0).toInt() else 0
}

fun List<ReadingStatistics>.readingTotals(): ReadingTotals {
    val deduplicated = deduplicateReadingStatistics()
    return ReadingTotals(
        readingTime = deduplicated.sumOf { it.readingTime },
        charactersRead = deduplicated.sumOf { it.charactersRead },
    )
}

/**
 * Collapses duplicate (day, device) entries, keeping the most recently modified one. Two
 * devices' entries for the same day are both kept: they are different reading.
 */
fun List<ReadingStatistics>.deduplicateReadingStatistics(): List<ReadingStatistics> =
    fold(linkedMapOf<String, ReadingStatistics>()) { grouped, statistic ->
        val key = dayDeviceKey(statistic.dateKey, statistic.deviceId)
        val existing = grouped[key]
        val mergedBaseline = existing?.let { mergeImportedBaseline(it, statistic) }
        val newerImportEvidence = existing != null && statistic.lastStatisticModified == existing.lastStatisticModified &&
            statistic.importedBaseline != null && (existing.importedBaseline == null || compareValuesBy(
                statistic.importedBaseline, existing.importedBaseline,
                { it.sourceModified }, { it.readingTime }, { it.charactersRead },
            ) > 0)
        // Older sync clients strip fields they do not understand. On an otherwise identical
        // equal-stamp entry, retain the more complete timing evidence instead of erasing it:
        // more located hours, or the same hours with the zone they were recorded in.
        val sameCounters = existing != null && statistic.lastStatisticModified == existing.lastStatisticModified &&
            statistic.copy(readingTimeByHour = emptyMap(), timeZone = null, mangaPageSeconds = emptyMap(), readingTimeByZone = emptyMap()) ==
            existing.copy(readingTimeByHour = emptyMap(), timeZone = null, mangaPageSeconds = emptyMap(), readingTimeByZone = emptyMap())
        val richerTiming = sameCounters &&
            (statistic.readingTimeByHour.values.sum() > existing.readingTimeByHour.values.sum() ||
                (statistic.readingTimeByHour == existing.readingTimeByHour && statistic.zoneEvidence() > existing.zoneEvidence()))
        if (mergedBaseline != null) {
            grouped[key] = mergedBaseline
        } else if (existing == null || statistic.lastStatisticModified > existing.lastStatisticModified || richerTiming ||
            newerImportEvidence) {
            grouped[key] = statistic.withImportedResidual()
        }
        if (existing != null) {
            // Samples are append-only per origin, independently of the scalar timestamp. A
            // same-day restore or older client can send a newer row missing earlier samples.
            // Maxima retain that evidence; summing snapshots would count every retry twice.
            grouped[key] = grouped.getValue(key).copy(
                mangaPageSeconds = (existing.mangaPageSeconds.keys + statistic.mangaPageSeconds.keys).sorted().associateWith { seconds ->
                    maxOf(existing.mangaPageSeconds[seconds] ?: 0, statistic.mangaPageSeconds[seconds] ?: 0)
                },
            )
        }
        grouped
    }.values.toList()

private fun ReadingStatistics.zoneEvidence(): Int = when {
    readingTimeByZone.isNotEmpty() -> 2
    timeZone != null -> 1
    else -> 0
}
