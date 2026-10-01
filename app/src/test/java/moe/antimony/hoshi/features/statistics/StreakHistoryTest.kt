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

    @Test fun todayAfterMidnightIsTheWholeReadingDayTheStreakCounts() {
        // Read 21:00-23:00, then a second just after midnight. At 00:20 with a 3am reset the
        // streak's day is still Sep 30 and its goal was reached, while Today showed 1 second.
        val entries = input(
            row("2026-09-30", 120.0, "2026-09-30T21:00" to 60.0, "2026-09-30T22:00" to 60.0).copy(charactersRead = 6000),
            row("2026-10-01", 1.0 / 60, "2026-10-01T00:00" to 1.0 / 60).copy(charactersRead = 10),
        )
        val readingDay = streakDate(ZonedDateTime.parse("2026-10-01T00:20:00+09:00"), 3)
        val overview = summarizeReadingStatistics(entries, readingDay.toString(), 3)
        assertEquals(7201.0, overview.todaySeconds, 0.001)
        assertEquals(6010, overview.todayCharacters)
        // The Today card and the streak card now agree about the same day.
        assertEquals(overview.todaySeconds, computeReadingStreak(overview.streakDaily, 600.0, readingDay).todaySeconds, 0.001)
    }

    @Test fun theReadingDayEndsAtTheResetHourAndSplitsCharactersByWhereTheTimeFell() {
        val entries = input(
            row("2026-09-30", 60.0, "2026-09-30T21:00" to 60.0).copy(charactersRead = 3000),
            row("2026-10-01", 60.0, "2026-10-01T02:00" to 20.0, "2026-10-01T09:00" to 40.0).copy(charactersRead = 600),
        )
        val octoberFirst = readingDayTotals(entries, LocalDate.parse("2026-10-01"), 3)
        assertEquals(2400.0, octoberFirst.seconds, 0.001)
        assertEquals(400, octoberFirst.characters)
        val septemberThirtieth = readingDayTotals(entries, LocalDate.parse("2026-09-30"), 3)
        assertEquals(4800.0, septemberThirtieth.seconds, 0.001)
        assertEquals(3200, septemberThirtieth.characters)
        // With midnight as the reset the reading day is simply the calendar day.
        assertEquals(3600.0, readingDayTotals(entries, LocalDate.parse("2026-10-01"), 0).seconds, 0.001)
    }

    @Test fun timeWithoutAnHourStaysOnItsOwnDateAndIsNeverCountedTwice() {
        // The streak gives unknown-hour time to both possible days; a total must not.
        val entries = input(row("2026-10-01", 10.0).copy(charactersRead = 500))
        assertEquals(600.0, readingDayTotals(entries, LocalDate.parse("2026-10-01"), 3).seconds, 0.0)
        assertEquals(500, readingDayTotals(entries, LocalDate.parse("2026-10-01"), 3).characters)
        assertEquals(0.0, readingDayTotals(entries, LocalDate.parse("2026-09-30"), 3).seconds, 0.0)
    }

    @Test fun anHourFromASkewedClockCountsAsAnUnknownHourOnItsOwnDate() {
        val entries = input(row("2026-10-01", 10.0, "1970-01-01T00:00" to 10.0))
        assertEquals(600.0, readingDayTotals(entries, LocalDate.parse("2026-10-01"), 3).seconds, 0.0)
        assertEquals(0.0, readingDayTotals(entries, LocalDate.parse("1969-12-31"), 3).seconds, 0.0)
    }
}
