package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.reader.loadReadingStatisticsOverview
import moe.antimony.hoshi.features.statistics.computeReadingStreak
import moe.antimony.hoshi.features.sync.http.HttpSyncException
import moe.antimony.hoshi.features.sync.http.HttpSyncKvFileFetched
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncStatisticsBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncStatisticsShard
import moe.antimony.hoshi.features.sync.http.StatisticsPreferences
import moe.antimony.hoshi.features.sync.http.StatisticsPreferencesStore
import moe.antimony.hoshi.features.sync.http.metadataKey
import moe.antimony.hoshi.features.sync.http.statisticsKey
import moe.antimony.hoshi.features.sync.http.statisticsShardKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/**
 * The reading history every device shows must be the same, whichever books each device has
 * installed, deleted or not downloaded yet, and whichever app version wrote it. Two installed
 * apps against the real test server, judged by the numbers the Statistics screen renders.
 */
class CrossDeviceReadingHistoryTest {
    companion object {
        @JvmField @ClassRule val serverRule = SyncTestServerRule()

        private val TODAY = LocalDate.of(2026, 10, 3)
        private val PHONE = DeviceIdentity("phone", "Phone")
        private val TABLET = DeviceIdentity("tablet", "Tablet")
        private const val RESET_HOUR = 3
        private const val GOAL_SECONDS = 600.0
    }

    @get:Rule val temp = TemporaryFolder()
    private val server get() = serverRule.server
    private val devices = mutableListOf<SyncDevice>()
    private val directories = mutableMapOf<String, File>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before fun reset() = server.reset()
    @After fun close() = devices.forEach(SyncDevice::close)

    private fun device(
        identity: DeviceIdentity,
        preferences: StatisticsPreferencesStore? = null,
        decorate: (HttpSyncKvTransport) -> HttpSyncKvTransport = { it },
    ): SyncDevice = SyncDevice(
        identity.id, SyncEngine.V3, directories.getOrPut(identity.id) { temp.newFolder(identity.id) }, server,
        installationId = identity.id, deviceIdentity = identity, decorateTransport = decorate,
        statisticsPreferences = preferences,
    ).also { devices += it }

    /** A phone whose book downloads keep failing: the book never gets installed there. */
    private val noBookDownloads: (HttpSyncKvTransport) -> HttpSyncKvTransport = { real ->
        object : HttpSyncKvTransport by real {
            override suspend fun downloadToFile(
                key: String,
                targetFile: File,
                onByteProgress: ((Long, Long) -> Unit)?,
            ): HttpSyncKvFileFetched? =
                if (key.endsWith(".zip")) throw HttpSyncException("download interrupted") else real.downloadToFile(key, targetFile, onByteProgress)
        }
    }

    private fun day(date: LocalDate, seconds: Double, device: DeviceIdentity, modified: Long = 1, characters: Int = 1_000) =
        ReadingStatistics(
            title = SyncCorpus.NOVEL_TITLE, dateKey = date.toString(),
            readingTime = seconds, charactersRead = characters, lastStatisticModified = modified,
            deviceId = device.id, deviceName = device.name,
            readingTimeByHour = mapOf("${date}T20:00" to seconds),
        )

    /** 22 evenings of 20 minutes ending today: the tablet's streak in the report. */
    private fun tabletMonth() = (0L until 22L).map { day(TODAY.minusDays(it), 1_200.0, TABLET) }

    private data class Visible(val todaySeconds: Double, val totalSeconds: Double, val streak: Int, val longest: Int)

    private suspend fun visible(device: SyncDevice): Visible {
        val overview = loadReadingStatisticsOverview(device.repo, TODAY.toString(), RESET_HOUR)
        val streak = computeReadingStreak(overview.streakDaily, GOAL_SECONDS, TODAY)
        return Visible(overview.todaySeconds, overview.totalSeconds, streak.currentDays, streak.longestDays)
    }

    private fun assertClean(outcome: SyncOutcome) =
        assertEquals("sync errors: ${outcome.errors}", emptyList<String>(), outcome.errors)

    @Test(timeout = 60_000)
    fun aPhoneThatNeverDownloadedTheBookShowsTheTabletsTodayAndWholeStreak() = runBlocking {
        val tablet = device(TABLET)
        val root = tablet.importNovel()
        assertClean(tablet.sync())
        val phone = device(PHONE, decorate = noBookDownloads)
        phone.sync()
        assertNull("the book is not on the phone", phone.bookOrNull(SyncCorpus.NOVEL_SYNC_ID))

        tablet.repo.saveStatistics(root, tabletMonth())
        assertClean(tablet.sync())
        phone.sync()

        val expected = Visible(todaySeconds = 1_200.0, totalSeconds = 22 * 1_200.0, streak = 22, longest = 22)
        assertEquals(expected, visible(tablet))
        assertEquals("the phone shows what the tablet shows", expected, visible(phone))
        assertNull("still not installed", phone.bookOrNull(SyncCorpus.NOVEL_SYNC_ID))
    }

    @Test(timeout = 60_000)
    fun theStreakArrivesOnAPhoneThatUpdatesADayLater() = runBlocking {
        // The tablet read for weeks while the phone (an older build) never received the book's
        // history; the server holds it in the per-book key every build writes.
        val tablet = device(TABLET)
        val root = tablet.importNovel()
        tablet.repo.saveStatistics(root, tabletMonth())
        assertClean(tablet.sync())
        val stored = server.client().get(statisticsKey(SyncCorpus.NOVEL_SYNC_ID))
        assertNotNull("the tablet's history is on the server", stored)

        // The phone updates and syncs once; its download is still pending.
        val phone = device(PHONE, decorate = noBookDownloads)
        phone.sync()

        assertEquals(visible(tablet), visible(phone))
        assertEquals(22, visible(phone).streak)
    }

    @Test(timeout = 60_000)
    fun aFinishedBookDeletedOnOneDeviceKeepsItsDaysOnEveryDevice() = runBlocking {
        val tablet = device(TABLET)
        val phone = device(PHONE)
        val root = tablet.importNovel()
        assertClean(tablet.sync())
        assertClean(phone.sync())
        assertClean(tablet.sync())
        tablet.repo.saveStatistics(root, tabletMonth())
        assertClean(tablet.sync())
        assertClean(phone.sync())
        val before = visible(tablet)
        assertEquals(before, visible(phone))

        tablet.deleteBook(tablet.book(SyncCorpus.NOVEL_SYNC_ID))
        assertClean(tablet.sync())
        assertClean(phone.sync())

        assertNull(phone.bookOrNull(SyncCorpus.NOVEL_SYNC_ID))
        assertEquals("deleting the book must not delete what was read in it", before, visible(tablet))
        assertEquals(before, visible(phone))
    }

    @Test(timeout = 60_000)
    fun anOlderBuildsPerBookHistoryShowsUpAndItReceivesNewReading() = runBlocking {
        val phone = device(PHONE)
        val root = phone.importNovel()
        assertClean(phone.sync())
        // An older build (0.11.x/0.12.x) on another device only speaks the per-book key.
        val old = DeviceIdentity("old-tablet", "Old tablet")
        val oldDays = (1L..3L).map { day(TODAY.minusDays(it), 900.0, old) }
        server.client().put(statisticsKey(SyncCorpus.NOVEL_SYNC_ID), "application/json", json.encodeToString(
            HttpSyncStatisticsBlob.serializer(), HttpSyncStatisticsBlob(syncId = SyncCorpus.NOVEL_SYNC_ID, entries = oldDays),
        ).toByteArray())

        phone.repo.saveStatistics(root, listOf(day(TODAY, 700.0, PHONE, modified = 5)))
        assertClean(phone.sync())

        assertEquals(Visible(700.0, 3 * 900.0 + 700.0, streak = 4, longest = 4), visible(phone))
        val serverBlob = json.decodeFromString(
            HttpSyncStatisticsBlob.serializer(),
            server.client().get(statisticsKey(SyncCorpus.NOVEL_SYNC_ID))!!.body.toString(Charsets.UTF_8),
        )
        assertEquals(
            "the older build reads the phone's day from the key it knows",
            setOf("old-tablet", "phone"), serverBlob.entries.mapNotNull { it.deviceId }.toSet(),
        )
    }

    @Test(timeout = 60_000)
    fun aStaleOverwriteOfThePerBookKeyCannotHideAnotherDevicesReading() = runBlocking {
        val tablet = device(TABLET)
        val phone = device(PHONE)
        val root = tablet.importNovel()
        assertClean(tablet.sync())
        assertClean(phone.sync())
        assertClean(tablet.sync())
        tablet.repo.saveStatistics(root, listOf(day(TODAY.minusDays(1), 600.0, TABLET)))
        assertClean(tablet.sync())
        tablet.repo.saveStatistics(root, listOf(day(TODAY, 1_500.0, TABLET, modified = 9)))
        assertClean(tablet.sync())
        // A client without compare-and-swap writes the per-book key back from an older read.
        server.client().put(statisticsKey(SyncCorpus.NOVEL_SYNC_ID), "application/json", json.encodeToString(
            HttpSyncStatisticsBlob.serializer(),
            HttpSyncStatisticsBlob(syncId = SyncCorpus.NOVEL_SYNC_ID, entries = listOf(day(TODAY.minusDays(1), 600.0, TABLET))),
        ).toByteArray())

        assertClean(phone.sync())

        assertEquals("the tablet's own shard still carries today", 1_500.0, visible(phone).todaySeconds, 0.0)
        assertEquals(visible(tablet), visible(phone))
        val shard = json.decodeFromString(
            HttpSyncStatisticsShard.serializer(),
            server.client().get(statisticsShardKey(TABLET.id, "2026-10"))!!.body.toString(Charsets.UTF_8),
        )
        assertEquals(
            "October holds both of the tablet's days, newest values",
            listOf(600.0, 1_500.0), shard.books.values.flatMap { book -> book.reading.map { it.readingTime } },
        )
    }

    @Test(timeout = 60_000)
    fun streakGoalAndDayResetChosenOnOneDeviceApplyOnEveryDevice() = runBlocking {
        val tabletPreferences = MemoryPreferences()
        val phonePreferences = MemoryPreferences()
        val tablet = device(TABLET, tabletPreferences)
        val phone = device(PHONE, phonePreferences)
        tablet.importNovel()
        assertClean(tablet.sync())
        assertClean(phone.sync())

        tabletPreferences.value = StatisticsPreferences(streakMinimumMinutes = 30, dayResetHour = 5, updatedAt = 1_000)
        assertClean(tablet.sync())
        assertClean(phone.sync())
        assertEquals(tabletPreferences.value, phonePreferences.value)

        phonePreferences.value = StatisticsPreferences(streakMinimumMinutes = 15, dayResetHour = 0, updatedAt = 2_000)
        assertClean(phone.sync())
        assertClean(tablet.sync())
        assertEquals(StatisticsPreferences(15, 0, 2_000), tabletPreferences.value)
        assertEquals(phonePreferences.value, tabletPreferences.value)
    }

    @Test(timeout = 60_000)
    fun manualSyncCountsOnlyTheBooksWhosePositionMoved() = runBlocking {
        val tablet = device(TABLET)
        val phone = device(PHONE)
        tablet.importNovel()
        tablet.importManga()
        assertClean(tablet.sync())
        assertClean(phone.sync())
        assertClean(tablet.sync())
        assertClean(phone.sync())

        val root = tablet.book(SyncCorpus.MANGA_SYNC_ID).root
        repeat(10) { page ->
            tablet.turnPage(root, SyncCorpus.MANGA_TITLE, Bookmark(page, page / 10.0, page * 10, 800_000_000.0 + page))
        }
        val pulled = phone.sync()
        assertClean(pulled)
        assertEquals("one book was read on the tablet", 1, pulled.downloadedBookmarks)
        assertEquals(9, phone.book(SyncCorpus.MANGA_SYNC_ID).let { phone.repo.loadBookmark(it.root)!!.chapterIndex })
        val again = phone.sync()
        assertClean(again)
        assertEquals("nothing moved since", 0, again.downloadedBookmarks)
        assertEquals(0, again.uploadedBookmarks)
    }

    @Test(timeout = 60_000)
    fun pollingDeliversTheTabletsReadingWithoutAManualSync() = runBlocking {
        val tablet = device(TABLET)
        val phone = device(PHONE, decorate = noBookDownloads)
        val root = tablet.importNovel()
        assertClean(tablet.sync())
        phone.poll()
        tablet.repo.saveStatistics(root, listOf(day(TODAY, 1_200.0, TABLET)))
        tablet.pushReaderStatistics()
        phone.poll()
        assertEquals(1_200.0, visible(phone).todaySeconds, 0.0)
        assertNotNull(server.client().get(metadataKey(SyncCorpus.NOVEL_SYNC_ID)))
    }

    private class MemoryPreferences : StatisticsPreferencesStore {
        var value = StatisticsPreferences(streakMinimumMinutes = 10, dayResetHour = 3, updatedAt = 0)
        override suspend fun load(): StatisticsPreferences = value
        override suspend fun save(preferences: StatisticsPreferences) {
            value = preferences
        }
    }
}
