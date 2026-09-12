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
)

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
        if (existing == null || statistic.lastStatisticModified > existing.lastStatisticModified) {
            grouped[key] = statistic
        }
        grouped
    }.values.toList()
