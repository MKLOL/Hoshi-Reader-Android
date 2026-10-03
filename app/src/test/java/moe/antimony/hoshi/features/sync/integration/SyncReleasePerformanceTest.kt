package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.bookContentType
import moe.antimony.hoshi.epub.dayDeviceKey
import moe.antimony.hoshi.features.sync.http.HttpSyncEngineDispatcher
import moe.antimony.hoshi.features.sync.http.HttpSyncFastSync
import moe.antimony.hoshi.features.sync.http.HttpSyncFullCycleRunner
import moe.antimony.hoshi.features.sync.http.ALL_BOOKS_PREFIX
import moe.antimony.hoshi.features.sync.http.HttpSyncKvList
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.mangaStatisticsKey
import moe.antimony.hoshi.features.sync.http.statisticsKey
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Release gates for the installed-library paths that previously stalled and hid reading history. */
class SyncReleasePerformanceTest {
    companion object {
        @JvmField @ClassRule val serverRule = SyncTestServerRule()
    }

    @get:Rule val temp = TemporaryFolder()
    private val server get() = serverRule.server
    private val devices = mutableListOf<SyncDevice>()

    @Before fun resetServer() = server.reset()
    @After fun closeDevices() = devices.forEach { it.close() }

    private fun device(name: String, files: File = temp.newFolder(), installationId: String = UUID.randomUUID().toString()) =
        SyncDevice(name, SyncEngine.V3, files, server, installationId = installationId).also { devices += it }

    private fun assertClean(outcome: SyncOutcome) {
        assertTrue("sync errors: ${outcome.errors}", outcome.errors.isEmpty())
    }

    private fun readingHistory(title: String) = (0 until 31).map { day ->
        ReadingStatistics(
            title, LocalDate.of(2026, 8, 1).plusDays(day.toLong()).toString(),
            charactersRead = 100 + day, readingTime = 1_200.0, lastStatisticModified = day.toLong() + 1,
            deviceId = "phone", readingTimeByHour = mapOf("01" to 300.0, "19" to 900.0),
        )
    }

    @Test(timeout = 120_000)
    fun installedLibrariesKeepConstantRequestBudgetsWithHistoryAfterRestartAndRemoteEdits() = runBlocking {
        // One and forty books must have the same request budget. Both formats carry real
        // content; the larger library also has 1,240 daily histories plus manga OCR counts.
        for (count in listOf(1, 40)) {
            server.reset()
            val phone = device("phone-$count")
            val tabletFiles = temp.newFolder()
            val tabletId = UUID.randomUUID().toString()
            val tablet = device("tablet-$count", tabletFiles, tabletId)
            for (number in 0 until count) {
                val title = "Release budget book %03d".format(number)
                val root = if (number % 2 == 0) SyncCorpus.importManga(phone.repo, title, pageCount = 1)
                    else SyncCorpus.importNovel(phone.repo, title)
                phone.repo.saveStatistics(root, readingHistory(title))
                if (bookContentType(root) == ContentType.Mokuro) {
                    phone.repo.saveMangaTextStatistics(root, readingHistory(title).map {
                        MangaTextStatistic(it.dateKey, 2_000, it.lastStatisticModified, deviceId = "phone")
                    })
                }
            }
            assertClean(phone.sync())
            val initial = tablet.sync()
            assertClean(initial)
            assertEquals(count, initial.downloadedPayloads)
            assertClean(phone.sync())
            assertClean(tablet.sync())
            assertEquals(count, tablet.books().size)
            for (book in tablet.books()) {
                assertEquals(readingHistory(book.metadata.title!!), tablet.repo.loadStatistics(book.root))
                if (bookContentType(book.root) == ContentType.Mokuro) {
                    assertEquals(31, tablet.repo.loadMangaTextStatistics(book.root).size)
                }
            }

            repeat(3) {
                server.clearRequests()
                assertClean(tablet.sync())
                val requests = server.requests()
                assertEquals("$count installed books need one request, iteration $it: $requests", 1, requests.size)
                assertTrue(requests.single().isListing)
                assertTrue("Restartable index must use its delta watermark", requests.single().path.contains("since="))
            }

            tablet.close()
            val restarted = device("tablet-restarted-$count", tabletFiles, tabletId)
            server.clearRequests()
            assertClean(restarted.sync())
            val restartRequests = server.requests()
            assertEquals("Restart must reuse the verified index and histories: $restartRequests", 1, restartRequests.size)
            assertTrue(restartRequests.single().isListing)

            val changed = phone.books().single { it.metadata.title == "Release budget book 000" }
            val syncId = changed.metadata.syncId!!
            val latestReading = phone.repo.loadStatistics(changed.root).last().copy(
                readingTime = 1_500.0, lastStatisticModified = 1_000,
                readingTimeByHour = mapOf("01" to 300.0, "19" to 1_200.0),
            )
            phone.repo.saveStatistics(changed.root, listOf(latestReading))
            phone.repo.saveMangaTextStatistics(changed.root, listOf(
                MangaTextStatistic(latestReading.dateKey, 2_500, 1_000, deviceId = "phone"),
            ))
            assertClean(phone.sync())
            server.clearRequests()
            val pull = restarted.sync()
            assertClean(pull)
            assertEquals(0, pull.transferredPayloads)
            val requests = server.requests()
            val bookReads = requests.filter { it.method == "GET" && it.path.startsWith("/v1/kv/books/") }
            assertEquals("Only the changed histories may be downloaded; manifests and books are unchanged", setOf(
                "/v1/kv/${statisticsKey(syncId)}", "/v1/kv/${mangaStatisticsKey(syncId)}",
            ), bookReads.map { it.path }.toSet())
            assertEquals("Each history is read once", 2, bookReads.size)
            assertTrue("$count books must have bounded history-only work: $requests", requests.size <= 7)
            assertFalse(requests.any { it.isPayloadDownload || it.isPayloadUpload })
            val copy = restarted.book(syncId)
            assertEquals(
                phone.repo.loadStatistics(changed.root).associateBy { dayDeviceKey(it.dateKey, it.deviceId) },
                restarted.repo.loadStatistics(copy.root).associateBy { dayDeviceKey(it.dateKey, it.deviceId) },
            )
            assertEquals(
                phone.repo.loadMangaTextStatistics(changed.root).associateBy { dayDeviceKey(it.dateKey, it.deviceId) },
                restarted.repo.loadMangaTextStatistics(copy.root).associateBy { dayDeviceKey(it.dateKey, it.deviceId) },
            )
            server.clearRequests()
            assertClean(restarted.sync())
            assertEquals("One poll after applying changed histories", listOf(true), server.requests().map { it.isListing })
            phone.close()
            restarted.close()
        }
    }

    @Test(timeout = 30_000)
    fun manualSyncTakesNewStatisticsWithoutWaitingBehindAFrozenBackgroundPass() = runBlocking {
        val phone = device("phone")
        val tablet = device("tablet")
        val title = "Release delayed history"
        val root = SyncCorpus.importManga(phone.repo, title, pageCount = 1)
        val old = readingHistory(title).last()
        phone.repo.saveStatistics(root, listOf(old))
        assertClean(phone.sync())
        assertClean(tablet.sync())
        assertClean(phone.sync())
        assertClean(tablet.sync())
        val syncId = phone.repo.loadMetadata(root)!!.syncId!!
        val tabletRoot = tablet.book(syncId).root
        val frozen = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gate = AtomicBoolean(true)
        val client = server.client()
        val delayed = object : HttpSyncKvTransport by client {
            override suspend fun list(prefix: String?, since: String?, cursor: String?, limit: Int?): HttpSyncKvList {
                val response = client.list(prefix, since, cursor, limit)
                if (prefix == ALL_BOOKS_PREFIX && gate.compareAndSet(true, false)) {
                    // Freeze a real HTTP listing of a background V3 pass (a long book download).
                    frozen.complete(Unit)
                    release.await()
                }
                return response
            }
        }
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val runner = HttpSyncFullCycleRunner(appScope)
        try {
            server.clearRequests()
            val background = async {
                runner.run { report ->
                    HttpSyncEngineDispatcher.syncOnce(tablet.reconciler, tablet.v3, tablet.settings, delayed, report)
                }
            }
            frozen.await()
            val newer = old.copy(readingTime = 1_800.0, lastStatisticModified = 2_000,
                readingTimeByHour = mapOf("01" to 300.0, "19" to 1_500.0))
            phone.repo.saveStatistics(root, listOf(newer))
            assertClean(phone.sync())
            // Reading history has its own lane: the frozen pass can neither hold the tap up nor
            // stand in for it with its old snapshot.
            val result = HttpSyncFastSync(tablet.batchState, runner) { delayed }.syncNow(tablet.settings) { settings, transport, report ->
                HttpSyncEngineDispatcher.syncOnce(tablet.reconciler, tablet.v3, settings, transport, report)
            }
            assertTrue(result.errors.toString(), result.errors.isEmpty())
            assertEquals(listOf(newer), tablet.repo.loadStatistics(tabletRoot))
            assertEquals(1, result.downloadedStatistics)
            assertFalse("the background pass is still frozen", background.isCompleted)
            release.complete(Unit)
            background.await()
            assertFalse("Delayed statistics cannot cause book transfers", server.requests().any { it.isPayloadDownload || it.isPayloadUpload })
            // The released pass ran on its stale snapshot without publishing the maps; one
            // sync settles that, after which a converged install costs one change check.
            assertClean(tablet.sync())
            server.clearRequests()
            assertClean(tablet.sync())
            assertEquals(listOf(true), server.requests().map { it.isListing })
        } finally {
            release.complete(Unit)
            appScope.cancel()
        }
    }
}
