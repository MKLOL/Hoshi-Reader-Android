package moe.antimony.hoshi.features.mangareader

import moe.antimony.hoshi.features.reader.ReaderStatisticsClock
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class MangaTextReadCounterTest {
    private class FakeClock(var date: LocalDate, var millis: Long) : ReaderStatisticsClock {
        override fun currentTimeMillis(): Long = millis
        override fun currentDate(): LocalDate = date
    }

    @Test
    fun startsFromTheSavedDaysAndAddsToTodayOnly() {
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 1_000L)
        val counter = MangaTextReadCounter(
            initialStatistics = listOf(
                MangaTextStatistic("2026-09-11", 300, lastModified = 5),
                MangaTextStatistic("2026-09-12", 40, lastModified = 6),
            ),
            clock = clock,
        )

        assertEquals(MangaTextReadState(sessionCharacters = 0, todayCharacters = 40, allTimeCharacters = 340), counter.state)
        assertNull(counter.statisticsForPersistenceOrNull())

        counter.add(12)
        counter.add(0)
        counter.add(-3)
        counter.add(8)

        assertEquals(MangaTextReadState(sessionCharacters = 20, todayCharacters = 60, allTimeCharacters = 360), counter.state)
        assertEquals(
            listOf(
                MangaTextStatistic("2026-09-11", 300, lastModified = 5),
                // Every add stamps strictly newer than the entry it replaces; the clock stood
                // still across the two adds, so the second is one past it.
                MangaTextStatistic("2026-09-12", 60, lastModified = 1_001L),
            ),
            counter.statisticsForPersistenceOrNull(),
        )
    }

    @Test
    fun aNewDayGetsItsOwnEntryWhileTheSessionKeepsCounting() {
        val clock = FakeClock(LocalDate.of(2026, 9, 12), 1L)
        val counter = MangaTextReadCounter(initialStatistics = emptyList(), clock = clock)

        counter.add(5)
        clock.date = LocalDate.of(2026, 9, 13)
        clock.millis = 2L
        counter.add(7)

        assertEquals(MangaTextReadState(sessionCharacters = 12, todayCharacters = 7, allTimeCharacters = 12), counter.state)
        assertEquals(
            listOf(MangaTextStatistic("2026-09-12", 5, 1L), MangaTextStatistic("2026-09-13", 7, 2L)),
            counter.statisticsForPersistenceOrNull(),
        )
    }
}
