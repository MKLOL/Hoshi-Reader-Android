package moe.antimony.hoshi.features.statistics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.reader.loadReadingStatisticsOverview
import moe.antimony.hoshi.features.sync.http.FakeKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncStatisticsSync
import moe.antimony.hoshi.features.sync.http.StatisticsRemoteListing
import moe.antimony.hoshi.features.sync.http.StatisticsSyncKind
import moe.antimony.hoshi.features.sync.http.statisticsKey
import moe.antimony.hoshi.features.usage.UsageLog
import moe.antimony.hoshi.features.usage.loadUsageStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class SyncedReadingTrendsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun phoneHistoryUpdatesTabletTotalsAndTrendsWithoutAnyLocalUsage() = runBlocking {
        val phone = DeviceIdentity("phone", "Phone")
        val tablet = DeviceIdentity("tablet", "Tablet")
        val phoneRepository = BookRepository(temporaryFolder.newFolder(), deviceIdentity = phone)
        val tabletRepository = BookRepository(temporaryFolder.newFolder(), deviceIdentity = tablet)
        val phoneRoot = book(phoneRepository)
        val tabletRoot = book(tabletRepository)
        val today = LocalDate.of(2026, 9, 30)
        phoneRepository.saveStatistics(phoneRoot, listOf(
            day("2026-09-29", 600.0, 1_000, phone),
            day("2026-09-30", 1_200.0, 2_000, phone),
        ))
        assertEquals(0.0, loadReadingStatisticsOverview(tabletRepository, today.toString()).totalSeconds, 0.0)
        val before = tabletRepository.statisticsChanges.value

        val transport = FakeKvTransport()
        HttpSyncStatisticsSync(phoneRepository).sync(
            transport, phoneRoot, "book", StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent,
        )
        val remote = transport.kv.getValue(statisticsKey("book"))
        val downloaded = HttpSyncStatisticsSync(tabletRepository).sync(
            transport, tabletRoot, "book", StatisticsSyncKind.Reading,
            StatisticsRemoteListing.Listed(remote.body.size, remote.lastModified),
        )

        assertTrue(downloaded.downloaded)
        assertTrue(tabletRepository.statisticsChanges.value > before)
        val overview = loadReadingStatisticsOverview(tabletRepository, today.toString(), streakResetHour = 3)
        assertEquals(1_800.0, overview.totalSeconds, 0.0)
        assertEquals(1_200.0, overview.todaySeconds, 0.0)
        assertEquals(3_000, overview.totalCharacters)
        assertEquals(phone.id, overview.devices.single().deviceId)
        assertEquals(2, computeReadingStreak(overview.streakDaily, 600.0, today).currentDays)

        // The same persisted daily history supplies the two reading charts on every device.
        val minutes = rollingAverageSeries(
            overview.daily.associate { LocalDate.parse(it.dateKey) to it.seconds / 60.0 }, today, 30,
        )
        val characters = rollingAverageSeries(
            overview.daily.associate { LocalDate.parse(it.dateKey) to it.characters.toDouble() }, today, 30,
        )
        assertEquals(20.0, minutes.last().value, 0.0)
        assertEquals(10.0, minutes.last().rollingAverage, 0.0)
        assertEquals(2_000.0, characters.last().value, 0.0)
        assertEquals(1_000.0, characters.last().rollingAverage, 0.0)
        val usage = loadUsageStatistics(
            UsageLog(temporaryFolder.newFolder(), dispatcher = Dispatchers.IO), today, historyDays = 30,
        )
        assertTrue(usage.days.isEmpty())
        assertTrue(usage.today.isEmpty)
    }

    private suspend fun book(repository: BookRepository): File = repository.createBookDirectory("book").also { root ->
        repository.saveMetadata(root, BookMetadata(
            id = "book", title = "Book", cover = null, folder = "book", lastAccess = 1.0, syncId = "book",
        ))
    }

    private fun day(date: String, seconds: Double, characters: Int, device: DeviceIdentity) = ReadingStatistics(
        title = "Book", dateKey = date, readingTime = seconds, charactersRead = characters,
        lastStatisticModified = 1L, deviceId = device.id, deviceName = device.name,
        readingTimeByHour = mapOf("${date}T10:00" to seconds),
    )
}
