package moe.antimony.hoshi.features.podcasts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R

internal data class PodcastUiState(
    val episodes: List<PodcastEpisode> = emptyList(),
    val length: PodcastLength = PodcastLength.All,
    val loading: Boolean = false,
    val errorRes: Int? = null,
    val feedStale: Boolean = false,
    val downloaded: Set<String> = emptySet(),
    val downloads: Map<String, Int> = emptyMap(),
    /** Enqueued but not running: waiting for the network or a retry back-off. */
    val waiting: Set<String> = emptySet(),
    val downloadFailures: Set<String> = emptySet(),
    val preparing: Set<String> = emptySet(),
) {
    val filtered get() = episodes.filter(length::includes)
}

internal class PodcastViewModel(val repository: PodcastRepository) : ViewModel() {
    private val _state = MutableStateFlow(PodcastUiState())
    val state = _state.asStateFlow()
    private val visible = MutableStateFlow(false)
    private var sessionAccount: String? = null
    fun setVisible(value: Boolean) { visible.value = value }

    init {
        viewModelScope.launch {
            observePodcastScreenSession(repository.account, visible) { account ->
                // Leaving and returning keeps the list; only another account starts from scratch.
                // A prepare request in flight (viewModelScope) keeps its marker either way.
                if (account != null && account != sessionAccount) {
                    _state.value = PodcastUiState(length = _state.value.length, preparing = _state.value.preparing)
                    sessionAccount = account
                }
                if (account != null) {
                    withContext(Dispatchers.IO) { repository.files.loadCatalogue(account) }?.let { cached ->
                        val downloaded = withContext(Dispatchers.IO) { cached.episodes.filter { validPodcastId(it.id) && repository.files.audio(account, it.id).isFile }.map { it.id }.toSet() }
                        _state.update { it.copy(episodes = cached.episodes.filter { item -> validPodcastId(item.id) }, downloaded = downloaded) }
                    }
                    launch { observeDownloads() }
                    while (true) { refresh(); delay(10_000) }
                }
            }
        }
    }

    fun filter(length: PodcastLength) { _state.update { it.copy(length = length) } }
    fun retry() { viewModelScope.launch { refresh() } }

    private suspend fun refresh() {
        val settings = repository.credentials
        if (!repository.access.value) return
        _state.update { it.copy(loading = it.episodes.isEmpty()) }
        try {
            val catalogue = repository.api.catalogue(settings)
            val episodes = catalogue.episodes.filter { validPodcastId(it.id) }
            val downloaded = withContext(Dispatchers.IO) {
                repository.files.saveCatalogue(podcastAccount(settings), catalogue.copy(episodes = episodes))
                episodes.filter { repository.files.audio(podcastAccount(settings), it.id).isFile }.map { it.id }.toSet()
            }
            if (settings != repository.credentials || !repository.access.value) return
            _state.update { it.copy(episodes = episodes, downloaded = downloaded, loading = false, errorRes = null, feedStale = catalogue.feedStale) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { handle(error, settings) }
    }

    fun prepare(episode: PodcastEpisode) {
        if (episode.id in _state.value.preparing) return
        val settings = repository.credentials
        _state.update { it.copy(preparing = it.preparing + episode.id) }
        viewModelScope.launch {
            try {
                val updated = repository.api.prepare(settings, episode.id)
                if (settings == repository.credentials && repository.access.value) {
                    _state.update { it.copy(episodes = it.episodes.map { item -> if (item.id == updated.id) updated else item }, errorRes = null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { handle(error, settings)
            } finally { _state.update { it.copy(preparing = it.preparing - episode.id) } }
        }
    }

    private fun handle(error: Exception, settings: moe.antimony.hoshi.features.sync.http.HttpSyncSettings) {
        if (settings != repository.credentials) return
        if (error is PodcastHttpException && error.status in listOf(401, 403)) repository.invalidate()
        val message = when ((error as? PodcastHttpException)?.status) {
            429 -> R.string.podcasts_queue_full
            503 -> R.string.podcasts_unavailable
            else -> R.string.podcasts_error
        }
        _state.update { it.copy(loading = false, errorRes = message) }
    }

    private suspend fun observeDownloads() {
        val account = podcastAccount(repository.credentials)
        repository.workManager.getWorkInfosByTagFlow(PodcastKeys.accountTag(account)).collect { infos ->
            val active = mutableMapOf<String, Int>()
            val waiting = mutableSetOf<String>()
            val failed = mutableSetOf<String>()
            val completed = mutableSetOf<String>()
            infos.forEach { info ->
                val id = info.tags.firstOrNull { it.startsWith(PodcastKeys.EPISODE_TAG_PREFIX) }?.removePrefix(PodcastKeys.EPISODE_TAG_PREFIX) ?: return@forEach
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> completed += id
                    WorkInfo.State.FAILED -> failed += id
                    WorkInfo.State.CANCELLED -> Unit
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> waiting += id
                    else -> active[id] = info.progress.getInt(PodcastKeys.PROGRESS_PERCENT, 0)
                }
            }
            val downloaded = withContext(Dispatchers.IO) {
                (_state.value.downloaded + completed).filter { repository.files.audio(account, it).isFile }.toSet()
            }
            _state.update { it.copy(downloaded = downloaded, downloads = active, waiting = waiting - active.keys, downloadFailures = failed - active.keys - waiting - downloaded) }
        }
    }
}
