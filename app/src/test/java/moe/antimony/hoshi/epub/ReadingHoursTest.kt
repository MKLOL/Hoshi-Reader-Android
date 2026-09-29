package moe.antimony.hoshi.epub

import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.reader.ReaderStatisticsClock
import moe.antimony.hoshi.features.reader.ReaderStatisticsTracker
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ReadingHoursTest {
    private fun hours(start: String, end: String, zone: String = "UTC") =
        readingHoursBetween(Instant.parse(start).toEpochMilli(), Instant.parse(end).toEpochMilli(), ZoneId.of(zone))

    @Test fun splitsMidnightAndResetHourWithoutLosingSeconds() {
        assertEquals(mapOf("2026-09-28T23:00" to 60.0, "2026-09-29T00:00" to 120.0),
            hours("2026-09-28T23:59:00Z", "2026-09-29T00:02:00Z"))
        assertEquals(mapOf("2026-09-29T02:00" to 30.0, "2026-09-29T03:00" to 90.0),
            hours("2026-09-29T02:59:30Z", "2026-09-29T03:01:30Z"))
    }

    @Test fun daylightSavingMissingAndRepeatedHoursPreserveElapsedTime() {
        assertEquals(mapOf("2026-03-08T01:00" to 1800.0, "2026-03-08T03:00" to 1800.0),
            hours("2026-03-08T06:30:00Z", "2026-03-08T07:30:00Z", "America/New_York"))
        assertEquals(mapOf("2026-11-01T01:00" to 5400.0, "2026-11-01T02:00" to 1800.0),
            hours("2026-11-01T05:30:00Z", "2026-11-01T07:30:00Z", "America/New_York"))
    }

    @Test(timeout = 2000) fun halfHourAndTwoHourDstTransitionsAlwaysAdvanceToCorrectHour() {
        assertEquals(mapOf("2026-04-05T01:00" to 900.0, "2026-04-05T02:00" to 900.0),
            hours("2026-04-04T15:15:00Z", "2026-04-04T15:45:00Z", "Australia/Lord_Howe"))
        assertEquals(mapOf("2026-10-04T02:00" to 900.0, "2026-10-04T03:00" to 900.0),
            hours("2026-10-03T15:45:00Z", "2026-10-03T16:15:00Z", "Australia/Lord_Howe"))
        assertEquals(mapOf("2026-10-25T01:00" to 1800.0, "2026-10-25T02:00" to 1800.0),
            hours("2026-10-25T01:30:00Z", "2026-10-25T02:30:00Z", "Antarctica/Troll"))
    }

    @Test fun trackerPersistsHoursAcrossPauseMidnightAndReopen() {
        var now = ZonedDateTime.parse("2026-09-28T23:59:00Z")
        val clock = object : ReaderStatisticsClock {
            override fun currentTimeMillis() = now.toInstant().toEpochMilli()
            override fun currentDate() = now.toLocalDate()
            override fun zoneId(): ZoneId = ZoneId.of("UTC")
        }
        val tracker = ReaderStatisticsTracker("Book", emptyList(), true, clock)
        tracker.start(0)
        now = now.plusMinutes(3)
        tracker.pause(10)
        val persisted = Json.decodeFromString<List<ReadingStatistics>>(Json.encodeToString(tracker.statisticsForPersistence()))
        assertEquals(180.0, persisted.sumOf { it.readingTime }, 0.0)
        assertEquals(mapOf("2026-09-28T23:00" to 60.0, "2026-09-29T00:00" to 120.0), persisted.map { it.readingTimeByHour }.sumReadingHours())
        now = now.plusHours(4)
        val reopened = ReaderStatisticsTracker("Book", persisted, true, clock)
        reopened.start(10)
        now = now.plusSeconds(30)
        reopened.pause(20)
        assertEquals(210.0, reopened.statisticsForPersistence().sumOf { it.readingTimeByHour.values.sum() }, 0.0)
        assertEquals(210.0, reopened.state.allTime.readingTime, 0.0)
    }

    @Test fun legacyJsonAndCollapsedDeviceHoursRemainCompatible() {
        val legacy = Json.decodeFromString<ReadingStatistics>("""{"title":"Book","dateKey":"2026-09-29","readingTime":600} """)
        assertTrue(legacy.readingTimeByHour.isEmpty())
        val entries = listOf("one", "two").map { legacy.copy(deviceId = it, readingTimeByHour = mapOf("2026-09-29T04:00" to 300.0)) }
        val collapsed = entries.collapsedByDay().single()
        assertEquals(mapOf("2026-09-29T04:00" to 600.0), collapsed.readingTimeByHour)
        assertEquals(1200.0, collapsed.readingTime, 0.0)
    }
}
