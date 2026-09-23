package moe.antimony.hoshi.features.usage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneOffset

class UsageStatisticsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val today = LocalDate.parse("2026-09-23")
    private var now = 0L
    private val log by lazy { UsageLog(folder.root, clock = { now }, zone = { ZoneOffset.UTC }) }

    private fun record(date: LocalDate, type: UsageEventType, source: String? = null) {
        now = date.atTime(20, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        log.append(log.newEvent(type).copy(contentType = "manga", source = source, text = "本", term = "本", outcome = "found"))
    }

    @Test
    fun dailyTotalsStartOnTheFirstLoggedDayAndSkipQuietDays() = runBlocking {
        val first = today.minusDays(3)
        record(first, UsageEventType.BubbleRevealed)
        record(first, UsageEventType.WordLookedUp, source = "page")
        record(today, UsageEventType.BubbleRevealed)
        record(today, UsageEventType.BubbleRevealed)
        record(today, UsageEventType.BubbleTranslated)

        val statistics = loadUsageStatistics(log, today, historyDays = 90, zone = ZoneOffset.UTC)

        assertEquals(first, statistics.firstLoggedDate)
        assertEquals(setOf(first, today), statistics.days.keys)
        assertEquals(UsageDayCounts(wordLookups = 1, mangaPageLookups = 1, bubblesRevealed = 1), statistics.days[first])
        assertEquals(UsageDayCounts(bubblesRevealed = 2, bubbleTranslations = 1), statistics.days[today])
        assertEquals(1, statistics.today.bubbleTranslations)
    }

    @Test
    fun theDaysBeforeTheRangeAreLoadedSoItsFirstAverageIsReal() = runBlocking {
        record(today.minusDays(4), UsageEventType.BubbleRevealed)
        record(today.minusDays(3), UsageEventType.BubbleRevealed)
        record(today, UsageEventType.BubbleRevealed)

        // Two days shown with a three-day average reaches back to today - 3, not today - 4.
        val statistics = loadUsageStatistics(log, today, historyDays = 2, zone = ZoneOffset.UTC, averageWindow = 3)

        assertEquals(setOf(today.minusDays(3), today), statistics.days.keys)
    }

    @Test
    fun finishedDaysAreReadOnceAndTodayIsAlwaysReread() = runBlocking {
        val yesterday = today.minusDays(1)
        record(yesterday, UsageEventType.BubbleRevealed)
        record(today, UsageEventType.BubbleRevealed)
        loadUsageStatistics(log, today, historyDays = 7, zone = ZoneOffset.UTC)
        record(yesterday, UsageEventType.BubbleRevealed)
        record(today, UsageEventType.BubbleRevealed)

        val statistics = loadUsageStatistics(log, today, historyDays = 7, zone = ZoneOffset.UTC)

        assertEquals(1, statistics.days[yesterday]?.bubblesRevealed)
        assertEquals(2, statistics.days[today]?.bubblesRevealed)
    }
}
