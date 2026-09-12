package moe.antimony.hoshi.features.statistics

import moe.antimony.hoshi.epub.ContentType
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

fun computeReadingStreak(daily: List<DailyReading>, minimumSeconds: Double, today: LocalDate): ReadingStreak {
    val qualifying = daily
        .filter { it.seconds >= minimumSeconds && minimumSeconds > 0.0 }
        .mapNotNull { runCatching { LocalDate.parse(it.dateKey) }.getOrNull() }
        .filter { !it.isAfter(today) }
        .toSortedSet()
    var longest = 0
    var run = 0
    var previous: LocalDate? = null
    for (date in qualifying) {
        run = if (previous != null && previous.plusDays(1) == date) run + 1 else 1
        longest = maxOf(longest, run)
        previous = date
    }
    var current = 0
    var cursor = if (today in qualifying) today else today.minusDays(1)
    while (cursor in qualifying) {
        current += 1
        cursor = cursor.minusDays(1)
    }
    return ReadingStreak(
        currentDays = current,
        longestDays = longest,
        qualifyingDays = qualifying.size,
        todaySeconds = daily.firstOrNull { it.dateKey == today.toString() }?.seconds ?: 0.0,
        minimumSeconds = minimumSeconds,
    )
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
            // Drop a label that would sit right next to the previous one (a month that starts
            // in the window's first days), so labels never overlap.
            if (labels.isEmpty() || weekIndex - labels.last().first >= HEATMAP_MIN_LABEL_GAP_WEEKS) {
                labels += weekIndex to start.month.getDisplayName(TextStyle.SHORT, locale)
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
    val hours = summary.totalSeconds / 3600.0
    val units = when (summary.contentType) {
        ContentType.Mokuro -> summary.pagesRead ?: 0
        ContentType.Epub -> summary.charactersRead
    }
    val unitsPerHour = if (hours > 0.0) (units / hours).toInt() else 0
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
