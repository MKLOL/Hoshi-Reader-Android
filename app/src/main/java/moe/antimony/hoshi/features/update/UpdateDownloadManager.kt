package moe.antimony.hoshi.features.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import java.io.File

internal class AndroidUpdateDownloadManager(
    context: Context,
    store: UpdateDownloadStore,
    private val directoryOverride: File? = null,
) : UpdateDownloadController {
    private val appContext = context.applicationContext
    private val coordinator = UpdateDownloadCoordinator(store, AndroidDownloadBackend(appContext), updateDirectory())

    override suspend fun statusFor(update: AvailableUpdate): UpdateDownloadStatus = coordinator.statusFor(update)
    override suspend fun enqueue(update: AvailableUpdate): Long = coordinator.enqueue(update)
    suspend fun retry(update: AvailableUpdate): Long = coordinator.retry(update)
    suspend fun cancel(downloadId: Long) = coordinator.cancel(downloadId)
    suspend fun refresh(downloadId: Long? = null): UpdateDownloadRecord? = coordinator.refresh(downloadId)
    suspend fun discardInstalledUpdate(currentVersionName: String) = coordinator.discardInstalledUpdate(currentVersionName)

    internal fun updateFile(fileName: String): File = File(updateDirectory(), fileName)

    internal fun updateApkFiles(): List<File> = updateDirectory()
        .listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
        ?.toList().orEmpty()

    internal fun updateDirectory(): File =
        directoryOverride ?: appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "downloads")

    companion object {
        const val ApkMimeType = "application/vnd.android.package-archive"
        const val UpdateFileName = "Hoshi-Reader-update.apk"
    }
}

private class AndroidDownloadBackend(private val context: Context) : UpdateDownloadBackend {
    private val manager = context.getSystemService(DownloadManager::class.java)

    override fun enqueue(update: AvailableUpdate, url: String, target: File): Long = manager.enqueue(
        DownloadManager.Request(Uri.parse(url))
            .setTitle("${context.getString(R.string.app_name)} ${update.versionName}")
            .setDescription(context.getString(R.string.update_downloading_notification))
            .setMimeType(AndroidUpdateDownloadManager.ApkMimeType)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target)),
    )

    override fun remove(downloadId: Long) { manager.remove(downloadId) }

    override fun query(downloadId: Long): UpdateTransferSnapshot? {
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return null
            val rawStatus = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val status = when (rawStatus) {
                DownloadManager.STATUS_PENDING -> UpdateDownloadRecordStatus.Queued
                DownloadManager.STATUS_RUNNING -> UpdateDownloadRecordStatus.Downloading
                DownloadManager.STATUS_PAUSED -> UpdateDownloadRecordStatus.Paused
                DownloadManager.STATUS_SUCCESSFUL -> UpdateDownloadRecordStatus.Downloaded
                else -> UpdateDownloadRecordStatus.Failed
            }
            val pauseReason = if (status == UpdateDownloadRecordStatus.Paused) {
                when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))) {
                    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> UpdateDownloadPauseReason.Network
                    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> UpdateDownloadPauseReason.Wifi
                    DownloadManager.PAUSED_WAITING_TO_RETRY -> UpdateDownloadPauseReason.Retry
                    else -> UpdateDownloadPauseReason.Unknown
                }
            } else null
            UpdateTransferSnapshot(
                status = status,
                bytesDownloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                totalBytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                pauseReason = pauseReason,
            )
        }
    }
}

internal class UpdateDownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId < 0) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    val appContext = context.applicationContext
                    AndroidUpdateDownloadManager(appContext, appContext.updateDownloadStore()).refresh(downloadId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
