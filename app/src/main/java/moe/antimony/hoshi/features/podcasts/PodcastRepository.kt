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
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import moe.antimony.hoshi.features.sync.http.HttpSyncSettingsRepository

internal class PodcastRepository(
    private val context: Context,
    private val settingsRepository: HttpSyncSettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val validation = context.getSharedPreferences(PodcastKeys.ACCESS_PREFS, Context.MODE_PRIVATE)
    val api = PodcastApi()
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
                    // Lessons belong to one server/token pair; another pair's files are dead weight.
                    files.pruneExcept(cachedAccount)
                    if (settings.isConfigured) {
                        while (true) {
                            try {
                                _access.value = api.access(settings)
                                _account.value = if (_access.value) podcastAccount(settings) else null
                                if (_access.value) validation.edit().putString(PodcastKeys.VALIDATED_ACCOUNT, podcastAccount(settings)).apply()
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

    /** The server rejected or disabled the token: stop everything and drop that account's lessons. */
    fun invalidate() {
        _access.value = false
        _account.value = null
        validation.edit().remove(PodcastKeys.VALIDATED_ACCOUNT).apply()
        context.stopService(Intent(context, PodcastPlaybackService::class.java))
        if (credentials.isConfigured) {
            val account = podcastAccount(credentials)
            workManager.cancelAllWorkByTag(PodcastKeys.accountTag(account))
            files.deleteAccount(account)
        }
    }

    fun download(episode: PodcastEpisode) {
        if (!access.value || !validPodcastId(episode.id)) return
        val account = podcastAccount(credentials)
        val request = OneTimeWorkRequestBuilder<PodcastDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .setInputData(workDataOf("account" to account, "episode" to episode.id))
            .addTag(PodcastKeys.accountTag(account)).addTag(PodcastKeys.EPISODE_TAG_PREFIX + episode.id).build()
        workManager.enqueueUniqueWork("podcast-$account-${episode.id}", ExistingWorkPolicy.KEEP, request)
    }
}
