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

/** A download outcome the episode row can name by code; the view maps codes to localized text. */
internal class DownloadProblem(val code: String) : IOException(code)

class PodcastDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val account = inputData.getString(PodcastKeys.INPUT_ACCOUNT) ?: return@withContext Result.failure()
        val id = inputData.getString(PodcastKeys.INPUT_EPISODE) ?: return@withContext Result.failure()
        if (!validPodcastId(account) || !validPodcastId(id)) return@withContext Result.failure()
        val settingsRepo = applicationContext.httpSyncSettingsRepository()
        val settings = settingsRepo.settings.first()
        if (account != podcastAccount(settings)) return@withContext failed("account_changed")
        val destination = PodcastFiles(applicationContext).audio(account, id)
        val temporary = java.io.File(destination.path + ".part")
        try {
            // Android 12+ refuses a foreground promotion from the background (a deferred or retried
            // start). The transfer is bounded and constrained, so it proceeds as ordinary work.
            try { setForeground(getForegroundInfo()) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            val api = PodcastApi.shared
            api.client.newCall(api.request(settings, "/$id/audio")).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code in listOf(401, 403) && account == podcastAccount(settingsRepo.settings.first())) {
                        PodcastRepository.getInstance(applicationContext).invalidate()
                    }
                    return@withContext if (response.code in listOf(429, 503) && runAttemptCount < 2) Result.retry() else failed("http:${response.code}")
                }
                if (response.header("Content-Type")?.substringBefore(';') != "audio/mpeg") throw DownloadProblem("content_type")
                val body = response.body ?: throw DownloadProblem("empty")
                val total = body.contentLength()
                val limit = 512L * 1024 * 1024
                if (total > limit) throw DownloadProblem("too_large")
                destination.parentFile?.mkdirs()
                var downloaded = 0L
                var lastProgress = 0L
                body.byteStream().use { source ->
                    temporary.outputStream().use { target ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (account != podcastAccount(settingsRepo.settings.first())) return@withContext failed("account_changed")
                            val size = source.read(buffer)
                            if (size < 0) break
                            downloaded += size
                            if (downloaded > limit) throw DownloadProblem("too_large")
                            target.write(buffer, 0, size)
                            if (downloaded - lastProgress >= 1024 * 1024) {
                                setProgress(workDataOf(PodcastKeys.PROGRESS_PERCENT to if (total > 0) (downloaded * 100 / total).toInt() else 0))
                                lastProgress = downloaded
                            }
                        }
                    }
                }
                if (downloaded == 0L || (total >= 0 && total != downloaded)) throw DownloadProblem("incomplete")
                if (account != podcastAccount(settingsRepo.settings.first())) return@withContext failed("account_changed")
                if (!temporary.renameTo(destination)) throw DownloadProblem("save_failed")
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("PodcastDownloadWorker", "Download attempt ${runAttemptCount + 1} failed (${error.javaClass.simpleName})")
            if (runAttemptCount < 2) Result.retry() else failed(if (error is DownloadProblem) error.code else "exception:" + error.javaClass.simpleName)
        } finally {
            temporary.delete()
        }
    }

    /** The final outcome carries a code the episode row can name; never a path, URL or token. */
    private fun failed(code: String): Result = Result.failure(workDataOf(PodcastKeys.OUTPUT_REASON to code.take(80)))

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
