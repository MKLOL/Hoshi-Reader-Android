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
        val tracker = ReaderStatisticsTracker(
            "Book", listOf(lastNight), enabled = true, clock = clock, resetHour = { 3 }, legacyZone = { ZoneOffset.UTC },
        )
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
    fun entriesNameTheRecordingZoneAndTodayFollowsTheViewingDevice() {
        val newYork = ZoneId.of("America/New_York")
        val shanghai = ZoneId.of("Asia/Shanghai")
        // 00:09 in New York on Oct 3 is 12:09 on the tablet's clock.
        val clock = ZonedClock(LocalDateTime.parse("2027-10-03T00:09").atZone(newYork).toInstant(), shanghai)
        val tracker = ReaderStatisticsTracker(
            "Book", emptyList(), enabled = true, clock = clock, device = moe.antimony.hoshi.epub.DeviceIdentity("tablet", "Tablet"),
            resetHour = { 3 },
        )
        tracker.start(currentCharacter = 0)
        clock.instant = clock.instant.plusSeconds(20 * 60)
        tracker.update(currentCharacter = 40)
        tracker.pause(currentCharacter = 40)

        val stored = tracker.statisticsForPersistence().single()
        assertEquals("recorded on the tablet's own clock", "2027-10-03", stored.dateKey)
        assertEquals(mapOf("2027-10-03T12:00" to 1200.0), stored.readingTimeByHour)
        assertEquals("Asia/Shanghai", stored.timeZone)
        assertEquals("2027-10-03", tracker.state.today.dateKey)
        assertEquals(1200.0, tracker.state.today.readingTime, 0.001)
        clock.zone = newYork
        assertEquals("2027-10-02", tracker.state.today.dateKey)
        assertEquals(0.0, tracker.state.today.readingTime, 0.0)
        clock.instant = LocalDateTime.parse("2027-10-03T03:00").atZone(newYork).toInstant()
        assertEquals("2027-10-03", tracker.state.today.dateKey)
        assertEquals(1200.0, tracker.state.today.readingTime, 0.0)
    }

    @Test
    fun sameDateZoneChangeKeepsEarlierHoursAndTagsNewReading() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        val newYork = ZoneId.of("America/New_York")
        val clock = ZonedClock(LocalDateTime.parse("2027-10-03T12:00").atZone(shanghai).toInstant(), shanghai)
        val tracker = ReaderStatisticsTracker("Book", emptyList(), enabled = true, clock = clock, resetHour = { 3 })
        tracker.start(currentCharacter = 0)
        clock.instant = clock.instant.plusSeconds(600)
        tracker.update(currentCharacter = 10)
        // The tablet's clock is set right (12:10 in Shanghai is 00:10 in New York, same date).
        clock.zone = newYork
        clock.instant = clock.instant.plusSeconds(600)
        tracker.update(currentCharacter = 20)

        clock.instant = clock.instant.plusSeconds(600)
        tracker.update(currentCharacter = 30)

        val stored = tracker.statisticsForPersistence().single { it.dateKey == "2027-10-03" }
        assertEquals(null, stored.timeZone)
        val before = mapOf("2027-10-03T12:00" to 1200.0)
        val after = mapOf("2027-10-03T00:00" to 600.0)
        assertEquals(before + after, stored.readingTimeByHour)
        assertEquals(mapOf(shanghai.id to before, newYork.id to after), stored.readingTimeByZone)
        assertEquals("2027-10-02", tracker.state.today.dateKey)
        assertEquals(600.0, tracker.state.today.readingTime, 0.001)
    }

    @Test
    fun sameDateLegacyHoursKeepTheirEasternTimeWhenNewReadingHasAnotherZone() {
        val eastern = ZoneId.of("America/New_York")
        val shanghai = ZoneId.of("Asia/Shanghai")
        val tablet = moe.antimony.hoshi.epub.DeviceIdentity("tablet", "Tablet")
        // A stamp suggestive of Shanghai must not override the US Eastern fallback.
        val legacyHours = mapOf("2027-10-03T12:00" to 1162.0)
        val legacy = ReadingStatistics(
            title = "Book", dateKey = "2027-10-03", readingTime = 1162.0, deviceId = tablet.id,
            lastStatisticModified = LocalDateTime.parse("2027-10-03T12:29").atZone(shanghai).toInstant().toEpochMilli(),
            readingTimeByHour = legacyHours,
        )
        val clock = ZonedClock(LocalDateTime.parse("2027-10-03T20:00").atZone(shanghai).toInstant(), shanghai)
        val tracker = ReaderStatisticsTracker(
            "Book", listOf(legacy), enabled = true, clock = clock, device = tablet, resetHour = { 3 },
        )
        tracker.start(currentCharacter = 0)
        clock.instant = clock.instant.plusSeconds(600)
        tracker.update(currentCharacter = 10)

        val stored = tracker.statisticsForPersistence().single()
        val newHours = mapOf("2027-10-03T20:00" to 600.0)
        assertEquals(null, stored.timeZone)
        assertEquals(legacyHours + newHours, stored.readingTimeByHour)
        assertEquals(mapOf(eastern.id to legacyHours, shanghai.id to newHours), stored.readingTimeByZone)
        assertEquals(1762.0, tracker.state.today.readingTime, 0.001)
    }

    @Test
    fun pendingReadingStaysOnThePreviousZoneDateWhenTheDeviceCrossesMidnight() {
        val pacific = ZoneId.of("America/Los_Angeles")
        val tokyo = ZoneId.of("Asia/Tokyo")
        val clock = ZonedClock(LocalDateTime.parse("2027-01-10T10:00").atZone(pacific).toInstant(), pacific)
        val tracker = ReaderStatisticsTracker("Book", emptyList(), enabled = true, clock = clock, resetHour = { 3 })
        tracker.start(currentCharacter = 0)
        clock.instant = clock.instant.plusSeconds(600)
        clock.zone = tokyo
        tracker.update(currentCharacter = 10)
        clock.instant = clock.instant.plusSeconds(600)
        tracker.update(currentCharacter = 20)

        val rows = tracker.statisticsForPersistence().filter { it.readingTime > 0 }.associateBy { it.dateKey }
        assertEquals(pacific.id, rows.getValue("2027-01-10").timeZone)
        assertEquals(mapOf("2027-01-10T10:00" to 600.0), rows.getValue("2027-01-10").readingTimeByHour)
        assertEquals(tokyo.id, rows.getValue("2027-01-11").timeZone)
        assertEquals(mapOf("2027-01-11T03:00" to 600.0), rows.getValue("2027-01-11").readingTimeByHour)
        assertEquals(600.0, tracker.state.today.readingTime, 0.001)
    }

    @Test
    fun todaysTotalsCanIncludePreviousDayHoursFromTwoDatesAhead() {
        val clock = Clock(LocalDateTime.parse("2026-01-10T12:00"))
        val ahead = ReadingStatistics(
            title = "Book", dateKey = "2026-01-12", charactersRead = 100, readingTime = 1200.0,
            deviceId = "tablet", readingTimeByHour = mapOf(
                "2026-01-11T02:00" to 600.0, "2026-01-11T04:00" to 600.0,
            ),
        )
        val older = lastNight.copy(dateKey = "2026-01-08", readingTimeByHour = emptyMap())
        var reset = 3
        val tracker = ReaderStatisticsTracker(
            "Book", listOf(older, ahead), enabled = true, clock = clock, resetHour = { reset },
        )
        val ocr = mapOf("2026-01-12" to 20, "2026-01-08" to 80)
        assertEquals("2026-01-10", tracker.state.today.dateKey)
        assertEquals(600.0, tracker.state.today.readingTime, 0.0)
        assertEquals(50, tracker.state.today.charactersRead)
        assertEquals(10, tracker.readingDayCharacters(ocr))
        assertEquals(4800.0, tracker.state.allTime.readingTime, 0.0)
        reset = 5
        // The date label stays the same while the new reset makes another hour eligible.
        assertEquals("2026-01-10", tracker.state.today.dateKey)
        assertEquals(1200.0, tracker.state.today.readingTime, 0.0)
        assertEquals(20, tracker.readingDayCharacters(ocr))
    }

    @Test
    fun aMidnightResetIsTheCalendarDayAsBefore() {
        val tracker = ReaderStatisticsTracker(
            "Book", listOf(lastNight), enabled = true, clock = Clock(LocalDateTime.parse("2026-10-01T00:30")),
            legacyZone = { ZoneOffset.UTC },
        )
        assertEquals(0.0, tracker.state.today.readingTime, 0.0)
    }

    @Test
    fun theReaderSheetSwitchesClocksAtTheSharedNovemberReset() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val clock = ZonedClock(java.time.Instant.parse("2026-11-01T07:59:59Z"), tokyo)
        val tablet = ReadingStatistics(
            title = "Book", dateKey = "2026-11-01", timeZone = tokyo.id,
            readingTime = 900.0, charactersRead = 90, deviceId = "tablet",
            readingTimeByHour = mapOf("2026-11-01T16:00" to 600.0, "2026-11-01T17:00" to 300.0),
        )
        val tracker = ReaderStatisticsTracker("Book", listOf(tablet), enabled = true, clock = clock, resetHour = { 3 })
        assertEquals("2026-10-31", tracker.state.today.dateKey)
        assertEquals(600.0, tracker.state.today.readingTime, 0.0)
        assertEquals(60, tracker.state.today.charactersRead)
        clock.instant = clock.instant.plusSeconds(1)
        assertEquals("2026-11-01", tracker.state.today.dateKey)
        assertEquals(300.0, tracker.state.today.readingTime, 0.0)
        assertEquals(30, tracker.state.today.charactersRead)
    }

    @Test
    fun recordingSplitsTheCutoffEvenWhenItFallsPartwayThroughALocalHour() {
        val india = ZoneId.of("Asia/Kolkata")
        val clock = ZonedClock(java.time.Instant.parse("2026-11-01T07:50:00Z"), india)
        val tracker = ReaderStatisticsTracker("Book", emptyList(), enabled = true, clock = clock, resetHour = { 3 })
        tracker.start(0)
        clock.instant = clock.instant.plusSeconds(20 * 60L)
        tracker.update(0)
        // An interval starting after the cutoff in the same hour must use the same later key.
        clock.instant = clock.instant.plusSeconds(5 * 60L)
        tracker.update(0)

        val records = tracker.statisticsForPersistence()
        assertEquals(
            mapOf("2026-11-01T13:00" to 600.0, "2026-11-01T13:30" to 900.0),
            records.single().readingTimeByHour,
        )
        val book = BookStatisticsInput("b", "Book", moe.antimony.hoshi.epub.ContentType.Epub, records)
        val overview = summarizeReadingStatistics(listOf(book), "2026-11-01", 3)
        assertEquals(listOf(DailyReading("2026-11-01", 900.0, 0), DailyReading("2026-10-31", 600.0, 0)), overview.daily)
        assertEquals(900.0, tracker.state.today.readingTime, 0.0)
        assertEquals(1500.0, tracker.state.allTime.readingTime, 0.0)
    }
}
