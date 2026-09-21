package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.readingTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The tracker keeps "today" and "all time" incrementally while the Statistics page recomputes
 * them from the persisted per-day list. After every pause/start cycle, day change and reopen,
 * both derivations must agree to the second.
 */
class ReaderStatisticsTrackerCyclesTest {
    private class FakeClock(var date: LocalDate, var millis: Long) : ReaderStatisticsClock {
        override fun currentTimeMillis(): Long = millis
        override fun currentDate(): LocalDate = date
        fun advanceSeconds(seconds: Long) { millis += seconds * 1_000L }
    }

    private fun day(dateKey: String, seconds: Double, characters: Int, modified: Long) =
        ReadingStatistics(
            title = "Book",
            dateKey = dateKey,
            charactersRead = characters,
            readingTime = seconds,
            lastStatisticModified = modified,
        )

    /** What the Statistics page would show for this tracker's persisted list, against the sheet. */
    private fun assertPageAgreesWithSheet(tracker: ReaderStatisticsTracker, todayKey: String) {
        val persisted = tracker.statisticsForPersistence()
        val totals = persisted.readingTotals()
        assertEquals(tracker.state.allTime.readingTime, totals.readingTime, 1e-9)
        assertEquals(tracker.state.allTime.charactersRead, totals.charactersRead)
        assertEquals(tracker.state.allTime.lastReadingSpeed, totals.readingSpeed)
        val overview = summarizeReadingStatistics(
            listOf(BookStatisticsInput("b", "Book", ContentType.Epub, persisted)),
            todayKey = todayKey,
        )
        val row = overview.books.single()
        assertEquals(tracker.state.allTime.readingTime, row.totalSeconds, 1e-9)
        assertEquals(tracker.state.allTime.charactersRead, row.charactersRead)
        assertEquals(tracker.state.today.readingTime, overview.todaySeconds, 1e-9)
        assertEquals(tracker.state.today.charactersRead, overview.todayCharacters)
        assertEquals(todayKey, row.lastReadDateKey)
    }

    @Test
    fun pauseAndStartCyclesAddOnlyActiveTimeToTodayAndAllTime() {
        val history = listOf(day("2026-09-10", 900.0, 2_000, modified = 1), day("2026-09-12", 120.0, 300, modified = 2))
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 1_000_000L)
        val tracker = ReaderStatisticsTracker("Book", history, enabled = true, clock = clock)
        assertEquals(1_020.0, tracker.state.allTime.readingTime, 0.0)
        assertEquals(120.0, tracker.state.today.readingTime, 0.0)

        var character = 300
        repeat(3) { cycle ->
            tracker.start(currentCharacter = character)
            clock.advanceSeconds(40)
            character += 100
            tracker.update(character)
            clock.advanceSeconds(20)
            character += 50
            assertTrue(tracker.pause(character))
            clock.advanceSeconds(600) // in the background: counts nowhere

            val cycles = cycle + 1
            assertEquals(60.0 * cycles, tracker.state.session.readingTime, 0.0)
            assertEquals(150 * cycles, tracker.state.session.charactersRead)
            assertEquals(120.0 + 60.0 * cycles, tracker.state.today.readingTime, 0.0)
            assertEquals(300 + 150 * cycles, tracker.state.today.charactersRead)
            assertEquals(1_020.0 + 60.0 * cycles, tracker.state.allTime.readingTime, 0.0)
            assertEquals(2_300 + 150 * cycles, tracker.state.allTime.charactersRead)
            assertPageAgreesWithSheet(tracker, todayKey = "2026-09-12")
        }

        assertFalse(tracker.pause(character)) // already paused: nothing more is added
        tracker.update(character + 999) // ignored while paused
        assertEquals(1_200.0, tracker.state.allTime.readingTime, 0.0)
        assertEquals(2_750, tracker.state.allTime.charactersRead)
        assertPageAgreesWithSheet(tracker, todayKey = "2026-09-12")
    }

    @Test
    fun aPauseThatSpansMidnightPutsNewReadingOnTheNewDayOnly() {
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 5_000L)
        val tracker = ReaderStatisticsTracker("Book", listOf(day("2026-09-12", 100.0, 10, modified = 1)), enabled = true, clock = clock)

        tracker.start(10)
        clock.advanceSeconds(50)
        assertTrue(tracker.pause(60)) // the 12th ends with 150 s and 60 characters
        clock.date = LocalDate.of(2026, 9, 13)
        clock.advanceSeconds(8 * 3_600) // overnight in the background
        tracker.start(60)
        clock.advanceSeconds(30)
        tracker.update(90) // the 13th: 30 s and 30 characters

        assertEquals("2026-09-13", tracker.state.today.dateKey)
        assertEquals(30.0, tracker.state.today.readingTime, 0.0)
        assertEquals(30, tracker.state.today.charactersRead)
        assertEquals(180.0, tracker.state.allTime.readingTime, 0.0)
        assertEquals(90, tracker.state.allTime.charactersRead)
        val persisted = tracker.statisticsForPersistence()
        assertEquals(150.0, persisted.single { it.dateKey == "2026-09-12" }.readingTime, 0.0)
        assertEquals(60, persisted.single { it.dateKey == "2026-09-12" }.charactersRead)
        assertEquals(30.0, persisted.single { it.dateKey == "2026-09-13" }.readingTime, 0.0)
        assertPageAgreesWithSheet(tracker, todayKey = "2026-09-13")
    }

    @Test
    fun reopeningTheBookShowsExactlyWhatTheLastSessionPersisted() {
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 42_000L)
        val first = ReaderStatisticsTracker("Book", listOf(day("2026-09-11", 300.0, 700, modified = 1)), enabled = true, clock = clock)
        first.start(0)
        clock.advanceSeconds(75)
        first.update(250)
        clock.advanceSeconds(15)
        assertTrue(first.pause(260))
        val persisted = first.statisticsForPersistence()

        clock.advanceSeconds(3_600)
        val reopened = ReaderStatisticsTracker("Book", persisted, enabled = true, clock = clock)

        assertEquals(first.state.today, reopened.state.today)
        assertEquals(first.state.allTime.readingTime, reopened.state.allTime.readingTime, 0.0)
        assertEquals(first.state.allTime.charactersRead, reopened.state.allTime.charactersRead)
        assertEquals(first.state.allTime.lastReadingSpeed, reopened.state.allTime.lastReadingSpeed)
        assertEquals(390.0, reopened.state.allTime.readingTime, 0.0)
        assertEquals(960, reopened.state.allTime.charactersRead)
        assertPageAgreesWithSheet(reopened, todayKey = "2026-09-12")
    }
}
