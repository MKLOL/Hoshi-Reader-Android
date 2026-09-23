package moe.antimony.hoshi.features.usage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneOffset

class ReaderUsageSessionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val day = LocalDate.parse("2026-09-23")
    private var now = day.atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val log by lazy { UsageLog(folder.root, clock = { now }, zone = { ZoneOffset.UTC }) }

    private fun session() = ReaderUsageSession(log, "book-1", "Test Manga", UsageContentType.Manga, id = "s1")

    private fun events() = runBlocking { log.eventsOn(day) }

    @Test
    fun aReadingSpanRecordsWhenItStartedOnItsStopEvent() {
        val session = session()
        session.opened(page = 1)
        session.readingChanged(true, page = 1)
        val startedAt = now
        now += 90_000
        session.readingChanged(false, page = 3)

        val stop = events().last()
        assertEquals(UsageEventType.ReadingStopped, stop.type)
        assertEquals(startedAt, stop.startedAt)
        assertEquals(3, stop.page)
        assertEquals("s1", stop.session)
        assertEquals("book-1", stop.bookId)
        assertEquals("manga", stop.contentType)
    }

    @Test
    fun repeatedStartsAndStopsOnlyLogRealChanges() {
        val session = session()
        session.readingChanged(true)
        session.readingChanged(true)
        session.readingChanged(false)
        session.readingChanged(false)

        assertEquals(
            listOf(UsageEventType.ReadingStarted, UsageEventType.ReadingStopped),
            events().map { it.type },
        )
    }

    @Test
    fun closingEndsAnOpenSpanAndNothingIsLoggedAfterwards() {
        val session = session()
        session.opened(page = 4)
        val openedAt = now
        session.readingChanged(true)
        now += 5_000
        session.closed(page = 5)
        session.pageTurned(5, 6)
        session.closed(page = 6)

        val events = events()
        assertEquals(
            listOf(
                UsageEventType.ReaderOpened,
                UsageEventType.ReadingStarted,
                UsageEventType.ReadingStopped,
                UsageEventType.ReaderClosed,
            ),
            events.map { it.type },
        )
        assertEquals(openedAt, events.last().startedAt)
    }

    @Test
    fun lookupsKeepTheirTextDictionaryFormSourceAndOutcome() {
        val session = session()
        session.wordLookedUp("食べ", UsageLookupSource.Page, found = true, page = 2, term = "食べる")
        session.wordLookedUp("ぬぬぬ", UsageLookupSource.Popup, found = false)
        session.wordLookedUp("   ", UsageLookupSource.Page, found = false)

        val (found, missing) = events()
        assertEquals("食べ", found.text)
        assertEquals("食べる", found.term)
        assertEquals("page", found.source)
        assertEquals("found", found.outcome)
        assertEquals(2, found.page)
        assertEquals("popup", missing.source)
        assertEquals("not-found", missing.outcome)
        assertNull(missing.term)
    }

    @Test
    fun aBubbleRevealedAgainInTheSameSessionIsOneBubble() {
        val session = session()
        session.bubbleRevealed("今日は", page = 1, blockId = "p0b1")
        session.bubbleRevealed("今日は", page = 1, blockId = "p0b1")
        session.bubbleRevealed("明日", page = 1, blockId = "p0b2")
        session.bubbleRevealed("?", page = 1, blockId = null)
        session.bubbleRevealed("?", page = 1, blockId = null)

        assertEquals(4, events().count { it.type == UsageEventType.BubbleRevealed })
    }

    @Test
    fun jumpsAreTaggedSoTheyAreNotCountedAsTurns() {
        val session = session()
        session.pageTurned(1, 2)
        session.pageTurned(2, 150, jump = true)

        val (turn, jump) = events()
        assertNull(turn.source)
        assertEquals(ReaderUsageSession.PAGE_JUMP, jump.source)
        assertEquals(1, summarizeUsageDay(events(), day, ZoneOffset.UTC).pageTurns)
    }

    @Test
    fun pageTurnsBubbleActionsAndScreenshotsAreEachTheirOwnEvent() {
        val session = session()
        session.pageTurned(1, 2)
        session.pageTurned(2, 2)
        session.bubbleRevealed("今日は", page = 2)
        session.bubbleTranslated("今日は", page = 2)
        session.bubbleCopied("今日は", page = 2)
        session.screenshotTranslated(page = 2)
        session.epubPageTurned(chapter = 3, character = 1_200)

        val events = events()
        assertEquals(
            listOf(
                UsageEventType.PageTurned,
                UsageEventType.BubbleRevealed,
                UsageEventType.BubbleTranslated,
                UsageEventType.BubbleCopied,
                UsageEventType.ScreenshotTranslated,
                UsageEventType.PageTurned,
            ),
            events.map { it.type },
        )
        assertEquals(1, events.first().fromPage)
        assertEquals(2, events.first().toPage)
        assertEquals(3, events.last().chapter)
        assertEquals(1_200, events.last().character)
    }
}
