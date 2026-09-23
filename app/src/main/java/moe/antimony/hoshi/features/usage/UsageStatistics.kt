package moe.antimony.hoshi.features.usage

import java.time.LocalDate
import java.time.ZoneId

/** What the Statistics screen shows from the usage log. */
data class UsageStatistics(
    val today: UsageDaySummary,
    /** Word lookups per day for the last days loaded; days without lookups are absent. */
    val lookupsByDay: Map<LocalDate, Int>,
    /** The first day the log has a file for; lookups before it were never recorded. */
    val firstLoggedDate: LocalDate?,
)

/** Reads today's timeline and [historyDays] days of lookup counts, ending on [today]. */
suspend fun loadUsageStatistics(
    log: UsageLog,
    today: LocalDate,
    historyDays: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): UsageStatistics {
    val firstLoggedDate = log.dayFiles().firstNotNullOfOrNull { file ->
        runCatching { LocalDate.parse(file.name.substringBefore('.')) }.getOrNull()
    }
    val lookupsByDay = mutableMapOf<LocalDate, Int>()
    var todaySummary: UsageDaySummary? = null
    var date = today.minusDays(historyDays - 1L)
    if (firstLoggedDate != null && date < firstLoggedDate) date = firstLoggedDate
    while (!date.isAfter(today)) {
        val summary = summarizeUsageDay(log.eventsOn(date), date, zone)
        if (summary.wordLookups > 0) lookupsByDay[date] = summary.wordLookups
        if (date == today) todaySummary = summary
        date = date.plusDays(1)
    }
    return UsageStatistics(
        today = todaySummary ?: summarizeUsageDay(emptyList(), today, zone),
        lookupsByDay = lookupsByDay,
        firstLoggedDate = firstLoggedDate,
    )
}
