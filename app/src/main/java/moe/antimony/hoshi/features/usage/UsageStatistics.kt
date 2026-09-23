package moe.antimony.hoshi.features.usage

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

/** What the Statistics screen shows from the usage log. */
data class UsageStatistics(
    val today: UsageDaySummary,
    /** Totals per day for the days loaded; days with nothing logged are absent. */
    val days: Map<LocalDate, UsageDayCounts>,
    /** The first day the log has a file for; nothing before it was recorded. */
    val firstLoggedDate: LocalDate?,
)

/** Reads today's timeline and [historyDays] days of daily totals, ending on [today]. */
suspend fun loadUsageStatistics(
    log: UsageLog,
    today: LocalDate,
    historyDays: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): UsageStatistics {
    val firstLoggedDate = log.dayFiles().firstNotNullOfOrNull { file ->
        runCatching { LocalDate.parse(file.name.substringBefore('.')) }.getOrNull()
    }
    val days = mutableMapOf<LocalDate, UsageDayCounts>()
    var todaySummary: UsageDaySummary? = null
    var date = today.minusDays(historyDays - 1L)
    if (firstLoggedDate != null && date < firstLoggedDate) date = firstLoggedDate
    while (!date.isAfter(today)) {
        val summary = summarizeUsageDay(log.eventsOn(date), date, zone)
        val counts = UsageDayCounts(
            wordLookups = summary.wordLookups,
            mangaPageLookups = summary.mangaPageLookups,
            bubblesRevealed = summary.bubblesRevealed,
            bubbleTranslations = summary.bubbleTranslations,
            screenshotTranslations = summary.screenshotTranslations,
        )
        if (!counts.isEmpty) days[date] = counts
        if (date == today) todaySummary = summary
        date = date.plusDays(1)
    }
    return UsageStatistics(
        today = todaySummary ?: summarizeUsageDay(emptyList(), today, zone),
        days = days,
        firstLoggedDate = firstLoggedDate,
    )
}
