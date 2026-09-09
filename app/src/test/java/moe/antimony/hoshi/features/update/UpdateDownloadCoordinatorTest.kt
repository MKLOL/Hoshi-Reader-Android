package moe.antimony.hoshi.features.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadCoordinatorTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = "verified APK fixture".toByteArray()
    private val update = AvailableUpdate(
        versionName = "0.11.8", releaseUrl = "https://github.com/example/app/releases/latest",
        assetName = "update.apk", downloadUrl = "https://github.com/example/app/update.apk",
        fallbackDownloadUrls = listOf("https://mirror.example/update.apk"),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
    )

    @Test fun pausedAndFailedTransfersReplaceHistoricalDownloadStartedMessage() = runBlocking {
        fixture().use { f ->
            val id = f.manager.enqueue(update)
            val started = AboutUpdateCheckState.Result(UpdateCheckOutcome.DownloadStarted(update, id))
            assertEquals(UpdateDownloadRecordStatus.Queued, f.store.load()?.status)
            f.backend.rows[id] = UpdateTransferSnapshot(UpdateDownloadRecordStatus.Paused, pauseReason = UpdateDownloadPauseReason.Network)
            val paused = f.manager.refresh()
            assertEquals(UiText.Resource(R.string.about_update_waiting_network), aboutUpdateStatus(started, paused))
            f.backend.rows[id] = UpdateTransferSnapshot(UpdateDownloadRecordStatus.Failed)
            val failed = f.manager.refresh()
            assertEquals(UiText.Resource(R.string.about_update_last_download_failed), aboutUpdateStatus(started, failed))
            assertEquals(UpdateDownloadStatus.None, f.manager.statusFor(update))
        }
    }

    @Test fun runningTransferShowsActualByteProgress() = runBlocking {
        fixture().use { f ->
            val id = f.manager.enqueue(update)
            f.backend.rows[id] = UpdateTransferSnapshot(UpdateDownloadRecordStatus.Downloading, 50, 200)
            val record = f.manager.refresh()
            assertEquals(UiText.Resource(R.string.about_update_progress_format, 25), aboutUpdateStatus(AboutUpdateCheckState.Idle, record))
        }
    }

    @Test fun missingSystemDownloadBecomesRetryableInsteadOfStayingDownloading() = runBlocking {
        fixture().use { f ->
            val id = f.manager.enqueue(update)
            f.backend.rows.remove(id)
            assertEquals(UpdateDownloadRecordStatus.Failed, f.manager.refresh()?.status)
            assertEquals(UpdateDownloadStatus.None, f.manager.statusFor(update))
            assertNotEquals(id, f.manager.enqueue(update))
        }
    }

    @Test fun completionIsReconciledAndVerifiedWithoutABroadcast() = runBlocking {
        fixture().use { f ->
            val id = f.manager.enqueue(update)
            f.backend.targets.getValue(id).writeBytes(bytes)
            f.backend.rows[id] = UpdateTransferSnapshot(UpdateDownloadRecordStatus.Downloaded, bytes.size.toLong(), bytes.size.toLong())
            val completed = f.manager.refresh()
            assertEquals(UpdateDownloadRecordStatus.Downloaded, completed?.status)
            val started = AboutUpdateCheckState.Result(UpdateCheckOutcome.DownloadStarted(update, id))
            assertEquals(UiText.Resource(R.string.about_update_downloaded_format, update.versionName), aboutUpdateStatus(started, completed))
            assertTrue(f.manager.statusFor(update) is UpdateDownloadStatus.Downloaded)
            f.backend.targets.getValue(id).delete()
            assertEquals(UpdateDownloadRecordStatus.Failed, f.manager.refresh()?.status)
            assertEquals(UpdateDownloadStatus.None, f.manager.statusFor(update))
        }
    }

    @Test fun corruptOrEmptyCompletedFilesNeverBecomeInstallable() = runBlocking {
        fixture().use { f ->
            for (invalid in listOf(byteArrayOf(), "corrupt".toByteArray())) {
                val id = f.manager.enqueue(update)
                f.backend.targets.getValue(id).writeBytes(invalid)
                f.backend.rows[id] = UpdateTransferSnapshot(UpdateDownloadRecordStatus.Downloaded)
                assertEquals(UpdateDownloadRecordStatus.Failed, f.manager.refresh()?.status)
                assertFalse(f.backend.targets.getValue(id).exists())
                assertTrue("system row of the rejected file is removed", id in f.backend.removed)
            }
        }
    }

    @Test fun retryStopsOldWriterBeforeReusingFileAndIgnoresOldCompletion() = runBlocking {
        fixture().use { f ->
            val first = f.manager.enqueue(update)
            f.backend.targets.getValue(first).writeBytes("partial".toByteArray())
            val second = f.manager.retry(update)
            assertNotEquals(first, second)
            assertEquals(listOf(first), f.backend.removed)
            assertEquals(update.fallbackDownloadUrls.single(), f.backend.urls.getValue(second))
            f.backend.targets.getValue(second).writeBytes(bytes)
            f.backend.rows[first] = UpdateTransferSnapshot(UpdateDownloadRecordStatus.Downloaded)
            f.manager.refresh(first)
            assertEquals(second, f.store.load()?.downloadId)
            assertEquals(UpdateDownloadRecordStatus.Queued, f.store.load()?.status)
            assertArrayEquals(bytes, f.backend.targets.getValue(second).readBytes())
            f.backend.rows.remove(first)
            val restored = requireNotNull(f.store.load()?.toAvailableUpdate())
            val third = f.manager.retry(restored)
            assertEquals(update.downloadUrl, f.backend.urls.getValue(third))
        }
    }

    @Test fun concurrentDownloadTapsCreateOnlyOneSystemRequest() = runBlocking {
        fixture().use { f ->
            val ids = List(20) { async(Dispatchers.Default) { f.manager.enqueue(update) } }.awaitAll()
            assertEquals(1, ids.distinct().size)
            assertEquals(1, f.backend.targets.size)
        }
    }

    @Test fun restoredRetriesVisitEveryMirrorBeforeRepeating() = runBlocking {
        fixture().use { f ->
            val multipleMirrors = update.copy(
                fallbackDownloadUrls = update.fallbackDownloadUrls + "https://another-mirror.example/update.apk",
            )
            val candidates = multipleMirrors.downloadUrlCandidates()
            val first = f.manager.enqueue(multipleMirrors)
            val attempted = mutableListOf(f.backend.urls.getValue(first))
            repeat(candidates.size * 2 - 1) {
                val restored = requireNotNull(f.store.load()?.toAvailableUpdate())
                val id = f.manager.retry(restored)
                attempted += f.backend.urls.getValue(id)
            }
            assertEquals(candidates + candidates, attempted)
        }
    }

    @Test fun cancellationRemovesPartialAndRestoresDownloadAction() = runBlocking {
        fixture().use { f ->
            val id = f.manager.enqueue(update)
            f.backend.targets.getValue(id).writeBytes(bytes)
            f.manager.cancel(id)
            assertEquals(UpdateDownloadRecordStatus.Available, f.store.load()?.status)
            assertNull(f.store.load()?.downloadId)
            assertFalse(f.backend.targets.getValue(id).exists())
            f.manager.refresh(id)
            assertEquals(UpdateDownloadRecordStatus.Available, f.store.load()?.status)
        }
    }

    @Test fun delayedAvailabilityCheckCannotEraseNewDownload() = runBlocking {
        fixture().use { f ->
            val before = f.store.load()
            val id = f.manager.enqueue(update)
            f.store.saveAvailableIfUnchanged(update, before)
            assertEquals(id, f.store.load()?.downloadId)
        }
    }

    @Test fun availabilityCheckCannotReplaceAnAlreadyActiveExpectedRecord() = runBlocking {
        fixture().use { f ->
            val id = f.manager.enqueue(update)
            val active = f.store.load()
            f.store.saveAvailableIfUnchanged(update, active)
            assertEquals(id, f.store.load()?.downloadId)
            assertEquals(UpdateDownloadRecordStatus.Queued, f.store.load()?.status)
        }
    }

    @Test fun clearedRecordDoesNotKeepDisplayingOldDownloadOutcome() {
        val started = AboutUpdateCheckState.Result(UpdateCheckOutcome.DownloadStarted(update, 1))
        assertEquals(UiText.Resource(R.string.about_update_check_github), aboutUpdateStatus(started, null))
    }

    @Test fun manualUpgradeDiscardsObsoletePausedRequestWithoutBlockingFutureUpdates() = runBlocking {
        fixture().use { f ->
            val id = f.manager.enqueue(update)
            f.backend.targets.getValue(id).writeBytes("partial".toByteArray())
            f.backend.rows[id] = UpdateTransferSnapshot(UpdateDownloadRecordStatus.Paused)
            f.manager.refresh()
            f.manager.discardInstalledUpdate(update.versionName)
            assertNull(f.store.load())
            assertEquals(listOf(id), f.backend.removed)
            assertFalse(f.backend.targets.getValue(id).exists())
            val next = update.copy(versionName = "0.11.9")
            f.store.saveAvailableIfUnchanged(next, null)
            assertEquals("0.11.9", f.store.load()?.versionName)
        }
    }

    @Test fun startupPreservesDownloadForVersionThatIsNotInstalledYet() = runBlocking {
        fixture().use { f ->
            val id = f.manager.enqueue(update)
            f.manager.discardInstalledUpdate("0.11.7")
            assertEquals(id, f.store.load()?.downloadId)
            assertTrue(f.backend.removed.isEmpty())
        }
    }

    @Test fun cancelAimedAtAnInFlightTransferDoesNotDiscardTheDownloadItRacedWith() = runBlocking {
        fixture().use { f ->
            val id = completeAndVerify(f)
            f.manager.cancel(id)
            assertEquals(UpdateDownloadRecordStatus.Downloaded, f.store.load()?.status)
            assertEquals(id, f.store.load()?.downloadId)
            assertTrue(f.backend.removed.isEmpty())
            assertArrayEquals(bytes, f.backend.targets.getValue(id).readBytes())
            assertTrue(f.manager.statusFor(update) is UpdateDownloadStatus.Downloaded)
        }
    }

    @Test fun cancelWithAStaleIdIsIgnored() = runBlocking {
        fixture().use { f ->
            val id = f.manager.enqueue(update)
            f.manager.cancel(id + 100)
            assertEquals(UpdateDownloadRecordStatus.Queued, f.store.load()?.status)
            assertTrue(f.backend.removed.isEmpty())
        }
    }

    @Test fun retryNeverReplacesAVerifiedDownload() = runBlocking {
        fixture().use { f ->
            val id = completeAndVerify(f)
            assertEquals(id, f.manager.retry(update))
            assertEquals(UpdateDownloadRecordStatus.Downloaded, f.store.load()?.status)
            assertTrue(f.backend.removed.isEmpty())
            assertEquals(1, f.backend.targets.size)
            assertArrayEquals(bytes, f.backend.targets.getValue(id).readBytes())
        }
    }

    @Test fun skipCannotOrphanATransferThatAlreadyStarted() = runBlocking {
        fixture().use { f ->
            val id = f.manager.enqueue(update)
            f.store.skip(update)
            assertEquals(UpdateDownloadRecordStatus.Queued, f.store.load()?.status)
            assertEquals(id, f.store.load()?.downloadId)
            f.backend.rows[id] = UpdateTransferSnapshot(UpdateDownloadRecordStatus.Paused, pauseReason = UpdateDownloadPauseReason.Network)
            f.manager.refresh()
            f.store.skip(update)
            assertEquals(UpdateDownloadRecordStatus.Paused, f.store.load()?.status)
        }
    }

    @Test fun skipCannotDiscardAVerifiedDownloadOfTheSameUpdate() = runBlocking {
        fixture().use { f ->
            val id = completeAndVerify(f)
            f.store.skip(update)
            assertEquals(UpdateDownloadRecordStatus.Downloaded, f.store.load()?.status)
            assertEquals(id, f.store.load()?.downloadId)
        }
    }

    @Test fun skipStillReplacesAnAvailableOrFailedRecord() = runBlocking {
        fixture().use { f ->
            f.store.saveAvailable(update)
            f.store.skip(update)
            assertEquals(UpdateDownloadRecordStatus.Skipped, f.store.load()?.status)
            val id = f.manager.enqueue(update)
            f.backend.rows.remove(id)
            assertEquals(UpdateDownloadRecordStatus.Failed, f.manager.refresh()?.status)
            f.store.skip(update)
            assertEquals(UpdateDownloadRecordStatus.Skipped, f.store.load()?.status)
        }
    }

    @Test fun startupSnapshotReadsThePersistedRecordWithoutTouchingTheSystemDownloadOrTheFile() = runBlocking {
        fixture().use { f ->
            val id = f.manager.enqueue(update)
            f.backend.targets.getValue(id).writeBytes(bytes)
            f.backend.rows[id] = UpdateTransferSnapshot(UpdateDownloadRecordStatus.Downloaded, bytes.size.toLong(), bytes.size.toLong())
            f.backend.queries = 0
            val startup = startup(f)

            val snapshot = startup.snapshot()

            assertEquals(UpdateDownloadRecordStatus.Queued, snapshot?.status)
            assertEquals(0, f.backend.queries)
            assertEquals(UpdateDownloadRecordStatus.Queued, f.store.load()?.status)

            startup.reconcile()

            assertTrue(f.backend.queries > 0)
            assertEquals(UpdateDownloadRecordStatus.Downloaded, f.store.load()?.status)
            assertTrue(f.manager.statusFor(update) is UpdateDownloadStatus.Downloaded)
        }
    }

    @Test fun startupReconcileDiscardsAnAlreadyInstalledUpdateAndSurvivesAFailingStep() = runBlocking {
        fixture().use { f ->
            val id = f.manager.enqueue(update)
            f.backend.rows[id] = UpdateTransferSnapshot(UpdateDownloadRecordStatus.Paused)
            var cleanupCalls = 0
            val startup = UpdateStartup(
                store = f.store,
                currentVersionName = update.versionName,
                discardInstalledUpdate = f.manager::discardInstalledUpdate,
                deleteCurrentVersionApks = { cleanupCalls++; error("cleanup failed") },
                refresh = { f.manager.refresh() },
            )

            startup.reconcile()

            assertEquals(1, cleanupCalls)
            assertNull(f.store.load())
            assertEquals(listOf(id), f.backend.removed)
        }
    }

    private suspend fun completeAndVerify(f: Fixture): Long {
        val id = f.manager.enqueue(update)
        f.backend.targets.getValue(id).writeBytes(bytes)
        f.backend.rows[id] = UpdateTransferSnapshot(UpdateDownloadRecordStatus.Downloaded, bytes.size.toLong(), bytes.size.toLong())
        assertEquals(UpdateDownloadRecordStatus.Downloaded, f.manager.refresh()?.status)
        return id
    }

    private fun startup(f: Fixture) = UpdateStartup(
        store = f.store,
        currentVersionName = "0.11.7",
        discardInstalledUpdate = f.manager::discardInstalledUpdate,
        deleteCurrentVersionApks = {},
        refresh = { f.manager.refresh() },
    )

    private fun fixture(): Fixture {
        val directory = temp.newFolder()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val store = UpdateDownloadStore(PreferenceDataStoreFactory.create(scope = scope) { File(directory, "state.preferences_pb") })
        val backend = FakeBackend()
        return Fixture(store, backend, UpdateDownloadCoordinator(store, backend, File(directory, "downloads")), scope)
    }

    private class Fixture(val store: UpdateDownloadStore, val backend: FakeBackend, val manager: UpdateDownloadCoordinator, val scope: CoroutineScope) : AutoCloseable {
        override fun close() { scope.cancel() }
    }

    private class FakeBackend : UpdateDownloadBackend {
        val rows = mutableMapOf<Long, UpdateTransferSnapshot>()
        val targets = mutableMapOf<Long, File>()
        val urls = mutableMapOf<Long, String>()
        val removed = mutableListOf<Long>()
        var queries = 0
        override fun query(downloadId: Long): UpdateTransferSnapshot? {
            queries++
            return rows[downloadId]
        }
        override fun enqueue(update: AvailableUpdate, url: String, target: File): Long {
            check(rows.isEmpty()) { "Previous writer must be removed before enqueue" }
            check(!target.exists()) { "Previous partial file must be removed" }
            val id = targets.size.toLong() + 1
            rows[id] = UpdateTransferSnapshot(UpdateDownloadRecordStatus.Queued)
            targets[id] = target
            urls[id] = url
            return id
        }
        override fun remove(downloadId: Long) {
            removed += downloadId
            rows.remove(downloadId)
            targets[downloadId]?.delete()
        }
    }
}
