package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Per-device statistics on the Statistics screens: a day read on two devices is one day with
 * both devices' time, the totals are sums across devices, and the "By device" rows add up to
 * exactly those totals.
 */
class ReadingStatisticsOverviewDevicesTest {
    private val phone = DeviceIdentity(id = "phone-id", name = "Pixel 8")
    private val tablet = DeviceIdentity(id = "tablet-id", name = "Galaxy Tab")

    private fun day(dateKey: String, seconds: Double, units: Int, modified: Long, device: DeviceIdentity?, name: String? = device?.name) =
        ReadingStatistics(
            title = "title",
            dateKey = dateKey,
            charactersRead = units,
            readingTime = seconds,
            lastStatisticModified = modified,
            deviceId = device?.id,
            deviceName = name,
        )

    private fun text(dateKey: String, characters: Int, modified: Long, device: DeviceIdentity) =
        MangaTextStatistic(dateKey, characters, lastModified = modified, deviceId = device.id, deviceName = device.name)

    @Test
    fun aDayReadOnTwoDevicesIsOneDayWithBothDevicesTime() {
        val overview = summarizeReadingStatistics(
            listOf(
                BookStatisticsInput(
                    bookId = "epub",
                    title = "Novel",
                    contentType = ContentType.Epub,
                    statistics = listOf(
                        day("2026-09-12", 600.0, 1000, modified = 5, device = phone),
                        day("2026-09-12", 300.0, 400, modified = 9, device = tablet),
                        day("2026-09-11", 120.0, 200, modified = 1, device = tablet),
                    ),
                ),
            ),
            todayKey = "2026-09-12",
        )

        val book = overview.books.single()
        assertEquals(listOf(DailyReading("2026-09-12", 900.0, 1400), DailyReading("2026-09-11", 120.0, 200)), book.days)
        assertEquals(1020.0, book.totalSeconds, 0.0)
        assertEquals(1600, book.charactersRead)
        assertEquals(2, book.daysRead)
        assertEquals(900.0, overview.todaySeconds, 0.0)
        assertEquals(1400, overview.todayCharacters)
        assertEquals(1020.0, overview.totalSeconds, 0.0)
        assertEquals(listOf(DailyReading("2026-09-12", 900.0, 1400), DailyReading("2026-09-11", 120.0, 200)), overview.daily)
    }

    @Test
    fun theBookPageListsEachDeviceWithItsOwnShare() {
        val overview = summarizeReadingStatistics(
            listOf(
                BookStatisticsInput(
                    bookId = "epub",
                    title = "Novel",
                    contentType = ContentType.Epub,
                    statistics = listOf(
                        day("2026-09-12", 600.0, 1000, modified = 5, device = phone),
                        day("2026-09-12", 300.0, 400, modified = 9, device = tablet),
                        day("2026-09-11", 120.0, 200, modified = 1, device = tablet),
                    ),
                ),
            ),
            todayKey = "2026-09-12",
        )

        assertEquals(
            listOf(
                DeviceReadingSummary(phone.id, "Pixel 8", totalSeconds = 600.0, charactersRead = 1000, pagesRead = 0, lastReadDateKey = "2026-09-12", bookCount = 1, newestStamp = 5),
                DeviceReadingSummary(tablet.id, "Galaxy Tab", totalSeconds = 420.0, charactersRead = 600, pagesRead = 0, lastReadDateKey = "2026-09-12", bookCount = 1, newestStamp = 9),
            ),
            overview.books.single().devices,
        )
    }

    @Test
    fun mangaDevicesCountPagesFromStatisticsAndCharactersFromTheTextSidecar() {
        val overview = summarizeReadingStatistics(
            listOf(
                BookStatisticsInput(
                    bookId = "manga",
                    title = "Manga",
                    contentType = ContentType.Mokuro,
                    statistics = listOf(
                        day("2026-09-12", 600.0, 12, modified = 5, device = phone),
                        day("2026-09-10", 200.0, 4, modified = 2, device = tablet),
                    ),
                    mangaTextStatistics = listOf(
                        text("2026-09-12", 900, modified = 6, device = phone),
                        text("2026-09-10", 250, modified = 3, device = tablet),
                    ),
                ),
            ),
            todayKey = "2026-09-12",
        )

        val book = overview.books.single()
        assertEquals(16, book.pagesRead)
        assertEquals(1150, book.charactersRead)
        assertEquals(
            listOf(
                DeviceReadingSummary(phone.id, "Pixel 8", totalSeconds = 600.0, charactersRead = 900, pagesRead = 12, lastReadDateKey = "2026-09-12", bookCount = 1, newestStamp = 6),
                DeviceReadingSummary(tablet.id, "Galaxy Tab", totalSeconds = 200.0, charactersRead = 250, pagesRead = 4, lastReadDateKey = "2026-09-10", bookCount = 1, newestStamp = 3),
            ),
            book.devices,
        )
    }

    @Test
    fun theOverviewAddsDevicesUpAcrossBooksAndCountsTheirBooks() {
        val overview = summarizeReadingStatistics(
            listOf(
                BookStatisticsInput(
                    bookId = "a",
                    title = "A",
                    contentType = ContentType.Epub,
                    statistics = listOf(day("2026-09-12", 600.0, 1000, modified = 5, device = phone), day("2026-09-09", 60.0, 50, modified = 1, device = tablet)),
                ),
                BookStatisticsInput(
                    bookId = "b",
                    title = "B",
                    contentType = ContentType.Epub,
                    statistics = listOf(day("2026-09-11", 240.0, 300, modified = 4, device = phone)),
                ),
            ),
            todayKey = "2026-09-12",
        )

        assertEquals(
            listOf(
                DeviceReadingSummary(phone.id, "Pixel 8", totalSeconds = 840.0, charactersRead = 1300, pagesRead = 0, lastReadDateKey = "2026-09-12", bookCount = 2, newestStamp = 5),
                DeviceReadingSummary(tablet.id, "Galaxy Tab", totalSeconds = 60.0, charactersRead = 50, pagesRead = 0, lastReadDateKey = "2026-09-09", bookCount = 1, newestStamp = 1),
            ),
            overview.devices,
        )
        // The "By device" card and the totals card can never disagree.
        assertEquals(overview.totalSeconds, overview.devices.sumOf { it.totalSeconds }, 0.0)
        assertEquals(overview.totalCharacters, overview.devices.sumOf { it.charactersRead })
    }

    @Test
    fun aRenamedDeviceShowsTheNameOnItsNewestEntry() {
        val overview = summarizeReadingStatistics(
            listOf(
                BookStatisticsInput(
                    bookId = "a",
                    title = "A",
                    contentType = ContentType.Epub,
                    statistics = listOf(
                        day("2026-09-10", 60.0, 50, modified = 1, device = phone, name = "Old phone"),
                        day("2026-09-12", 60.0, 50, modified = 9, device = phone, name = "Pixel 8"),
                    ),
                ),
            ),
            todayKey = "2026-09-12",
        )

        assertEquals("Pixel 8", overview.devices.single().deviceName)
        assertEquals("Pixel 8", overview.books.single().devices.single().deviceName)
    }

    @Test
    fun theOverviewPicksTheNameFromTheNewestEntryAcrossBooks() {
        val overview = summarizeReadingStatistics(
            listOf(
                BookStatisticsInput(
                    bookId = "a",
                    title = "A",
                    contentType = ContentType.Epub,
                    // Read later in the day than book B, but recorded under the old name.
                    statistics = listOf(day("2026-09-12", 60.0, 50, modified = 5, device = phone, name = "Old phone")),
                ),
                BookStatisticsInput(
                    bookId = "b",
                    title = "B",
                    contentType = ContentType.Epub,
                    statistics = listOf(day("2026-09-11", 60.0, 50, modified = 9, device = phone, name = "Pixel 8")),
                ),
            ),
            todayKey = "2026-09-12",
        )

        assertEquals("Pixel 8", overview.devices.single().deviceName)
    }

    @Test
    fun entriesNoInstallHasClaimedFormAnUnknownDeviceRow() {
        val overview = summarizeReadingStatistics(
            listOf(
                BookStatisticsInput(
                    bookId = "a",
                    title = "A",
                    contentType = ContentType.Epub,
                    statistics = listOf(day("2026-09-10", 60.0, 50, modified = 1, device = null), day("2026-09-12", 30.0, 5, modified = 2, device = phone)),
                ),
            ),
            todayKey = "2026-09-12",
        )

        assertEquals(listOf(null, phone.id).toSet(), overview.devices.map { it.deviceId }.toSet())
        assertEquals(overview.totalSeconds, overview.devices.sumOf { it.totalSeconds }, 0.0)
    }
}
