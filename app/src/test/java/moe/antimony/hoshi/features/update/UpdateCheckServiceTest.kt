package moe.antimony.hoshi.features.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateCheckServiceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val update = AvailableUpdate(
        versionName = "0.3.5",
        releaseUrl = "https://example.com/releases/tag/v0.3.5",
        assetName = "Hoshi-Reader-v0.3.5.apk",
        downloadUrl = "https://example.com/Hoshi-Reader-v0.3.5.apk",
        sha256 = "7977f9e95adec03fce35ef0640fdd2fe662c6521d625dc12242df5b66fb2254b",
    )

    @Test
    fun checkRecordsAvailableUpdateWithoutStartingDownload() = runBlocking {
        updateStore().use { store ->
            val downloads = FakeUpdateDownloadController()
            val service = service(downloadController = downloads, updateStore = store.store)

            val outcome = service.check()

            assertTrue(outcome is UpdateCheckOutcome.Available)
            assertEquals(0, downloads.startedDownloads)
            assertEquals(UpdateDownloadRecordStatus.Available, store.store.load()?.status)
        }
    }

    @Test
    fun skippedUpdateDoesNotSurfaceAutomaticallyButManualChecksCanShowIt() = runBlocking {
        updateStore().use { store ->
            store.store.skip(update)
            val service = service(downloadController = FakeUpdateDownloadController(), updateStore = store.store)

            val automatic = service.check()
            val manual = service.check(ignoreSkipped = true)

            assertTrue(automatic is UpdateCheckOutcome.Skipped)
            assertTrue(manual is UpdateCheckOutcome.Available)
            assertEquals(UpdateDownloadRecordStatus.Skipped, store.store.load()?.status)
        }
    }

    @Test
    fun newerReleaseReplacesOlderAvailableSkippedAndDownloadedRecords() = runBlocking {
        updateStore().use { handle ->
            val older = update.copy(versionName = "0.3.4", assetName = "Hoshi-Reader-v0.3.4.apk")
            val service = service(FakeUpdateDownloadController(), handle.store)
            for (status in listOf(UpdateDownloadRecordStatus.Available, UpdateDownloadRecordStatus.Skipped, UpdateDownloadRecordStatus.Downloaded)) {
                when (status) {
                    UpdateDownloadRecordStatus.Skipped -> handle.store.skip(older)
                    UpdateDownloadRecordStatus.Downloaded -> {
                        handle.store.saveDownloading(older, "older.apk", 42, older.downloadUrl)
                        handle.store.updateTransfer(requireNotNull(handle.store.load()), UpdateTransferSnapshot(UpdateDownloadRecordStatus.Downloaded))
                    }
                    else -> handle.store.saveAvailable(older)
                }

                assertEquals(UpdateCheckOutcome.Available(update), service.check())
                val record = requireNotNull(handle.store.load())
                assertTrue(record.matches(update))
                assertEquals(UpdateDownloadRecordStatus.Available, record.status)
            }
        }
    }

    @Test
    fun newerReleaseCheckPreservesOlderActiveTransfer() = runBlocking {
        updateStore().use { handle ->
            val older = update.copy(versionName = "0.3.4", assetName = "Hoshi-Reader-v0.3.4.apk")
            val service = service(FakeUpdateDownloadController(), handle.store)
            for (status in listOf(UpdateDownloadRecordStatus.Queued, UpdateDownloadRecordStatus.Downloading, UpdateDownloadRecordStatus.Paused)) {
                handle.store.saveDownloading(older, "older.apk", 42, older.downloadUrl)
                handle.store.updateTransfer(requireNotNull(handle.store.load()), UpdateTransferSnapshot(status))
                val expected = handle.store.load()

                assertEquals(UpdateCheckOutcome.Available(update), service.check())
                assertEquals(expected, handle.store.load())
            }
        }
    }

    @Test
    fun downloadStartedAfterInitialStatusQueryIsRequeriedAndPreserved() = runBlocking {
        updateStore().use { handle ->
            var queries = 0
            val downloads = FakeUpdateDownloadController {
                if (++queries == 1) {
                    handle.store.saveDownloading(update, "update.apk", 42, update.downloadUrl)
                    UpdateDownloadStatus.None
                } else UpdateDownloadStatus.Downloading(42)
            }

            assertEquals(UpdateCheckOutcome.DownloadInProgress(update, 42), service(downloads, handle.store).check())
            assertEquals(2, queries)
            assertEquals(42L, handle.store.load()?.downloadId)
            assertEquals(UpdateDownloadRecordStatus.Queued, handle.store.load()?.status)
        }
    }

    @Test
    fun downloadFinishedAfterInitialStatusQueryIsRequeriedAndPreserved() = runBlocking {
        updateStore().use { handle ->
            val file = tempFolder.newFile("update.apk")
            var queries = 0
            val downloads = FakeUpdateDownloadController {
                if (++queries == 1) {
                    handle.store.saveDownloading(update, file.name, 42, update.downloadUrl)
                    handle.store.updateTransfer(requireNotNull(handle.store.load()), UpdateTransferSnapshot(UpdateDownloadRecordStatus.Downloaded))
                    UpdateDownloadStatus.None
                } else UpdateDownloadStatus.Downloaded(file)
            }

            assertEquals(UpdateCheckOutcome.DownloadAlreadyFinished(update, file), service(downloads, handle.store).check())
            assertEquals(2, queries)
            assertEquals(42L, handle.store.load()?.downloadId)
            assertEquals(UpdateDownloadRecordStatus.Downloaded, handle.store.load()?.status)
        }
    }

    private fun service(
        downloadController: UpdateDownloadController,
        updateStore: UpdateDownloadStore,
    ): UpdateCheckService =
        UpdateCheckService(
            currentVersionName = "0.3.4",
            releaseRepository = FakeReleaseRepository(update),
            downloadController = downloadController,
            updateStore = updateStore,
        )

    private fun updateStore(): StoreHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("update-downloads.preferences_pb") },
        )
        return StoreHandle(UpdateDownloadStore(dataStore), scope)
    }

    private class StoreHandle(
        val store: UpdateDownloadStore,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        override fun close() {
            scope.cancel()
        }
    }

    private class FakeReleaseRepository(
        private val update: AvailableUpdate,
    ) : ReleaseUpdateRepository {
        override suspend fun latestRelease(): GitHubRelease =
            GitHubRelease(
                tagName = "v${update.versionName}",
                htmlUrl = update.releaseUrl,
                assets = listOf(
                    GitHubReleaseAsset(
                        name = update.assetName,
                        browserDownloadUrl = update.downloadUrl,
                        digest = update.sha256?.let { "sha256:$it" },
                    ),
                ),
            )
    }

    private class FakeUpdateDownloadController(
        private val query: suspend (AvailableUpdate) -> UpdateDownloadStatus = { UpdateDownloadStatus.None },
    ) : UpdateDownloadController {
        var startedDownloads = 0
            private set

        override suspend fun statusFor(update: AvailableUpdate): UpdateDownloadStatus = query(update)

        override suspend fun enqueue(update: AvailableUpdate): Long {
            startedDownloads += 1
            return 42L
        }
    }
}
