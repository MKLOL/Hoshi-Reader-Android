package moe.antimony.hoshi.features.statistics

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.features.reader.BookStatisticsInput
import moe.antimony.hoshi.features.reader.DailyReading
import moe.antimony.hoshi.mokuro.deduplicateMangaTextStatistics
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** A wall-clock boundary, not a fixed 24-hour duration: this also works on DST days. */
fun streakDate(now: ZonedDateTime, resetHour: Int): LocalDate =
    now.toLocalDate().let { if (now.hour < resetHour.coerceIn(0, 23)) it.minusDays(1) else it }

data class StreakHistory(val days: List<DailyReading>, val hasEstimatedHistory: Boolean)

/** The reading day an exact hour belongs to: before [reset] it is still the previous day. */
private fun readingDayOf(hour: LocalDateTime, reset: Int): LocalDate =
    if (hour.hour < reset) hour.toLocalDate().minusDays(1) else hour.toLocalDate()

/**
 * What was read on one reading day: from [resetHour] on [day] until [resetHour] the next morning,
 * the same day the streak counts, so "Today" and the streak goal never disagree after midnight.
 * Time comes from the exact hours; time recorded without them stays on its own calendar date
 * (unlike the streak's benefit of the doubt, a total must never count it twice). Characters are
 * only stored per calendar day, so each day's characters follow where that day's time fell.
 */
fun readingDayTotals(
    inputs: List<BookStatisticsInput>,
    day: LocalDate,
    resetHour: Int,
): DailyReading {
    val reset = resetHour.coerceIn(0, 23)
    val dayKey = day.toString()
    var seconds = 0.0
    var characters = 0.0
    inputs.forEach { book ->
        val statistics = book.statistics.deduplicateReadingStatistics()
        val inWindow = mutableMapOf<String, Double>()
        val recorded = mutableMapOf<String, Double>()
        statistics.forEach { entry ->
            val total = entry.readingTime.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            val date = runCatching { LocalDate.parse(entry.dateKey) }.getOrNull()
            // An hour belongs to its record's date, or to the evening before when a tick crossed
            // midnight. Anything else came from a skewed clock and counts as an unknown hour.
            val hours = entry.readingTimeByHour.mapNotNull { (key, value) ->
                val hour = runCatching { LocalDateTime.parse(key) }.getOrNull()
                val plausible = hour != null && date != null &&
                    (hour.toLocalDate() == date || hour.toLocalDate() == date.minusDays(1))
                if (plausible && value.isFinite() && value > 0.0) hour to value else null
            }
            val known = hours.sumOf { it.second }
            // Bound malformed/imported hour maps to the record's counted time, as the streak does.
            val scale = if (known > total && known > 0.0) total / known else 1.0
            val unknown = (total - known * scale).coerceAtLeast(0.0)
            val counted = hours.filter { readingDayOf(it.first, reset) == day }.sumOf { it.second * scale } +
                if (entry.dateKey == dayKey) unknown else 0.0
            seconds += counted
            inWindow[entry.dateKey] = (inWindow[entry.dateKey] ?: 0.0) + counted
            recorded[entry.dateKey] = (recorded[entry.dateKey] ?: 0.0) + total
        }
        val charactersByDate: Map<String, Int> = when (book.contentType) {
            ContentType.Epub -> statistics.groupBy { it.dateKey }.mapValues { (_, entries) -> entries.sumOf { it.charactersRead } }
            ContentType.Mokuro -> book.mangaTextStatistics.deduplicateMangaTextStatistics()
                .groupBy { it.dateKey }.mapValues { (_, entries) -> entries.sumOf { it.charactersRead } }
        }
        charactersByDate.forEach { (dateKey, read) ->
            val time = recorded[dateKey] ?: 0.0
            val share = if (time > 0.0) (inWindow[dateKey] ?: 0.0) / time else if (dateKey == dayKey) 1.0 else 0.0
            characters += read * share
        }
    }
    return DailyReading(dayKey, seconds, characters.roundToInt())
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
