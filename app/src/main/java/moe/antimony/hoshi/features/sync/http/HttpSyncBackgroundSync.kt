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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import moe.antimony.hoshi.ui.resolve

/** The device's default network, as Android's transfer constraints see it. */
internal enum class HttpSyncNetwork { None, Unvalidated, Validated }

/**
 * Anything short of validated internet may still reach a self-hosted server, so only a missing
 * network is reported as offline.
 */
internal fun httpSyncNetwork(connected: Boolean, internet: Boolean, validated: Boolean): HttpSyncNetwork = when {
    !connected -> HttpSyncNetwork.None
    internet && validated -> HttpSyncNetwork.Validated
    else -> HttpSyncNetwork.Unvalidated
}

/**
 * Android starts transfer jobs and workers only on a validated internet connection. A tap with
 * no network fails at once instead of waiting forever, and a network Android has not validated
 * (a LAN-only server, a captive portal) runs the pass in the app, where the server can still be
 * reached or fail with its own error.
 */
internal fun httpSyncStartRoute(network: HttpSyncNetwork): HttpSyncStartRoute = when (network) {
    HttpSyncNetwork.None -> throw HttpSyncException(R.string.http_sync_no_network)
    HttpSyncNetwork.Unvalidated -> HttpSyncStartRoute.InProcess
    HttpSyncNetwork.Validated -> HttpSyncStartRoute.AndroidTransfer
}

/** Screen-off transfers are owned by Android; the readers' small automatic syncs stay lightweight. */
internal object HttpSyncBackgroundSync {
    const val JOB_ID = 0x4853
    const val NOTIFICATION_ID = 0x4854
    const val WORK_NAME = "http-manual-sync"
    private const val CHANNEL = "http-sync"

    /** [useWorker] exists so the API 28–33 path can be exercised on newer test devices. */
    suspend fun schedule(
        context: Context,
        useWorker: Boolean = Build.VERSION.SDK_INT < 34,
        network: () -> HttpSyncNetwork = { currentNetwork(context) },
    ): HttpSyncStartRoute {
        val route = httpSyncStartRoute(network())
        if (route != HttpSyncStartRoute.AndroidTransfer) return route
        try {
            if (!useWorker && Build.VERSION.SDK_INT >= 34) {
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, HttpSyncTransferJob::class.java))
                    .setUserInitiated(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build()
                if (context.getSystemService(JobScheduler::class.java).schedule(job) != JobScheduler.RESULT_SUCCESS) {
                    throw HttpSyncException(R.string.http_sync_background_start_failed)
                }
            } else {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
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
        return route
    }

    /** Withdraws a scheduled or running transfer; Android then stops it without a retry. */
    fun unschedule(context: Context) {
        if (Build.VERSION.SDK_INT >= 34) context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private fun currentNetwork(context: Context): HttpSyncNetwork {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return HttpSyncNetwork.Validated
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
        return httpSyncNetwork(
            connected = capabilities != null,
            internet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
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
                val progress = (status as? SyncStatus.Running)?.progress
                progress?.fraction?.let { (it * 100).toInt() } to progress?.transfer?.speedText()
            }.distinctUntilChanged().collectLatest { (percent, speed) ->
                notify(notification(context, percent, speed?.resolve(context)))
            }
        }
        try { sync.execute() } finally { observer.cancel() }
    }

    fun notification(context: Context, percent: Int? = null, transferText: String? = null): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, context.getString(R.string.http_sync_title), NotificationManager.IMPORTANCE_LOW,
        ))
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.http_sync_running))
            .setContentText(transferText)
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
