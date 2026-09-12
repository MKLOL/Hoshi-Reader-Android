package moe.antimony.hoshi.features.statistics

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.features.reader.BookReadingSummary
import moe.antimony.hoshi.features.reader.DailyReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class StatisticsMathTest {
    private fun day(date: String, minutes: Double, characters: Int = 0) = DailyReading(date, minutes * 60.0, characters)
    private val today = LocalDate.of(2026, 9, 12) // a Saturday

    @Test
    fun streakCountsConsecutiveDaysThatMeetTheGoalEndingToday() {
        val daily = listOf(day("2026-09-12", 12.0), day("2026-09-11", 30.0), day("2026-09-10", 10.0), day("2026-09-08", 45.0))

        val streak = computeReadingStreak(daily, minimumSeconds = 10 * 60.0, today = today)

        assertEquals(3, streak.currentDays)
        assertEquals(3, streak.longestDays)
        assertEquals(4, streak.qualifyingDays)
        assertTrue(streak.todayQualifies)
        assertEquals(12 * 60.0, streak.todaySeconds, 0.0)
    }

    @Test
    fun streakStaysAliveThroughAnUnfinishedTodayButNotThroughAMissedYesterday() {
        val daily = listOf(day("2026-09-12", 3.0), day("2026-09-11", 20.0), day("2026-09-10", 20.0))

        val alive = computeReadingStreak(daily, minimumSeconds = 10 * 60.0, today = today)
        assertEquals(2, alive.currentDays)
        assertFalse(alive.todayQualifies)

        val broken = computeReadingStreak(listOf(day("2026-09-10", 20.0), day("2026-09-09", 20.0)), 10 * 60.0, today)
        assertEquals(0, broken.currentDays)
        assertEquals(2, broken.longestDays)
    }

    @Test
    fun streakRespectsTheMinimumAndIgnoresFutureDays() {
        val daily = listOf(day("2026-09-12", 9.9), day("2026-09-11", 10.0), day("2026-09-13", 60.0))

        val streak = computeReadingStreak(daily, minimumSeconds = 10 * 60.0, today = today)

        assertEquals(1, streak.currentDays)
        assertEquals(1, streak.longestDays)
        assertEquals(1, streak.qualifyingDays)
        assertEquals(0, computeReadingStreak(daily, minimumSeconds = 0.0, today = today).currentDays)
    }

    @Test
    fun longestStreakFindsTheBestRunAnywhereInHistory() {
        val daily = (1..5).map { day("2026-08-0$it", 15.0) } + listOf(day("2026-09-11", 15.0), day("2026-09-12", 15.0))

        val streak = computeReadingStreak(daily, 10 * 60.0, today)

        assertEquals(2, streak.currentDays)
        assertEquals(5, streak.longestDays)
    }

    @Test
    fun weekdayDistributionSumsSecondsMondayFirst() {
        val distribution = weekdayDistribution(
            listOf(day("2026-09-07", 10.0), day("2026-09-14", 5.0), day("2026-09-12", 7.0), day("bad-date", 99.0)),
        )

        assertEquals(7, distribution.size)
        assertEquals(15 * 60.0, distribution[0], 0.0) // two Mondays
        assertEquals(7 * 60.0, distribution[5], 0.0) // Saturday
        assertEquals(0.0, distribution[6], 0.0)
        assertEquals(7, weekdayLabels(Locale.ENGLISH).size)
        assertEquals("Mon", weekdayLabels(Locale.ENGLISH).first())
    }

    @Test
    fun heatmapLevelsSplitTheBusiestDayIntoQuarters() {
        assertEquals(0, heatmapLevel(0.0, 100.0))
        assertEquals(0, heatmapLevel(50.0, 0.0))
        assertEquals(1, heatmapLevel(25.0, 100.0))
        assertEquals(2, heatmapLevel(26.0, 100.0))
        assertEquals(3, heatmapLevel(75.0, 100.0))
        assertEquals(4, heatmapLevel(76.0, 100.0))
        assertEquals(4, heatmapLevel(100.0, 100.0))
    }

    @Test
    fun heatmapCoversSixteenMondayStartedWeeksEndingTodayWithNoFutureCells() {
        val heatmap = buildReadingHeatmap(listOf(day("2026-09-12", 40.0), day("2026-09-07", 10.0)), today, weeks = 16, locale = Locale.ENGLISH)

        assertEquals(16, heatmap.weeks.size)
        val lastWeek = heatmap.weeks.last()
        assertEquals(LocalDate.of(2026, 9, 7), lastWeek.cells[0]?.date)
        assertEquals(today, lastWeek.cells[5]?.date)
        assertNull(lastWeek.cells[6]) // Sunday after today is not drawn
        assertEquals(4, lastWeek.cells[5]?.level)
        assertEquals(1, lastWeek.cells[0]?.level)
        assertEquals(0, lastWeek.cells[1]?.level)
        assertEquals(LocalDate.of(2026, 5, 25), heatmap.weeks.first().cells[0]?.date)
        assertEquals(40 * 60.0, heatmap.maxSeconds, 0.0)
        assertEquals(listOf("May", "Jul", "Aug", "Sep"), heatmap.monthLabels.map { it.second }) // Jun starts one week in
        assertTrue(heatmap.monthLabels.zipWithNext().all { (a, b) -> b.first - a.first >= HEATMAP_MIN_LABEL_GAP_WEEKS })
        assertEquals(0, heatmap.monthLabels.first().first)
    }

    @Test
    fun heatmapIgnoresReadingOutsideTheWindowWhenScalingLevels() {
        val heatmap = buildReadingHeatmap(listOf(day("2020-01-01", 600.0), day("2026-09-12", 10.0)), today, weeks = 4)

        assertEquals(10 * 60.0, heatmap.maxSeconds, 0.0)
        assertEquals(4, heatmap.weeks.last().cells[5]?.level)
    }

    private fun summary(type: ContentType, seconds: Double, characters: Int, pages: Int?, days: List<DailyReading>) =
        BookReadingSummary(
            bookId = "b", title = "T", contentType = type, totalSeconds = seconds, charactersRead = characters,
            pagesRead = pages, startedDateKey = days.minOfOrNull { it.dateKey }, lastReadDateKey = days.maxOfOrNull { it.dateKey },
            progress = 0.5, finished = false, coverSource = null, days = days,
        )

    @Test
    fun mangaPaceUsesPagesAndEpubPaceUsesCharacters() {
        val days = listOf(day("2026-09-12", 30.0, 300), day("2026-09-11", 30.0, 600))
        val manga = bookPace(summary(ContentType.Mokuro, seconds = 3600.0, characters = 900, pages = 120, days = days))
        assertEquals(120, manga.unitsPerHour)
        assertEquals(30.0, manga.secondsPerUnit!!, 0.0)
        assertEquals(1800.0, manga.averageSecondsPerDay, 0.0)
        assertEquals("2026-09-12", manga.bestDay?.dateKey)

        val epub = bookPace(summary(ContentType.Epub, seconds = 1800.0, characters = 9_000, pages = null, days = days.take(1)))
        assertEquals(18_000, epub.unitsPerHour)
        assertEquals(20.0, epub.secondsPerUnit!!, 0.0) // per 100 characters
        assertEquals(1800.0, epub.averageSecondsPerDay, 0.0)
    }

    @Test
    fun paceHandlesBooksWithNothingReadYet() {
        val pace = bookPace(summary(ContentType.Mokuro, seconds = 0.0, characters = 0, pages = 0, days = emptyList()))

        assertEquals(0, pace.unitsPerHour)
        assertNull(pace.secondsPerUnit)
        assertEquals(0.0, pace.averageSecondsPerDay, 0.0)
        assertNull(pace.bestDay)
    }
}
