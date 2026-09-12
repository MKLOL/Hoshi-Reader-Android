package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.ReadingStatistics
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The tracker adds to this device's entry for the day and never touches another device's.
 * The sheet's "Today" still shows the whole day (every device), the same number the
 * Statistics page's history shows for that day.
 */
class ReaderStatisticsTrackerDeviceTest {
    private val phone = DeviceIdentity(id = "phone-id", name = "Pixel 8")
    private val tablet = DeviceIdentity(id = "tablet-id", name = "Galaxy Tab")

    private class FakeClock(var millis: Long, var date: LocalDate) : ReaderStatisticsClock {
        override fun currentTimeMillis(): Long = millis
        override fun currentDate(): LocalDate = date
        fun advance(seconds: Long) { millis += seconds * 1000 }
    }

    private fun day(dateKey: String, seconds: Double, characters: Int, modified: Long, device: DeviceIdentity?, name: String? = device?.name) =
        ReadingStatistics(
            title = "Book",
            dateKey = dateKey,
            charactersRead = characters,
            readingTime = seconds,
            lastStatisticModified = modified,
            deviceId = device?.id,
            deviceName = name,
        )

    @Test
    fun readingAddsToThisDevicesDayAndLeavesTheOtherDevicesDayAlone() {
        val clock = FakeClock(millis = 1_000_000L, date = LocalDate.parse("2026-09-12"))
        val tabletDay = day("2026-09-12", 300.0, 40, modified = 900_000L, device = tablet)
        val tracker = ReaderStatisticsTracker(
            title = "Book",
            initialStatistics = listOf(tabletDay),
            enabled = true,
            clock = clock,
            device = phone,
        )

        tracker.start(currentCharacter = 0)
        clock.advance(seconds = 60)
        tracker.update(currentCharacter = 20)

        val persisted = tracker.statisticsForPersistence()
        val own = persisted.single { it.deviceId == phone.id }
        assertEquals(tabletDay, persisted.single { it.deviceId == tablet.id })
        assertEquals("2026-09-12", own.dateKey)
        assertEquals(phone.name, own.deviceName)
        assertEquals(60.0, own.readingTime, 0.0)
        assertEquals(20, own.charactersRead)
        // Today on the sheet is the whole day across devices.
        assertEquals(360.0, tracker.state.today.readingTime, 0.0)
        assertEquals(60, tracker.state.today.charactersRead)
        assertEquals(360.0, tracker.state.allTime.readingTime, 0.0)
        assertEquals(60, tracker.state.allTime.charactersRead)
    }

    @Test
    fun thisDevicesStoredDayIsContinuedUnderItsCurrentName() {
        val clock = FakeClock(millis = 1_000_000L, date = LocalDate.parse("2026-09-12"))
        val tracker = ReaderStatisticsTracker(
            title = "Book",
            initialStatistics = listOf(day("2026-09-12", 100.0, 10, modified = 1L, device = phone, name = "Old name")),
            enabled = true,
            clock = clock,
            device = phone,
        )

        tracker.start(currentCharacter = 10)
        clock.advance(seconds = 5)
        tracker.update(currentCharacter = 15)

        val own = tracker.statisticsForPersistence().single()
        assertEquals(phone.id, own.deviceId)
        assertEquals("Pixel 8", own.deviceName)
        assertEquals(105.0, own.readingTime, 0.0)
        assertEquals(15, own.charactersRead)
    }

    @Test
    fun aNewDayStartsThisDevicesOwnEntryForIt() {
        val clock = FakeClock(millis = 1_000_000L, date = LocalDate.parse("2026-09-12"))
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = emptyList(), enabled = true, clock = clock, device = phone)
        tracker.start(currentCharacter = 0)
        clock.advance(seconds = 10)
        tracker.update(currentCharacter = 5)

        clock.date = LocalDate.parse("2026-09-13")
        clock.advance(seconds = 10)
        tracker.update(currentCharacter = 9)

        val persisted = tracker.statisticsForPersistence().sortedBy { it.dateKey }
        assertEquals(listOf("2026-09-12", "2026-09-13"), persisted.map { it.dateKey })
        assertEquals(listOf(phone.id, phone.id), persisted.map { it.deviceId })
        assertEquals("2026-09-13", tracker.state.today.dateKey)
        assertEquals(10.0, tracker.state.today.readingTime, 0.0)
    }

    @Test
    fun aNewDayTheOtherDeviceAlreadyReadOnStartsThisDevicesEntryNextToIt() {
        val clock = FakeClock(millis = 1_000_000L, date = LocalDate.parse("2026-09-12"))
        val tabletTomorrow = day("2026-09-13", 200.0, 20, modified = 1L, device = tablet)
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = listOf(tabletTomorrow), enabled = true, clock = clock, device = phone)
        tracker.start(currentCharacter = 0)
        clock.advance(seconds = 10)
        tracker.update(currentCharacter = 5)

        clock.date = LocalDate.parse("2026-09-13")
        clock.advance(seconds = 10)
        tracker.update(currentCharacter = 9)

        val persisted = tracker.statisticsForPersistence()
        assertEquals(tabletTomorrow, persisted.single { it.deviceId == tablet.id })
        assertEquals(10.0, persisted.single { it.dateKey == "2026-09-13" && it.deviceId == phone.id }.readingTime, 0.0)
        assertEquals("the sheet's today is the whole day", 210.0, tracker.state.today.readingTime, 0.0)
        assertEquals(24, tracker.state.today.charactersRead)
    }

    @Test
    fun withoutADeviceTheTrackerBehavesAsBefore() {
        val clock = FakeClock(millis = 1_000_000L, date = LocalDate.parse("2026-09-12"))
        val stored = day("2026-09-12", 100.0, 10, modified = 1L, device = null)
        val tracker = ReaderStatisticsTracker(title = "Book", initialStatistics = listOf(stored), enabled = true, clock = clock)

        tracker.start(currentCharacter = 10)
        clock.advance(seconds = 5)
        tracker.update(currentCharacter = 15)

        val own = tracker.statisticsForPersistence().single()
        assertEquals(null, own.deviceId)
        assertEquals(105.0, own.readingTime, 0.0)
    }
}
