package moe.antimony.hoshi.features.mangareader

import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.features.reader.ReaderStatisticsClock
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MangaTextReadCounterDeviceTest {
    private val phone = DeviceIdentity(id = "phone-id", name = "Pixel 8")
    private val tablet = DeviceIdentity(id = "tablet-id", name = "Galaxy Tab")

    private class FakeClock(val date: LocalDate, val millis: Long) : ReaderStatisticsClock {
        override fun currentTimeMillis(): Long = millis
        override fun currentDate(): LocalDate = date
    }

    @Test
    fun charactersGoToThisDevicesDayWhileTodayShowsEveryDevice() {
        val tabletDay = MangaTextStatistic("2026-09-12", 90, lastModified = 5, deviceId = tablet.id, deviceName = tablet.name)
        val counter = MangaTextReadCounter(
            initialStatistics = listOf(tabletDay),
            clock = FakeClock(LocalDate.of(2026, 9, 12), 1_000L),
            device = phone,
        )

        counter.add(30)
        counter.add(12)

        assertEquals(MangaTextReadState(sessionCharacters = 42, todayCharacters = 132, allTimeCharacters = 132), counter.state)
        val persisted = counter.statisticsForPersistenceOrNull()!!
        assertEquals(tabletDay, persisted.single { it.deviceId == tablet.id })
        assertEquals(
            MangaTextStatistic("2026-09-12", 42, lastModified = 1_001L, deviceId = phone.id, deviceName = phone.name),
            persisted.single { it.deviceId == phone.id },
        )
    }

    @Test
    fun thisDevicesStoredDayIsContinued() {
        val counter = MangaTextReadCounter(
            initialStatistics = listOf(MangaTextStatistic("2026-09-12", 100, lastModified = 5, deviceId = phone.id, deviceName = "Old name")),
            clock = FakeClock(LocalDate.of(2026, 9, 12), 1_000L),
            device = phone,
        )

        counter.add(10)

        assertEquals(
            listOf(MangaTextStatistic("2026-09-12", 110, lastModified = 1_000L, deviceId = phone.id, deviceName = phone.name)),
            counter.statisticsForPersistenceOrNull(),
        )
    }
}
