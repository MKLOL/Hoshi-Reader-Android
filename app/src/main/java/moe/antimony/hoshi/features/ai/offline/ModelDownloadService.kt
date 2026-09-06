package moe.antimony.hoshi.features.ai.offline

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
import androidx.core.content.ContextCompat
import moe.antimony.hoshi.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that downloads an offline translation model (see [OfflineLlmManager]).
 *
 * Running the multi-GB transfer as a foreground service is what lets it keep going while the
 * screen is off and the app is backgrounded — a plain background coroutine gets suspended by
 * Doze. A partial wake lock keeps the CPU awake mid-transfer; the actual range-resume streaming
 * lives in [OfflineLlmManager.runDownload]. The `.part` file is kept across stops, so stopping
 * and restarting resumes rather than re-downloading.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val model = intent?.getStringExtra(EXTRA_MODEL_ID)?.let { LlmModelCatalog.byId(it) }
        if (model == null) {
            // This start arrived via startForegroundService (see [start]), so Android 8+ demands
            // a startForeground() call within a few seconds even on this dead-end path — skipping
            // it risks a RemoteServiceException. Post a minimal foreground notification, then tear
            // the foreground state down immediately before stopping.
            startForegroundCompat(buildPlaceholderNotification())
            stopForegroundCompat()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(model, 0L, model.approxSizeBytes))
        acquireWakeLock()

        // One download at a time — a duplicate start just refreshes the notification.
        if (job?.isActive == true) return START_NOT_STICKY

        job = scope.launch {
            try {
                OfflineLlmManager.runDownload(applicationContext, model) { done, total ->
                    updateNotification(model, done, total)
                }
            } finally {
                releaseWakeLock()
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
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

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // Safety cap so a wedged download can't pin the CPU forever.
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun updateNotification(model: LlmModel, downloaded: Long, total: Long) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(model, downloaded, total))
    }

    private fun buildNotification(model: LlmModel, downloaded: Long, total: Long): Notification {
        ensureChannel()
        val indeterminate = total <= 0L
        val percent = if (indeterminate) 0 else ((downloaded * 100) / total).toInt().coerceIn(0, 100)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.offline_download_notification_title))
            .setContentText(
                getString(
                    R.string.offline_download_notification_body_format,
                    getString(model.displayNameRes),
                    percent,
                ),
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * A bare foreground notification for the invalid-model early return in [onStartCommand],
     * where there is no [LlmModel] to describe. Only exists to satisfy the startForeground()
     * obligation before the service stops itself.
     */
    private fun buildPlaceholderNotification(): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.offline_download_notification_title))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.offline_download_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 0x4711
        private const val CHANNEL_ID = "offline-model-download"
        private const val EXTRA_MODEL_ID = "model_id"
        private const val ACTION_CANCEL = "moe.antimony.hoshi.action.CANCEL_MODEL_DOWNLOAD"
        private const val WAKE_LOCK_TAG = "hoshi:model-download"
        private const val WAKE_LOCK_TIMEOUT_MS = 3L * 60L * 60L * 1000L // 3 hours

        /** Starts (or resumes) the download of [modelId] as a foreground service. */
        fun start(context: Context, modelId: String) {
            val intent = Intent(context, ModelDownloadService::class.java)
                .putExtra(EXTRA_MODEL_ID, modelId)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Signals the running service to stop the current download (keeping the `.part` file). */
        fun cancel(context: Context) {
            val intent = Intent(context, ModelDownloadService::class.java).setAction(ACTION_CANCEL)
            // The service is already foregrounded; a plain startService from the (foreground)
            // settings screen is allowed and just delivers the cancel action.
            runCatching { context.startService(intent) }
        }
    }
}
