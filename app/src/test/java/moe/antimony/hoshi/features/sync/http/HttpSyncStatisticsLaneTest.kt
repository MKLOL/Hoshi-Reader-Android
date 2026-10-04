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
import org.junit.Assert.assertNull
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
    fun aChosenZoneReachesEveryDeviceAndOlderBuildsKeepIt() = runBlocking {
        val server = ContentAddressedKv()
        val phoneStore = MemoryPreferences(StatisticsPreferences(10, 3, 0))
        val tabletStore = MemoryPreferences(StatisticsPreferences(10, 3, 0))
        val phoneLane = lane(repository(phone), phoneStore)
        val tabletLane = lane(repository(tablet), tabletStore)
        phoneLane.run(server, server.listing())
        tabletLane.run(server, server.listing())
        assertNull("Eastern Time until chosen; nothing to publish", tabletStore.value.timeZone)
        assertFalse(server.keys().any { it.endsWith("settings.json") })

        // Moving to Romania: chosen on the phone, applied on the tablet.
        phoneStore.value = StatisticsPreferences(10, 3, 900, "Europe/Bucharest")
        phoneLane.run(server, server.listing())
        assertTrue(tabletLane.needsRun(server, server.listing()))
        tabletLane.run(server, server.listing())
        assertEquals(StatisticsPreferences(10, 3, 900, "Europe/Bucharest"), tabletStore.value)

        // A newer goal from a build that does not sync the zone keeps the chosen zone.
        server.put(statisticsPreferencesKey("old-build"), "application/json", """{"version":1,"streakMinimumMinutes":20,"dayResetHour":3,"updatedAt":1000}""".toByteArray())
        tabletLane.run(server, server.listing())
        assertEquals(StatisticsPreferences(20, 3, 1000, "Europe/Bucharest"), tabletStore.value)
    }

    @Test
    fun aChoiceRepublishedWithoutItsZoneByAnOlderBuildNeverUndoesIt() = runBlocking {
        val server = ContentAddressedKv()
        suspend fun put(device: String, body: String) = server.put(statisticsPreferencesKey(device), "application/json", body.toByteArray())
        // "aaa" chose Shanghai at 900; "zzz" (larger id) still ran 0.12.2, applied the choice
        // without the zone it did not know and republished it with the same stamp.
        put("aaa", """{"version":1,"streakMinimumMinutes":10,"dayResetHour":3,"updatedAt":900,"timeZone":"Asia/Shanghai"}""")
        put("zzz", """{"version":1,"streakMinimumMinutes":10,"dayResetHour":3,"updatedAt":900}""")
        val updated = MemoryPreferences(StatisticsPreferences(10, 3, 900))
        val laneOfUpdated = HttpSyncStatisticsLane(
            BookRepository(temporaryFolder.newFolder(), deviceIdentity = DeviceIdentity("zzz", "Z")), HttpSyncBookLocks(), updated, now = { nowMs },
        )

        laneOfUpdated.run(server, server.listing())

        assertEquals(StatisticsPreferences(10, 3, 900, "Asia/Shanghai"), updated.value)
        val republished = json.decodeFromString(
            HttpSyncStatisticsPreferences.serializer(), server.body(statisticsPreferencesKey("zzz")).toString(Charsets.UTF_8),
        )
        assertEquals("Asia/Shanghai", republished.timeZone)
        assertFalse(laneOfUpdated.needsRun(server, server.listing()))
    }

    @Test
    fun settingsCachedByABuildThatDroppedTheZoneAreFetchedAgainOnce() = runBlocking {
        val server = ContentAddressedKv()
        val repository = repository()
        val store = MemoryPreferences(StatisticsPreferences(10, 3, 0))
        // The phone chose Bucharest while this device still ran 0.12.2, which cached the
        // settings without the zone field it did not know and marked them as merged.
        server.put(statisticsPreferencesKey("other"), "application/json", json.encodeToString(
            HttpSyncStatisticsPreferences.serializer(),
            HttpSyncStatisticsPreferences(streakMinimumMinutes = 10, dayResetHour = 3, updatedAt = 900, timeZone = "Europe/Bucharest"),
        ).toByteArray())
        val etag = server.listing().single().etag
        repository.booksDirectory.mkdirs()
        repository.booksDirectory.resolve(".http_sync_statistics_lane.json").writeText(
            """{"scope":"","applied":{"${statisticsPreferencesKey("other")}":"$etag"},""" +
                """"preferences":{"${statisticsPreferencesKey("other")}":{"version":1,"streakMinimumMinutes":10,"dayResetHour":3,"updatedAt":900}},""" +
                """"bootstrapped":true}""",
        )
        val lane = lane(repository, store)

        assertTrue(lane.needsRun(server, server.listing()))
        lane.run(server, server.listing())
        assertEquals(StatisticsPreferences(10, 3, 900, "Europe/Bucharest"), store.value)
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
    fun aReinstalledDeviceTakesBackItsOwnSettingsAndSettles() = runBlocking {
        val repository = repository()
        val server = ContentAddressedKv()
        server.put(statisticsPreferencesKey("phone"), "application/json", json.encodeToString(
            HttpSyncStatisticsPreferences.serializer(), HttpSyncStatisticsPreferences(streakMinimumMinutes = 25, dayResetHour = 5, updatedAt = 500),
        ).toByteArray())
        val store = MemoryPreferences(StatisticsPreferences(10, 3, updatedAt = 0))
        val lane = lane(repository, store)

        lane.run(server, server.listing())

        assertEquals(StatisticsPreferences(25, 5, 500), store.value)
        assertFalse("no pass every five seconds afterwards", lane.needsRun(server, server.listing()))
        server.resetCounts()
        lane.run(server, server.listing())
        assertEquals(0, server.gets + server.puts)
    }

    @Test
    fun anOutOfRangeChoiceFromAnotherDeviceSettlesOnceClamped() = runBlocking {
        val repository = repository()
        val server = ContentAddressedKv()
        server.put(statisticsPreferencesKey("tablet"), "application/json", json.encodeToString(
            HttpSyncStatisticsPreferences.serializer(), HttpSyncStatisticsPreferences(streakMinimumMinutes = 5_000, dayResetHour = 40, updatedAt = 900),
        ).toByteArray())
        val store = MemoryPreferences(StatisticsPreferences(10, 3, updatedAt = 0))
        val lane = lane(repository, store)
        lane.run(server, server.listing())
        assertEquals(StatisticsPreferences(600, 23, 900), store.value)
        lane.run(server, server.listing())
        assertFalse(lane.needsRun(server, server.listing()))
    }

    @Test
    fun aShardFromANewerVersionIsSkippedQuietlyNotReportedEveryPass() = runBlocking {
        val repository = repository()
        book(repository, "book")
        val server = ContentAddressedKv()
        server.put(statisticsShardKey("tablet", "2026-10"), "application/json", """{"version":2,"deviceId":"tablet","month":"2026-10"}""".toByteArray())
        val lane = lane(repository)

        val result = lane.run(server, server.listing(), flush = true)

        assertTrue("not an error a user can act on", result.errors.isEmpty())
        assertEquals(null, lane.status.value.problem)
        assertFalse(lane.needsRun(server, server.listing()))
        server.resetCounts()
        lane.run(server, server.listing(), flush = true)
        assertEquals("a flush does not fetch it again before its pause ends", 0, server.gets)
    }

    @Test
    fun aKeyAStaleListingStillShowsIsNotFetchedEveryPoll() = runBlocking {
        val repository = repository()
        val server = ContentAddressedKv()
        server.listGhost(statisticsKey("gone"))
        server.listGhost(statisticsShardKey("tablet", "2026-09"))
        val lane = lane(repository)

        val result = lane.run(server, server.listing())

        assertTrue(result.errors.isEmpty())
        assertFalse(lane.needsRun(server, server.listing()))
        assertEquals("no empty history folder for a key that is gone", null, repository.loadStatisticsHistory().singleOrNull { repository.loadStatistics(it.root).isEmpty() && it.syncId != "gone" })
    }

    @Test
    fun failedUploadsOfThisDevicesMonthsPauseInsteadOfRetryingEveryPoll() = runBlocking {
        val repository = repository()
        val root = book(repository, "book")
        repository.saveStatistics(root, listOf(day("2026-10-01", 600.0, phone)))
        val server = ContentAddressedKv()
        server.failPuts = { it.startsWith(STATISTICS_SHARD_PREFIX) }
        val lane = lane(repository)

        val first = lane.run(server, server.listing(), flush = true)
        assertTrue(first.errors.isNotEmpty())
        assertEquals(StatisticsSyncProblem.Offline, lane.status.value.problem)
        // The pause doubles: 30 s after the first failure, 60 s after the second.
        nowMs += 20_000
        assertFalse("waits out its pause", lane.needsRun(server, server.listing()))
        lane.markChecked()
        assertEquals("the problem stays shown while the month is unsent", StatisticsSyncProblem.Offline, lane.status.value.problem)
        nowMs += 10_000
        assertTrue(lane.needsRun(server, server.listing()))
        server.resetCounts()
        assertTrue(lane.run(server, server.listing(), flush = true).errors.isNotEmpty())
        nowMs += 40_000
        assertFalse(lane.needsRun(server, server.listing()))
        server.resetCounts()
        lane.run(server, server.listing(), flush = true)
        assertEquals("a reader flush honours the pause too", emptyList<String>(), server.putKeys)

        server.failPuts = { false }
        nowMs += 20_000
        assertTrue(lane.needsRun(server, server.listing()))
        assertTrue(lane.run(server, server.listing()).errors.isEmpty())
        assertEquals(null, lane.status.value.problem)
    }

    @Test
    fun aServerWithOpaqueEtagsIsNotSentUnchangedMonthsAgain() = runBlocking {
        val repository = repository()
        val root = book(repository, "book")
        repository.saveStatistics(root, listOf(day("2026-09-30", 600.0, phone), day("2026-10-01", 600.0, phone)))
        val server = ContentAddressedKv(opaqueEtags = true)
        val lane = lane(repository)
        lane.run(server, server.listing(), flush = true)
        server.resetCounts()

        lane.run(server, server.listing(), flush = true)

        assertEquals(emptyList<String>(), server.putKeys)
        assertFalse(lane.needsRun(server, server.listing()))
    }

    @Test
    fun anOwnMonthIsNotOverwrittenBeforeItsServerCopyCouldBeRead() = runBlocking {
        val server = ContentAddressedKv()
        server.put(statisticsShardKey("phone", "2026-09"), "application/json", "{ damaged".toByteArray())
        val repository = repository()
        val root = book(repository, "book")
        repository.saveStatistics(root, listOf(day("2026-09-21", 300.0, phone)))
        server.resetCounts()

        lane(repository).run(server, server.listing(), flush = true)

        assertFalse(
            "the server's copy of this device's month was never merged, so it is not replaced",
            statisticsShardKey("phone", "2026-09") in server.putKeys,
        )
    }

    @Test
    fun whileReadingThePerBookKeyFollowsItsOwnSlowerCadenceWithoutIdlePasses() = runBlocking {
        val repository = repository()
        val root = book(repository, "book")
        repository.saveStatistics(root, listOf(day("2026-10-01", 60.0, phone)))
        val server = ContentAddressedKv()
        val lane = lane(repository)
        lane.run(server, server.listing(), flush = true)

        // Thirty-five seconds of reading later: the shard goes up, the per-book key waits.
        nowMs += 35_000
        repository.saveStatistics(root, listOf(day("2026-10-01", 95.0, phone, modified = 2)))
        assertTrue(lane.needsRun(server, server.listing()))
        server.resetCounts()
        lane.run(server, server.listing())
        assertEquals(listOf(statisticsShardKey("phone", "2026-10")), server.putKeys)
        // Nothing new: no pass every five seconds while the per-book key waits its turn.
        nowMs += 35_000
        assertFalse(lane.needsRun(server, server.listing()))
        nowMs += HttpSyncStatisticsLane.LEGACY_PUSH_INTERVAL_MS
        assertTrue(lane.needsRun(server, server.listing()))
        server.resetCounts()
        lane.run(server, server.listing())
        assertEquals(listOf(statisticsKey("book")), server.putKeys)
    }

    @Test
    fun aDamagedCopyOfThisDevicesMonthDoesNotMakeEveryPollRunThePass() = runBlocking {
        val server = ContentAddressedKv()
        server.put(statisticsShardKey("phone", "2026-09"), "application/json", "{ damaged".toByteArray())
        val repository = repository()
        val root = book(repository, "book")
        repository.saveStatistics(root, listOf(day("2026-09-21", 300.0, phone)))
        val lane = lane(repository)
        lane.run(server, server.listing(), flush = true)

        // Retried on a doubling pause (30 s, then 60 s, ...), never on every five-second poll.
        nowMs += 10_000
        assertFalse(lane.needsRun(server, server.listing()))
        nowMs += 20_000
        assertTrue(lane.needsRun(server, server.listing()))
        lane.run(server, server.listing())
        nowMs += 40_000
        assertFalse(lane.needsRun(server, server.listing()))
    }

    @Test
    fun anAnswerWithAnHttpErrorIsAServerProblemNotAMissingConnection() = runBlocking {
        val repository = repository()
        val root = book(repository, "book")
        repository.saveStatistics(root, listOf(day("2026-10-01", 600.0, phone)))
        val server = ContentAddressedKv()
        val failing = object : HttpSyncKvTransport by server {
            override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse =
                throw HttpSyncException("Server is having trouble (HTTP 503). Try again in a moment.")
        }
        lane(repository).let { lane ->
            lane.run(failing, server.listing(), flush = true)
            assertEquals(StatisticsSyncProblem.Server, lane.status.value.problem)
        }
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
        override val defaults = StatisticsPreferences(10, 3, 0)
        override fun normalize(preferences: StatisticsPreferences) = preferences.copy(
            streakMinimumMinutes = preferences.streakMinimumMinutes.coerceIn(1, 600),
            dayResetHour = preferences.dayResetHour.coerceIn(0, 23),
        )
        override suspend fun load() = value
        override suspend fun save(preferences: StatisticsPreferences) {
            value = preferences
        }
    }
}

/** A KV store whose ETags are the content hash, as the real server's are; counts requests. */
internal class ContentAddressedKv(
    override val cacheIdentity: String? = "test",
    /** ETags like many servers give: not a hash of the body. */
    private val opaqueEtags: Boolean = false,
) : HttpSyncKvTransport {
    /** PUTs of keys matching this fail like an unreachable server. */
    var failPuts: (String) -> Boolean = { false }

    /** Keys a stale listing still shows after they were deleted. */
    private val ghosts = mutableMapOf<String, HttpSyncKvKeyMeta>()

    fun listGhost(key: String) {
        ghosts[key] = HttpSyncKvKeyMeta(key, "2026-10-03T00:00:00Z", "sha256:" + "0".repeat(64), 10, "application/json")
    }

    private class Stored(val body: ByteArray, val contentType: String, val lastModified: String, val etag: String)

    private val values = sortedMapOf<String, Stored>()
    private var clock = 0
    var gets = 0
        private set
    var puts = 0
        private set
    @Volatile var lists = 0
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
    } + ghosts.values

    override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
        puts += 1
        putKeys += key
        if (failPuts(key)) throw HttpSyncException("Sync request timed out. Network is too slow or the server is hung.")
        clock += 1
        val etag = if (opaqueEtags) "\"v$clock\"" else "sha256:" + MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val stamp = "2026-10-03T00:%02d:%02dZ".format(clock / 60, clock % 60)
        values[key] = Stored(body.copyOf(), contentType, stamp, etag)
        return HttpSyncKvWriteResponse(key, stamp, etag, body.size, contentType)
    }

    override suspend fun get(key: String): HttpSyncKvFetched? {
        gets += 1
        val stored = values[key] ?: return null
        return HttpSyncKvFetched(stored.body.copyOf(), stored.contentType, stored.lastModified, stored.etag)
    }

    override suspend fun list(prefix: String?, since: String?, cursor: String?, limit: Int?): HttpSyncKvList {
        lists += 1
        return HttpSyncKvList(listing().filter { prefix == null || it.key.startsWith(prefix) })
    }

    override suspend fun delete(key: String) {
        values.remove(key)
    }
}
