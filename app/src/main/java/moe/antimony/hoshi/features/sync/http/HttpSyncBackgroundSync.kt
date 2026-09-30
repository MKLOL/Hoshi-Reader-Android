package moe.antimony.hoshi.features.sync.http

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import moe.antimony.hoshi.HoshiApplication
import moe.antimony.hoshi.MainActivity
import moe.antimony.hoshi.R

/** Screen-off transfers are owned by Android; the readers' small automatic syncs stay lightweight. */
internal object HttpSyncBackgroundSync {
    const val JOB_ID = 0x4853
    const val NOTIFICATION_ID = 0x4854
    private const val CHANNEL = "http-sync"

    suspend fun schedule(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, HttpSyncTransferJob::class.java))
                    .setUserInitiated(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build()
                if (context.getSystemService(JobScheduler::class.java).schedule(job) != JobScheduler.RESULT_SUCCESS) {
                    throw HttpSyncException(R.string.http_sync_background_start_failed)
                }
            } else {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "http-manual-sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
                    OneTimeWorkRequestBuilder<HttpSyncTransferWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build(),
                ).await()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw HttpSyncException(R.string.http_sync_background_start_failed)
        }
    }

    fun failedToStart(context: Context) {
        (context.applicationContext as HoshiApplication).appContainer.httpSyncManualSync.backgroundStartFailed()
    }

    fun stoppedBeforeExecution(context: Context) {
        (context.applicationContext as HoshiApplication).appContainer.httpSyncManualSync.cancelledBeforeExecution()
    }

    suspend fun execute(context: Context, notify: (Notification) -> Unit) = coroutineScope {
        val sync = (context.applicationContext as HoshiApplication).appContainer.httpSyncManualSync
        val observer = launch {
            sync.status.map { status ->
                (status as? SyncStatus.Running)?.progress?.fraction?.let { (it * 100).toInt() }
            }.distinctUntilChanged().collectLatest { percent ->
                notify(notification(context, percent))
            }
        }
        try { sync.execute() } finally { observer.cancel() }
    }

    fun notification(context: Context, percent: Int? = null): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, context.getString(R.string.http_sync_title), NotificationManager.IMPORTANCE_LOW,
        ))
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.http_sync_running))
            .setContentIntent(PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent ?: 0, percent == null)
            .build()
    }
}

@RequiresApi(34)
class HttpSyncTransferJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transfer: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        try {
            setNotification(params, HttpSyncBackgroundSync.NOTIFICATION_ID,
                HttpSyncBackgroundSync.notification(this), JOB_END_NOTIFICATION_POLICY_REMOVE)
        } catch (_: Exception) {
            HttpSyncBackgroundSync.failedToStart(this)
            return false
        }
        transfer = scope.launch {
            try {
                HttpSyncBackgroundSync.execute(this@HttpSyncTransferJob) { notification ->
                    setNotification(params, HttpSyncBackgroundSync.NOTIFICATION_ID, notification,
                        JOB_END_NOTIFICATION_POLICY_REMOVE)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                HttpSyncBackgroundSync.failedToStart(this@HttpSyncTransferJob)
            } finally {
                if (isActive) jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        transfer?.cancel()
        HttpSyncBackgroundSync.stoppedBeforeExecution(this)
        return params.stopReason != JobParameters.STOP_REASON_CANCELLED_BY_APP
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** API 28–33 fallback; WorkManager manages CPU wakefulness and its foreground service. */
class HttpSyncTransferWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        try {
            setForeground(getForegroundInfo())
            HttpSyncBackgroundSync.execute(applicationContext) { notification ->
                if (Build.VERSION.SDK_INT < 33 || applicationContext.checkSelfPermission(
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    applicationContext.getSystemService(NotificationManager::class.java)
                        .notify(HttpSyncBackgroundSync.NOTIFICATION_ID, notification)
                }
            }
            return Result.success()
        } catch (cancelled: CancellationException) {
            HttpSyncBackgroundSync.stoppedBeforeExecution(applicationContext)
            throw cancelled
        } catch (_: Exception) {
            HttpSyncBackgroundSync.failedToStart(applicationContext)
            return Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = HttpSyncBackgroundSync.notification(applicationContext)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(HttpSyncBackgroundSync.NOTIFICATION_ID,
            notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(HttpSyncBackgroundSync.NOTIFICATION_ID, notification)
    }
}
