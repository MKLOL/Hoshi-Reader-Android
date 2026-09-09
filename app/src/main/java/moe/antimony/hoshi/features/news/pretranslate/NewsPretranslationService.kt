package moe.antimony.hoshi.features.news.pretranslate

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R

/**
 * Foreground service that drains [NewsPretranslationManager]'s queue.
 *
 * A job sends dozens of requests to a cloud model or runs the on-device model for minutes, so it
 * follows the offline model download's pattern: a `dataSync` foreground service with a partial
 * wake lock keeps it alive while the screen is off, and the notification shows sentence progress.
 * A start that arrives while the previous drain is winding down is never lost: the drain loop
 * re-checks the queue, and `stopSelfResult` refuses to stop when a newer start id exists.
 */
class NewsPretranslationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var latestStartId = 0
    @Volatile private var lastState: PretranslationJobState? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lock) { latestStartId = startId }
        startForegroundCompat(buildNotification(lastState))
        ensureDraining()
        return START_NOT_STICKY
    }

    private fun ensureDraining() {
        synchronized(lock) {
            if (job?.isActive == true) return
            acquireWakeLock()
            job = scope.launch {
                try {
                    do {
                        NewsPretranslationManager.drain(this@NewsPretranslationService) { state -> updateNotification(state) }
                    } while (NewsPretranslationManager.hasPendingWork)
                } finally {
                    val stopped: Boolean
                    synchronized(lock) {
                        job = null
                        releaseWakeLock()
                        stopForegroundCompat()
                        stopped = stopSelfResult(latestStartId)
                    }
                    // A start command arrived after the last queue check: serve it.
                    if (!stopped) ensureDraining()
                }
            }
        }
    }

    override fun onDestroy() {
        synchronized(lock) {
            job?.cancel()
            job = null
            releaseWakeLock()
        }
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
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

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

    private fun updateNotification(state: PretranslationJobState) {
        lastState = state.takeIf { it.isActive }
        if (!state.isActive) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: PretranslationJobState?): Notification {
        ensureChannel()
        val running = state as? PretranslationJobState.Running
        val total = running?.total ?: 0
        val completed = running?.completed ?: 0
        val indeterminate = running == null || total <= 0
        val percent = if (indeterminate) 0 else (completed * 100 / total).coerceIn(0, 100)
        val text = when {
            running != null -> getString(R.string.news_pretranslate_progress_format, completed, total)
            state is PretranslationJobState.Uploading -> getString(R.string.news_pretranslate_uploading)
            else -> getString(R.string.news_pretranslate_preparing)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.news_pretranslate_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.news_pretranslate_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 0x4E45
        private const val CHANNEL_ID = "news-pretranslation"
        private const val WAKE_LOCK_TAG = "hoshi:news-pretranslation"
        private const val WAKE_LOCK_TIMEOUT_MS = 2L * 60L * 60L * 1000L

        fun start(context: Context) {
            val intent = Intent(context, NewsPretranslationService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
