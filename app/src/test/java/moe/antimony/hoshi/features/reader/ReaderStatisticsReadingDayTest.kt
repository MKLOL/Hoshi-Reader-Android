package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.ReadingStatistics
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ReaderStatisticsReadingDayTest {
    /** A clock whose date and time agree, as the real one's do. */
    private class Clock(var at: LocalDateTime) : ReaderStatisticsClock {
        override fun currentTimeMillis(): Long = at.toInstant(ZoneOffset.UTC).toEpochMilli()
        override fun currentDate(): LocalDate = at.toLocalDate()
        override fun zoneId(): ZoneId = ZoneOffset.UTC
    }

    private val lastNight = ReadingStatistics(
        title = "Book", dateKey = "2026-09-30", charactersRead = 3000, readingTime = 3600.0,
        lastStatisticModified = 1L, deviceId = "tablet",
        readingTimeByHour = mapOf("2026-09-30T21:00" to 3600.0),
    )

    @Test
    fun theSheetsTodayKeepsLastNightUntilTheResetHour() {
        val clock = Clock(LocalDateTime.parse("2026-10-01T00:30"))
        val tracker = ReaderStatisticsTracker("Book", listOf(lastNight), enabled = true, clock = clock, resetHour = { 3 })
        // Half past midnight is still last night's reading day.
        assertEquals(3600.0, tracker.state.today.readingTime, 0.0)
        assertEquals(3000, tracker.state.today.charactersRead)

        tracker.start(currentCharacter = 0)
        clock.at = clock.at.plusMinutes(10)
        tracker.update(currentCharacter = 500)
        assertEquals(4200.0, tracker.state.today.readingTime, 0.001)
        assertEquals(3500, tracker.state.today.charactersRead)
        tracker.pause(currentCharacter = 500)

        // From the reset hour on it is a new day, even though the calendar date did not change.
        clock.at = LocalDateTime.parse("2026-10-01T03:10")
        assertEquals(0.0, tracker.state.today.readingTime, 0.0)
        assertEquals("2026-10-01", tracker.state.today.dateKey)
    }

    @Test
    fun mangaOcrCharactersForTodayFollowTheSameReadingDay() {
        val clock = Clock(LocalDateTime.parse("2026-10-01T00:30"))
        val tracker = ReaderStatisticsTracker("Book", listOf(lastNight), enabled = true, clock = clock, resetHour = { 3 })
        assertEquals(800, tracker.readingDayCharacters(mapOf("2026-09-30" to 800, "2026-09-29" to 50)))
    }

    @Test
    fun aMidnightResetIsTheCalendarDayAsBefore() {
        val tracker = ReaderStatisticsTracker("Book", listOf(lastNight), enabled = true, clock = Clock(LocalDateTime.parse("2026-10-01T00:30")))
        assertEquals(0.0, tracker.state.today.readingTime, 0.0)
    }
}
