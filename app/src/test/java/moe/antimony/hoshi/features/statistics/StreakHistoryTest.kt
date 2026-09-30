package moe.antimony.hoshi.features.statistics

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.reader.BookStatisticsInput
import moe.antimony.hoshi.features.reader.summarizeReadingStatistics
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class StreakHistoryTest {
    private fun input(vararg rows: ReadingStatistics) = listOf(BookStatisticsInput("b", "Book", ContentType.Epub, rows.toList()))
    private fun row(date: String, minutes: Double, vararg hours: Pair<String, Double>) = ReadingStatistics(
        "Book", date, readingTime = minutes * 60, deviceId = "local",
        readingTimeByHour = hours.associate { it.first to it.second * 60 },
    )
    private fun seconds(history: StreakHistory, date: String) = history.days.find { it.dateKey == date }?.seconds ?: 0.0

    @Test fun threeAmBoundaryUsesLocalDateIncludingDst() {
        for (zone in listOf("+09:00", "-04:00", "+00:00")) {
            assertEquals(LocalDate.parse("2026-09-28"), streakDate(ZonedDateTime.parse("2026-09-29T02:59:59$zone"), 3))
            assertEquals(LocalDate.parse("2026-09-29"), streakDate(ZonedDateTime.parse("2026-09-29T03:00:00$zone"), 3))
        }
        assertEquals(LocalDate.parse("2026-03-07"), streakDate(ZonedDateTime.parse("2026-03-08T01:59:59-05:00[America/New_York]"), 3))
        assertEquals(LocalDate.parse("2026-03-08"), streakDate(ZonedDateTime.parse("2026-03-08T03:00:00-04:00[America/New_York]"), 3))
    }

    @Test fun exactHoursMoveOnceAndChangingResetReconstructsAgain() {
        val entries = input(row("2026-09-29", 20.0, "2026-09-29T02:00" to 8.0, "2026-09-29T03:00" to 12.0))
        val three = reconstructStreakHistory(entries, 3)
        assertEquals(480.0, seconds(three, "2026-09-28"), 0.0)
        assertEquals(720.0, seconds(three, "2026-09-29"), 0.0)
        assertFalse(three.hasEstimatedHistory)
        assertEquals(1200.0, seconds(reconstructStreakHistory(entries, 0), "2026-09-29"), 0.0)
        assertEquals(1200.0, seconds(reconstructStreakHistory(entries, 4), "2026-09-28"), 0.0)
    }

    @Test fun legacyTotalsGetOptimisticAdjacentCreditWithoutChangingCalendarTotals() {
        val overview = summarizeReadingStatistics(input(row("2026-09-27", 10.0), row("2026-09-29", 10.0)), "2026-09-29", 3)
        val streak = computeReadingStreak(overview.streakDaily, 600.0, LocalDate.parse("2026-09-29"))
        assertEquals(4, streak.currentDays)
        assertTrue(overview.hasEstimatedStreakHistory)
        assertEquals(1200.0, overview.totalSeconds, 0.0)
        assertEquals(600.0, overview.todaySeconds, 0.0)
        assertEquals(2, overview.daily.size)
        assertEquals(0, computeReadingStreak(overview.streakDaily, 1200.0, LocalDate.parse("2026-09-29")).currentDays)
    }

    @Test fun partialHistoryCreditsOnlyUnknownTimeOptimistically() {
        val history = reconstructStreakHistory(input(row("2026-09-29", 10.0, "2026-09-29T04:00" to 6.0)), 3)
        assertEquals(600.0, seconds(history, "2026-09-29"), 0.0)
        assertEquals(240.0, seconds(history, "2026-09-28"), 0.0)
        assertTrue(history.hasEstimatedHistory)
    }

    @Test fun oldAfterMidnightReadingCannotLoseItsCalendarDayOnTheOriginatingDevice() {
        val synced = input(row("2026-09-29", 10.0).copy(deviceId = "tablet"))
        // Before this fix the tablet's private 1am log moved all 600s to Sep28, yielding 0,
        // while the phone's optimistic view of the same synced row retained a 2-day streak.
        val history = reconstructStreakHistory(synced, 3)
        val today = LocalDate.parse("2026-09-30")
        assertEquals(2, computeReadingStreak(history.days, 600.0, today).currentDays)
        assertEquals(600.0, seconds(history, "2026-09-29"), 0.0)
        assertTrue(history.hasEstimatedHistory)
    }

    @Test fun newAfterMidnightReadingRemainsExactOnBothDevices() {
        val synced = input(row("2026-09-29", 10.0, "2026-09-29T01:00" to 10.0).copy(deviceId = "tablet"))
        val history = reconstructStreakHistory(synced, 3)
        assertEquals(600.0, seconds(history, "2026-09-28"), 0.0)
        assertEquals(0.0, seconds(history, "2026-09-29"), 0.0)
        assertFalse(history.hasEstimatedHistory)
    }

    @Test fun duplicateRowsAreDeduplicatedAndDevicesAddedTogether() {
        val older = row("2026-09-29", 5.0, "2026-09-29T04:00" to 5.0)
        val newer = row("2026-09-29", 6.0, "2026-09-29T04:00" to 6.0).copy(lastStatisticModified = 1)
        val remote = row("2026-09-29", 4.0, "2026-09-29T04:00" to 4.0).copy(deviceId = "remote")
        assertEquals(600.0, seconds(reconstructStreakHistory(input(older, newer, remote), 3), "2026-09-29"), 0.0)
    }

    @Test fun malformedAndOversizedHourDataNeverInflatesExactTime() {
        val entry = row("2026-09-29", 10.0).copy(readingTimeByHour = mapOf("broken" to 99.0, "2026-09-29T04:00" to 900.0))
        assertEquals(600.0, seconds(reconstructStreakHistory(input(entry), 3), "2026-09-29"), 0.0)
    }
}
