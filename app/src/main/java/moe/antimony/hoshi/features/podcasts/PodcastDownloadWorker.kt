package moe.antimony.hoshi.features.podcasts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.sync.http.httpSyncSettingsRepository

class PodcastDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val account = inputData.getString("account") ?: return@withContext Result.failure()
        val id = inputData.getString("episode") ?: return@withContext Result.failure()
        if (!validPodcastId(account) || !validPodcastId(id)) return@withContext Result.failure()
        val settingsRepo = applicationContext.httpSyncSettingsRepository()
        val settings = settingsRepo.settings.first()
        if (account != podcastAccount(settings)) return@withContext Result.failure()
        val destination = PodcastFiles(applicationContext).audio(account, id)
        val temporary = java.io.File(destination.path + ".part")
        try {
            // Android 12+ refuses a foreground promotion from the background (a deferred or retried
            // start). The transfer is bounded and constrained, so it proceeds as ordinary work.
            runCatching { setForeground(getForegroundInfo()) }
            val api = PodcastRepository.getInstance(applicationContext).api
            api.client.newCall(api.request(settings, "/$id/audio")).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code in listOf(401, 403) && account == podcastAccount(settingsRepo.settings.first())) {
                        PodcastRepository.getInstance(applicationContext).invalidate()
                    }
                    return@withContext if (response.code in listOf(429, 503) && runAttemptCount < 2) Result.retry() else Result.failure()
                }
                if (response.header("Content-Type")?.substringBefore(';') != "audio/mpeg") throw IOException()
                val body = response.body ?: throw IOException()
                val total = body.contentLength()
                val limit = 512L * 1024 * 1024
                if (total > limit) throw IOException()
                destination.parentFile?.mkdirs()
                var downloaded = 0L
                var lastProgress = 0L
                body.byteStream().use { source ->
                    temporary.outputStream().use { target ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (account != podcastAccount(settingsRepo.settings.first())) return@withContext Result.failure()
                            val size = source.read(buffer)
                            if (size < 0) break
                            downloaded += size
                            if (downloaded > limit) throw IOException()
                            target.write(buffer, 0, size)
                            if (downloaded - lastProgress >= 1024 * 1024) {
                                setProgress(workDataOf(PodcastKeys.PROGRESS_PERCENT to if (total > 0) (downloaded * 100 / total).toInt() else 0))
                                lastProgress = downloaded
                            }
                        }
                    }
                }
                if (downloaded == 0L || (total >= 0 && total != downloaded)) throw IOException()
                if (account != podcastAccount(settingsRepo.settings.first())) return@withContext Result.failure()
                if (!temporary.renameTo(destination)) throw IOException()
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("PodcastDownloadWorker", "Download attempt ${runAttemptCount + 1} failed (${error.javaClass.simpleName})")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } finally {
            temporary.delete()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("podcast-downloads", applicationContext.getString(R.string.podcasts_downloads), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "podcast-downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.podcasts_downloading))
            .setOngoing(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id.hashCode(), notification)
    }
}
