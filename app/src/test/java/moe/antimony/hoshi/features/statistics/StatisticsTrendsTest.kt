package moe.antimony.hoshi.features.statistics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StatisticsTrendsTest {
    private val end = LocalDate.parse("2026-09-23")

    @Test
    fun theAverageCoversTheDayAndTheTwoBeforeItEvenAcrossTheRangeStart() {
        val values = mapOf(
            end.minusDays(3) to 30.0,
            end.minusDays(2) to 60.0,
            end to 90.0,
        )

        val series = rollingAverageSeries(values, end, days = 3)

        assertEquals(listOf(end.minusDays(2), end.minusDays(1), end), series.map { it.date })
        assertEquals(listOf(60.0, 0.0, 90.0), series.map { it.value })
        // (60 + 30 + 0)/3 reaching back before the range, (0 + 60 + 30)/3 with the unread day
        // counted as zero, then (90 + 0 + 60)/3.
        assertEquals(listOf(30.0, 30.0, 50.0), series.map { it.rollingAverage })
    }

    @Test
    fun unrecordedDaysBeforeTheLogBeganAreLeftOut() {
        val since = end.minusDays(1)
        val values = mapOf(since to 4.0, end to 8.0)

        val series = rollingAverageSeries(values, end, days = 30, since = since)

        assertEquals(listOf(since, end), series.map { it.date })
        assertEquals(listOf(4.0, 6.0), series.map { it.rollingAverage })
    }

    @Test
    fun aLogThatBeginsAfterTheEndDateHasNoPoints() {
        assertEquals(emptyList<TrendPoint>(), rollingAverageSeries(emptyMap(), end, days = 7, since = end.plusDays(1)))
    }

    @Test
    fun aRateSeriesDividesEachDayAndTheWindowBySums() {
        val lookups = mapOf(end.minusDays(2) to 3.0, end.minusDays(1) to 20.0, end to 6.0)
        val bubbles = mapOf(end.minusDays(2) to 1.0, end.minusDays(1) to 40.0, end to 12.0)

        val series = rollingRatioSeries(lookups, bubbles, end, days = 3)

        assertEquals(listOf(3.0, 0.5, 0.5), series.map { it.value })
        // The trailing rate is (3 + 20 + 6) / (1 + 40 + 12), not the mean of 3, 0.5 and 0.5.
        assertEquals(29.0 / 53.0, series.last().rollingAverage, 1e-9)
    }

    @Test
    fun aDayWithoutBubblesHasNoRateAndDoesNotDivideByZero() {
        val series = rollingRatioSeries(mapOf(end to 2.0), emptyMap(), end, days = 2)

        assertEquals(listOf(0.0, 0.0), series.map { it.value })
        assertEquals(listOf(0.0, 0.0), series.map { it.rollingAverage })
    }

    @Test
    fun axisTopsAreRoundNumbers() {
        assertEquals(2.0, niceAxisMax(0.0), 0.0)
        assertEquals(10.0, niceAxisMax(7.0), 0.0)
        assertEquals(50.0, niceAxisMax(42.0), 0.0)
        assertEquals(100.0, niceAxisMax(95.0), 0.0)
        assertEquals(5_000.0, niceAxisMax(4_120.0), 0.0)
        assertEquals(1.0, niceAxisMax(0.8), 0.0)
    }
}
