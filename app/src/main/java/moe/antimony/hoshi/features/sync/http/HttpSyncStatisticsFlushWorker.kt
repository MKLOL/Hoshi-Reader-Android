package moe.antimony.hoshi.features.sync.http

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.HoshiApplication
import java.util.concurrent.TimeUnit

/**
 * Sends the reading time and position of a session that just ended even when Android freezes
 * or ends the app right after the reader closes, or the device is offline until later. The
 * in-app push usually finishes first; this pass then only confirms that nothing is left.
 */
class HttpSyncStatisticsFlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? HoshiApplication)?.appContainer ?: return Result.success()
        return try {
            // Anything left unsent (a failed write, a queued position) is tried again later,
            // a bounded number of times, while the app may not be opened for hours.
            val result = container.httpSyncBookmarkScheduler.flushInBackground()
            if (result == null || result.errors.isEmpty() || runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "hoshi-statistics-flush"
        private const val MAX_ATTEMPTS = 8

        /** Queues one flush; a newer request replaces a waiting one, so reading sessions coalesce. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<HttpSyncStatisticsFlushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(15, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
