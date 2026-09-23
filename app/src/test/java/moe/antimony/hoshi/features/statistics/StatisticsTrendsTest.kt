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
    fun axisTopsAreRoundNumbers() {
        assertEquals(2.0, niceAxisMax(0.0), 0.0)
        assertEquals(10.0, niceAxisMax(7.0), 0.0)
        assertEquals(50.0, niceAxisMax(42.0), 0.0)
        assertEquals(100.0, niceAxisMax(95.0), 0.0)
        assertEquals(5_000.0, niceAxisMax(4_120.0), 0.0)
        assertEquals(1.0, niceAxisMax(0.8), 0.0)
    }
}
