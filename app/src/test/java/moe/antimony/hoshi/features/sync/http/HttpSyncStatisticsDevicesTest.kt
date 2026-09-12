package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.readingTotals
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import moe.antimony.hoshi.epub.STATISTICS_SYNC_STATE_FILE_NAME
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * HTTP sync merges statistics per (day, device): the phone's and the tablet's entries for the
 * same day both survive the exchange, in both directions, and the totals on either device are
 * the sum of both.
 */
class HttpSyncStatisticsDevicesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val syncId = "shirokuma"
    private val phone = DeviceIdentity(id = "phone-id", name = "Pixel 8")
    private val tablet = DeviceIdentity(id = "tablet-id", name = "Galaxy Tab")

    private fun day(dateKey: String, seconds: Double, characters: Int, modified: Long, device: DeviceIdentity) =
        ReadingStatistics(
            title = "Shirokuma",
            dateKey = dateKey,
            charactersRead = characters,
            readingTime = seconds,
            lastStatisticModified = modified,
            deviceId = device.id,
            deviceName = device.name,
        )

    private suspend fun book(repository: BookRepository): File {
        val root = repository.createBookDirectory("book")
        repository.saveMetadata(root, BookMetadata(id = "book", title = "Shirokuma", cover = null, folder = "book", lastAccess = 1.0, syncId = syncId))
        root.resolve("mokuro.json").writeText("{}")
        return root
    }

    @Test
    fun bothDevicesEntriesForOneDaySurviveTheExchangeInBothDirections() = runBlocking {
        val phoneRepository = BookRepository(temp.newFolder(), deviceIdentity = phone)
        val tabletRepository = BookRepository(temp.newFolder(), deviceIdentity = tablet)
        val phoneRoot = book(phoneRepository)
        val tabletRoot = book(tabletRepository)
        val transport = FakeKvTransport()
        val phoneSync = HttpSyncStatisticsSync(phoneRepository)
        val tabletSync = HttpSyncStatisticsSync(tabletRepository)
        phoneRepository.saveStatistics(phoneRoot, listOf(day("2026-09-12", 600.0, 12, modified = 5, device = phone)))
        tabletRepository.saveStatistics(tabletRoot, listOf(day("2026-09-12", 300.0, 4, modified = 9, device = tablet)))

        phoneSync.sync(transport, phoneRoot, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)
        val remoteAfterPhone = transport.kv.getValue(statisticsKey(syncId))
        val tabletOutcome = tabletSync.sync(
            transport, tabletRoot, syncId, StatisticsSyncKind.Reading,
            StatisticsRemoteListing.Listed(remoteAfterPhone.body.size, remoteAfterPhone.lastModified),
        )
        assertEquals(StatisticsSyncOutcome(downloaded = true, uploaded = true), tabletOutcome)
        val remoteAfterTablet = transport.kv.getValue(statisticsKey(syncId))
        val phoneOutcome = phoneSync.sync(
            transport, phoneRoot, syncId, StatisticsSyncKind.Reading,
            StatisticsRemoteListing.Listed(remoteAfterTablet.body.size, remoteAfterTablet.lastModified),
        )
        assertEquals(StatisticsSyncOutcome(downloaded = true, uploaded = false), phoneOutcome)

        val expected = setOf(
            day("2026-09-12", 600.0, 12, modified = 5, device = phone),
            day("2026-09-12", 300.0, 4, modified = 9, device = tablet),
        )
        assertEquals(expected, phoneRepository.loadStatistics(phoneRoot).toSet())
        assertEquals(expected, tabletRepository.loadStatistics(tabletRoot).toSet())
        assertEquals(900.0, phoneRepository.loadStatistics(phoneRoot).readingTotals().readingTime, 0.0)
        val remote = json.decodeFromString(HttpSyncStatisticsBlob.serializer(), remoteAfterTablet.body.decodeToString()).entries
        assertEquals(expected, remote.toSet())
    }

    @Test
    fun aDeviceLessRemoteDayFromAnOlderClientStaysItsOwnEntryAndTheExchangeConverges() = runBlocking {
        val phoneRepository = BookRepository(temp.newFolder(), deviceIdentity = phone)
        val root = book(phoneRepository)
        val transport = FakeKvTransport()
        val sync = HttpSyncStatisticsSync(phoneRepository)
        phoneRepository.saveStatistics(root, listOf(day("2026-09-12", 600.0, 12, modified = 5, device = phone)))
        val legacy = ReadingStatistics(title = "Shirokuma", dateKey = "2026-09-12", charactersRead = 3, readingTime = 100.0, lastStatisticModified = 1)
        transport.put(
            statisticsKey(syncId),
            "application/json",
            json.encodeToString(HttpSyncStatisticsBlob.serializer(), HttpSyncStatisticsBlob(syncId = syncId, entries = listOf(legacy))).toByteArray(),
        )
        val listed = transport.kv.getValue(statisticsKey(syncId))

        val first = sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Listed(listed.body.size, listed.lastModified))

        // The older client's day is its own "unknown device" entry next to this device's, on
        // both sides, and the exchange converges: the next sync costs nothing.
        assertEquals(StatisticsSyncOutcome(downloaded = true, uploaded = true), first)
        val expected = setOf(day("2026-09-12", 600.0, 12, modified = 5, device = phone), legacy)
        assertEquals(expected, phoneRepository.loadStatistics(root).toSet())
        val remote = transport.kv.getValue(statisticsKey(syncId))
        assertEquals(expected, json.decodeFromString(HttpSyncStatisticsBlob.serializer(), remote.body.decodeToString()).entries.toSet())
        assertEquals(
            StatisticsSyncOutcome.NONE,
            sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Listed(remote.body.size, remote.lastModified)),
        )
        assertEquals(StatisticsSyncOutcome.NONE, sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Unknown))
    }

    @Test
    fun twoDevicesThatAlreadySyncedDeviceLessHistoryDoNotDoubleItWhenTheyUpgrade() = runBlocking {
        // Before this version both devices held the same device-less day and had exchanged it.
        val legacyBody = json.encodeToString(
            HttpSyncStatisticsBlob.serializer(),
            HttpSyncStatisticsBlob(syncId = syncId, entries = listOf(ReadingStatistics(title = "Shirokuma", dateKey = "2026-09-10", charactersRead = 30, readingTime = 300.0, lastStatisticModified = 1))),
        )
        val transport = FakeKvTransport()
        transport.put(statisticsKey(syncId), "application/json", legacyBody.toByteArray())
        val repositories = listOf(phone, tablet).map { BookRepository(temp.newFolder(), deviceIdentity = it) }
        val roots = repositories.map { repository ->
            book(repository).also { root ->
                root.resolve("statistics.json").writeText(
                    """[{"title":"Shirokuma","dateKey":"2026-09-10","charactersRead":30,"readingTime":300.0,"lastStatisticModified":1}]""",
                )
                root.resolve(STATISTICS_SYNC_STATE_FILE_NAME).writeText("{}")
            }
        }

        repositories.zip(roots).forEach { (repository, root) ->
            val listed = transport.kv.getValue(statisticsKey(syncId))
            HttpSyncStatisticsSync(repository).sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Listed(listed.body.size, listed.lastModified))
        }

        repositories.zip(roots).forEach { (repository, root) ->
            assertEquals(300.0, repository.loadStatistics(root).readingTotals().readingTime, 0.0)
        }
    }

    @Test
    fun mangaTextEntriesMergePerDeviceToo() = runBlocking {
        val phoneRepository = BookRepository(temp.newFolder(), deviceIdentity = phone)
        val root = book(phoneRepository)
        val transport = FakeKvTransport()
        val sync = HttpSyncStatisticsSync(phoneRepository)
        val phoneDay = MangaTextStatistic("2026-09-12", 900, lastModified = 6, deviceId = phone.id, deviceName = phone.name)
        val tabletDay = MangaTextStatistic("2026-09-12", 250, lastModified = 3, deviceId = tablet.id, deviceName = tablet.name)
        phoneRepository.saveMangaTextStatistics(root, listOf(phoneDay))
        transport.put(
            mangaStatisticsKey(syncId),
            "application/json",
            json.encodeToString(HttpSyncMangaStatisticsBlob.serializer(), HttpSyncMangaStatisticsBlob(syncId = syncId, entries = listOf(tabletDay))).toByteArray(),
        )
        val listed = transport.kv.getValue(mangaStatisticsKey(syncId))

        sync.sync(transport, root, syncId, StatisticsSyncKind.MangaText, StatisticsRemoteListing.Listed(listed.body.size, listed.lastModified))

        assertEquals(setOf(phoneDay, tabletDay), phoneRepository.loadMangaTextStatistics(root).toSet())
        val remote = json.decodeFromString(HttpSyncMangaStatisticsBlob.serializer(), transport.kv.getValue(mangaStatisticsKey(syncId)).body.decodeToString()).entries
        assertEquals(setOf(phoneDay, tabletDay), remote.toSet())
    }
}
