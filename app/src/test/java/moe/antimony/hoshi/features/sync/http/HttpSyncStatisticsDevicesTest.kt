package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.readingTotals
import moe.antimony.hoshi.epub.collapsedByDay
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import moe.antimony.hoshi.epub.STATISTICS_SYNC_STATE_FILE_NAME
import moe.antimony.hoshi.epub.TTU_STATISTICS_DEVICE_ID
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
    fun replacingImportedTtuHistorySurvivesStaleHttpCopiesAndKeepsActualDeviceReading() = runBlocking {
        for (replaceWithEmptyFile in listOf(true, false)) {
            val phoneRepository = BookRepository(temp.newFolder(), deviceIdentity = phone)
            val tabletRepository = BookRepository(temp.newFolder(), deviceIdentity = tablet)
            val phoneRoot = book(phoneRepository)
            val tabletRoot = book(tabletRepository)
            val transport = FakeKvTransport()
            val phoneReading = day("2026-09-12", 10.0, 10, 1, phone)
            val tabletReading = day("2026-09-12", 50.0, 50, 2, tablet)
            phoneRepository.saveStatistics(phoneRoot, listOf(phoneReading))
            tabletRepository.saveStatistics(tabletRoot, listOf(tabletReading))
            val removed = ReadingStatistics("Shirokuma", "2026-09-12", charactersRead = 300, readingTime = 300.0, lastStatisticModified = 10)
            val kept = removed.copy(dateKey = "2026-09-13", charactersRead = 20, readingTime = 20.0)
            phoneRepository.applyDayTotals(phoneRoot, listOf(removed, kept), false)
            tabletRepository.applyDayTotals(tabletRoot, listOf(removed, kept), false)
            suspend fun exchange(repository: BookRepository, root: File) {
                val remote = transport.kv[statisticsKey(syncId)]
                HttpSyncStatisticsSync(repository).sync(
                    transport, root, syncId, StatisticsSyncKind.Reading,
                    remote?.let { StatisticsRemoteListing.Listed(it.body.size, it.lastModified) }
                        ?: StatisticsRemoteListing.Absent,
                )
            }
            exchange(phoneRepository, phoneRoot)
            exchange(tabletRepository, tabletRoot)
            exchange(phoneRepository, phoneRoot)
            assertEquals(320, phoneRepository.loadStatistics(phoneRoot).readingTotals().charactersRead)

            // Replace removes an external day (or the whole explicit file) while the server
            // and the other install still hold the previous nonzero imported history.
            phoneRepository.applyDayTotals(phoneRoot, if (replaceWithEmptyFile) emptyList() else listOf(kept), true)
            val replaced = phoneRepository.loadStatistics(phoneRoot).toSet()
            phoneRepository.applyDayTotals(phoneRoot, if (replaceWithEmptyFile) emptyList() else listOf(kept), true)
            assertEquals("repeating Replace must not keep advancing tombstones", replaced, phoneRepository.loadStatistics(phoneRoot).toSet())
            exchange(phoneRepository, phoneRoot)
            exchange(tabletRepository, tabletRoot)
            exchange(phoneRepository, phoneRoot)
            val expectedCharacters = if (replaceWithEmptyFile) 60 else 80
            for ((repository, root) in listOf(phoneRepository to phoneRoot, tabletRepository to tabletRoot)) {
                val stored = repository.loadStatistics(root)
                assertEquals(expectedCharacters, stored.readingTotals().charactersRead)
                assertEquals(expectedCharacters.toDouble(), stored.readingTotals().readingTime, 0.0)
                assertEquals(phoneReading, stored.single { it.deviceId == phone.id })
                assertEquals(tabletReading, stored.single { it.deviceId == tablet.id })
                assertEquals(0, stored.single { it.dateKey == removed.dateKey && it.deviceId == TTU_STATISTICS_DEVICE_ID }.charactersRead)
            }
            val remote = json.decodeFromString(HttpSyncStatisticsBlob.serializer(), transport.kv.getValue(statisticsKey(syncId)).body.decodeToString())
            assertEquals(expectedCharacters, remote.entries.readingTotals().charactersRead)

            // Clearing external history must not suppress subsequent real reading either.
            phoneRepository.saveStatistics(phoneRoot, listOf(phoneReading.copy(
                charactersRead = 17, readingTime = 17.0, lastStatisticModified = 20,
            )))
            exchange(phoneRepository, phoneRoot)
            exchange(tabletRepository, tabletRoot)
            assertEquals(expectedCharacters + 7, tabletRepository.loadStatistics(tabletRoot).readingTotals().charactersRead)
        }
    }

    @Test
    fun independentlyImportedTtuHistoryConvergesOnceWhileRealDeviceReadingsSurvive() = runBlocking {
        for (sourceStamp in listOf(10L, 9_999_999_999_999L)) for ((phoneCharacters, tabletCharacters) in listOf(0 to 0, 100 to 50, 300 to 0)) {
            val phoneRepository = BookRepository(temp.newFolder(), deviceIdentity = phone)
            val tabletRepository = BookRepository(temp.newFolder(), deviceIdentity = tablet)
            val phoneRoot = book(phoneRepository)
            val tabletRoot = book(tabletRepository)
            val transport = FakeKvTransport()
            if (phoneCharacters > 0) phoneRepository.saveStatistics(phoneRoot, listOf(day("2026-09-12", phoneCharacters.toDouble(), phoneCharacters, 1, phone)))
            if (tabletCharacters > 0) tabletRepository.saveStatistics(tabletRoot, listOf(day("2026-09-12", tabletCharacters.toDouble(), tabletCharacters, 2, tablet)))
            val external = ReadingStatistics("Shirokuma", "2026-09-12", charactersRead = 300, readingTime = 300.0, lastStatisticModified = sourceStamp)
            // Neither device has seen the other's HTTP history at import time.
            phoneRepository.applyDayTotals(phoneRoot, listOf(external), false)
            tabletRepository.applyDayTotals(tabletRoot, listOf(external), false)
            suspend fun exchange(repository: BookRepository, root: File) {
                val remote = transport.kv[statisticsKey(syncId)]
                HttpSyncStatisticsSync(repository).sync(
                    transport, root, syncId, StatisticsSyncKind.Reading,
                    remote?.let { StatisticsRemoteListing.Listed(it.body.size, it.lastModified) } ?: StatisticsRemoteListing.Absent,
                )
            }
            exchange(phoneRepository, phoneRoot)
            exchange(tabletRepository, tabletRoot)
            exchange(phoneRepository, phoneRoot)
            for ((repository, root) in listOf(phoneRepository to phoneRoot, tabletRepository to tabletRoot)) {
                assertEquals(300, repository.loadStatistics(root).readingTotals().charactersRead)
                assertEquals(300.0, repository.loadStatistics(root).readingTotals().readingTime, 0.0)
            }
            val before = phoneRepository.loadStatistics(phoneRoot)
            if (phoneCharacters > 0) assertEquals(phoneCharacters, before.single { it.deviceId == phone.id }.charactersRead)
            if (tabletCharacters > 0) assertEquals(tabletCharacters, before.single { it.deviceId == tablet.id }.charactersRead)
            // Only new reading is added after the shared imported baseline.
            val own = before.firstOrNull { it.deviceId == phone.id } ?: day("2026-09-12", 0.0, 0, 0, phone)
            phoneRepository.saveStatistics(phoneRoot, listOf(own.copy(
                charactersRead = own.charactersRead + 10, readingTime = own.readingTime + 10.0, lastStatisticModified = 20,
            )))
            exchange(phoneRepository, phoneRoot)
            exchange(tabletRepository, tabletRoot)
            assertEquals(310, tabletRepository.loadStatistics(tabletRoot).readingTotals().charactersRead)
            val remote = json.decodeFromString(HttpSyncStatisticsBlob.serializer(), transport.kv.getValue(statisticsKey(syncId)).body.decodeToString())
            assertEquals(310, remote.entries.readingTotals().charactersRead)
            // Checkpoint the next collapsed TTU export. A third install can import that
            // export independently without counting the phone's new ten characters twice.
            val exported = phoneRepository.loadStatistics(phoneRoot).collapsedByDay()
            phoneRepository.applyDayTotals(phoneRoot, exported, false)
            val laptopRepository = BookRepository(temp.newFolder(), deviceIdentity = DeviceIdentity("laptop", "Laptop"))
            val laptopRoot = book(laptopRepository)
            laptopRepository.applyDayTotals(laptopRoot, exported, false)
            exchange(phoneRepository, phoneRoot)
            exchange(laptopRepository, laptopRoot)
            exchange(phoneRepository, phoneRoot)
            assertEquals(310, laptopRepository.loadStatistics(laptopRoot).readingTotals().charactersRead)
            assertEquals(310, phoneRepository.loadStatistics(phoneRoot).readingTotals().charactersRead)
        }
    }

    @Test
    fun oldClientsCannotEraseExactHoursOnAnUnchangedEntry() = runBlocking {
        for (richLocal in listOf(true, false)) {
            val repository = BookRepository(temp.newFolder(), deviceIdentity = phone)
            val root = book(repository)
            val transport = FakeKvTransport()
            val rich = day("2026-09-29", 600.0, 10, 5, phone).copy(readingTimeByHour = mapOf("2026-09-29T01:00" to 600.0))
            val legacy = rich.copy(readingTimeByHour = emptyMap())
            repository.saveStatistics(root, listOf(if (richLocal) rich else legacy))
            transport.put(statisticsKey(syncId), "application/json", json.encodeToString(
                HttpSyncStatisticsBlob.serializer(), HttpSyncStatisticsBlob(syncId = syncId, entries = listOf(if (richLocal) legacy else rich)),
            ).toByteArray())
            val remote = transport.kv.getValue(statisticsKey(syncId))
            HttpSyncStatisticsSync(repository).sync(transport, root, syncId, StatisticsSyncKind.Reading,
                StatisticsRemoteListing.Listed(remote.body.size, remote.lastModified))
            assertEquals(rich, repository.loadStatistics(root).single())
            val synced = json.decodeFromString(HttpSyncStatisticsBlob.serializer(), transport.kv.getValue(statisticsKey(syncId)).body.decodeToString())
            assertEquals(rich, synced.entries.single())
        }
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
