package moe.antimony.hoshi.features.ai

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
 * Keeps the app in the foreground while a translation request runs, so leaving the app does not
 * lose a paid reply: Android cuts a background app's network within seconds. The request itself
 * runs in [AiChatRequests]; this `dataSync` foreground service (the same type as the offline
 * model download and news pretranslation) only lives until no request is running. Android shows
 * its notification only when that takes more than a few seconds.
 *
 * It is started only while the app is visible, with `startService`, and promotes itself: a
 * `startForegroundService` start would crash the app if a refused promotion stopped the service.
 */
class AiChatRequestService : Service() {
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
            // Refused (the app already left, or the dataSync time limit is used up): the request
            // runs anyway, without the keep-alive. Safe to stop: this start never asked for one.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        acquireWakeLock()
        watchRequests()
        return START_NOT_STICKY
    }

    private fun watchRequests() {
        if (watcher?.isActive == true) return
        val requests = (application as HoshiApplication).appContainer.aiChatRequests
        watcher = scope.launch {
            requests.requests.first { list -> list.none { it.status == AiChatRequest.Status.Running } }
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Refused when a newer start is still on its way; its onStartCommand watches again.
            stopSelfResult(latestStartId)
        }
    }

    /** Android 15+ ends `dataSync` time after its daily limit: stop within seconds, or the app crashes. */
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

    // The screen may go off while the reply is on its way; the timeout bounds a hung request.
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
                    getString(R.string.ai_chat_request_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ai_chat_request_notification_title))
            .setContentText(getString(R.string.ai_chat_request_notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x4149
        private const val CHANNEL_ID = "ai-chat-requests"
        private const val WAKE_LOCK_TAG = "hoshi:ai-chat-request"
        private const val WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L

        /** Called while the app is visible, when a request starts running; the service promotes itself. */
        fun start(context: Context) {
            try {
                context.startService(Intent(context, AiChatRequestService::class.java))
            } catch (_: Exception) {
                // A start Android refuses (for example from the background) changes nothing else.
            }
        }
    }
}
