package moe.antimony.hoshi.features.podcasts

import android.os.SystemClock
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
import kotlinx.coroutines.sync.Mutex
import moe.antimony.hoshi.R

internal data class PodcastUiState(
    val episodes: List<PodcastEpisode> = emptyList(),
    val shows: List<PodcastShow> = emptyList(),
    val length: PodcastLength = PodcastLength.All,
    /** The show whose episodes are listed, or null for every show. */
    val showId: String? = null,
    val loading: Boolean = false,
    val errorRes: Int? = null,
    /** The server's error sentence or the failing exception's type, shown under [errorRes]. */
    val errorDetail: String? = null,
    val feedStale: Boolean = false,
    val worker: PodcastWorkerStatus? = null,
    val maxFailures: Int? = null,
    val generating: PodcastGeneration? = null,
    /** Monotonic receipt time; never restored from disk. */
    val generationReceivedAtMs: Long? = null,
    val downloaded: Set<String> = emptySet(),
    val downloads: Map<String, Int> = emptyMap(),
    /** Enqueued but not running: waiting for the network or a retry back-off. */
    val waiting: Set<String> = emptySet(),
    /** Episode id to the reason its last download failed. */
    val downloadFailures: Map<String, String> = emptyMap(),
    val preparing: Set<String> = emptySet(),
) {
    private val inChosenShow get() = episodes.filter { showId == null || it.show == showId }
    val filtered get() = inChosenShow.filter(length::includes)
    /**
     * Episodes of the chosen show that only the length filter is holding back. A feed that
     * states no durations (the Teppei beginners one states none) would otherwise look empty
     * for every length but "All lengths", with nothing on screen saying why.
     */
    val hiddenByLength get() = if (length == PodcastLength.All) 0 else inChosenShow.count { !length.includes(it) }
}

/**
 * Replace the offered shows, dropping a chosen one the new list no longer has. Without this a
 * dangling id filters every episode away while the chip that would clear it is gone.
 */
internal fun PodcastUiState.withShows(shows: List<PodcastShow>): PodcastUiState =
    copy(shows = shows, showId = showId?.takeIf { id -> shows.any { it.id == id } })

internal fun PodcastUiState.withoutLivePodcastStatus(): PodcastUiState =
    copy(generating = null, generationReceivedAtMs = null, worker = null)

/** Disk snapshots keep episode metadata but cannot claim a worker is live. */
internal fun PodcastUiState.withCatalogue(
    catalogue: PodcastCatalogue,
    downloaded: Set<String>,
    receivedAtMs: Long? = null,
    downloadedBeforeScan: Set<String>? = null,
): PodcastUiState {
    val completedSinceScan = downloadedBeforeScan?.let { this.downloaded - it }.orEmpty()
    val merged = catalogue.withSavedDownloads(PodcastCatalogue(episodes, shows), completedSinceScan)
    return withShows(podcastVisibleShows(merged.shows)).copy(
        episodes = merged.episodes,
        downloaded = downloadedBeforeScan?.let { reconcilePodcastDownloads(downloaded, it) } ?: downloaded,
        loading = false, errorRes = null, errorDetail = null,
        generating = catalogue.generating.takeIf { receivedAtMs != null },
        generationReceivedAtMs = receivedAtMs.takeIf { catalogue.generating != null },
        feedStale = catalogue.feedStale, worker = catalogue.worker.takeIf { receivedAtMs != null },
        maxFailures = catalogue.maxFailures,
    ).let { if (receivedAtMs != null) it.expirePodcastProgress(receivedAtMs) else it }
}

/** Keep observations published while IO was suspended, without keeping old missing files. */
private fun PodcastUiState.reconcilePodcastDownloads(scanned: Set<String>, beforeScan: Set<String>): Set<String> =
    (scanned - (beforeScan - downloaded)) + (downloaded - beforeScan)

internal fun PodcastUiState.withDownloadObservation(
    local: PodcastCatalogue,
    scanned: Set<String>,
    beforeScan: Set<String>,
): PodcastUiState {
    val downloaded = reconcilePodcastDownloads(scanned, beforeScan)
    val merged = PodcastCatalogue(episodes, shows).withSavedDownloads(local, downloaded)
    return withShows(podcastVisibleShows(merged.shows)).copy(episodes = merged.episodes, downloaded = downloaded)
}

internal fun PodcastUiState.expirePodcastProgress(nowMs: Long): PodcastUiState {
    val progress = generating ?: return this
    val received = generationReceivedAtMs
    return if (received == null || podcastProgressFor(progress, progress.episode, worker, (nowMs - received).coerceAtLeast(0) / 1000) == null) {
        copy(generating = null, generationReceivedAtMs = null)
    } else this
}

internal class PodcastViewModel(val repository: PodcastRepository) : ViewModel() {
    private val _state = MutableStateFlow(PodcastUiState())
    val state = _state.asStateFlow()
    private val visible = MutableStateFlow(false)
    private val refreshMutex = Mutex()
    private var sessionAccount: String? = null
    fun setVisible(value: Boolean) {
        visible.value = value
        if (!value) _state.update { it.withoutLivePodcastStatus() }
    }

    init {
        viewModelScope.launch {
            observePodcastScreenSession(repository.account, visible) { account ->
                // Leaving and returning keeps the list; only another account starts from scratch.
                // A prepare request in flight (viewModelScope) keeps its marker either way.
                if (account != null && account != sessionAccount) {
                    // The show filter is not carried across: ids belong to the server that sent them.
                    _state.value = PodcastUiState(length = _state.value.length, preparing = _state.value.preparing)
                    sessionAccount = account
                }
                _state.update { it.withoutLivePodcastStatus() }
                if (account != null) {
                    withContext(Dispatchers.IO) { repository.files.loadCatalogue(account) }?.let { cached ->
                        val downloaded = withContext(Dispatchers.IO) { cached.episodes.filter { validPodcastId(it.id) && repository.files.audio(account, it.id).isFile }.map { it.id }.toSet() }
                        _state.update { it.withCatalogue(cached, downloaded) }
                    }
                    launch { observeDownloads() }
                    launch {
                        while (true) {
                            delay(1_000)
                            _state.update { it.expirePodcastProgress(SystemClock.elapsedRealtime()) }
                        }
                    }
                    while (true) { refresh(); delay(10_000) }
                }
            }
        }
    }

    fun filter(length: PodcastLength) { _state.update { it.copy(length = length) } }
    fun filterShow(showId: String?) { _state.update { it.copy(showId = showId) } }
    fun retry() { viewModelScope.launch { refresh() } }

    private suspend fun refresh() {
        // Coalesce taps with polling: an older response must not overtake a newer snapshot.
        if (!refreshMutex.tryLock()) return
        try { refreshCatalogue() } finally { refreshMutex.unlock() }
    }

    private suspend fun refreshCatalogue() {
        val settings = repository.credentials
        if (!repository.access.value || !visible.value) return
        _state.update { it.copy(loading = it.episodes.isEmpty()) }
        try {
            val catalogue = repository.api.catalogue(settings)
            val downloadedBeforeScan = _state.value.downloaded
            val (merged, downloaded) = withContext(Dispatchers.IO) {
                val saved = repository.files.saveCatalogue(podcastAccount(settings), catalogue)
                saved to saved.episodes.filter { repository.files.audio(podcastAccount(settings), it.id).isFile }.map { it.id }.toSet()
            }
            if (settings != repository.credentials || !repository.access.value || !visible.value) return
            _state.update { it.withCatalogue(merged, downloaded, SystemClock.elapsedRealtime(), downloadedBeforeScan) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            if (settings == repository.credentials) _state.update { it.withoutLivePodcastStatus() }
            handle(error, settings)
        }
    }

    fun download(episode: PodcastEpisode) {
        val settings = repository.credentials
        viewModelScope.launch {
            try { repository.download(episode)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { handle(error, settings) }
        }
    }

    fun prepare(episode: PodcastEpisode) {
        if (episode.id in _state.value.preparing || podcastNeedsAdministrator(episode, _state.value.maxFailures)) return
        val settings = repository.credentials
        _state.update { it.copy(preparing = it.preparing + episode.id) }
        viewModelScope.launch {
            try {
                val updated = repository.api.prepare(settings, episode.id)
                if (settings == repository.credentials && repository.access.value) {
                    _state.update { it.copy(episodes = it.episodes.map { item -> if (item.id == updated.id) updated else item }, errorRes = null, errorDetail = null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { handle(error, settings)
            } finally { _state.update { it.copy(preparing = it.preparing - episode.id) } }
        }
    }

    private fun handle(error: Exception, settings: moe.antimony.hoshi.features.sync.http.HttpSyncSettings) {
        if (settings != repository.credentials) return
        if (error is PodcastHttpException && error.status in listOf(401, 403)) repository.invalidate()
        val http = error as? PodcastHttpException
        val message = when (http?.status) {
            429 -> R.string.podcasts_queue_full
            503 -> R.string.podcasts_unavailable
            else -> R.string.podcasts_error
        }
        // The server's own sentence when it sent one, else the failure type (never a URL or token).
        val detail = http?.serverMessage ?: http?.let { "HTTP ${it.status}" } ?: error.javaClass.simpleName
        _state.update { it.copy(loading = false, errorRes = message, errorDetail = detail) }
    }

    private suspend fun observeDownloads() {
        val account = podcastAccount(repository.credentials)
        repository.workManager.getWorkInfosByTagFlow(PodcastKeys.accountTag(account)).collect { infos ->
            val active = mutableMapOf<String, Int>()
            val waiting = mutableSetOf<String>()
            val failed = mutableMapOf<String, String>()
            val completed = mutableSetOf<String>()
            infos.forEach { info ->
                val id = info.tags.firstOrNull { it.startsWith(PodcastKeys.EPISODE_TAG_PREFIX) }?.removePrefix(PodcastKeys.EPISODE_TAG_PREFIX) ?: return@forEach
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> completed += id
                    WorkInfo.State.FAILED -> failed[id] = info.outputData.getString(PodcastKeys.OUTPUT_REASON).orEmpty()
                    WorkInfo.State.CANCELLED -> Unit
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> waiting += id
                    else -> active[id] = info.progress.getInt(PodcastKeys.PROGRESS_PERCENT, 0)
                }
            }
            val downloadedBeforeScan = _state.value.downloaded
            val (local, downloaded) = withContext(Dispatchers.IO) {
                val cached = repository.files.loadCatalogue(account) ?: PodcastCatalogue(emptyList())
                cached to (downloadedBeforeScan + completed + cached.episodes.map { it.id })
                    .filter { validPodcastId(it) && repository.files.audio(account, it).isFile }.toSet()
            }
            _state.update {
                // A transfer can finish after the server archived its show. Restore its local
                // row immediately, including when the next catalogue poll cannot get online.
                val updated = it.withDownloadObservation(local, downloaded, downloadedBeforeScan)
                updated.copy(downloads = active, waiting = waiting - active.keys,
                    downloadFailures = failed.filterKeys { id -> id !in active && id !in waiting && id !in updated.downloaded })
            }
        }
    }
}
