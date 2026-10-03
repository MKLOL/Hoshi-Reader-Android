package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.ReadingStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class HttpSyncStatisticsLaneTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val phone = DeviceIdentity("phone", "Phone")
    private val tablet = DeviceIdentity("tablet", "Tablet")
    private var nowMs = 1_000_000L

    private fun repository(device: DeviceIdentity? = phone) =
        BookRepository(temporaryFolder.newFolder(), deviceIdentity = device)

    private fun lane(repository: BookRepository, preferences: StatisticsPreferencesStore? = null) =
        HttpSyncStatisticsLane(repository, HttpSyncBookLocks(), preferences, now = { nowMs })

    private suspend fun book(repository: BookRepository, syncId: String, title: String = "Book"): File {
        val root = repository.createBookDirectory(syncId)
        repository.saveMetadata(root, BookMetadata(id = syncId, title = title, cover = null, folder = syncId, lastAccess = 0.0, syncId = syncId))
        return root
    }

    private fun day(date: String, seconds: Double, device: DeviceIdentity, modified: Long = 1, title: String = "Book") =
        ReadingStatistics(
            title = title, dateKey = date, readingTime = seconds, charactersRead = 100,
            lastStatisticModified = modified, deviceId = device.id, deviceName = device.name,
        )

    private fun shard(device: DeviceIdentity, month: String, books: Map<String, HttpSyncStatisticsShardBook>) =
        json.encodeToString(
            HttpSyncStatisticsShard.serializer(),
            HttpSyncStatisticsShard(deviceId = device.id, month = month, books = books),
        ).toByteArray()

    private fun legacy(syncId: String, entries: List<ReadingStatistics>) = json.encodeToString(
        HttpSyncStatisticsBlob.serializer(), HttpSyncStatisticsBlob(syncId = syncId, entries = entries),
    ).toByteArray()

    @Test
    fun aConvergedLibraryCostsNoRequestAfterTheFirstPass() = runBlocking {
        val repository = repository()
        val root = book(repository, "book")
        repository.saveStatistics(root, listOf(day("2026-10-01", 600.0, phone)))
        val server = ContentAddressedKv()
        val lane = lane(repository)

        lane.run(server, server.listing())
        assertFalse("everything was sent", lane.needsRun(server, server.listing()))
        server.resetCounts()
        val second = lane.run(server, server.listing())

        assertEquals(StatisticsLaneResult.NONE, second)
        assertEquals("a converged pass makes no request", 0, server.gets + server.puts)
    }

    @Test
    fun aBookWithoutAFolderKeepsItsHistoryUnderItsTitleAndType() = runBlocking {
        val repository = repository()
        val server = ContentAddressedKv()
        server.put(metadataKey("manga_1"), "application/json", """{"title":"Manga One","contentType":"mokuro"}""".toByteArray())
        server.put(statisticsKey("manga_1"), "application/json", legacy("manga_1", listOf(day("2026-10-02", 900.0, tablet, title = "Manga One"))))
        val lane = lane(repository)

        assertTrue(lane.needsRun(server, server.listing()))
        val result = lane.run(server, server.listing())

        assertEquals(1, result.downloadedBooks)
        val kept = repository.loadStatisticsHistory().single()
        assertEquals("manga_1", kept.syncId)
        assertEquals("Manga One", kept.title)
        assertEquals(ContentType.Mokuro, kept.contentType)
        assertEquals(listOf(900.0), repository.loadStatistics(kept.root).map { it.readingTime })
        assertTrue("a device-less exchange state keeps others' days unclaimed", kept.root.resolve(".http_sync_statistics.json").isFile)
        assertFalse(lane.needsRun(server, server.listing()))
    }

    @Test
    fun onlyTheChangedMonthOfThisDeviceIsPublishedAgain() = runBlocking {
        val repository = repository()
        val root = book(repository, "book")
        repository.saveStatistics(root, listOf(day("2026-09-30", 600.0, phone), day("2026-10-01", 600.0, phone)))
        val server = ContentAddressedKv()
        val lane = lane(repository)
        lane.run(server, server.listing(), flush = true)
        assertEquals(setOf(statisticsShardKey("phone", "2026-09"), statisticsShardKey("phone", "2026-10")),
            server.keys().filter { it.startsWith(STATISTICS_SHARD_PREFIX) }.toSet())

        repository.saveStatistics(root, listOf(day("2026-10-01", 1_200.0, phone, modified = 2)))
        server.resetCounts()
        lane.run(server, server.listing(), flush = true)

        assertEquals(listOf(statisticsKey("book"), statisticsShardKey("phone", "2026-10")), server.putKeys)
    }

    @Test
    fun localDaysAreBatchedWhileReadingAndSentAtOnceOnFlush() = runBlocking {
        val repository = repository()
        val root = book(repository, "book")
        repository.saveStatistics(root, listOf(day("2026-10-01", 60.0, phone)))
        val server = ContentAddressedKv()
        val lane = lane(repository)
        lane.run(server, server.listing())

        repository.saveStatistics(root, listOf(day("2026-10-01", 120.0, phone, modified = 2)))
        nowMs += 10_000
        assertFalse("a page turn 10 s after the last push waits for the batch", lane.needsRun(server, server.listing()))
        nowMs += HttpSyncStatisticsLane.LOCAL_PUSH_INTERVAL_MS
        assertTrue(lane.needsRun(server, server.listing()))

        repository.saveStatistics(root, listOf(day("2026-10-01", 180.0, phone, modified = 3)))
        server.resetCounts()
        lane.run(server, server.listing(), flush = true)
        assertTrue("leaving the reader sends at once", server.putKeys.isNotEmpty())
    }

    @Test
    fun anotherDevicesShardArrivesEvenWithoutItsPerBookKey() = runBlocking {
        val repository = repository()
        val root = book(repository, "book")
        val server = ContentAddressedKv()
        server.put(statisticsShardKey("tablet", "2026-10"), "application/json", shard(tablet, "2026-10", mapOf(
            "book" to HttpSyncStatisticsShardBook(title = "Book", reading = listOf(day("2026-10-03", 1_200.0, tablet))),
            "gone" to HttpSyncStatisticsShardBook(title = "Gone", contentType = ContentType.Epub,
                reading = listOf(day("2026-10-02", 300.0, tablet, title = "Gone"))),
        )))
        val lane = lane(repository)

        val result = lane.run(server, server.listing())

        assertEquals(2, result.downloadedBooks)
        assertEquals(listOf(1_200.0), repository.loadStatistics(root).map { it.readingTime })
        val gone = repository.loadStatisticsHistory().single()
        assertEquals("Gone", gone.title)
        assertEquals(listOf(300.0), repository.loadStatistics(gone.root).map { it.readingTime })
        assertFalse(lane.needsRun(server, server.listing()))
    }

    @Test
    fun aShardSpeaksOnlyForItsOwnDeviceAndMonth() = runBlocking {
        val repository = repository()
        val root = book(repository, "book")
        val server = ContentAddressedKv()
        // Written under the tablet's key but claiming to be the phone's: refused entirely.
        server.put(statisticsShardKey("tablet", "2026-10"), "application/json", shard(phone, "2026-10", mapOf(
            "book" to HttpSyncStatisticsShardBook(reading = listOf(day("2026-10-03", 999.0, phone))),
        )))
        // A valid shard: rows of another device or month are ignored.
        server.put(statisticsShardKey("reader", "2026-10"), "application/json", shard(DeviceIdentity("reader", "R"), "2026-10", mapOf(
            "book" to HttpSyncStatisticsShardBook(reading = listOf(
                day("2026-10-01", 60.0, DeviceIdentity("reader", "R")),
                day("2026-10-01", 77.0, phone),
                day("2026-09-30", 88.0, DeviceIdentity("reader", "R")),
            )),
        )))
        val result = lane(repository).run(server, server.listing())

        assertEquals(1, result.errors.size)
        assertEquals(listOf(60.0), repository.loadStatistics(root).map { it.readingTime })
    }

    @Test
    fun aFailingKeyIsRetriedWhenItChangesNotOnEveryPoll() = runBlocking {
        val repository = repository()
        book(repository, "book")
        val server = ContentAddressedKv()
        server.put(statisticsKey("book"), "application/json", "{not json".toByteArray())
        val lane = lane(repository)

        assertTrue(lane.run(server, server.listing()).errors.isNotEmpty())
        assertFalse("the same broken body is not fetched every five seconds", lane.needsRun(server, server.listing()))

        server.put(statisticsKey("book"), "application/json", legacy("book", listOf(day("2026-10-01", 60.0, tablet))))
        assertTrue(lane.needsRun(server, server.listing()))
        assertTrue(lane.run(server, server.listing()).errors.isEmpty())
    }

    @Test
    fun aReinstalledDeviceTakesBackItsOwnHistoryBeforePublishing() = runBlocking {
        val server = ContentAddressedKv()
        server.put(statisticsShardKey("phone", "2026-09"), "application/json", shard(phone, "2026-09", mapOf(
            "book" to HttpSyncStatisticsShardBook(title = "Book", reading = listOf(day("2026-09-20", 600.0, phone))),
        )))
        val repository = repository()
        val root = book(repository, "book")
        repository.saveStatistics(root, listOf(day("2026-09-21", 300.0, phone)))

        lane(repository).run(server, server.listing(), flush = true)

        assertEquals(listOf(600.0, 300.0), repository.loadStatistics(root).sortedBy { it.dateKey }.map { it.readingTime })
        val published = json.decodeFromString(
            HttpSyncStatisticsShard.serializer(), server.body(statisticsShardKey("phone", "2026-09")).toString(Charsets.UTF_8),
        )
        assertEquals("the republished month keeps both days", 2, published.books.getValue("book").reading.size)
    }

    @Test
    fun theNewestStreakSettingsWinAndTiesGoToTheLargerDeviceId() = runBlocking {
        val repository = repository()
        val server = ContentAddressedKv()
        val store = MemoryPreferences(StatisticsPreferences(10, 3, updatedAt = 0))
        fun preferences(minutes: Int, reset: Int, at: Long) = json.encodeToString(
            HttpSyncStatisticsPreferences.serializer(), HttpSyncStatisticsPreferences(streakMinimumMinutes = minutes, dayResetHour = reset, updatedAt = at),
        ).toByteArray()
        server.put(statisticsPreferencesKey("aaa"), "application/json", preferences(20, 4, 500))
        server.put(statisticsPreferencesKey("zzz"), "application/json", preferences(25, 6, 500))
        val lane = lane(repository, store)

        lane.run(server, server.listing())

        assertEquals("equal choices: the larger device id wins everywhere", StatisticsPreferences(25, 6, 500), store.value)
        // A later local choice wins and is published for the others.
        store.value = StatisticsPreferences(40, 2, 900)
        assertTrue(lane.needsRun(server, server.listing()))
        lane.run(server, server.listing())
        assertEquals(StatisticsPreferences(40, 2, 900), store.value)
        val own = json.decodeFromString(
            HttpSyncStatisticsPreferences.serializer(), server.body(statisticsPreferencesKey("phone")).toString(Charsets.UTF_8),
        )
        assertEquals(HttpSyncStatisticsPreferences(streakMinimumMinutes = 40, dayResetHour = 2, updatedAt = 900), own)
        assertFalse(lane.needsRun(server, server.listing()))
    }

    @Test
    fun aGoalChosenBeforeSettingsWereSyncedStillReachesOtherDevices() = runBlocking {
        val repository = repository()
        val server = ContentAddressedKv()
        val store = MemoryPreferences(StatisticsPreferences(30, 3, updatedAt = 0))
        lane(repository, store).run(server, server.listing())
        assertEquals(1L, store.value.updatedAt)
        assertTrue(server.keys().contains(statisticsPreferencesKey("phone")))
    }

    @Test
    fun laneKeysParseOnlyWhatTheLaneWrites() {
        assertEquals(StatisticsLaneKey.Shard("phone", "2026-10"), parseStatisticsLaneKey("sync/maps/stats/phone/2026-10.json"))
        assertEquals(StatisticsLaneKey.Preferences("phone"), parseStatisticsLaneKey("sync/maps/stats/phone/settings.json"))
        assertEquals(null, parseStatisticsLaneKey("sync/maps/stats/phone/2026-1.json"))
        assertEquals(null, parseStatisticsLaneKey("sync/maps/stats/../2026-10.json"))
        assertEquals(null, parseStatisticsLaneKey("sync/maps/bookmarks/phone.json"))
        assertEquals(null, parseStatisticsLaneKey("sync/maps/stats/phone/2026-10.json/x"))
    }

    private class MemoryPreferences(var value: StatisticsPreferences) : StatisticsPreferencesStore {
        override suspend fun load() = value
        override suspend fun save(preferences: StatisticsPreferences) {
            value = preferences
        }
    }
}

/** A KV store whose ETags are the content hash, as the real server's are; counts requests. */
internal class ContentAddressedKv(override val cacheIdentity: String? = "test") : HttpSyncKvTransport {
    private class Stored(val body: ByteArray, val contentType: String, val lastModified: String, val etag: String)

    private val values = sortedMapOf<String, Stored>()
    private var clock = 0
    var gets = 0
        private set
    var puts = 0
        private set
    val putKeys = mutableListOf<String>()

    fun resetCounts() {
        gets = 0
        puts = 0
        putKeys.clear()
    }

    fun keys(): Set<String> = values.keys.toSet()

    fun body(key: String): ByteArray = values.getValue(key).body

    fun listing(): List<HttpSyncKvKeyMeta> = values.map { (key, stored) ->
        HttpSyncKvKeyMeta(key, stored.lastModified, stored.etag, stored.body.size, stored.contentType)
    }

    override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
        puts += 1
        putKeys += key
        clock += 1
        val etag = "sha256:" + MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val stamp = "2026-10-03T00:%02d:%02dZ".format(clock / 60, clock % 60)
        values[key] = Stored(body.copyOf(), contentType, stamp, etag)
        return HttpSyncKvWriteResponse(key, stamp, etag, body.size, contentType)
    }

    override suspend fun get(key: String): HttpSyncKvFetched? {
        gets += 1
        val stored = values[key] ?: return null
        return HttpSyncKvFetched(stored.body.copyOf(), stored.contentType, stored.lastModified, stored.etag)
    }

    override suspend fun list(prefix: String?, since: String?, cursor: String?, limit: Int?): HttpSyncKvList =
        HttpSyncKvList(listing().filter { prefix == null || it.key.startsWith(prefix) })

    override suspend fun delete(key: String) {
        values.remove(key)
    }
}
