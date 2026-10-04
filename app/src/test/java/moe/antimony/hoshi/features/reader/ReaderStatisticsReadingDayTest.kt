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

    /** A clock set to [zone], whatever the instant: the tablet left on China time. */
    private class ZonedClock(var instant: java.time.Instant, var zone: ZoneId) : ReaderStatisticsClock {
        override fun currentTimeMillis(): Long = instant.toEpochMilli()
        override fun currentDate(): LocalDate = instant.atZone(zone).toLocalDate()
        override fun zoneId(): ZoneId = zone
    }

    @Test
    fun entriesNameTheZoneTheyAreRecordedInAndTodayFollowsTheStatisticsZone() {
        val newYork = ZoneId.of("America/New_York")
        val shanghai = ZoneId.of("Asia/Shanghai")
        // 00:09 in New York on Oct 3 is 12:09 on the tablet's clock.
        val clock = ZonedClock(LocalDateTime.parse("2026-10-03T00:09").atZone(newYork).toInstant(), shanghai)
        val tracker = ReaderStatisticsTracker(
            "Book", emptyList(), enabled = true, clock = clock, device = moe.antimony.hoshi.epub.DeviceIdentity("tablet", "Tablet"),
            resetHour = { 3 }, statisticsZone = { newYork },
        )
        tracker.start(currentCharacter = 0)
        clock.instant = clock.instant.plusSeconds(20 * 60)
        tracker.update(currentCharacter = 40)
        tracker.pause(currentCharacter = 40)

        val stored = tracker.statisticsForPersistence().single()
        assertEquals("recorded on the tablet's own clock", "2026-10-03", stored.dateKey)
        assertEquals(mapOf("2026-10-03T12:00" to 1200.0), stored.readingTimeByHour)
        assertEquals("Asia/Shanghai", stored.timeZone)
        // Counted in New York: still the evening of Oct 2 until 03:00.
        assertEquals("2026-10-02", tracker.state.today.dateKey)
        assertEquals(1200.0, tracker.state.today.readingTime, 0.001)
        clock.instant = LocalDateTime.parse("2026-10-03T03:00").atZone(newYork).toInstant()
        assertEquals("2026-10-03", tracker.state.today.dateKey)
        assertEquals(0.0, tracker.state.today.readingTime, 0.0)
    }

    @Test
    fun hoursRecordedBeforeTheDevicesZoneChangedMoveToTheNewZone() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        val newYork = ZoneId.of("America/New_York")
        val clock = ZonedClock(LocalDateTime.parse("2026-10-03T12:00").atZone(shanghai).toInstant(), shanghai)
        val tracker = ReaderStatisticsTracker("Book", emptyList(), enabled = true, clock = clock, resetHour = { 3 })
        tracker.start(currentCharacter = 0)
        clock.instant = clock.instant.plusSeconds(600)
        tracker.update(currentCharacter = 10)
        // The tablet's clock is set right (12:10 in Shanghai is 00:10 in New York, same date).
        clock.zone = newYork
        clock.instant = clock.instant.plusSeconds(600)
        tracker.update(currentCharacter = 20)

        val stored = tracker.statisticsForPersistence().single { it.dateKey == "2026-10-03" }
        assertEquals("America/New_York", stored.timeZone)
        assertEquals(mapOf("2026-10-03T00:00" to 1200.0), stored.readingTimeByHour)
    }

    @Test
    fun theTabletsChinaTimeNightIsConvertedWhenItsClockIsSetRight() {
        val newYork = ZoneId.of("America/New_York")
        val tablet = moe.antimony.hoshi.epub.DeviceIdentity("tablet", "Tablet")
        // Recorded by 0.12.x on China time: 00:09–00:29 in New York stored as noon, no zone named.
        val night = ReadingStatistics(
            title = "Book", dateKey = "2026-10-03", readingTime = 1162.0, deviceId = tablet.id,
            lastStatisticModified = LocalDateTime.parse("2026-10-03T00:29").atZone(newYork).toInstant().toEpochMilli(),
            readingTimeByHour = mapOf("2026-10-03T12:00" to 1162.0),
        )
        // The clock is now on New York time and the tablet reads again that evening.
        val clock = ZonedClock(LocalDateTime.parse("2026-10-03T20:00").atZone(newYork).toInstant(), newYork)
        val tracker = ReaderStatisticsTracker(
            "Book", listOf(night), enabled = true, clock = clock, device = tablet, resetHour = { 3 }, statisticsZone = { newYork },
        )
        tracker.start(currentCharacter = 0)
        clock.instant = clock.instant.plusSeconds(600)
        tracker.update(currentCharacter = 10)

        val stored = tracker.statisticsForPersistence().single()
        assertEquals("America/New_York", stored.timeZone)
        assertEquals(mapOf("2026-10-03T00:00" to 1162.0, "2026-10-03T20:00" to 600.0), stored.readingTimeByHour)
        val days = moe.antimony.hoshi.features.statistics.readingDays(listOf(stored), emptyMap(), 3, newYork).associateBy { it.dateKey }
        assertEquals(1162.0, days.getValue("2026-10-02").seconds, 0.001)
        assertEquals(600.0, days.getValue("2026-10-03").seconds, 0.001)
    }

    @Test
    fun aMidnightResetIsTheCalendarDayAsBefore() {
        val tracker = ReaderStatisticsTracker("Book", listOf(lastNight), enabled = true, clock = Clock(LocalDateTime.parse("2026-10-01T00:30")))
        assertEquals(0.0, tracker.state.today.readingTime, 0.0)
    }
}
