package moe.antimony.hoshi.features.usage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/**
 * Reads today's timeline and daily totals ending on [today]: [historyDays] days plus the
 * [averageWindow] - 1 before them, so the first day's trailing average is real rather than
 * padded with days that were never read. Days before today are cached once read.
 */
suspend fun loadUsageStatistics(
    log: UsageLog,
    today: LocalDate,
    historyDays: Int,
    zone: ZoneId = ZoneId.systemDefault(),
    averageWindow: Int = 3,
): UsageStatistics = withContext(Dispatchers.Default) {
    val firstLoggedDate = log.dayFiles().firstNotNullOfOrNull { file ->
        runCatching { LocalDate.parse(file.name.substringBefore('.')) }.getOrNull()
    }
    val days = mutableMapOf<LocalDate, UsageDayCounts>()
    var todaySummary: UsageDaySummary? = null
    var date = today.minusDays(historyDays + averageWindow - 2L)
    if (firstLoggedDate != null && date < firstLoggedDate) date = firstLoggedDate
    while (!date.isAfter(today)) {
        val cached = if (date < today) log.finishedDayCounts[date] else null
        val counts = cached ?: run {
            val summary = summarizeUsageDay(log.eventsOn(date), date, zone)
            if (date == today) todaySummary = summary
            summary.toCounts().also { if (date < today) log.finishedDayCounts[date] = it }
        }
        if (!counts.isEmpty) days[date] = counts
        date = date.plusDays(1)
    }
    UsageStatistics(
        today = todaySummary ?: summarizeUsageDay(emptyList(), today, zone),
        days = days,
        firstLoggedDate = firstLoggedDate,
    )
}

private fun UsageDaySummary.toCounts() = UsageDayCounts(
    wordLookups = wordLookups,
    mangaPageLookups = mangaPageLookups,
    bubblesRevealed = bubblesRevealed,
    bubbleTranslations = bubbleTranslations,
    screenshotTranslations = screenshotTranslations,
)
