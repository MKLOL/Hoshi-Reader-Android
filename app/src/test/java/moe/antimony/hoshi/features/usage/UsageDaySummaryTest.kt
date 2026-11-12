package moe.antimony.hoshi.features.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class UsageDaySummaryTest {
    private val day = LocalDate.parse("2027-01-23")
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

        val summary = summarizeUsageDay(events, day)

        assertEquals(listOf(at(8), at(20)), summary.spans.map { it.startMillis })
        assertEquals(45 * 60_000L, summary.readingMillis)
        assertEquals(at(8), summary.firstActivityMillis)
        assertEquals(at(20, 30), summary.lastActivityMillis)
    }

    @Test
    fun aSpanThatBeganYesterdayCountsFromMidnight() {
        val yesterdayEvening = at(0) - 20 * 60_000L
        val events = listOf(event(UsageEventType.ReadingStopped, at(0, 10)).copy(startedAt = yesterdayEvening))

        val span = summarizeUsageDay(events, day).spans.single()

        assertEquals(at(0), span.startMillis)
        assertEquals(10 * 60_000L, span.durationMillis)
    }

    @Test
    fun stopEventKeepsTheStartZoneWhenTheDeviceZoneChanged() {
        val start = at(8)
        val stop = at(8, 20)
        val event = event(UsageEventType.ReadingStopped, stop).copy(
            utcOffset = "+14:00", timeZone = "Pacific/Kiritimati", startedAt = start,
            startedTimeZone = "America/New_York",
        )
        val span = summarizeUsageDay(listOf(event), day, resetHour = 3).spans.single()
        assertEquals(start, span.startMillis)
        assertEquals(20 * 60_000L, span.durationMillis)
        assertEquals("America/New_York", span.zone.id)
    }

    @Test
    fun aSpanCrossingTheNovemberResetUsesBothClocksWithoutCountingTimeTwice() {
        val cutoff = Instant.parse("2026-11-01T08:00:00Z").toEpochMilli()
        val start = cutoff - 5 * 60_000L
        val end = cutoff + 5 * 60_000L
        val stop = event(UsageEventType.ReadingStopped, end).copy(
            utcOffset = "+09:00", timeZone = "Asia/Tokyo", startedAt = start, startedTimeZone = "Asia/Tokyo",
        )
        val events = listOf(
            stop,
            stop.copy(type = UsageEventType.WordLookedUp, at = cutoff - 1),
            stop.copy(type = UsageEventType.WordLookedUp, at = cutoff),
        )
        val october = summarizeUsageDay(events, LocalDate.parse("2026-10-31"), resetHour = 3)
        val november = summarizeUsageDay(events, LocalDate.parse("2026-11-01"), resetHour = 3)

        assertEquals(1, october.wordLookups)
        assertEquals(1, november.wordLookups)
        assertEquals(start, october.spans.single().startMillis)
        assertEquals(cutoff, october.spans.single().endMillis)
        assertEquals("America/New_York", october.spans.single().zone.id)
        assertEquals(cutoff, november.spans.single().startMillis)
        assertEquals(end, november.spans.single().endMillis)
        assertEquals("Asia/Tokyo", november.spans.single().zone.id)
        assertEquals(end - start, october.readingMillis + november.readingMillis)
    }

    @Test
    fun aSpanNeverStoppedEndsAtTheLastThingItsSessionLogged() {
        val events = listOf(
            event(UsageEventType.ReadingStarted, at(12)),
            event(UsageEventType.PageTurned, at(12, 5)),
            event(UsageEventType.WordLookedUp, at(12, 9)).copy(text = "本"),
            event(UsageEventType.PageTurned, at(13), session = "other"),
        )

        val span = summarizeUsageDay(events, day).spans.single()

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

        val summary = summarizeUsageDay(events, day)

        assertEquals(1, summary.sessions)
        assertEquals(4, summary.wordLookups)
        assertEquals(2, summary.distinctWords)
        // Only 学校 was found twice; 食べる once, and the miss is not a word.
        assertEquals(listOf(UsageWordCount("学校", 2)), summary.repeatedWords)
        assertEquals(2, summary.pageTurns)
        assertEquals(1, summary.bubblesRevealed)
        assertEquals(1, summary.bubbleTranslations)
        assertEquals(1, summary.bubblesCopied)
        assertEquals(2, summary.screenshotTranslations)
    }

    @Test
    fun repeatedWordsAreTheMostLookedUpFirstThenTheLatestAndCappedAtFive() {
        fun lookup(minute: Int, term: String) =
            event(UsageEventType.WordLookedUp, at(10, minute)).copy(text = term, term = term, outcome = "found")
        val terms = listOf("本", "読む", "本", "学校", "読む", "今日", "読む", "学校", "行く", "今日", "好き", "行く", "明日", "好き", "楽しい")
        val events = terms.mapIndexed { minute, term -> lookup(minute, term) } +
            lookup(40, "明日") + lookup(41, "一度だけ")

        val repeated = summarizeUsageDay(events, day).repeatedWords

        assertEquals(
            listOf(
                UsageWordCount("読む", 3),
                UsageWordCount("明日", 2),
                UsageWordCount("好き", 2),
                UsageWordCount("行く", 2),
                UsageWordCount("今日", 2),
            ),
            repeated,
        )
    }

    @Test
    fun onlyPressesOnAMangaPageCountTowardsLookupsPerBubble() {
        fun lookup(minute: Int, contentType: String, source: String) =
            event(UsageEventType.WordLookedUp, at(11, minute))
                .copy(text = "本", term = "本", outcome = "found", contentType = contentType, source = source)
        val events = listOf(
            lookup(1, "manga", "page"),
            lookup(2, "manga", "page"),
            lookup(3, "manga", "popup"),
            lookup(4, "epub", "page"),
        )

        val summary = summarizeUsageDay(events, day)

        assertEquals(4, summary.wordLookups)
        assertEquals(2, summary.mangaPageLookups)
    }

    @Test
    fun aDayWithoutEventsIsEmpty() {
        assertTrue(summarizeUsageDay(emptyList(), day).isEmpty)
    }

    @Test
    fun aReadingDayRunsFromTheResetHourToTheResetHour() {
        val nextDay = day.plusDays(1)
        fun next(hour: Int): Long = nextDay.atTime(hour, 0).toInstant(zone).toEpochMilli()
        val events = listOf(
            event(UsageEventType.WordLookedUp, at(1)),
            event(UsageEventType.WordLookedUp, at(22)),
            event(UsageEventType.WordLookedUp, next(1)),
            event(UsageEventType.WordLookedUp, next(4)),
        )

        // From 03:00 on the day until 03:00 the next morning, as the streak counts it.
        val summary = summarizeUsageDay(events, day, resetHour = 3)

        assertEquals(2, summary.wordLookups)
        assertEquals(at(22), summary.firstActivityMillis)
        assertEquals(next(1), summary.lastActivityMillis)
        assertEquals(2, summarizeUsageDay(events, day).wordLookups)
    }
}
