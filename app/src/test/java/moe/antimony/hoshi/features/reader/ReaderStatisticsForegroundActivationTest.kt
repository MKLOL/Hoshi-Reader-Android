package moe.antimony.hoshi.features.reader

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReaderStatisticsForegroundActivationTest {
    @Test
    fun lateStatisticsLoadWaitsForResumeAndDoesNotCreditBackgroundTime() {
        for (inactiveState in listOf(Lifecycle.State.CREATED, Lifecycle.State.STARTED)) {
            val clock = Clock()
            val tracker = ReaderStatisticsTracker("Book", emptyList(), true, clock)

            assertTrue(tracker.startWhenForeground(100, inactiveState))
            clock.millis += 60_000
            tracker.update(100)
            assertFalse(tracker.state.isTracking)
            assertEquals(0.0, tracker.state.allTime.readingTime, 0.0)
            assertEquals(null, tracker.statisticsForPersistenceOrNull())

            assertFalse(tracker.startWhenForeground(100, Lifecycle.State.RESUMED))
            clock.millis += 10_000
            tracker.pause(110)
            assertEquals(10.0, tracker.state.allTime.readingTime, 0.0)
            assertEquals(10, tracker.state.allTime.charactersRead)
        }
    }

    @Test
    fun aForegroundOpeningStartsCountingImmediately() {
        val clock = Clock()
        val tracker = ReaderStatisticsTracker("Book", emptyList(), true, clock)
        assertFalse(tracker.startWhenForeground(100, Lifecycle.State.RESUMED))
        assertTrue(tracker.state.isTracking)
        clock.millis += 5_000
        tracker.pause(105)
        assertEquals(5.0, tracker.state.allTime.readingTime, 0.0)
        assertEquals(5, tracker.state.allTime.charactersRead)
    }

    private class Clock : ReaderStatisticsClock {
        var millis = 1_000_000L
        override fun currentTimeMillis() = millis
        override fun currentDate(): LocalDate = LocalDate.of(2026, 10, 24)
    }
}
