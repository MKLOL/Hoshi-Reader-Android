package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.reader.DailyReading
import moe.antimony.hoshi.features.reader.DeviceReadingSummary
import moe.antimony.hoshi.features.reader.loadReadingStatisticsOverview
import moe.antimony.hoshi.features.statistics.TrendPoint
import moe.antimony.hoshi.features.statistics.computeReadingStreak
import moe.antimony.hoshi.features.statistics.rollingAverageSeries
import moe.antimony.hoshi.features.sync.http.HttpSyncKvFetched
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.STATISTICS_SYNC_STATE_FILENAME
import moe.antimony.hoshi.features.sync.http.statisticsKey
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Release regressions for the real user path: two installed apps, actual HTTP KV storage,
 * production map/engine orchestration, and the same aggregates that render Statistics.
 * All network responses come from the Python server. The only transport decoration pauses
 * already-fetched real responses to reproduce concurrent reader uploads deterministically.
 */
@RunWith(Parameterized::class)
class SyncReleaseRegressionTest(private val phoneEngine: SyncEngine, private val tabletEngine: SyncEngine) {
    companion object {
        @JvmField @ClassRule val serverRule = SyncTestServerRule()

        @JvmStatic
        @Parameterized.Parameters(name = "phone={0} tablet={1}")
        fun engines(): List<Array<Any>> = listOf(
            arrayOf(SyncEngine.V3, SyncEngine.V3),
            arrayOf(SyncEngine.V2, SyncEngine.V2),
            arrayOf(SyncEngine.V3, SyncEngine.V2),
            arrayOf(SyncEngine.V2, SyncEngine.V3),
        )

        private val TODAY = LocalDate.of(2026, 9, 30)
        private val PHONE = DeviceIdentity("phone", "Phone")
        private val TABLET = DeviceIdentity("tablet", "Tablet")
    }

    @get:Rule val temp = TemporaryFolder()
    private val server get() = serverRule.server
    private val devices = mutableListOf<SyncDevice>()
    private val directories = mutableMapOf<String, File>()

    @Before fun reset() = server.reset()
    @After fun close() = devices.forEach(SyncDevice::close)

    private fun device(
        name: String,
        engine: SyncEngine,
        identity: DeviceIdentity? = if (name == "phone") PHONE else TABLET,
        decorate: (HttpSyncKvTransport) -> HttpSyncKvTransport = { it },
    ): SyncDevice = SyncDevice(
        name, engine, directories.getOrPut(name) { temp.newFolder(name) }, server,
        installationId = name, deviceIdentity = identity, decorateTransport = decorate,
    ).also { devices += it }

    private fun assertClean(outcome: SyncOutcome) {
        assertEquals("sync errors: ${outcome.errors}", emptyList<String>(), outcome.errors)
    }

    private fun assertNoPayloadTransfer() {
        val payloads = server.requests().filter { it.isPayloadDownload || it.isPayloadUpload || it.isManifestWrite }
        assertEquals("Reading history repair must reuse installed books", emptyList<RecordedRequest>(), payloads)
    }

    private suspend fun seedNovel(phone: SyncDevice, tablet: SyncDevice) {
        phone.importNovel()
        assertClean(phone.sync())
        assertClean(tablet.sync())
        assertClean(phone.sync())
        assertClean(tablet.sync())
    }

    private fun day(
        date: String,
        seconds: Double,
        characters: Int,
        device: DeviceIdentity? = PHONE,
        hour: Int = 10,
        modified: Long = 1,
        exactTiming: Boolean = true,
    ) = ReadingStatistics(
        title = SyncCorpus.NOVEL_TITLE, dateKey = date,
        readingTime = seconds, charactersRead = characters, lastStatisticModified = modified,
        deviceId = device?.id, deviceName = device?.name,
        readingTimeByHour = if (exactTiming) mapOf("${date}T${hour.toString().padStart(2, '0')}:00" to seconds) else emptyMap(),
    )

    private val phoneDays get() = listOf(
        day("2026-09-28", 600.0, 1_000),
        day("2026-09-29", 600.0, 1_000),
        // Calendar totals remain on the 30th; the 3 a.m. study-day reset credits the 29th.
        day("2026-09-30", 1_200.0, 2_000, hour = 1),
    )

    private data class VisibleHistory(
        val totalSeconds: Double,
        val todaySeconds: Double,
        val totalCharacters: Int,
        val todayCharacters: Int,
        val daily: List<DailyReading>,
        val devices: List<DeviceReadingSummary>,
        val currentStreak: Int,
        val longestStreak: Int,
        val estimatedStreak: Boolean,
        val minutes: List<TrendPoint>,
        val characters: List<TrendPoint>,
    )

    private suspend fun history(device: SyncDevice): VisibleHistory {
        val overview = loadReadingStatisticsOverview(device.repo, TODAY.toString(), streakResetHour = 3)
        val streak = computeReadingStreak(overview.streakDaily, minimumSeconds = 600.0, today = TODAY)
        return VisibleHistory(
            overview.totalSeconds, overview.todaySeconds, overview.totalCharacters, overview.todayCharacters,
            overview.daily, overview.devices, streak.currentDays, streak.longestDays,
            overview.hasEstimatedStreakHistory,
            rollingAverageSeries(overview.daily.associate { LocalDate.parse(it.dateKey) to it.seconds / 60.0 }, TODAY, 30),
            rollingAverageSeries(overview.daily.associate { LocalDate.parse(it.dateKey) to it.characters.toDouble() }, TODAY, 30),
        )
    }

    private fun assertPhoneHistory(history: VisibleHistory) {
        assertEquals(2_400.0, history.totalSeconds, 0.0)
        // Every day is a reading day from 3 a.m., as the streak counts it: the 1 a.m. reading
        // recorded on the 30th belongs to the 29th, so the 30th (today) is still empty and the
        // 29th carries both records, which also proves the 30th's record arrived.
        assertEquals(0.0, history.todaySeconds, 0.0)
        assertEquals(4_000, history.totalCharacters)
        assertEquals(0, history.todayCharacters)
        assertEquals(
            listOf(DailyReading("2026-09-29", 1_800.0, 3_000), DailyReading("2026-09-28", 600.0, 1_000)),
            history.daily,
        )
        assertEquals(2, history.currentStreak)
        assertEquals(2, history.longestStreak)
        assertFalse(history.estimatedStreak)
        assertEquals(listOf(PHONE.id), history.devices.map { it.deviceId })
        assertEquals(0.0, history.minutes.last().value, 0.0)
        assertEquals(40.0 / 3, history.minutes.last().rollingAverage, 0.0001)
        assertEquals(0.0, history.characters.last().value, 0.0)
        assertEquals(4_000.0 / 3, history.characters.last().rollingAverage, 0.0001)
    }

    @Test(timeout = 60_000)
    fun installedTabletRepairsZeroStatisticsDespiteAcknowledgedMapsAndSurvivesRestart() = runBlocking {
        val phone = device("phone", phoneEngine)
        var tablet = device("tablet", tabletEngine)
        seedNovel(phone, tablet)
        phone.repo.saveStatistics(phone.book(SyncCorpus.NOVEL_SYNC_ID).root, phoneDays)
        assertClean(phone.sync())
        assertClean(tablet.sync())
        assertPhoneHistory(history(tablet))

        // Model both broken installed states: acknowledged remote maps with missing history,
        // and the same state without any per-book exchange marker from an older build.
        for (removeExchangeMarker in listOf(false, true)) {
            val root = tablet.book(SyncCorpus.NOVEL_SYNC_ID).root
            assertTrue(root.resolve("statistics.json").delete())
            if (removeExchangeMarker) assertTrue(root.resolve(STATISTICS_SYNC_STATE_FILENAME).delete())
            assertEquals(0.0, history(tablet).totalSeconds, 0.0)
            assertEquals(0, history(tablet).currentStreak)
            assertTrue(history(tablet).minutes.all { it.value == 0.0 })
            // A failed GET must not certify the already-listed history as applied. The
            // retry below has no intervening phone write or changed remote ETag to help it.
            server.failNext("/v1/kv/${statisticsKey(SyncCorpus.NOVEL_SYNC_ID)}", status = 500, method = "GET")
            assertTrue("failed history recovery must be visible", tablet.sync().errors.isNotEmpty())
            assertEquals(0.0, history(tablet).totalSeconds, 0.0)
            assertPhoneHistory(history(phone))
            val before = tablet.repo.statisticsChanges.value
            server.clearRequests()

            assertClean(tablet.sync())

            assertTrue("a successful pull must notify the visible Statistics screen", tablet.repo.statisticsChanges.value > before)
            assertPhoneHistory(history(tablet))
            assertEquals(history(phone), history(tablet))
            assertNoPayloadTransfer()
        }

        tablet.close()
        tablet = device("tablet", tabletEngine)
        assertPhoneHistory(history(tablet))
        // Stabilize each peer's bookmark-map acknowledgement before asserting the idle budget.
        assertClean(phone.sync())
        assertClean(tablet.sync())
        val settled = history(tablet)
        repeat(3) {
            server.clearRequests()
            assertClean(tablet.sync())
            assertEquals("A restarted converged install needs one change check", listOf(true), server.requests().map { it.isListing })
            assertEquals(settled, history(tablet))
        }
    }

    @Test(timeout = 60_000)
    fun missedReaderPushAndFailedManualUploadRemainRecoverableWithoutReadingAgain() = runBlocking {
        val phone = device("phone", phoneEngine)
        val tablet = device("tablet", tabletEngine)
        seedNovel(phone, tablet)
        val root = phone.book(SyncCorpus.NOVEL_SYNC_ID).root
        phone.repo.saveStatistics(root, phoneDays)
        val path = "/v1/kv/${statisticsKey(SyncCorpus.NOVEL_SYNC_ID)}"
        server.failNext(path, status = 500, method = "PUT")
        val failedPush = runCatching {
            phone.pusher.pushStatistics(root, SyncCorpus.NOVEL_TITLE, phone.settings, SyncCorpus.NOVEL_SYNC_ID)
        }.exceptionOrNull()
        assertNotNull("the reader's upload actually failed over HTTP", failedPush)
        assertPhoneHistory(history(phone))
        assertClean(tablet.sync())
        assertEquals(0.0, history(tablet).totalSeconds, 0.0)

        server.failNext(path, status = 500, method = "PUT")
        assertTrue("manual failure must be reported, not acknowledged as synchronized", phone.sync().errors.isNotEmpty())
        assertPhoneHistory(history(phone))
        server.clearRequests()
        assertClean(phone.sync())
        assertClean(tablet.sync())
        assertPhoneHistory(history(tablet))
        assertEquals(history(phone), history(tablet))
        assertNoPayloadTransfer()
    }

    @Test(timeout = 60_000)
    fun firstLegacyDownloadIsVisibleWithoutClaimingItOrDoublingCalendarTotals() = runBlocking {
        val legacyPhone = device("phone", phoneEngine, identity = null)
        var tablet = device("tablet", tabletEngine)
        seedNovel(legacyPhone, tablet)
        val legacy = day("2026-09-29", 600.0, 1_000, device = null, exactTiming = false)
        legacyPhone.repo.saveStatistics(legacyPhone.book(SyncCorpus.NOVEL_SYNC_ID).root, listOf(legacy))
        assertClean(legacyPhone.sync())
        val refreshed = async(start = CoroutineStart.UNDISPATCHED) {
            tablet.repo.statisticsChanges.drop(1).first()
            history(tablet)
        }

        assertClean(tablet.sync())
        val observed = withTimeout(5_000) { refreshed.await() }
        assertEquals(600.0, observed.totalSeconds, 0.0)
        assertEquals(1_000, observed.totalCharacters)
        assertEquals(2, observed.currentStreak)
        assertTrue(observed.estimatedStreak)
        assertEquals(listOf<String?>(null), observed.devices.map { it.deviceId })
        assertEquals(history(legacyPhone), history(tablet))

        tablet.close()
        tablet = device("tablet", tabletEngine)
        repeat(2) { assertClean(tablet.sync()); assertClean(legacyPhone.sync()) }
        assertEquals("optimistic streak credit cannot duplicate calendar totals after restart", observed, history(tablet))
        assertEquals(observed, history(legacyPhone))
    }

    @Test(timeout = 60_000)
    fun simultaneousReaderHistoriesConvergeAfterBothDevicesFetchedTheSameRemoteSnapshot() = runBlocking {
        val key = statisticsKey(SyncCorpus.NOVEL_SYNC_ID)
        val gate = StatisticsReadRendezvous(key)
        val phone = device("phone", phoneEngine, decorate = gate::decorate)
        val tablet = device("tablet", tabletEngine, decorate = gate::decorate)
        seedNovel(phone, tablet)
        phone.repo.saveStatistics(phone.book(SyncCorpus.NOVEL_SYNC_ID).root,
            listOf(day(TODAY.toString(), 600.0, 1_000, PHONE)))
        tablet.repo.saveStatistics(tablet.book(SyncCorpus.NOVEL_SYNC_ID).root,
            listOf(day(TODAY.toString(), 900.0, 1_500, TABLET)))
        server.clearRequests()
        gate.armed.set(true)
        val phoneSync = async { phone.sync() }
        val tabletSync = async { tablet.sync() }
        try {
            withTimeout(5_000) { gate.bothRead.await() }
        } finally {
            gate.release.complete(Unit)
        }
        assertClean(phoneSync.await())
        assertClean(tabletSync.await())
        assertEquals("both real GET snapshots were held before either upload", 2, gate.captured.get())

        // Concurrent last-writer KV PUTs may temporarily omit one device, but each device's
        // durable local bucket and the next change check must restore the union without loss.
        repeat(2) { assertClean(phone.sync()); assertClean(tablet.sync()) }
        val expected = history(phone)
        assertEquals(expected, history(tablet))
        assertEquals(1_500.0, expected.totalSeconds, 0.0)
        assertEquals(2_500, expected.totalCharacters)
        assertEquals(1, expected.currentStreak)
        assertEquals(mapOf("phone" to 600.0, "tablet" to 900.0), expected.devices.associate { it.deviceId to it.totalSeconds })
        assertEquals(25.0, expected.minutes.last().value, 0.0)
        assertNoPayloadTransfer()

        phone.repo.saveStatistics(phone.book(SyncCorpus.NOVEL_SYNC_ID).root,
            listOf(day(TODAY.toString(), 1_200.0, 2_000, PHONE, modified = 2)))
        assertClean(phone.sync())
        assertClean(tablet.sync())
        assertEquals(history(phone), history(tablet))
        assertEquals("a newer phone session replaces only the phone bucket", 2_100.0, history(tablet).totalSeconds, 0.0)
    }

    @Test(timeout = 60_000)
    fun mangaHistoryRepairRestoresTimePagesAndOcrCharactersTogether() = runBlocking {
        val phone = device("phone", phoneEngine)
        val tablet = device("tablet", tabletEngine)
        val root = phone.importManga()
        phone.repo.saveStatistics(root, listOf(day(TODAY.toString(), 600.0, 6)))
        phone.repo.saveMangaTextStatistics(root,
            listOf(MangaTextStatistic(TODAY.toString(), 1_234, 1, PHONE.id, PHONE.name)))
        assertClean(phone.sync())
        assertClean(tablet.sync())
        val installed = tablet.book(SyncCorpus.MANGA_SYNC_ID).root
        assertTrue(installed.resolve("statistics.json").delete())
        assertTrue(installed.resolve("manga_statistics.json").delete())
        assertEquals(0.0, history(tablet).totalSeconds, 0.0)
        server.clearRequests()

        assertClean(tablet.sync())

        val overview = loadReadingStatisticsOverview(tablet.repo, TODAY.toString(), streakResetHour = 3)
        assertEquals(600.0, overview.totalSeconds, 0.0)
        assertEquals(1_234, overview.totalCharacters)
        assertEquals(6, overview.books.single().pagesRead)
        assertEquals(6, overview.devices.single().pagesRead)
        assertEquals(1, computeReadingStreak(overview.streakDaily, 600.0, TODAY).currentDays)
        assertEquals(history(phone), history(tablet))
        assertNoPayloadTransfer()
    }

    /** Gates real, completed HTTP reads only; it never supplies a fabricated response. */
    private class StatisticsReadRendezvous(private val key: String) {
        val armed = AtomicBoolean(false)
        val captured = AtomicInteger()
        val bothRead = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        fun decorate(real: HttpSyncKvTransport): HttpSyncKvTransport = object : HttpSyncKvTransport by real {
            override suspend fun getBounded(key: String, maxBytes: Int): HttpSyncKvFetched? {
                val fetched = real.getBounded(key, maxBytes)
                if (key == this@StatisticsReadRendezvous.key && armed.get()) {
                    val count = captured.incrementAndGet()
                    if (count <= 2) {
                        if (count == 2) { armed.set(false); bothRead.complete(Unit) }
                        release.await()
                    }
                }
                return fetched
            }
        }
    }

    @Test
    fun aCleanSyncRemovesDownloadsNoSyncCameBackForAndKeepsRecentOnes() = runBlocking {
        val phone = device("phone", phoneEngine)
        val filesDir = phone.repo.booksDirectory.parentFile!!
        val now = System.currentTimeMillis()
        fun leftover(path: String, ageDays: Long): File = filesDir.resolve(path).apply {
            mkdirs()
            resolve("archive.zip").writeBytes(ByteArray(8))
            val stamp = now - java.util.concurrent.TimeUnit.DAYS.toMillis(ageDays)
            walkTopDown().forEach { it.setLastModified(stamp) }
        }
        val abandoned = leftover(".http-sync-downloads/deleted-elsewhere", ageDays = 8)
        val recent = leftover(".http-sync-downloads/still-resuming", ageDays = 2)
        val stranded = leftover(".http-sync-import-killed", ageDays = 2)

        // A first sync bootstraps through the engine.
        assertClean(phone.sync())
        assertFalse(abandoned.exists())
        assertFalse(stranded.exists())
        assertTrue(recent.exists())

        // An already up-to-date library skips the engine, and still clears what nothing will resume.
        val replaced = leftover(".http-sync-downloads/old-account", ageDays = 9)
        assertClean(phone.sync())
        assertFalse(replaced.exists())
        assertTrue(recent.exists())
    }
}
