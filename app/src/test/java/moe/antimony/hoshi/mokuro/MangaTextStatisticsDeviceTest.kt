package moe.antimony.hoshi.mokuro

import moe.antimony.hoshi.epub.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaTextStatisticsDeviceTest {
    private val phone = DeviceIdentity(id = "phone-id", name = "Pixel 8")
    private val tablet = DeviceIdentity(id = "tablet-id", name = "Galaxy Tab")

    @Test
    fun eachDeviceKeepsItsOwnDayAndTheNewestEntryWinsWithinADevice() {
        val entries = listOf(
            MangaTextStatistic("2026-09-12", 300, lastModified = 5, deviceId = phone.id, deviceName = phone.name),
            MangaTextStatistic("2026-09-12", 320, lastModified = 6, deviceId = phone.id, deviceName = phone.name),
            MangaTextStatistic("2026-09-12", 90, lastModified = 2, deviceId = tablet.id, deviceName = tablet.name),
        )

        assertEquals(
            listOf(
                MangaTextStatistic("2026-09-12", 320, lastModified = 6, deviceId = phone.id, deviceName = phone.name),
                MangaTextStatistic("2026-09-12", 90, lastModified = 2, deviceId = tablet.id, deviceName = tablet.name),
            ),
            entries.deduplicateMangaTextStatistics(),
        )
    }

    @Test
    fun attributedToFillsOnlyTheEntriesWithoutADevice() {
        val entries = listOf(
            MangaTextStatistic("2026-09-11", 100, lastModified = 1),
            MangaTextStatistic("2026-09-12", 90, lastModified = 2, deviceId = tablet.id, deviceName = tablet.name),
        )

        assertEquals(
            listOf(
                MangaTextStatistic("2026-09-11", 100, lastModified = 1, deviceId = phone.id, deviceName = phone.name),
                MangaTextStatistic("2026-09-12", 90, lastModified = 2, deviceId = tablet.id, deviceName = tablet.name),
            ),
            entries.attributedTo(phone),
        )
    }
}
