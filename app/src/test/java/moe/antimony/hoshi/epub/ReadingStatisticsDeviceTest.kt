package moe.antimony.hoshi.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every device keeps its own entry per day in `statistics.json`, so "how much did I read on
 * the tablet" is answerable and two devices reading the same day no longer overwrite each
 * other. Totals are sums across devices.
 */
class ReadingStatisticsDeviceTest {
    private val phone = DeviceIdentity(id = "phone-id", name = "Pixel 8")
    private val tablet = DeviceIdentity(id = "tablet-id", name = "Galaxy Tab")

    private fun day(dateKey: String, seconds: Double, characters: Int, modified: Long, device: DeviceIdentity? = null) =
        ReadingStatistics(
            title = "Book",
            dateKey = dateKey,
            charactersRead = characters,
            readingTime = seconds,
            lastStatisticModified = modified,
            deviceId = device?.id,
            deviceName = device?.name,
        )

    @Test
    fun twoDevicesReadingTheSameDayAreBothKept() {
        val entries = listOf(
            day("2026-09-12", 600.0, 100, modified = 5, device = phone),
            day("2026-09-12", 300.0, 40, modified = 9, device = tablet),
        )

        assertEquals(entries, entries.deduplicateReadingStatistics())
        assertEquals(ReadingTotals(readingTime = 900.0, charactersRead = 140), entries.readingTotals())
    }

    @Test
    fun withinOneDeviceTheNewestEntryForADayWins() {
        val entries = listOf(
            day("2026-09-12", 600.0, 100, modified = 5, device = phone),
            day("2026-09-12", 660.0, 110, modified = 6, device = phone),
            day("2026-09-12", 300.0, 40, modified = 9, device = tablet),
        )

        assertEquals(
            listOf(
                day("2026-09-12", 660.0, 110, modified = 6, device = phone),
                day("2026-09-12", 300.0, 40, modified = 9, device = tablet),
            ),
            entries.deduplicateReadingStatistics(),
        )
    }

    @Test
    fun entriesWithoutADeviceShareOneBucketSeparateFromEveryDevice() {
        val entries = listOf(
            day("2026-09-12", 600.0, 100, modified = 5),
            day("2026-09-12", 700.0, 120, modified = 6),
            day("2026-09-12", 300.0, 40, modified = 2, device = phone),
        )

        assertEquals(
            listOf(day("2026-09-12", 700.0, 120, modified = 6), day("2026-09-12", 300.0, 40, modified = 2, device = phone)),
            entries.deduplicateReadingStatistics(),
        )
    }

    @Test
    fun attributedToFillsOnlyTheEntriesWithoutADevice() {
        val entries = listOf(
            day("2026-09-11", 100.0, 10, modified = 1),
            day("2026-09-12", 300.0, 40, modified = 2, device = tablet),
        )

        assertEquals(
            listOf(
                day("2026-09-11", 100.0, 10, modified = 1, device = phone),
                day("2026-09-12", 300.0, 40, modified = 2, device = tablet),
            ),
            entries.attributedTo(phone),
        )
    }

    @Test
    fun collapsedByDayAddsEveryDeviceUpIntoOneDeviceLessEntryPerDay() {
        val entries = listOf(
            day("2026-09-12", 600.0, 100, modified = 5, device = phone).copy(minReadingSpeed = 500, maxReadingSpeed = 700),
            day("2026-09-12", 300.0, 50, modified = 9, device = tablet).copy(minReadingSpeed = 400, maxReadingSpeed = 900),
            day("2026-09-11", 120.0, 30, modified = 1, device = phone),
        )

        val collapsed = entries.collapsedByDay().sortedBy { it.dateKey }

        assertEquals(listOf("2026-09-11", "2026-09-12"), collapsed.map { it.dateKey })
        val day12 = collapsed.last()
        assertEquals(900.0, day12.readingTime, 0.0)
        assertEquals(150, day12.charactersRead)
        assertEquals(600, day12.lastReadingSpeed)
        assertEquals(400, day12.minReadingSpeed)
        assertEquals(900, day12.maxReadingSpeed)
        assertEquals(9L, day12.lastStatisticModified)
        assertNull(day12.deviceId)
        assertNull(day12.deviceName)
    }

    @Test
    fun theDeviceIsWrittenOnlyWhenKnownSoDeviceLessFilesKeepTheIosShape() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }

        val withDevice = json.encodeToString(ReadingStatistics.serializer(), day("2026-09-12", 30.0, 12, modified = 7, device = phone))
        val withoutDevice = json.encodeToString(ReadingStatistics.serializer(), day("2026-09-12", 30.0, 12, modified = 7))

        assertEquals(true, withDevice.endsWith(""","deviceId":"phone-id","deviceName":"Pixel 8"}"""))
        assertEquals(false, withoutDevice.contains("device"))
        assertEquals(day("2026-09-12", 30.0, 12, modified = 7, device = phone), json.decodeFromString(ReadingStatistics.serializer(), withDevice))
    }

    @Test
    fun filesWrittenBeforeDevicesWereTrackedStillDecode() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val legacy = """{"title":"Book","dateKey":"2026-09-12","charactersRead":12,"readingTime":30.5,"lastStatisticModified":7}"""

        val decoded = json.decodeFromString(ReadingStatistics.serializer(), legacy)

        assertNull(decoded.deviceId)
        assertEquals(12, decoded.charactersRead)
    }
}
