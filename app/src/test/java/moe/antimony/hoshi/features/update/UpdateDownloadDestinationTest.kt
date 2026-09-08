package moe.antimony.hoshi.features.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadDestinationTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = "verified update bytes".toByteArray()
    private val update = AvailableUpdate(
        versionName = "0.11.8",
        releaseUrl = "https://github.com/example/app/releases/latest",
        assetName = "update.apk",
        downloadUrl = "https://github.com/example/app/update.apk",
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) },
    )

    @Test fun delayedOldWriterCleanupCannotDeleteReplacementDownload() = runBlocking {
        fixture().use { fixture ->
            val first = fixture.manager.enqueue(update)
            fixture.backend.targets.getValue(first).writeText("partial old download")
            val second = fixture.manager.retry(update)
            val replacement = fixture.backend.targets.getValue(second)
            assertNotEquals(fixture.backend.targets.getValue(first), replacement)
            assertEquals(replacement.name, fixture.store.load()?.fileName)

            replacement.writeBytes(bytes)
            // DownloadManager may finish canceling its old worker after the new
            // worker has already created its destination file.
            fixture.backend.finishOldWriterCleanup(first)
            assertArrayEquals(bytes, replacement.readBytes())
            fixture.backend.rows[second] = UpdateTransferSnapshot(UpdateDownloadRecordStatus.Downloaded)
            assertEquals(UpdateDownloadRecordStatus.Downloaded, fixture.manager.refresh()?.status)
            assertEquals(UpdateDownloadStatus.Downloaded(replacement), fixture.manager.statusFor(update))
        }
    }

    @Test fun persistedLegacyDestinationStillReconcilesAfterUpgrade() = runBlocking {
        fixture().use { fixture ->
            val legacyFile = File(fixture.downloads, AndroidUpdateDownloadManager.UpdateFileName)
            val id = fixture.backend.enqueue(update, update.downloadUrl, legacyFile)
            fixture.store.saveDownloading(update, legacyFile.name, id, update.downloadUrl)
            legacyFile.writeBytes(bytes)
            fixture.backend.rows[id] = UpdateTransferSnapshot(UpdateDownloadRecordStatus.Downloaded)

            assertEquals(UpdateDownloadRecordStatus.Downloaded, fixture.manager.refresh()?.status)
            assertEquals(UpdateDownloadStatus.Downloaded(legacyFile), fixture.manager.statusFor(update))
            assertTrue(legacyFile.isFile)
        }
    }

    private fun fixture(): Fixture {
        val directory = temp.newFolder()
        val downloads = File(directory, "downloads").apply { mkdirs() }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val store = UpdateDownloadStore(PreferenceDataStoreFactory.create(scope = scope) {
            File(directory, "state.preferences_pb")
        })
        val backend = DelayedCleanupBackend()
        return Fixture(store, backend, downloads, UpdateDownloadCoordinator(store, backend, downloads), scope)
    }

    private class Fixture(
        val store: UpdateDownloadStore,
        val backend: DelayedCleanupBackend,
        val downloads: File,
        val manager: UpdateDownloadCoordinator,
        val scope: CoroutineScope,
    ) : AutoCloseable {
        override fun close() { scope.cancel() }
    }

    private class DelayedCleanupBackend : UpdateDownloadBackend {
        val rows = mutableMapOf<Long, UpdateTransferSnapshot>()
        val targets = mutableMapOf<Long, File>()
        override fun query(downloadId: Long) = rows[downloadId]
        override fun enqueue(update: AvailableUpdate, url: String, target: File): Long {
            val id = targets.size.toLong() + 1
            targets[id] = target
            rows[id] = UpdateTransferSnapshot(UpdateDownloadRecordStatus.Queued)
            return id
        }
        override fun remove(downloadId: Long) {
            rows.remove(downloadId)
            targets[downloadId]?.delete()
        }
        fun finishOldWriterCleanup(downloadId: Long) {
            targets.getValue(downloadId).delete()
        }
    }
}
