package moe.antimony.hoshi.features.statistics

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.epub.recordedZone
import moe.antimony.hoshi.features.reader.BookStatisticsInput
import moe.antimony.hoshi.features.reader.DailyReading
import moe.antimony.hoshi.features.reader.summarizeReadingStatistics
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class StatisticsTimeZoneTest {
    private val eastern = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Test
    fun legacyRowsUseEasternEvenIfTheirStampSuggestsADifferentZone() {
        val legacy = ReadingStatistics(
            title = "Book", dateKey = "2026-01-11", readingTime = 600.0,
            lastStatisticModified = Instant.parse("2026-01-11T03:10:00Z").toEpochMilli(),
            readingTimeByHour = mapOf("2026-01-11T12:00" to 600.0),
        )
        assertEquals(eastern, legacy.recordedZone())
        assertEquals(eastern, legacy.copy(timeZone = "invalid").recordedZone())
        assertEquals(tokyo, legacy.copy(timeZone = tokyo.id).recordedZone())
        assertEquals(listOf(DailyReading("2026-01-11", 600.0, 0)), readingDays(listOf(legacy), emptyMap(), 3))
    }

    @Test
    fun resetIsAppliedToEachRecordingClockAndCharactersFollowTheirDevice() {
        val jan11 = "2026-01-11"
        val phone = ReadingStatistics(
            title = "Book", dateKey = jan11, readingTime = 900.0, charactersRead = 1000,
            deviceId = "phone", readingTimeByHour = mapOf("${jan11}T20:00" to 900.0), timeZone = eastern.id,
        )
        val tablet = ReadingStatistics(
            title = "Book", dateKey = jan11, readingTime = 600.0, charactersRead = 5000,
            deviceId = "tablet", readingTimeByHour = mapOf("${jan11}T02:00" to 600.0), timeZone = tokyo.id,
        )
        val input = BookStatisticsInput("b", "Book", ContentType.Epub, listOf(phone, tablet))
        val overview = summarizeReadingStatistics(listOf(input), jan11, 3)
        assertEquals(listOf(DailyReading(jan11, 900.0, 1000), DailyReading("2026-01-10", 600.0, 5000)), overview.daily)
        assertEquals(900.0, overview.todaySeconds, 0.0)
        assertEquals(2, computeReadingStreak(overview.daily, 600.0, java.time.LocalDate.parse(jan11)).currentDays)
    }

    @Test
    fun aCopyStrippedOfItsZoneMetadataNeverReplacesTheTaggedRow() {
        val first = mapOf("2026-01-11T02:00" to 600.0)
        val second = mapOf("2026-01-11T20:00" to 900.0)
        val mixed = ReadingStatistics(
            title = "Book", dateKey = "2026-01-11", readingTime = 1500.0,
            readingTimeByHour = first + second, readingTimeByZone = mapOf(tokyo.id to first, eastern.id to second),
        )
        val stripped = mixed.copy(readingTimeByZone = emptyMap())
        val uniform = stripped.copy(timeZone = eastern.id)
        assertEquals(listOf(mixed), listOf(mixed, stripped, uniform).deduplicateReadingStatistics())
        assertEquals(listOf(mixed), listOf(stripped, uniform, mixed).deduplicateReadingStatistics())
    }

    @Test
    fun exactLocalHoursSplitAcrossMidnightEvenWhenTheRowWasSavedAfterMidnight() {
        val row = ReadingStatistics(
            title = "Book", dateKey = "2026-01-11", readingTime = 1200.0, timeZone = tokyo.id,
            readingTimeByHour = mapOf("2026-01-10T23:00" to 600.0, "2026-01-11T00:00" to 600.0),
        )
        assertEquals(
            listOf(DailyReading("2026-01-11", 600.0, 0), DailyReading("2026-01-10", 600.0, 0)),
            readingDays(listOf(row), emptyMap(), 0),
        )
    }
}
