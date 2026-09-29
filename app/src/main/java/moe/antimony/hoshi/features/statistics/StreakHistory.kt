package moe.antimony.hoshi.features.statistics

import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.epub.readingHoursBetween
import moe.antimony.hoshi.features.reader.BookStatisticsInput
import moe.antimony.hoshi.features.reader.DailyReading
import moe.antimony.hoshi.features.usage.UsageEvent
import moe.antimony.hoshi.features.usage.UsageEventType
import moe.antimony.hoshi.features.usage.UsageLog
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** A wall-clock boundary, not a fixed 24-hour duration: this also works on DST days. */
fun streakDate(now: ZonedDateTime, resetHour: Int): LocalDate =
    now.toLocalDate().let { if (now.hour < resetHour.coerceIn(0, 23)) it.minusDays(1) else it }

data class StreakHistory(val days: List<DailyReading>, val hasEstimatedHistory: Boolean)

/**
 * Rebuild on every load/settings change, without rewriting calendar-day sidecars. Exact hours
 * travel with statistics. Older local logs recover time where available; remaining legacy
 * seconds get the benefit of the doubt on BOTH possible dates (D and D-1). This credit is only
 * for streaks, never added to reading totals, charts or characters. No missing day without
 * evidence of reading on that day or the following morning is filled in.
 */
fun reconstructStreakHistory(
    inputs: List<BookStatisticsInput>,
    resetHour: Int,
    loggedHours: Map<String, Map<String, Double>> = emptyMap(),
    localDeviceId: String? = null,
): StreakHistory {
    val reset = resetHour.coerceIn(0, 23)
    // Parse/index once: a long legacy history must not rescan a book's lifetime hours per day.
    val logsByBookDate = loggedHours.mapValues { (_, hours) ->
        hours.mapNotNull { (key, seconds) ->
            runCatching { LocalDateTime.parse(key) }.getOrNull()?.let { it to seconds }
        }.groupBy { it.first.toLocalDate() }
    }
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
            }.toMap().toMutableMap()
            // The log is device-local. Never use it to reconstruct another device's totals.
            if (hours.values.sum() < total && localDeviceId != null && entry.deviceId == localDeviceId) {
                logsByBookDate[book.bookId]?.get(date).orEmpty().forEach { (hour, seconds) ->
                    if (seconds.isFinite() && seconds > 0.0) {
                        // New persisted hours and the log describe the same seconds.
                        hours[hour] = maxOf(hours[hour] ?: 0.0, seconds)
                    }
                }
            }
            val known = hours.values.sum()
            // Log writes can straddle the tracker's last tick. Never manufacture counted time.
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

/** Only completed, counted spans are evidence; reader-open duration includes paused time. */
internal fun historicalReadingHours(events: List<UsageEvent>): Map<String, Map<String, Double>> {
    val result = mutableMapOf<String, MutableMap<String, Double>>()
    val starts = events.filter { it.type == UsageEventType.ReadingStarted }
        .associateBy { Triple(it.bookId, it.session, it.at) }
    events.distinctBy { listOf(it.bookId, it.session, it.type, it.startedAt, it.at) }.forEach { event ->
        if (event.type != UsageEventType.ReadingStopped) return@forEach
        val bookId = event.bookId ?: return@forEach
        val start = event.startedAt?.takeIf { it < event.at } ?: return@forEach
        // A span crossing an offset change cannot be placed exactly from just its two offsets.
        // Missing starts and travel/DST spans remain unknown and receive optimistic credit.
        val startEvent = starts[Triple(bookId, event.session, start)] ?: return@forEach
        if (startEvent.utcOffset != event.utcOffset) return@forEach
        val offset = runCatching { ZoneOffset.of(event.utcOffset) }.getOrNull() ?: return@forEach
        val hours = result.getOrPut(bookId) { mutableMapOf() }
        readingHoursBetween(start, event.at, offset).forEach { (key, seconds) ->
            hours[key] = (hours[key] ?: 0.0) + seconds
        }
    }
    return result
}

internal suspend fun loadHistoricalReadingHours(log: UsageLog): Map<String, Map<String, Double>> {
    val today = log.dateOf(System.currentTimeMillis())
    val events = log.dayFiles().flatMap { file ->
        val date = runCatching { LocalDate.parse(file.nameWithoutExtension) }.getOrNull()
        if (date == null) emptyList() else log.finishedDayReadingEvents[date] ?: log.eventsOn(date).filter {
            it.type == UsageEventType.ReadingStopped || it.type == UsageEventType.ReadingStarted
        }.also { if (date < today) log.finishedDayReadingEvents[date] = it }
    }
    return historicalReadingHours(events)
}
