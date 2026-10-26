package moe.antimony.hoshi.features.dictionary

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.antimony.hoshi.HoshiApplication
import moe.antimony.hoshi.R

/**
 * Keeps the app in the foreground while [DefaultDictionaryDownload] runs, so leaving first-launch
 * setup does not lose the download: Android cuts a background app's network within seconds. The
 * download itself runs in the app owner; this `dataSync` foreground service (the same pattern as
 * [moe.antimony.hoshi.features.ai.AiChatRequestService]) only lives until it ends.
 *
 * It is started only while the app is visible, with `startService`, and promotes itself. A start
 * made with `startForegroundService` obliges the service to call `startForeground` before it
 * stops, so stopping after a refused promotion would crash the app; a plain start has no such
 * obligation, and the visible app may still promote the service it started.
 */
class DefaultDictionaryDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var latestStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        try {
            startForegroundCompat(buildNotification())
        } catch (_: Exception) {
            // Refused (the app already left, or the dataSync time limit is used up): the download
            // runs anyway, without the keep-alive. Safe to stop: this start never asked for one.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        acquireWakeLock()
        watchDownload()
        return START_NOT_STICKY
    }

    private fun watchDownload() {
        if (watcher?.isActive == true) return
        val download = (application as HoshiApplication).appContainer.defaultDictionaryDownload
        watcher = scope.launch {
            download.status.first { it !is DefaultDictionaryDownload.Status.Running }
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Refused when a newer start is still on its way; its onStartCommand watches again.
            stopSelfResult(latestStartId)
        }
    }

    /**
     * Android 15+ allows `dataSync` services a few hours a day; past that the service must stop
     * within seconds or the app crashes. The download carries on without the keep-alive.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        watcher?.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // The screen may go off during a large import; the timeout bounds a hung download.
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.default_dictionary_download_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.default_dictionary_download_notification_title))
            .setContentText(getString(R.string.default_dictionary_download_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x4443
        private const val CHANNEL_ID = "default-dictionary-download"
        private const val WAKE_LOCK_TAG = "hoshi:default-dictionary-download"
        private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L

        /** Called while the app is visible, when the download starts; the service promotes itself. */
        fun start(context: Context) {
            try {
                context.startService(Intent(context, DefaultDictionaryDownloadService::class.java))
            } catch (_: Exception) {
                // A start Android refuses (for example from the background) changes nothing else.
            }
        }
    }
}
