package moe.antimony.hoshi.features.statistics

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingTotals
import moe.antimony.hoshi.features.reader.BookReadingSummary
import moe.antimony.hoshi.features.reader.DailyReading
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Streak of consecutive days on which at least [minimumSeconds] were read. */
data class ReadingStreak(
    /** Consecutive qualifying days ending today, or ending yesterday while today is still open. */
    val currentDays: Int,
    val longestDays: Int,
    /** Days that ever met the goal. */
    val qualifyingDays: Int,
    val todaySeconds: Double,
    val minimumSeconds: Double,
) {
    val todayQualifies: Boolean get() = todaySeconds >= minimumSeconds
}

private data class StreakRun(
    var lastDay: LocalDate? = null,
    var trailingDays: Int = 0,
    var longestDays: Int = 0,
    var qualifyingDays: Int = 0,
) {
    fun add(date: LocalDate) {
        val previous = lastDay
        if (previous != null && date <= previous) return
        trailingDays = if (previous != null && previous.plusDays(1) == date) trailingDays + 1 else 1
        longestDays = maxOf(longestDays, trailingDays)
        qualifyingDays++
        lastDay = date
    }

    fun progress(today: LocalDate, todaySeconds: Double, minimumSeconds: Double): ReadingStreak =
        ReadingStreak(
            currentDays = if (lastDay == today || lastDay == today.minusDays(1)) trailingDays else 0,
            longestDays = longestDays,
            qualifyingDays = qualifyingDays,
            todaySeconds = todaySeconds,
            minimumSeconds = minimumSeconds,
        )
}

private fun Sequence<DailyReading>.qualifyingDates(minimumSeconds: Double, through: LocalDate): Sequence<LocalDate> =
    filter { minimumSeconds > 0.0 && it.seconds >= minimumSeconds }
        .mapNotNull { runCatching { LocalDate.parse(it.dateKey) }.getOrNull() }
        .filter { it <= through }

fun computeReadingStreak(daily: List<DailyReading>, minimumSeconds: Double, today: LocalDate): ReadingStreak {
    val run = StreakRun()
    daily.asSequence().qualifyingDates(minimumSeconds, today).sorted().forEach(run::add)
    val todayKey = today.toString()
    return run.progress(today, daily.firstOrNull { it.dateKey == todayKey }?.seconds ?: 0.0, minimumSeconds)
}

/** Keep the completed run and the all-time record while only the last three days change. */
internal class ReadingStreakCache {
    companion object {
        // Used on the UI thread by the Statistics screen across navigation.
        val shared = ReadingStreakCache()
    }

    private var revision: Any? = null
    private var goalSeconds = 0.0
    private var through: LocalDate? = null
    private var historical = StreakRun()

    fun compute(
        older: List<DailyReading>,
        recent: List<DailyReading>,
        historyRevision: Any,
        minimumSeconds: Double,
        today: LocalDate,
    ): ReadingStreak {
        val endOfHistory = today.minusDays(3)
        val previousEnd = through
        if (revision !== historyRevision || goalSeconds != minimumSeconds || previousEnd == null || endOfHistory < previousEnd) {
            historical = StreakRun()
            older.asSequence().qualifyingDates(minimumSeconds, endOfHistory).sorted().forEach(historical::add)
        } else if (endOfHistory > previousEnd) {
            // Older days are newest first. At rollover only the newly finished days enter the prefix.
            val previousKey = previousEnd.toString()
            older.asSequence().takeWhile { it.dateKey > previousKey }
                .qualifyingDates(minimumSeconds, endOfHistory).sorted().forEach(historical::add)
        }
        revision = historyRevision
        goalSeconds = minimumSeconds
        through = endOfHistory
        val run = historical.copy()
        recent.asSequence().qualifyingDates(minimumSeconds, today)
            .filter { it > endOfHistory }.sorted().forEach(run::add)
        return run.progress(today, recent.firstOrNull { it.dateKey == today.toString() }?.seconds ?: 0.0, minimumSeconds)
    }
}

/** Seconds read per weekday, indexed Monday (0) to Sunday (6). */
fun weekdayDistribution(daily: List<DailyReading>): List<Double> {
    val totals = DoubleArray(7)
    daily.forEach { day ->
        val date = runCatching { LocalDate.parse(day.dateKey) }.getOrNull() ?: return@forEach
        totals[date.dayOfWeek.value - 1] += day.seconds
    }
    return totals.toList()
}

fun weekdayLabels(locale: Locale = Locale.getDefault()): List<String> =
    DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, locale) }

data class HeatmapCell(val date: LocalDate, val seconds: Double, val level: Int)

/** Seven cells, Monday to Sunday; null where the day is before the window or after today. */
data class HeatmapWeek(val cells: List<HeatmapCell?>)

data class ReadingHeatmap(
    val weeks: List<HeatmapWeek>,
    /** Week index to month label, for the first week of each month in the window. */
    val monthLabels: List<Pair<Int, String>>,
    val maxSeconds: Double,
)

const val HEATMAP_LEVELS = 4

/** Weeks between two month labels on the heatmap, so short-name labels never collide. */
const val HEATMAP_MIN_LABEL_GAP_WEEKS = 3

/** 0 for nothing read, otherwise 1..4 by quarter of the busiest day in the window. */
fun heatmapLevel(seconds: Double, maxSeconds: Double): Int {
    if (seconds <= 0.0 || maxSeconds <= 0.0) return 0
    val fraction = seconds / maxSeconds
    return when {
        fraction <= 0.25 -> 1
        fraction <= 0.5 -> 2
        fraction <= 0.75 -> 3
        else -> 4
    }
}

/** GitHub-style contribution grid for the last [weeks] weeks ending in the week of [today]. */
fun buildReadingHeatmap(
    daily: List<DailyReading>,
    today: LocalDate,
    weeks: Int = 16,
    locale: Locale = Locale.getDefault(),
): ReadingHeatmap {
    val secondsByDate = daily.mapNotNull { day ->
        runCatching { LocalDate.parse(day.dateKey) }.getOrNull()?.let { it to day.seconds }
    }.toMap()
    val weekStart = today.minusDays(((today.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7).toLong())
    val firstWeekStart = weekStart.minusWeeks((weeks - 1).toLong())
    val maxSeconds = secondsByDate.filterKeys { !it.isBefore(firstWeekStart) && !it.isAfter(today) }
        .values.maxOrNull() ?: 0.0
    val labels = mutableListOf<Pair<Int, String>>()
    var lastMonth: Int? = null
    val grid = (0 until weeks).map { weekIndex ->
        val start = firstWeekStart.plusWeeks(weekIndex.toLong())
        if (start.monthValue != lastMonth) {
            val label = weekIndex to start.month.getDisplayName(TextStyle.SHORT, locale)
            val previous = labels.lastOrNull()
            if (previous == null || weekIndex - previous.first >= HEATMAP_MIN_LABEL_GAP_WEEKS) {
                labels += label
            } else {
                // Two month starts within the gap can only be the window's partial leading month
                // followed by the month that owns the next columns: the latter gets the label.
                labels[labels.lastIndex] = label
            }
            lastMonth = start.monthValue
        }
        HeatmapWeek(
            (0 until 7).map { dayOffset ->
                val date = start.plusDays(dayOffset.toLong())
                if (date.isAfter(today)) {
                    null
                } else {
                    val seconds = secondsByDate[date] ?: 0.0
                    HeatmapCell(date, seconds, heatmapLevel(seconds, maxSeconds))
                }
            },
        )
    }
    return ReadingHeatmap(weeks = grid, monthLabels = labels, maxSeconds = maxSeconds)
}

/** Derived pace figures for one book. */
data class BookPace(
    /** Pages per hour for a manga, characters per hour for an EPUB. */
    val unitsPerHour: Int,
    /** Seconds per page for a manga, seconds per 100 characters for an EPUB; null when nothing was read. */
    val secondsPerUnit: Double?,
    val averageSecondsPerDay: Double,
    val bestDay: DailyReading?,
)

fun bookPace(summary: BookReadingSummary): BookPace {
    val units = when (summary.contentType) {
        ContentType.Mokuro -> summary.pagesRead ?: 0
        ContentType.Epub -> summary.charactersRead
    }
    // The reader sheet shows ReadingTotals.readingSpeed; the same arithmetic, so the same integer.
    val unitsPerHour = ReadingTotals(readingTime = summary.totalSeconds, charactersRead = units).readingSpeed
    val secondsPerUnit = when (summary.contentType) {
        ContentType.Mokuro -> summary.pagesRead?.takeIf { it > 0 }?.let { summary.totalSeconds / it }
        ContentType.Epub -> summary.charactersRead.takeIf { it > 0 }?.let { summary.totalSeconds / (it / 100.0) }
    }
    val daysRead = summary.daysRead
    return BookPace(
        unitsPerHour = unitsPerHour,
        secondsPerUnit = secondsPerUnit,
        averageSecondsPerDay = if (daysRead > 0) summary.totalSeconds / daysRead else 0.0,
        bestDay = summary.days.maxByOrNull { it.seconds }?.takeIf { it.seconds > 0.0 },
    )
}
