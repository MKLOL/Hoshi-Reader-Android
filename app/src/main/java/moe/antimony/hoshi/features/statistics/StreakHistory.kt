package moe.antimony.hoshi.features.statistics

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.features.reader.BookStatisticsInput
import moe.antimony.hoshi.features.reader.DailyReading
import moe.antimony.hoshi.mokuro.deduplicateMangaTextStatistics
import moe.antimony.hoshi.epub.DAY_DEVICE_SEPARATOR
import moe.antimony.hoshi.epub.dayDeviceKey
import moe.antimony.hoshi.epub.localHourBuckets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** A wall-clock boundary, not a fixed 24-hour duration: this also works on DST days. */
fun streakDate(now: ZonedDateTime, resetHour: Int): LocalDate =
    now.toLocalDate().let { if (now.hour < resetHour.coerceIn(0, 23)) it.minusDays(1) else it }

/** The reading day an exact hour belongs to: before [reset] it is still the previous day. */
internal fun readingDayOf(hour: LocalDateTime, reset: Int): LocalDate =
    if (hour.hour < reset) hour.toLocalDate().minusDays(1) else hour.toLocalDate()

/**
 * One book's records regrouped into reading days, from [resetHour] to the same hour the
 * next morning on each recording device's clock. Records stay keyed by calendar date on
 * disk (iOS and ッツ read them). Time without a plausible hour stays on its recorded date.
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
): List<DailyReading> {
    val reset = resetHour.coerceIn(0, 23)
    val seconds = mutableMapOf<String, Double>()
    // Calendar date and device (and the date alone) -> reading day -> seconds of those records
    // that fell on that day.
    val split = mutableMapOf<String, MutableMap<String, Double>>()
    val splitByDate = mutableMapOf<String, MutableMap<String, Double>>()
    statistics.deduplicateReadingStatistics().forEach { entry ->
        val total = entry.readingTime.takeIf { it.isFinite() && it > 0.0 } ?: return@forEach
        val hours = entry.localReadingHours()
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
        credit(entry.dateKey, (total - known * scale).coerceAtLeast(0.0))
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
    return (seconds.keys + characters.keys).distinct()
        .map { DailyReading(it, seconds[it] ?: 0.0, characters[it] ?: 0) }
        .filter { it.seconds > 0.0 || it.characters > 0 }
        .sortedByDescending { it.dateKey }
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
): DailyReading {
    val key = day.toString()
    val days = inputs.mapNotNull { book ->
        readingDays(book.statistics, book.charactersByDate(), resetHour).firstOrNull { it.dateKey == key }
    }
    return DailyReading(key, days.sumOf { it.seconds }, days.sumOf { it.characters })
}
