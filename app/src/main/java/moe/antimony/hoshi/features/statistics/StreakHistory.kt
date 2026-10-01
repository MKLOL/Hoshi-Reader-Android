package moe.antimony.hoshi.features.statistics

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.features.reader.BookStatisticsInput
import moe.antimony.hoshi.features.reader.DailyReading
import moe.antimony.hoshi.mokuro.deduplicateMangaTextStatistics
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** A wall-clock boundary, not a fixed 24-hour duration: this also works on DST days. */
fun streakDate(now: ZonedDateTime, resetHour: Int): LocalDate =
    now.toLocalDate().let { if (now.hour < resetHour.coerceIn(0, 23)) it.minusDays(1) else it }

data class StreakHistory(val days: List<DailyReading>, val hasEstimatedHistory: Boolean)

/** The reading day an exact hour belongs to: before [reset] it is still the previous day. */
internal fun readingDayOf(hour: LocalDateTime, reset: Int): LocalDate =
    if (hour.hour < reset) hour.toLocalDate().minusDays(1) else hour.toLocalDate()

/**
 * One book's records regrouped into reading days: each day runs from [resetHour] until the same
 * hour the next morning, the day the streak counts, so nothing in the statistics changes day
 * at midnight. Records stay keyed by calendar date on disk (iOS and ッツ read them); only what
 * is shown is regrouped. Time comes from the exact hours; time recorded without a plausible
 * hour stays on its own date (unlike the streak's benefit of the doubt, a total must never
 * count it twice). Characters are only stored per calendar date, so each date's characters
 * follow where that date's time fell, rounded so the book's total is unchanged.
 * Newest day first; days with neither time nor characters are left out.
 */
fun readingDays(
    statistics: List<ReadingStatistics>,
    charactersByDate: Map<String, Int>,
    resetHour: Int,
): List<DailyReading> {
    val reset = resetHour.coerceIn(0, 23)
    val seconds = mutableMapOf<String, Double>()
    // Calendar date -> reading day -> seconds of that date's records that fell on that day.
    val split = mutableMapOf<String, MutableMap<String, Double>>()
    statistics.deduplicateReadingStatistics().forEach { entry ->
        val total = entry.readingTime.takeIf { it.isFinite() && it > 0.0 } ?: return@forEach
        val date = runCatching { LocalDate.parse(entry.dateKey) }.getOrNull()
        // With a midnight reset the reading day is the record's own date. Otherwise an hour
        // belongs to its record's date, or to the evening before when a tick crossed midnight;
        // anything else came from a skewed clock and counts as an unknown hour.
        val hours = if (reset == 0) emptyList() else entry.readingTimeByHour.mapNotNull { (key, value) ->
            val hour = runCatching { LocalDateTime.parse(key) }.getOrNull()
            val plausible = hour != null && date != null &&
                (hour.toLocalDate() == date || hour.toLocalDate() == date.minusDays(1))
            if (plausible && value.isFinite() && value > 0.0) hour to value else null
        }
        val known = hours.sumOf { it.second }
        // Bound malformed/imported hour maps to the record's counted time, as the streak does.
        val scale = if (known > total) total / known else 1.0
        val shares = split.getOrPut(entry.dateKey) { mutableMapOf() }
        fun credit(day: String, value: Double) {
            if (value <= 0.0) return
            seconds[day] = (seconds[day] ?: 0.0) + value
            shares[day] = (shares[day] ?: 0.0) + value
        }
        hours.forEach { (hour, value) -> credit(readingDayOf(hour, reset).toString(), value * scale) }
        credit(entry.dateKey, (total - known * scale).coerceAtLeast(0.0))
    }
    val characters = mutableMapOf<String, Int>()
    charactersByDate.forEach { (dateKey, read) ->
        if (read <= 0) return@forEach
        val shares = split[dateKey].orEmpty().filterValues { it > 0.0 }
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

/** The characters a book's records hold per calendar date: text for an EPUB, OCR text for a manga. */
internal fun BookStatisticsInput.charactersByDate(): Map<String, Int> = when (contentType) {
    ContentType.Epub -> statistics.deduplicateReadingStatistics()
        .groupBy { it.dateKey }.mapValues { (_, entries) -> entries.sumOf { it.charactersRead } }
    ContentType.Mokuro -> mangaTextStatistics.deduplicateMangaTextStatistics()
        .groupBy { it.dateKey }.mapValues { (_, entries) -> entries.sumOf { it.charactersRead } }
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

/**
 * Rebuild on every load/settings change, without rewriting calendar-day sidecars. Exact hours
 * travel with statistics. Legacy seconds without synced timing get the benefit of the doubt
 * on BOTH possible dates (D and D-1). Private usage logs must not change streak eligibility:
 * they differ by device, and using them can erase a day only on the originating device. Credit is only
 * for streaks, never added to reading totals, charts or characters. No missing day without
 * evidence of reading on that day or the following morning is filled in.
 */
fun reconstructStreakHistory(
    inputs: List<BookStatisticsInput>,
    resetHour: Int,
): StreakHistory {
    val reset = resetHour.coerceIn(0, 23)
    val days = mutableMapOf<LocalDate, Double>()
    var estimated = false
    fun credit(date: LocalDate, seconds: Double) {
        if (seconds > 0.0) days[date] = (days[date] ?: 0.0) + seconds
    }
    inputs.forEach { book ->
        book.statistics.deduplicateReadingStatistics().forEach entry@ { entry ->
            val date = runCatching { LocalDate.parse(entry.dateKey) }.getOrNull() ?: return@entry
            val total = entry.readingTime.takeIf { it.isFinite() && it > 0.0 } ?: return@entry
            val hours = entry.readingTimeByHour.mapNotNull { (key, seconds) ->
                val hour = runCatching { LocalDateTime.parse(key) }.getOrNull()
                if (hour != null && seconds.isFinite() && seconds > 0.0) hour to seconds else null
            }.toMap()
            val known = hours.values.sum()
            // Bound malformed/imported hour maps to the record's counted time.
            val scale = if (known > total) total / known else 1.0
            hours.forEach { (hour, seconds) ->
                credit(if (hour.hour < reset) hour.toLocalDate().minusDays(1) else hour.toLocalDate(), seconds * scale)
            }
            val unknown = (total - known).coerceAtLeast(0.0)
            if (unknown > 0.001) {
                credit(date, unknown)
                if (reset > 0) {
                    credit(date.minusDays(1), unknown)
                    estimated = true
                }
            }
        }
    }
    return StreakHistory(
        days.entries.sortedByDescending { it.key }.map { DailyReading(it.key.toString(), it.value, 0) },
        estimated,
    )
}
