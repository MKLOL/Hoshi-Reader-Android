package moe.antimony.hoshi.features.update

import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class UpdateTransferSnapshot(
    val status: UpdateDownloadRecordStatus,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = -1,
    val pauseReason: UpdateDownloadPauseReason? = null,
)

internal interface UpdateDownloadBackend {
    fun query(downloadId: Long): UpdateTransferSnapshot?
    fun enqueue(update: AvailableUpdate, url: String, target: File): Long
    fun remove(downloadId: Long)
}

/** Reconciles persisted state with the system download, including after a missed broadcast. */
internal class UpdateDownloadCoordinator(
    private val store: UpdateDownloadStore,
    private val backend: UpdateDownloadBackend,
    private val directory: File,
) : UpdateDownloadController {
    override suspend fun statusFor(update: AvailableUpdate): UpdateDownloadStatus = withContext(Dispatchers.IO) {
        transferMutex.withLock {
            val record = refreshLocked() ?: return@withLock UpdateDownloadStatus.None
            when {
                !record.matches(update) -> UpdateDownloadStatus.None
                record.status == UpdateDownloadRecordStatus.Downloaded -> UpdateDownloadStatus.Downloaded(file(record))
                record.status.isInFlight && record.downloadId != null -> UpdateDownloadStatus.Downloading(record.downloadId)
                else -> UpdateDownloadStatus.None
            }
        }
    }

    suspend fun refresh(downloadId: Long? = null): UpdateDownloadRecord? = withContext(Dispatchers.IO) {
        transferMutex.withLock {
            if (downloadId != null && store.load()?.downloadId != downloadId) return@withLock store.load()
            refreshLocked()
        }
    }

    private suspend fun refreshLocked(): UpdateDownloadRecord? {
        val record = store.load() ?: return null
        val target = file(record)
        if (record.status == UpdateDownloadRecordStatus.Downloaded) {
            if (!target.isFile || target.length() == 0L) {
                store.updateTransfer(record, UpdateTransferSnapshot(UpdateDownloadRecordStatus.Failed))
            }
        } else if (record.status.isInFlight) {
            var snapshot = record.downloadId?.let(backend::query)
                ?: UpdateTransferSnapshot(UpdateDownloadRecordStatus.Failed)
            if (snapshot.status == UpdateDownloadRecordStatus.Downloaded && !record.downloadIsInstallable(target)) {
                target.delete()
                // Drop the system row too, or its "download complete" notification would keep
                // pointing at a file that no longer exists.
                record.downloadId?.let(backend::remove)
                snapshot = snapshot.copy(status = UpdateDownloadRecordStatus.Failed)
            }
            store.updateTransfer(record, snapshot)
        }
        return store.load()
    }

    override suspend fun enqueue(update: AvailableUpdate): Long = start(update, retry = false)

    suspend fun retry(update: AvailableUpdate): Long = start(update, retry = true)

    private suspend fun start(update: AvailableUpdate, retry: Boolean): Long = withContext(Dispatchers.IO) {
        transferMutex.withLock {
            val previous = refreshLocked()
            if (previous?.matches(update) == true && previous.downloadId != null) {
                // A verified APK is never replaced: a Retry tap can land after the poll above
                // promoted the transfer it was aimed at to Downloaded.
                if (previous.status == UpdateDownloadRecordStatus.Downloaded) return@withLock previous.downloadId
                if (!retry && previous.status.isInFlight) return@withLock previous.downloadId
            }

            val failedUrl = previous?.takeIf { it.matches(update) && (retry || it.status == UpdateDownloadRecordStatus.Failed) }
                ?.downloadUrl
            val url = update.downloadUrlAfterFailed(failedUrl)
            // Removal requests asynchronous writer shutdown. Its final cleanup may still
            // delete the old destination, so every transfer needs a different file name.
            previous?.downloadId?.let(backend::remove)
            val target = File(directory, "Hoshi-Reader-update-${UUID.randomUUID()}.apk")
            directory.mkdirs()
            directory.listFiles { item -> item.isFile && item.extension.equals("apk", ignoreCase = true) }
                ?.forEach { it.delete() }
            val id = backend.enqueue(update, url, target)
            // Navigating away immediately after enqueue must not leave an untracked system download.
            withContext(NonCancellable) {
                try {
                    store.saveDownloading(update, target.name, id, url)
                } catch (error: Exception) {
                    backend.remove(id)
                    throw error
                }
            }
            id
        }
    }

    suspend fun cancel(downloadId: Long) = withContext(Dispatchers.IO) {
        transferMutex.withLock {
            // About offers Cancel for in-flight transfers only, but the tap can land after a
            // refresh promoted the same id to Downloaded. Never discard a verified APK.
            val record = store.load()?.takeIf { it.downloadId == downloadId && it.status.isInFlight }
                ?: return@withLock
            backend.remove(downloadId)
            withContext(NonCancellable) {
                record.toAvailableUpdate()?.let { store.saveAvailable(it) } ?: store.clear()
            }
        }
    }

    suspend fun discardInstalledUpdate(currentVersionName: String) = withContext(Dispatchers.IO) {
        transferMutex.withLock {
            val currentVersion = AppVersion.parse(currentVersionName) ?: return@withLock
            val record = store.load() ?: return@withLock
            val downloadVersion = AppVersion.parse(record.versionName) ?: return@withLock
            if (downloadVersion > currentVersion) return@withLock
            // A manual upgrade may leave a paused request with an incomplete, unparseable APK.
            record.downloadId?.let(backend::remove)
            file(record).delete()
            store.clear(record)
        }
    }

    private fun file(record: UpdateDownloadRecord): File = File(directory, record.fileName)

    private companion object {
        // UI, startup reconciliation, workers and the completion receiver create separate adapters.
        val transferMutex = Mutex()
    }
}

private fun UpdateDownloadRecord.downloadIsInstallable(file: File): Boolean {
    if (!file.isFile || file.length() == 0L) return false
    return try {
        val expected = sha256
        if (expected != null) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
        } else {
            // Unverifiable mirrors remain rejected; only the canonical GitHub origin is trusted.
            val host = downloadUrl?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }
            host == "github.com" || host == "api.github.com" || host == "objects.githubusercontent.com"
        }
    } catch (_: IOException) {
        false
    }
}
