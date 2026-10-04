package moe.antimony.hoshi.features.usage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.features.statistics.streakDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One day's totals from the usage log, for the trend charts. */
data class UsageDayCounts(
    val wordLookups: Int = 0,
    /** Word presses on a manga page, the numerator of lookups per bubble. */
    val mangaPageLookups: Int = 0,
    val bubblesRevealed: Int = 0,
    val bubbleTranslations: Int = 0,
    val screenshotTranslations: Int = 0,
) {
    val isEmpty: Boolean
        get() = wordLookups == 0 && bubblesRevealed == 0 && bubbleTranslations == 0 && screenshotTranslations == 0
}

/** A finished reading day's place in [UsageLog.finishedDayCounts]. */
internal data class UsageDayKey(val date: LocalDate, val resetHour: Int, val zoneId: String)

/** What the Statistics screen shows from the usage log. */
data class UsageStatistics(
    val today: UsageDaySummary,
    /** Totals per day for the days loaded; days with nothing logged are absent. */
    val days: Map<LocalDate, UsageDayCounts>,
    /** The first day the log has a file for; nothing before it was recorded. */
    val firstLoggedDate: LocalDate?,
)

/**
 * Reads today's timeline and daily totals ending on [today]: [historyDays] days plus the
 * [averageWindow] - 1 before them, so the first day's trailing average is real rather than
 * padded with days that were never read. Days before today are cached once read.
 * Every day is a reading day running from [resetHour] to the same hour the next morning, the
 * day the streak counts, so each one reads its own calendar file and the next morning's.
 */
suspend fun loadUsageStatistics(
    log: UsageLog,
    today: LocalDate,
    historyDays: Int,
    /** The zone reading days are counted in; the log's files are this device's calendar days. */
    zone: ZoneId = ZoneId.systemDefault(),
    averageWindow: Int = 3,
    resetHour: Int = 0,
): UsageStatistics = withContext(Dispatchers.Default) {
    val reset = resetHour.coerceIn(0, 23)
    val firstLoggedDate = log.dayFiles().firstNotNullOfOrNull { file ->
        runCatching { LocalDate.parse(file.name.substringBefore('.')) }.getOrNull()
    }
    // The reading day the first file's first moment belongs to.
    val firstDay = firstLoggedDate?.let { first ->
        streakDate(Instant.ofEpochMilli(log.startOf(first)).atZone(zone), reset)
    }
    val days = mutableMapOf<LocalDate, UsageDayCounts>()
    var todaySummary: UsageDaySummary? = null
    var date = today.minusDays(historyDays + averageWindow - 2L)
    if (firstDay != null && date < firstDay) date = firstDay
    // Each calendar file is read once even though neighbouring reading days share it.
    val files = mutableMapOf<LocalDate, List<UsageEvent>>()
    while (!date.isAfter(today)) {
        val key = UsageDayKey(date, reset, zone.id)
        val cached = if (date < today) log.finishedDayCounts[key] else null
        val counts = cached ?: run {
            // The calendar files holding this reading day's instants, and one more on each side
            // for files written while this device was set to another zone.
            val start = date.atTime(reset, 0).atZone(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atTime(reset, 0).atZone(zone).toInstant().toEpochMilli()
            var file = log.dateOf(start).minusDays(1)
            val last = log.dateOf(end - 1).plusDays(1)
            val events = mutableListOf<UsageEvent>()
            while (!file.isAfter(last)) {
                events += files.getOrPut(file) { log.eventsOn(file) }
                file = file.plusDays(1)
            }
            files.keys.removeAll { it < log.dateOf(start).minusDays(1) }
            val summary = summarizeUsageDay(events, date, zone, resetHour = reset)
            if (date == today) todaySummary = summary
            summary.toCounts().also { if (date < today) log.finishedDayCounts[key] = it }
        }
        if (!counts.isEmpty) days[date] = counts
        date = date.plusDays(1)
    }
    UsageStatistics(
        today = todaySummary ?: summarizeUsageDay(emptyList(), today, zone, resetHour = reset),
        days = days,
        firstLoggedDate = firstDay,
    )
}

private fun UsageDaySummary.toCounts() = UsageDayCounts(
    wordLookups = wordLookups,
    mangaPageLookups = mangaPageLookups,
    bubblesRevealed = bubblesRevealed,
    bubbleTranslations = bubbleTranslations,
    screenshotTranslations = screenshotTranslations,
)
