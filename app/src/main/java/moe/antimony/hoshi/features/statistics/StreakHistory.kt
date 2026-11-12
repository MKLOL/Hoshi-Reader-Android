package moe.antimony.hoshi.features.statistics

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.features.reader.BookStatisticsInput
import moe.antimony.hoshi.features.reader.DailyReading
import moe.antimony.hoshi.mokuro.deduplicateMangaTextStatistics
import moe.antimony.hoshi.epub.DAY_DEVICE_SEPARATOR
import moe.antimony.hoshi.epub.LEGACY_READING_ZONE
import moe.antimony.hoshi.epub.dayDeviceKey
import moe.antimony.hoshi.epub.legacySourceZone
import moe.antimony.hoshi.epub.localHourBuckets
import moe.antimony.hoshi.epub.zoneOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/** The first day eligible for travel-aware statistics on both platforms. */
internal val TRAVEL_READING_START: LocalDate = LocalDate.of(2026, 11, 1)

// At most 26 hours of zone difference and a 23-hour reset can move a bucket back three dates.
private val UNAMBIGUOUS_TRAVEL_DATE = TRAVEL_READING_START.plusDays(3)

internal fun legacyStatisticsZone(id: String?): ZoneId = zoneOrNull(id) ?: LEGACY_READING_ZONE

internal fun legacyStatisticsCutoff(resetHour: Int, legacyZone: ZoneId): Instant =
    TRAVEL_READING_START.atTime(resetHour.coerceIn(0, 23), 0).atZone(legacyZone).toInstant()

internal fun statisticsClock(now: ZonedDateTime, resetHour: Int, legacyZone: ZoneId): ZonedDateTime =
    if (now.toInstant() < legacyStatisticsCutoff(resetHour, legacyZone)) now.withZoneSameInstant(legacyZone) else now

/** A wall-clock boundary, not a fixed 24-hour duration: this also works on DST days. */
fun streakDate(now: ZonedDateTime, resetHour: Int): LocalDate =
    now.toLocalDate().let { if (now.hour < resetHour.coerceIn(0, 23)) it.minusDays(1) else it }

/** The reading day an exact hour belongs to: before [reset] it is still the previous day. */
internal fun readingDayOf(hour: LocalDateTime, reset: Int): LocalDate =
    if (hour.hour < reset) hour.toLocalDate().minusDays(1) else hour.toLocalDate()

/**
 * One book's records regrouped into reading days. Through October 2026, days follow the
 * previously selected shared clock. Starting in November, each recording device's clock
 * applies. Records stay keyed by calendar date on disk (iOS and ッツ read them). Time without
 * a plausible hour stays on its recorded date. Only a streak projection estimates extra
 * historical credit on the other possible day.
 * Characters are stored per calendar date and device, then divided according to where
 * that device's time fell, rounded so the book's total stays the same.
 * [charactersByDate] is keyed by [dayDeviceKey], or by the calendar date alone, which then
 * follows every device's time on that date.
 * Newest day first; days with neither time nor characters are left out.
 */
fun readingDays(
    statistics: List<ReadingStatistics>,
    charactersByDate: Map<String, Int>,
    resetHour: Int,
    legacyZone: ZoneId = LEGACY_READING_ZONE,
    includeEstimatedStreakCredit: Boolean = false,
): List<DailyReading> {
    val reset = resetHour.coerceIn(0, 23)
    val seconds = mutableMapOf<String, Double>()
    val estimatedStreakCredit = mutableMapOf<String, Double>()
    // Calendar date and device (and the date alone) -> reading day -> seconds of those records
    // that fell on that day.
    val split = mutableMapOf<String, MutableMap<String, Double>>()
    val splitByDate = mutableMapOf<String, MutableMap<String, Double>>()
    statistics.deduplicateReadingStatistics().forEach { entry ->
        val total = entry.readingTime.takeIf { it.isFinite() && it > 0.0 } ?: return@forEach
        val hours = entry.statisticsReadingHours(reset, legacyZone)
        val known = hours.sumOf { it.second }
        // Bound malformed or imported hour maps to the record's counted time.
        val scale = if (known > total) total / known else 1.0
        val shares = split.getOrPut(dayDeviceKey(entry.dateKey, entry.deviceId)) { mutableMapOf() }
        val dateShares = splitByDate.getOrPut(entry.dateKey) { mutableMapOf() }
        fun credit(day: String, value: Double) {
            if (value <= 0.0) return
            seconds[day] = (seconds[day] ?: 0.0) + value
            shares[day] = (shares[day] ?: 0.0) + value
            dateShares[day] = (dateShares[day] ?: 0.0) + value
        }
        hours.forEach { (hour, value) -> credit(readingDayOf(hour, reset).toString(), value * scale) }
        val unknown = (total - known * scale).coerceAtLeast(0.0)
        credit(entry.dateKey, unknown)
        if (includeEstimatedStreakCredit && reset > 0 && unknown > 0.001) {
            val date = runCatching { LocalDate.parse(entry.dateKey) }.getOrNull()
            if (date != null && date < TRAVEL_READING_START) {
                val previous = date.minusDays(1).toString()
                estimatedStreakCredit[previous] = (estimatedStreakCredit[previous] ?: 0.0) + unknown
            }
        }
    }
    val characters = mutableMapOf<String, Int>()
    charactersByDate.forEach { (key, read) ->
        if (read <= 0) return@forEach
        val dateKey = key.substringBefore(DAY_DEVICE_SEPARATOR)
        // That device's time on that date; else every device's (characters without time of their own).
        val shares = (split[key]?.filterValues { it > 0.0 }?.takeIf { it.isNotEmpty() } ?: splitByDate[dateKey])
            .orEmpty().filterValues { it > 0.0 }
        if (shares.isEmpty()) {
            characters[dateKey] = (characters[dateKey] ?: 0) + read
            return@forEach
        }
        // Largest remainder, so the date's characters add up exactly.
        val time = shares.values.sum()
        val exact = shares.mapValues { (_, value) -> read * value / time }
        val floors = exact.mapValues { (_, value) -> value.toInt() }.toMutableMap()
        var left = read - floors.values.sum()
        exact.entries.sortedByDescending { it.value - it.value.toInt() }.forEach { (day, _) ->
            if (left > 0) {
                floors[day] = floors.getValue(day) + 1
                left -= 1
            }
        }
        floors.forEach { (day, value) -> characters[day] = (characters[day] ?: 0) + value }
    }
    return (seconds.keys + characters.keys + estimatedStreakCredit.keys)
        .map { DailyReading(it, seconds[it] ?: 0.0, characters[it] ?: 0, estimatedStreakCredit[it] ?: 0.0) }
        .sortedByDescending { it.dateKey }
}

/** Preserve each bucket's old reading day if it belonged to the pre-travel history. */
internal fun ReadingStatistics.statisticsReadingHours(reset: Int, legacyZone: ZoneId): List<Pair<LocalDateTime, Double>> {
    val hours = localReadingHours()
    if (hours.none { (hour, _) -> hour.toLocalDate() < UNAMBIGUOUS_TRAVEL_DATE }) return hours
    val splitByHour = readingTimeByZone.flatMap { (id, buckets) ->
        val source = zoneOrNull(id) ?: LEGACY_READING_ZONE
        buckets.entries.mapNotNull { (key, seconds) ->
            val hour = runCatching { LocalDateTime.parse(key) }.getOrNull()
            if (hour != null && seconds.isFinite() && seconds > 0.0) hour to (source to seconds) else null
        }
    }.groupBy({ it.first }, { it.second })
    val defaultSource = legacySourceZone(legacyZone)
    return hours.flatMap { (hour, seconds) ->
        if (hour.toLocalDate() >= UNAMBIGUOUS_TRAVEL_DATE) return@flatMap listOf(hour to seconds)
        // The flat hours are canonical. Use finer zone metadata only if it accounts for them.
        val shares = splitByHour[hour]?.takeIf { abs(it.sumOf { share -> share.second } - seconds) <= 0.001 }
            ?: listOf(defaultSource to seconds)
        shares.map { (source, share) ->
            val sharedHour = if (source == legacyZone) hour else hour.atZone(source).withZoneSameInstant(legacyZone).toLocalDateTime()
            val clockHour = if (readingDayOf(sharedHour, reset) < TRAVEL_READING_START) sharedHour else hour
            clockHour to share
        }
    }
}

/**
 * Date/hour keys already contain the recording device's local time. Legacy keys with no
 * zone are US Eastern wall-clock times. A tick that crossed midnight can include the prior
 * date; untrusted imported keys beyond that range are credited to the row's stored date.
 */
internal fun ReadingStatistics.localReadingHours(): List<Pair<LocalDateTime, Double>> {
    val date = runCatching { LocalDate.parse(dateKey) }.getOrNull() ?: return emptyList()
    val previous = date.minusDays(1)
    return localHourBuckets().mapNotNull { (key, seconds) ->
        val hour = runCatching { LocalDateTime.parse(key) }.getOrNull() ?: return@mapNotNull null
        if (hour.toLocalDate() in previous..date && seconds.isFinite() && seconds > 0.0) hour to seconds else null
    }
}

/**
 * The characters a book's records hold per calendar date and device ([dayDeviceKey]): text for
 * an EPUB, OCR text for a manga.
 */
internal fun BookStatisticsInput.charactersByDate(): Map<String, Int> = when (contentType) {
    ContentType.Epub -> statistics.deduplicateReadingStatistics()
        .groupBy { dayDeviceKey(it.dateKey, it.deviceId) }.mapValues { (_, entries) -> entries.sumOf { it.charactersRead } }
    ContentType.Mokuro -> mangaTextStatistics.deduplicateMangaTextStatistics()
        .groupBy { dayDeviceKey(it.dateKey, it.deviceId) }.mapValues { (_, entries) -> entries.sumOf { it.charactersRead } }
}

/** What every listed book read on one reading day (see [readingDays]). */
fun readingDayTotals(
    inputs: List<BookStatisticsInput>,
    day: LocalDate,
    resetHour: Int,
    legacyZone: ZoneId = LEGACY_READING_ZONE,
): DailyReading {
    val key = day.toString()
    val days = inputs.mapNotNull { book ->
        readingDays(book.statistics, book.charactersByDate(), resetHour, legacyZone).firstOrNull { it.dateKey == key }
    }
    return DailyReading(key, days.sumOf { it.seconds }, days.sumOf { it.characters })
}
