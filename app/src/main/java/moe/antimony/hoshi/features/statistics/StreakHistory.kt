package moe.antimony.hoshi.features.statistics

import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.features.reader.BookStatisticsInput
import moe.antimony.hoshi.features.reader.DailyReading
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** A wall-clock boundary, not a fixed 24-hour duration: this also works on DST days. */
fun streakDate(now: ZonedDateTime, resetHour: Int): LocalDate =
    now.toLocalDate().let { if (now.hour < resetHour.coerceIn(0, 23)) it.minusDays(1) else it }

data class StreakHistory(val days: List<DailyReading>, val hasEstimatedHistory: Boolean)

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
