package moe.antimony.hoshi.features.statistics

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.ReadingTotals
import moe.antimony.hoshi.features.reader.BookReadingSummary
import moe.antimony.hoshi.features.reader.BookStatisticsInput
import moe.antimony.hoshi.features.reader.DailyReading
import moe.antimony.hoshi.features.reader.formatDurationSeconds
import moe.antimony.hoshi.features.reader.summarizeReadingStatistics
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** Review tests for the Statistics screens' math and formatting. */
class StatisticsReviewTest {
    private fun day(date: String, minutes: Double, characters: Int = 0) = DailyReading(date, minutes * 60.0, characters)
    private fun stat(dateKey: String, seconds: Double, units: Int = 0, modified: Long = 1L) =
        ReadingStatistics(title = "t", dateKey = dateKey, charactersRead = units, readingTime = seconds, lastStatisticModified = modified)
    private val saturday = LocalDate.of(2026, 9, 12)

    private fun summary(type: ContentType, seconds: Double, characters: Int, pages: Int?, days: List<DailyReading>) =
        BookReadingSummary(
            bookId = "b", title = "T", contentType = type, totalSeconds = seconds, charactersRead = characters,
            pagesRead = pages, startedDateKey = days.minOfOrNull { it.dateKey }, lastReadDateKey = days.maxOfOrNull { it.dateKey },
            progress = 0.5, finished = false, coverSource = null, days = days,
        )

    // ---- computeReadingStreak ----

    @Test
    fun streakOfOneWhenOnlyTodayQualifies() {
        val streak = computeReadingStreak(listOf(day("2026-09-12", 10.0), day("2026-09-10", 10.0)), 600.0, saturday)
        assertEquals(1, streak.currentDays)
        assertEquals(1, streak.longestDays)
        assertEquals(2, streak.qualifyingDays)
    }

    @Test
    fun exactlyTheMinimumQualifiesAndOneSecondLessDoesNot() {
        val daily = listOf(DailyReading("2026-09-12", 600.0, 0), DailyReading("2026-09-11", 599.0, 0))
        val streak = computeReadingStreak(daily, 600.0, saturday)
        assertTrue(streak.todayQualifies)
        assertEquals(1, streak.currentDays)
        assertEquals(1, streak.qualifyingDays)
    }

    @Test
    fun streakIsZeroWhenNeitherTodayNorYesterdayQualifyEvenIfTheDayBeforeDid() {
        val streak = computeReadingStreak(listOf(day("2026-09-10", 30.0), day("2026-09-12", 2.0)), 600.0, saturday)
        assertEquals(0, streak.currentDays)
        assertEquals(1, streak.longestDays)
        assertEquals(120.0, streak.todaySeconds, 0.0)
        assertFalse(streak.todayQualifies)
    }

    @Test
    fun streakRunsAcrossMonthAndYearBoundaries() {
        val daily = listOf(day("2025-12-31", 15.0), day("2026-01-01", 15.0), day("2026-01-02", 15.0))
        val streak = computeReadingStreak(daily, 600.0, LocalDate.of(2026, 1, 3))
        assertEquals(3, streak.currentDays) // Jan 3 is still open, Jan 2 met the goal
        assertEquals(3, streak.longestDays)
        assertEquals(0.0, streak.todaySeconds, 0.0)
    }

    @Test
    fun streakIgnoresUnparseableDateKeys() {
        val streak = computeReadingStreak(listOf(day("not-a-date", 60.0), day("2026-09-11", 60.0)), 600.0, saturday)
        assertEquals(1, streak.currentDays)
        assertEquals(1, streak.qualifyingDays)
    }

    @Test
    fun zeroMinimumNeverBuildsAStreakButStillClaimsTodayQualifies() {
        // ReaderSettings clamps the goal to 1..600 minutes, so 0 is unreachable from the UI;
        // this documents that the two fields disagree if it ever were.
        val streak = computeReadingStreak(listOf(day("2026-09-12", 60.0)), 0.0, saturday)
        assertEquals(0, streak.currentDays)
        assertEquals(0, streak.qualifyingDays)
        assertTrue(streak.todayQualifies)
    }

    // ---- buildReadingHeatmap ----

    @Test
    fun heatmapEndsWithASingleCellWhenTodayIsMonday() {
        val monday = LocalDate.of(2026, 9, 7)
        val heatmap = buildReadingHeatmap(emptyList(), monday, weeks = 2, locale = Locale.ENGLISH)
        assertEquals(2, heatmap.weeks.size)
        assertEquals(LocalDate.of(2026, 8, 31), heatmap.weeks.first().cells[0]?.date)
        assertEquals(7, heatmap.weeks.first().cells.count { it != null })
        assertEquals(listOf(monday), heatmap.weeks.last().cells.mapNotNull { it?.date })
        assertEquals(0.0, heatmap.maxSeconds, 0.0)
        assertTrue(heatmap.weeks.flatMap { it.cells }.filterNotNull().all { it.level == 0 })
    }

    @Test
    fun heatmapFillsTheWholeLastWeekWhenTodayIsSunday() {
        val sunday = LocalDate.of(2026, 9, 13)
        val heatmap = buildReadingHeatmap(listOf(day("2026-09-13", 5.0)), sunday, weeks = 1)
        val week = heatmap.weeks.single()
        assertEquals(LocalDate.of(2026, 9, 7), week.cells[0]?.date)
        assertEquals(sunday, week.cells[6]?.date)
        assertEquals(4, week.cells[6]?.level)
        assertEquals(7, week.cells.count { it != null })
    }

    @Test
    fun heatmapLevelsUseTheWindowMaximumWithQuartileBoundaries() {
        val heatmap = buildReadingHeatmap(
            listOf(day("2026-09-07", 40.0), day("2026-09-08", 10.0), day("2026-09-09", 10.0001), day("2026-09-10", 20.0), day("2026-09-11", 30.0)),
            saturday,
            weeks = 1,
        )
        assertEquals(listOf(4, 1, 2, 2, 3, 0), heatmap.weeks.single().cells.take(6).map { it!!.level })
    }

    @Test
    fun everyMonthThatOwnsWholeColumnsGetsALabel() {
        // Window 2026-05-25 .. 2026-09-12: June owns columns 1..5, May only the partial column 0.
        val heatmap = buildReadingHeatmap(emptyList(), saturday, weeks = 16, locale = Locale.ENGLISH)
        assertTrue("June has no label: ${heatmap.monthLabels}", heatmap.monthLabels.any { it.second == "Jun" })
        assertEquals(listOf(1 to "Jun", 6 to "Jul", 10 to "Aug", 15 to "Sep"), heatmap.monthLabels)
    }

    @Test
    fun aLeadingColumnWithOneDayOfThePreviousMonthDoesNotLabelTheWholeGrid() {
        // today 2026-10-03 (Sat), 5 weeks: Aug 31 | Sep 7 | Sep 14 | Sep 21 | Sep 28. Only Aug 31 is August.
        val heatmap = buildReadingHeatmap(emptyList(), LocalDate.of(2026, 10, 3), weeks = 5, locale = Locale.ENGLISH)
        assertEquals(listOf(1 to "Sep"), heatmap.monthLabels)
    }

    // ---- bookPace ----

    @Test
    fun paceSpeedMatchesTheReaderSheetsAllTimeSpeed() {
        // The reader sheet shows ReadingTotals.readingSpeed; the detail screen must show the same integer.
        val chars = 211_904
        val epub = bookPace(summary(ContentType.Epub, 3612.0, chars, null, listOf(DailyReading("2026-09-12", 3612.0, chars))))
        assertEquals(ReadingTotals(3612.0, chars).readingSpeed, epub.unitsPerHour)
        val manga = bookPace(summary(ContentType.Mokuro, 48.0, 0, 3, listOf(DailyReading("2026-09-12", 48.0, 0))))
        assertEquals(ReadingTotals(48.0, 3).readingSpeed, manga.unitsPerHour)
    }

    @Test
    fun averagePerDayIgnoresCharacterOnlyDaysAndBestDayNeedsTime() {
        val days = listOf(day("2026-09-12", 0.0, 500), day("2026-09-11", 20.0, 100), day("2026-09-10", 40.0, 100))
        val pace = bookPace(summary(ContentType.Epub, 3600.0, 700, null, days))
        assertEquals(1800.0, pace.averageSecondsPerDay, 0.0)
        assertEquals("2026-09-10", pace.bestDay?.dateKey)
    }

    @Test
    fun mangaWithTimeButNoPagesShowsNoPerPageFigure() {
        val pace = bookPace(summary(ContentType.Mokuro, 600.0, 0, 0, listOf(day("2026-09-12", 10.0))))
        assertEquals(0, pace.unitsPerHour)
        assertNull(pace.secondsPerUnit)
        assertEquals(600.0, pace.averageSecondsPerDay, 0.0)
    }

    // ---- summarizeReadingStatistics ----

    @Test
    fun mangaWithOnlyOcrCharactersIsListedWithoutDates() {
        val overview = summarizeReadingStatistics(
            listOf(
                BookStatisticsInput(
                    "m", "M", ContentType.Mokuro,
                    statistics = emptyList(),
                    mangaTextStatistics = listOf(MangaTextStatistic("2026-09-12", 300, lastModified = 1)),
                ),
            ),
            todayKey = "2026-09-12",
        )
        val row = overview.books.single()
        assertNull(row.startedDateKey)
        assertNull(row.lastReadDateKey)
        assertEquals(0, row.daysRead)
        assertEquals(0, row.pagesRead)
        assertEquals(300, row.charactersRead)
        assertEquals(300, overview.todayCharacters)
        assertEquals(listOf(DailyReading("2026-09-12", 0.0, 300)), row.days)
        val pace = bookPace(row)
        assertEquals(0.0, pace.averageSecondsPerDay, 0.0)
        assertNull(pace.bestDay)
        assertNull(pace.secondsPerUnit)
    }

    @Test
    fun finishedFollowsTheBookshelfThresholdAndProgressIsClamped() {
        val overview = summarizeReadingStatistics(
            listOf(
                BookStatisticsInput("a", "A", ContentType.Epub, listOf(stat("2026-09-01", 60.0)), progress = 0.999),
                BookStatisticsInput("b", "B", ContentType.Epub, listOf(stat("2026-09-01", 60.0)), progress = 0.9989),
                BookStatisticsInput("c", "C", ContentType.Epub, listOf(stat("2026-09-01", 60.0)), progress = 1.7),
            ),
            todayKey = "2026-09-12",
        )
        val byId = overview.books.associateBy { it.bookId }
        assertTrue(byId.getValue("a").finished)
        assertFalse(byId.getValue("b").finished)
        assertTrue(byId.getValue("c").finished)
        assertEquals(1.0, byId.getValue("c").progress, 0.0)
    }

    @Test
    fun startedAndLastReadComeFromDaysWithTimeNotCharacterOnlyDays() {
        val overview = summarizeReadingStatistics(
            listOf(
                BookStatisticsInput(
                    "a", "A", ContentType.Epub,
                    listOf(stat("2026-09-01", 0.0, units = 50), stat("2026-09-05", 60.0), stat("2026-09-09", 30.0), stat("2026-09-12", 0.0, units = 20)),
                ),
            ),
            todayKey = "2026-09-12",
        )
        val row = overview.books.single()
        assertEquals("2026-09-05", row.startedDateKey)
        assertEquals("2026-09-09", row.lastReadDateKey)
        assertEquals(2, row.daysRead)
        assertEquals(listOf("2026-09-12", "2026-09-09", "2026-09-05", "2026-09-01"), row.days.map { it.dateKey })
        assertEquals(row.totalSeconds, row.days.sumOf { it.seconds }, 0.0)
    }

    @Test
    fun streakHeatmapAndTotalsCardAllSeeTheSameDailyNumbers() {
        val overview = summarizeReadingStatistics(
            listOf(
                BookStatisticsInput("a", "A", ContentType.Epub, listOf(stat("2026-09-12", 400.0), stat("2026-09-11", 700.0))),
                BookStatisticsInput("b", "B", ContentType.Mokuro, listOf(stat("2026-09-12", 300.0, units = 4))),
            ),
            todayKey = "2026-09-12",
        )
        val streak = computeReadingStreak(overview.daily, 600.0, saturday)
        assertEquals(overview.todaySeconds, streak.todaySeconds, 0.0)
        assertEquals(2, streak.currentDays)
        val heatmap = buildReadingHeatmap(overview.daily, saturday, weeks = 1)
        assertEquals(overview.todaySeconds, heatmap.weeks.single().cells[5]!!.seconds, 0.0)
        assertEquals(overview.totalSeconds, overview.daily.sumOf { it.seconds }, 0.0)
        assertEquals(overview.totalSeconds, overview.books.sumOf { it.totalSeconds }, 0.0)
    }

    // ---- formatting ----

    @Test
    fun goalAndDurationFormatting() {
        assertEquals("10m", formatGoalDuration(600.0))
        assertEquals("5m", formatGoalDuration(300.0))
        assertEquals("0m", formatGoalDuration(0.0))
        assertEquals("1m 30s", formatGoalDuration(90.0))
        assertEquals("0s", formatDurationSeconds(-5.0))
        assertEquals("59m 59s", formatDurationSeconds(3599.9))
        assertEquals("1h 0m", formatDurationSeconds(3600.0))
    }

    @Test
    fun countsAndDatesFollowTheDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("4,050", formatStatisticsCount(4_050))
            assertEquals("Sep 12, 2026", formatStatisticsDate("2026-09-12"))
            assertEquals("bad-key", formatStatisticsDate("bad-key"))
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            assertEquals("2026年9月12日", formatStatisticsDate("2026-09-12"))
            assertEquals("周一", weekdayLabels().first())
        } finally {
            Locale.setDefault(previous)
        }
    }
}
