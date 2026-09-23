package moe.antimony.hoshi.features.statistics

import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** How far back the trend charts look. */
enum class TrendRange(val days: Int) {
    TwoWeeks(14),
    Month(30),
    Quarter(90),
}

/** One day on a trend chart: that day's value and the trailing average ending on it. */
data class TrendPoint(
    val date: LocalDate,
    val value: Double,
    val rollingAverage: Double,
)

/** Days in the trailing average the trend charts draw. */
const val TREND_AVERAGE_DAYS: Int = 3

/**
 * The last [days] days up to [endDate], each with its value (0 when nothing was recorded) and
 * the average of it and the [window] - 1 days before it. The average reaches back before the
 * range, so the first bar's average is as real as the last one's.
 *
 * Days before [since] were never recorded (the usage log began then): they are left out of
 * the range, and averages near its start only average the recorded days.
 */
fun rollingAverageSeries(
    valuesByDate: Map<LocalDate, Double>,
    endDate: LocalDate,
    days: Int,
    window: Int = TREND_AVERAGE_DAYS,
    since: LocalDate? = null,
): List<TrendPoint> {
    require(days > 0 && window > 0)
    fun valueOn(date: LocalDate): Double? =
        if (since != null && date < since) null else valuesByDate[date] ?: 0.0

    val firstDay = endDate.minusDays(days - 1L).let { if (since != null && it < since) since else it }
    if (firstDay > endDate) return emptyList()
    return generateSequence(firstDay) { it.plusDays(1) }
        .takeWhile { it <= endDate }
        .map { date ->
            val windowValues = (0 until window).mapNotNull { back -> valueOn(date.minusDays(back.toLong())) }
            TrendPoint(
                date = date,
                value = valueOn(date) ?: 0.0,
                rollingAverage = if (windowValues.isEmpty()) 0.0 else windowValues.average(),
            )
        }
        .toList()
}

/**
 * A round axis top at or above [max], split into two even steps: 1, 2, 2.5 or 5 times a
 * power of ten per step, so gridlines land on numbers people read at a glance.
 */
fun niceAxisMax(max: Double, steps: Int = 2): Double {
    if (max <= 0.0 || max.isNaN()) return steps.toDouble()
    val rawStep = max / steps
    val magnitude = 10.0.pow(floor(log10(rawStep)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= rawStep - 1e-9 }
    return ceil(max / step - 1e-9) * step
}
