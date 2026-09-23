package moe.antimony.hoshi.features.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class UsageDaySummaryTest {
    private val day = LocalDate.parse("2026-09-23")
    private val zone = ZoneOffset.UTC

    private fun at(hour: Int, minute: Int = 0): Long = day.atTime(hour, minute).toInstant(zone).toEpochMilli()

    private fun event(type: UsageEventType, at: Long, session: String = "s1", title: String = "Manga") =
        UsageEvent(at = at, utcOffset = "+00:00", type = type, session = session, bookTitle = title)

    @Test
    fun spansComeFromStopEventsAndAreSortedAndSummed() {
        val events = listOf(
            event(UsageEventType.ReadingStarted, at(20)),
            event(UsageEventType.ReadingStopped, at(20, 30)).copy(startedAt = at(20)),
            event(UsageEventType.ReadingStarted, at(8), session = "s0"),
            event(UsageEventType.ReadingStopped, at(8, 15), session = "s0").copy(startedAt = at(8)),
        )

        val summary = summarizeUsageDay(events, day, zone)

        assertEquals(listOf(at(8), at(20)), summary.spans.map { it.startMillis })
        assertEquals(45 * 60_000L, summary.readingMillis)
        assertEquals(at(8), summary.firstActivityMillis)
        assertEquals(at(20, 30), summary.lastActivityMillis)
    }

    @Test
    fun aSpanThatBeganYesterdayCountsFromMidnight() {
        val yesterdayEvening = at(0) - 20 * 60_000L
        val events = listOf(event(UsageEventType.ReadingStopped, at(0, 10)).copy(startedAt = yesterdayEvening))

        val span = summarizeUsageDay(events, day, zone).spans.single()

        assertEquals(at(0), span.startMillis)
        assertEquals(10 * 60_000L, span.durationMillis)
    }

    @Test
    fun aSpanNeverStoppedEndsAtTheLastThingItsSessionLogged() {
        val events = listOf(
            event(UsageEventType.ReadingStarted, at(12)),
            event(UsageEventType.PageTurned, at(12, 5)),
            event(UsageEventType.WordLookedUp, at(12, 9)).copy(text = "本"),
            event(UsageEventType.PageTurned, at(13), session = "other"),
        )

        val span = summarizeUsageDay(events, day, zone).spans.single()

        assertEquals(at(12), span.startMillis)
        assertEquals(at(12, 9), span.endMillis)
    }

    @Test
    fun countsAndRecentWordsDescribeTheDay() {
        val events = listOf(
            event(UsageEventType.ReaderOpened, at(9)),
            event(UsageEventType.WordLookedUp, at(9, 1)).copy(text = "学校", term = "学校", outcome = "found"),
            event(UsageEventType.WordLookedUp, at(9, 2)).copy(text = "食べ", term = "食べる", outcome = "found"),
            event(UsageEventType.WordLookedUp, at(9, 3)).copy(text = "学校", term = "学校", outcome = "found"),
            event(UsageEventType.WordLookedUp, at(9, 3)).copy(text = "を食べる", outcome = "not-found"),
            event(UsageEventType.PageTurned, at(9, 4)),
            event(UsageEventType.PageTurned, at(9, 5)),
            event(UsageEventType.BubbleRevealed, at(9, 6)),
            event(UsageEventType.BubbleTranslated, at(9, 7)),
            event(UsageEventType.BubbleCopied, at(9, 8)),
            event(UsageEventType.ScreenshotTranslated, at(9, 9)),
            event(UsageEventType.ScreenshotTranslated, at(9, 10)),
            // Another day's event is ignored.
            event(UsageEventType.PageTurned, at(0) - 1),
        )

        val summary = summarizeUsageDay(events, day, zone)

        assertEquals(1, summary.sessions)
        assertEquals(4, summary.wordLookups)
        assertEquals(2, summary.distinctWords)
        assertEquals(listOf("学校", "食べる"), summary.recentWords)
        assertEquals(2, summary.pageTurns)
        assertEquals(1, summary.bubblesRevealed)
        assertEquals(1, summary.bubbleTranslations)
        assertEquals(1, summary.bubblesCopied)
        assertEquals(2, summary.screenshotTranslations)
    }

    @Test
    fun aDayWithoutEventsIsEmpty() {
        assertTrue(summarizeUsageDay(emptyList(), day, zone).isEmpty)
    }
}
