package moe.antimony.hoshi.features.usage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class UsageReadingDayTest {
    @get:Rule val folder = TemporaryFolder()

    private fun lookup(at: String) = UsageEvent(
        at = LocalDateTime.parse(at).toInstant(ZoneOffset.UTC).toEpochMilli(), utcOffset = "+00:00",
        type = UsageEventType.WordLookedUp, session = "s", bookTitle = "Book", term = "天気",
    )

    @Test
    fun eventsKeepTheirRecordedDaysEvenAfterTravelAndMissingZoneUsesEastern() {
        val newYork = Instant.parse("2027-01-10T10:00:00Z")
        val tokyo = Instant.parse("2027-01-11T14:00:00Z")
        val legacy = Instant.parse("2027-01-12T05:30:00Z")
        val events = listOf(
            UsageEvent(at = newYork.toEpochMilli(), utcOffset = "-05:00", type = UsageEventType.WordLookedUp),
            UsageEvent(at = tokyo.toEpochMilli(), utcOffset = "+09:00", type = UsageEventType.WordLookedUp),
            UsageEvent(at = legacy.toEpochMilli(), type = UsageEventType.WordLookedUp),
        )
        assertEquals(28 * 3600L, tokyo.epochSecond - newYork.epochSecond)
        val counts = (10..12).map { summarizeUsageDay(events, LocalDate.of(2027, 1, it), resetHour = 3).wordLookups }
        assertEquals(listOf(1, 2, 0), counts)
    }

    @Test
    fun trendDaysAreReadingDaysThatReachIntoTheNextMorningsFile() = runBlocking {
        val directory = folder.newFolder()
        fun write(date: String, vararg events: UsageEvent) = File(directory, "$date.ndjson")
            .writeText(events.joinToString("") { Json.encodeToString(UsageEvent.serializer(), it) + "\n" })
        write("2026-09-30", lookup("2026-09-30T22:00"))
        write("2026-10-01", lookup("2026-10-01T01:00"), lookup("2026-10-01T10:00"))
        val log = UsageLog(directory, dispatcher = Dispatchers.IO)
        val today = LocalDate.parse("2026-10-01")

        val readingDays = loadUsageStatistics(log, today, historyDays = 7, resetHour = 3)
        assertEquals(2, readingDays.days.getValue(LocalDate.parse("2026-09-30")).wordLookups)
        assertEquals(1, readingDays.days.getValue(today).wordLookups)
        assertEquals(1, readingDays.today.wordLookups)

        // A midnight reset is the calendar, and the finished-day cache keeps the two apart.
        val calendar = loadUsageStatistics(log, today, historyDays = 7, resetHour = 0)
        assertEquals(1, calendar.days.getValue(LocalDate.parse("2026-09-30")).wordLookups)
        assertEquals(2, calendar.days.getValue(today).wordLookups)
    }
}
