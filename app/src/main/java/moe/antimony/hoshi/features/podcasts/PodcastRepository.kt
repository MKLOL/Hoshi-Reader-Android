package moe.antimony.hoshi.features.podcasts

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import moe.antimony.hoshi.features.sync.http.HttpSyncSettingsRepository

internal class PodcastRepository(
    private val context: Context,
    private val settingsRepository: HttpSyncSettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val validation = context.getSharedPreferences(PodcastKeys.ACCESS_PREFS, Context.MODE_PRIVATE)
    val api = PodcastApi.shared
    val files = PodcastFiles(context)
    private val _access = MutableStateFlow(false)
    val access = _access.asStateFlow()
    /** The validated account fingerprint, or null; the playback service only serves this account's files. */
    private val _account = MutableStateFlow<String?>(null)
    val account = _account.asStateFlow()
    @Volatile var credentials = HttpSyncSettings(baseUrl = "", bearerToken = "")
        private set
    val workManager = WorkManager.getInstance(context)

    init {
        scope.launch(Dispatchers.IO) {
            settingsRepository.settings.map { it.copy(lastSyncedAt = null, useV3Sync = true) }
                .distinctUntilChanged().collectLatest { settings ->
                    val old = credentials
                    credentials = settings
                    val cachedAccount = settings.takeIf { it.isConfigured }?.let(::podcastAccount)
                    _access.value = cachedAccount != null && validation.getString(PodcastKeys.VALIDATED_ACCOUNT, null) == cachedAccount
                    _account.value = cachedAccount.takeIf { _access.value }
                    // Cold startup must leave persisted downloads eligible to resume.
                    if (old.isConfigured) {
                        workManager.cancelAllWorkByTag(PodcastKeys.accountTag(podcastAccount(old)))
                        context.stopService(Intent(context, PodcastPlaybackService::class.java))
                    }
                    if (settings.isConfigured) {
                        while (true) {
                            try {
                                _access.value = api.access(settings)
                                _account.value = if (_access.value) podcastAccount(settings) else null
                                if (_access.value) {
                                    validation.edit().putString(PodcastKeys.VALIDATED_ACCOUNT, podcastAccount(settings)).apply()
                                    // Only a pair the server just accepted retires the others' lessons: an
                                    // unvalidated edit of the token (typed one character at a time) or a
                                    // transient rejection must never delete anything.
                                    files.pruneExcept(podcastAccount(settings))
                                }
                                if (!_access.value) invalidate()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                if (error is PodcastHttpException && error.status in listOf(401, 403)) invalidate()
                                // Keep an already validated account usable during temporary offline periods.
                                else Log.w(TAG, "Podcast access check failed (${error.javaClass.simpleName})")
                            }
                            delay(60_000)
                        }
                    }
                }
        }
    }

    companion object {
        private const val TAG = "PodcastRepository"
        fun getInstance(context: Context): PodcastRepository =
            (context.applicationContext as moe.antimony.hoshi.HoshiApplication).podcastRepository
    }

    /**
     * The server rejected or disabled the token: stop playback and downloads and hide the tab.
     * Files stay: a rejection can be transient (proxy, maintenance, a flag flipped back) and are
     * unusable without validation anyway; another account validating is what removes them.
     */
    fun invalidate() {
        _access.value = false
        _account.value = null
        validation.edit().remove(PodcastKeys.VALIDATED_ACCOUNT).apply()
        context.stopService(Intent(context, PodcastPlaybackService::class.java))
        if (credentials.isConfigured) workManager.cancelAllWorkByTag(PodcastKeys.accountTag(podcastAccount(credentials)))
    }

    suspend fun download(episode: PodcastEpisode): Unit = withContext(Dispatchers.IO) {
        val settings = credentials
        val account = this@PodcastRepository.account.value ?: return@withContext
        if (!access.value || account != podcastAccount(settings) || !validPodcastId(episode.id)) return@withContext
        files.rememberDownload(account, episode)
        if (settings != credentials || account != this@PodcastRepository.account.value) return@withContext
        val request = OneTimeWorkRequestBuilder<PodcastDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .setInputData(workDataOf(PodcastKeys.INPUT_ACCOUNT to account, PodcastKeys.INPUT_EPISODE to episode.id))
            .addTag(PodcastKeys.accountTag(account)).addTag(PodcastKeys.EPISODE_TAG_PREFIX + episode.id).build()
        workManager.enqueueUniqueWork(PodcastKeys.workName(account, episode.id), ExistingWorkPolicy.KEEP, request)
    }
}
