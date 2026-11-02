package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.MOKURO_SIDECAR_FILE
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.sync.http.HttpSyncBookLocks
import moe.antimony.hoshi.features.sync.http.HttpSyncStatisticsBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncStatisticsLane
import moe.antimony.hoshi.features.sync.http.HttpSyncStatisticsShard
import moe.antimony.hoshi.features.sync.http.HttpSyncStatisticsSync
import moe.antimony.hoshi.features.sync.http.StatisticsRemoteListing
import moe.antimony.hoshi.features.sync.http.StatisticsSyncKind
import moe.antimony.hoshi.features.sync.http.StatisticsSyncOutcome
import moe.antimony.hoshi.features.sync.http.statisticsKey
import moe.antimony.hoshi.features.sync.http.statisticsShardKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Median pace evidence follows the production statistics lane over the real HTTP KV API. */
class MangaPaceSyncIntegrationTest {
    companion object {
        @JvmField @ClassRule val serverRule = SyncTestServerRule()
    }

    @get:Rule val temp = TemporaryFolder()

    private val server get() = serverRule.server
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val phone = DeviceIdentity("phone", "Phone")
    private val tablet = DeviceIdentity("tablet", "Tablet")
    private val syncId = "manga-pace"

    @Before
    fun resetServer() = server.reset()

    private fun repository(directory: File, device: DeviceIdentity) =
        BookRepository(directory, deviceIdentity = device)

    private suspend fun book(repository: BookRepository): File {
        val root = repository.createBookDirectory(syncId)
        repository.saveMetadata(root, BookMetadata(syncId, "Manga", null, syncId, 0.0, syncId = syncId))
        root.resolve(MOKURO_SIDECAR_FILE).writeText("{}")
        return root
    }

    private fun day(device: DeviceIdentity, date: String, samples: Map<Int, Int>, modified: Long) =
        ReadingStatistics(
            title = "Manga", dateKey = date,
            charactersRead = samples.values.sum(),
            readingTime = samples.entries.sumOf { (seconds, count) -> seconds.toDouble() * count },
            lastStatisticModified = modified, deviceId = device.id, deviceName = device.name,
            mangaPageSeconds = samples,
        )

    private fun lane(repository: BookRepository) = HttpSyncStatisticsLane(repository, HttpSyncBookLocks())

    private suspend fun exchange(lane: HttpSyncStatisticsLane) {
        val client = server.client()
        val result = lane.run(client, client.list().keys, flush = true)
        assertEquals("statistics sync errors", emptyList<String>(), result.errors)
    }

    private suspend fun shard(device: DeviceIdentity): HttpSyncStatisticsShard = json.decodeFromString(
        HttpSyncStatisticsShard.serializer(),
        requireNotNull(server.client().get(statisticsShardKey(device.id, "2026-11"))).body.decodeToString(),
    )

    @Test
    fun samplesSurviveReopeningAndBothDevicesExchangeThemOnce() = runBlocking {
        val phoneDirectory = temp.newFolder("phone")
        val phoneRepository = repository(phoneDirectory, phone)
        val phoneRoot = book(phoneRepository)
        val phoneDay = day(phone, "2026-11-01", mapOf(30 to 2, 3_600 to 1), modified = 10)
        phoneRepository.saveStatistics(phoneRoot, listOf(phoneDay))
        val phoneLane = lane(phoneRepository)
        exchange(phoneLane)

        // The monthly shard must be sufficient by itself; compatibility keys can disappear.
        server.client().delete(statisticsKey(syncId))
        val tabletRepository = repository(temp.newFolder("tablet"), tablet)
        val tabletRoot = book(tabletRepository)
        val tabletLane = lane(tabletRepository)
        exchange(tabletLane)
        assertEquals(listOf(phoneDay), tabletRepository.loadStatistics(tabletRoot))

        val tabletDay = day(tablet, "2026-11-01", mapOf(40 to 3), modified = 20)
        tabletRepository.saveStatistics(tabletRoot, listOf(tabletDay))
        exchange(tabletLane)
        exchange(phoneLane)

        val expected = setOf(phoneDay, tabletDay)
        assertEquals(expected, phoneRepository.loadStatistics(phoneRoot).toSet())
        assertEquals(expected, tabletRepository.loadStatistics(tabletRoot).toSet())
        assertEquals(listOf(phoneDay), shard(phone).books.getValue(syncId).reading)
        assertEquals(listOf(tabletDay), shard(tablet).books.getValue(syncId).reading)

        // Recreate every in-memory repository/lane object, as after process death.
        val reopened = repository(phoneDirectory, phone)
        val reopenedLane = lane(reopened)
        assertEquals(expected, reopened.loadStatistics(phoneRoot).toSet())
        repeat(3) {
            exchange(reopenedLane)
            exchange(tabletLane)
        }
        assertEquals(expected, reopened.loadStatistics(phoneRoot).toSet())
        assertEquals(expected, tabletRepository.loadStatistics(tabletRoot).toSet())
        assertEquals(6, reopened.loadStatistics(phoneRoot).sumOf { it.mangaPageSeconds.values.sum() })

        server.clearRequests()
        exchange(reopenedLane)
        exchange(tabletLane)
        assertTrue("converged samples are not uploaded again", server.requests().none { it.method == "PUT" })
    }

    @Test
    fun aRestoredDeviceReadsItsOwnServerSamplesBeforePublishingANewerDay() = runBlocking {
        val original = repository(temp.newFolder("original"), phone)
        val originalRoot = book(original)
        val oldDay = day(phone, "2026-11-01", mapOf(30 to 4, 7_200 to 1), modified = 10)
        original.saveStatistics(originalRoot, listOf(oldDay))
        exchange(lane(original))
        server.client().delete(statisticsKey(syncId))

        val restored = repository(temp.newFolder("restored"), phone)
        val restoredRoot = book(restored)
        val newDay = day(phone, "2026-11-02", mapOf(40 to 2), modified = 20)
        restored.saveStatistics(restoredRoot, listOf(newDay))

        exchange(lane(restored))

        assertEquals(setOf(oldDay, newDay), restored.loadStatistics(restoredRoot).toSet())
        assertEquals(setOf(oldDay, newDay), shard(phone).books.getValue(syncId).reading.toSet())
    }

    @Test
    fun aSameDayRestoreRetainsServerSamplesBeforePublishingNewReading() = runBlocking {
        val original = repository(temp.newFolder("original-same-day"), phone)
        val originalRoot = book(original)
        val oldDay = day(phone, "2026-11-01", mapOf(30 to 4, 7_200 to 1), modified = 10)
        original.saveStatistics(originalRoot, listOf(oldDay))
        exchange(lane(original))
        server.client().delete(statisticsKey(syncId))

        val restored = repository(temp.newFolder("restored-same-day"), phone)
        val restoredRoot = book(restored)
        val newReading = day(phone, "2026-11-01", mapOf(40 to 2), modified = 20)
        restored.saveStatistics(restoredRoot, listOf(newReading))
        val restoredLane = lane(restored)

        repeat(3) { exchange(restoredLane) }

        val expected = newReading.copy(mangaPageSeconds = mapOf(30 to 4, 40 to 2, 7_200 to 1))
        assertEquals(listOf(expected), restored.loadStatistics(restoredRoot))
        assertEquals(listOf(expected), shard(phone).books.getValue(syncId).reading)
    }

    @Test
    fun anOlderCompatibilityClientCannotEraseSamplesAtTheSameModificationStamp() = runBlocking {
        for (richLocal in listOf(true, false)) {
            server.reset()
            val repository = repository(temp.newFolder(), phone)
            val root = book(repository)
            val rich = day(phone, "2026-11-01", mapOf(30 to 4, 7_200 to 1), modified = 10)
            val stripped = rich.copy(mangaPageSeconds = emptyMap())
            repository.saveStatistics(root, listOf(if (richLocal) rich else stripped))
            val client = server.client()
            client.put(
                statisticsKey(syncId), "application/json",
                json.encodeToString(
                    HttpSyncStatisticsBlob.serializer(),
                    HttpSyncStatisticsBlob(syncId = syncId, entries = listOf(if (richLocal) stripped else rich)),
                ).toByteArray(),
            )
            val sync = HttpSyncStatisticsSync(repository)
            suspend fun exchange(): StatisticsSyncOutcome {
                val meta = client.list().keys.single { it.key == statisticsKey(syncId) }
                return sync.sync(
                    client, root, syncId, StatisticsSyncKind.Reading,
                    StatisticsRemoteListing.Listed(meta.size, meta.lastModified, meta.etag),
                )
            }

            exchange()

            assertEquals(listOf(rich), repository.loadStatistics(root))
            val remote = json.decodeFromString(
                HttpSyncStatisticsBlob.serializer(), requireNotNull(client.get(statisticsKey(syncId))).body.decodeToString(),
            )
            assertEquals(listOf(rich), remote.entries)
            assertEquals("equal samples converge", StatisticsSyncOutcome.NONE, exchange())
        }
    }
}
