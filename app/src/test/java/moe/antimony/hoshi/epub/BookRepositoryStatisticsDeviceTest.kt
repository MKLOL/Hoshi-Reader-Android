package moe.antimony.hoshi.epub

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The repository is where device-less statistics become this device's: files written before
 * devices were tracked, and entries handed in without one. Everything read through it, by
 * screens or by sync, therefore carries a device.
 */
class BookRepositoryStatisticsDeviceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val phone = DeviceIdentity(id = "phone-id", name = "Pixel 8")
    private val tablet = DeviceIdentity(id = "tablet-id", name = "Galaxy Tab")
    private val json = Json { ignoreUnknownKeys = true }

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

    private fun repository(device: DeviceIdentity?) = BookRepository(temp.root, deviceIdentity = device)

    @Test
    fun aFileFromBeforeDevicesWereTrackedIsAttributedToThisDeviceAndRewrittenOnce() = runBlocking {
        val root = repository(null).createBookDirectory("book")
        root.resolve("statistics.json").writeText(
            """[{"title":"Book","dateKey":"2026-09-10","charactersRead":12,"readingTime":30.0,"lastStatisticModified":7}]""",
        )

        val loaded = repository(phone).loadStatistics(root)

        assertEquals(listOf(day("2026-09-10", 30.0, 12, modified = 7, device = phone)), loaded)
        val onDisk = json.decodeFromString(ListSerializer(ReadingStatistics.serializer()), root.resolve("statistics.json").readText())
        assertEquals("the file itself now names the device, for sync and every other reader", loaded, onDisk)
    }

    @Test
    fun aSaveMergesADeviceLessDayOnDiskWithThisDevicesEntryInsteadOfKeepingBoth() = runBlocking {
        val root = repository(null).createBookDirectory("book")
        root.resolve("statistics.json").writeText(
            """[{"title":"Book","dateKey":"2026-09-12","charactersRead":12,"readingTime":30.0,"lastStatisticModified":7}]""",
        )
        val repository = repository(phone)

        repository.saveStatistics(root, listOf(day("2026-09-12", 45.0, 20, modified = 8, device = phone)))

        assertEquals(listOf(day("2026-09-12", 45.0, 20, modified = 8, device = phone)), repository.loadStatistics(root))
        assertEquals(ReadingTotals(45.0, 20), repository.loadStatistics(root).readingTotals())
    }

    @Test
    fun anotherDevicesEntryForTheSameDaySurvivesThisDevicesSave() = runBlocking {
        val repository = repository(phone)
        val root = repository.createBookDirectory("book")
        repository.saveStatistics(root, listOf(day("2026-09-12", 300.0, 40, modified = 9, device = tablet)))

        repository.saveStatistics(root, listOf(day("2026-09-12", 600.0, 100, modified = 5, device = phone)))

        assertEquals(
            setOf(
                day("2026-09-12", 600.0, 100, modified = 5, device = phone),
                day("2026-09-12", 300.0, 40, modified = 9, device = tablet),
            ),
            repository.loadStatistics(root).toSet(),
        )
        assertEquals(ReadingTotals(900.0, 140), repository.loadStatistics(root).readingTotals())
    }

    @Test
    fun aDeviceLessEntryArrivingAfterTheFileNamesADeviceStaysInTheUnknownBucket() = runBlocking {
        val repository = repository(phone)
        val root = repository.createBookDirectory("book")
        repository.saveStatistics(root, listOf(day("2026-09-12", 600.0, 100, modified = 5, device = phone)))

        // What HTTP sync stores after merging an older client's entry: it must stay separate,
        // so the file converges and this device's own day is not replaced.
        repository.saveStatistics(root, listOf(day("2026-09-12", 100.0, 3, modified = 9)))

        assertEquals(
            setOf(day("2026-09-12", 600.0, 100, modified = 5, device = phone), day("2026-09-12", 100.0, 3, modified = 9)),
            repository.loadStatistics(root).toSet(),
        )
        assertEquals(listOf(day("2026-09-12", 100.0, 3, modified = 9)), repository.loadStatistics(root).filter { it.deviceId == null })
    }

    @Test
    fun aLegacyFileOfABookWhoseStatisticsWereAlreadySyncedIsNotClaimed() = runBlocking {
        val root = repository(null).createBookDirectory("book")
        root.resolve("statistics.json").writeText(
            """[{"title":"Book","dateKey":"2026-09-10","charactersRead":12,"readingTime":30.0,"lastStatisticModified":7}]""",
        )
        // The same device-less day already sits on the server under the "unknown device" key.
        root.resolve(STATISTICS_SYNC_STATE_FILE_NAME).writeText("{}")

        val loaded = repository(phone).loadStatistics(root)

        assertNull("claiming it here would count the day twice after the next sync", loaded.single().deviceId)
    }

    @Test
    fun theLegacyRewriteHappensOnceAndReplaceWritesWhatItIsGiven() = runBlocking {
        val root = repository(null).createBookDirectory("book")
        root.resolve("statistics.json").writeText(
            """[{"title":"Book","dateKey":"2026-09-10","charactersRead":12,"readingTime":30.0,"lastStatisticModified":7}]""",
        )
        val repository = repository(phone)
        repository.loadStatistics(root)
        val rewritten = root.resolve("statistics.json").lastModified()
        check(root.resolve("statistics.json").setLastModified(rewritten - 60_000))

        repository.loadStatistics(root)
        assertEquals("a second load does not touch the file", rewritten - 60_000, root.resolve("statistics.json").lastModified())

        // Replace writes exactly what it is given; the next load then treats a wholly
        // device-less, never-synced file as this device's history again.
        repository.replaceStatistics(root, listOf(day("2026-09-12", 20.0, 2, modified = 2)))
        assertEquals(
            """[{"title":"Book","dateKey":"2026-09-12","charactersRead":2,"readingTime":20.0,"minReadingSpeed":0,"altMinReadingSpeed":0,"lastReadingSpeed":0,"maxReadingSpeed":0,"lastStatisticModified":2}]""",
            root.resolve("statistics.json").readText().replace(Regex("\\s"), ""),
        )
        assertEquals(listOf(day("2026-09-12", 20.0, 2, modified = 2, device = phone)), repository.loadStatistics(root))
    }

    @Test
    fun applyDayTotalsAdjustsOnlyThisDevicesShareOfADay() = runBlocking {
        val repository = repository(phone)
        val root = repository.createBookDirectory("book")
        repository.saveStatistics(
            root,
            listOf(
                day("2026-09-12", 600.0, 100, modified = 5, device = phone),
                day("2026-09-12", 300.0, 40, modified = 9, device = tablet),
                day("2026-09-11", 120.0, 30, modified = 1, device = tablet),
            ),
        )

        // ッツ says the 12th was 1000 s / 160 characters in total and adds a day only it knows.
        val changed = repository.applyDayTotals(
            root,
            listOf(day("2026-09-12", 1000.0, 160, modified = 20), day("2026-09-13", 50.0, 5, modified = 21)),
            replaceOtherDays = false,
        )

        assertEquals(true, changed)
        val entries = repository.loadStatistics(root)
        val phone12 = entries.single { it.dateKey == "2026-09-12" && it.deviceId == phone.id }
        assertEquals(700.0, phone12.readingTime, 0.0)
        assertEquals(120, phone12.charactersRead)
        assertEquals(20L, phone12.lastStatisticModified)
        assertEquals(day("2026-09-12", 300.0, 40, modified = 9, device = tablet), entries.single { it.dateKey == "2026-09-12" && it.deviceId == tablet.id })
        assertEquals(day("2026-09-11", 120.0, 30, modified = 1, device = tablet), entries.single { it.dateKey == "2026-09-11" })
        assertEquals(day("2026-09-13", 50.0, 5, modified = 21, device = phone).copy(lastReadingSpeed = 360), entries.single { it.dateKey == "2026-09-13" })
        assertEquals(1170.0, entries.readingTotals().readingTime, 0.0)

        // Applying the same totals again changes nothing: importing what was exported is a no-op.
        assertEquals(false, repository.applyDayTotals(root, entries.collapsedByDay(), replaceOtherDays = false))
        assertEquals(1170.0, repository.loadStatistics(root).readingTotals().readingTime, 0.0)
    }

    @Test
    fun applyDayTotalsNeverReducesOtherDevicesAndReplaceDropsOnlyThisDevicesOtherDays() = runBlocking {
        val repository = repository(phone)
        val root = repository.createBookDirectory("book")
        repository.saveStatistics(
            root,
            listOf(
                day("2026-09-12", 600.0, 100, modified = 5, device = phone),
                day("2026-09-12", 300.0, 40, modified = 9, device = tablet),
                day("2026-09-10", 120.0, 30, modified = 1, device = phone),
                day("2026-09-09", 90.0, 9, modified = 1, device = tablet),
            ),
        )

        // ッツ Replace: the 12th is only 200 s in total (less than the tablet alone), the 10th is gone.
        repository.applyDayTotals(root, listOf(day("2026-09-12", 200.0, 20, modified = 30)), replaceOtherDays = true)

        val entries = repository.loadStatistics(root)
        assertEquals(setOf("2026-09-12", "2026-09-09"), entries.map { it.dateKey }.toSet())
        assertEquals(0.0, entries.single { it.deviceId == phone.id }.readingTime, 0.0)
        assertEquals(day("2026-09-12", 300.0, 40, modified = 9, device = tablet), entries.single { it.dateKey == "2026-09-12" && it.deviceId == tablet.id })
        assertEquals(day("2026-09-09", 90.0, 9, modified = 1, device = tablet), entries.single { it.dateKey == "2026-09-09" })
    }

    @Test
    fun mangaTextStatisticsAreAttributedTheSameWay() = runBlocking {
        val root = repository(null).createBookDirectory("manga")
        root.resolve("manga_statistics.json").writeText("""[{"dateKey":"2026-09-12","charactersRead":250,"lastModified":3}]""")
        val repository = repository(phone)

        repository.saveMangaTextStatistics(root, listOf(MangaTextStatistic("2026-09-12", 40, lastModified = 1, deviceId = tablet.id, deviceName = tablet.name)))

        assertEquals(
            setOf(
                MangaTextStatistic("2026-09-12", 40, lastModified = 1, deviceId = tablet.id, deviceName = tablet.name),
                MangaTextStatistic("2026-09-12", 250, lastModified = 3, deviceId = phone.id, deviceName = phone.name),
            ),
            repository.loadMangaTextStatistics(root).toSet(),
        )
    }

    @Test
    fun withoutADeviceTheRepositoryLeavesFilesAlone() = runBlocking {
        val repository = repository(null)
        val root = repository.createBookDirectory("book")
        repository.saveStatistics(root, listOf(day("2026-09-12", 20.0, 2, modified = 2)))

        assertNull(repository.loadStatistics(root).single().deviceId)
        assertNull(repository.statisticsDevice)
    }
}
