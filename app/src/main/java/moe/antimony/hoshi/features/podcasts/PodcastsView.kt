package moe.antimony.hoshi.features.podcasts

import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerControlView
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun PodcastsView(modifier: Modifier = Modifier) {
    val repository = LocalHoshiAppContainer.current.podcastRepository
    val available by repository.access.collectAsStateWithLifecycle()
    val model: PodcastViewModel = viewModel(factory = viewModelFactory { initializer { PodcastViewModel(repository) } })
    val state by model.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(model) {
        model.setVisible(true)
        onStopOrDispose { model.setVisible(false) }
    }
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var playingTitle by remember { mutableStateOf("") }
    var playerError by remember { mutableStateOf<String?>(null) }
    val fileMissingReason = stringResource(R.string.podcasts_reason_file_missing)
    DisposableEffect(available) {
        val future = if (available) MediaController.Builder(context, SessionToken(context, ComponentName(context, PodcastPlaybackService::class.java))).buildAsync() else null
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { playingTitle = mediaItem?.mediaMetadata?.title?.toString().orEmpty() }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { playerError = error.errorCodeName }
        }
        future?.addListener({
            if (!future.isCancelled) runCatching { future.get() }.onSuccess {
                controller = it
                it.addListener(listener)
                playingTitle = it.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
            }.onFailure { playerError = (it.cause ?: it).javaClass.simpleName }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            controller?.removeListener(listener)
            controller = null
            future?.let(MediaController::releaseFuture)
        }
    }
    if (!available) return
    LazyColumn(modifier.statusBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "podcasts-header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.main_tab_podcasts), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
                Text(
                    stringResource(if (state.shows.size > 1) R.string.podcasts_intro_shows else R.string.podcasts_intro),
                    style = MaterialTheme.typography.bodySmall,
                )
                // Only worth choosing between when the server offers more than one show.
                if (state.shows.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.showId == null,
                            onClick = { model.filterShow(null) },
                            label = { Text(stringResource(R.string.podcasts_show_all), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                        state.shows.forEach { show ->
                            FilterChip(
                                selected = state.showId == show.id,
                                onClick = { model.filterShow(show.id) },
                                label = { Text(show.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PodcastLength.entries.forEach { length ->
                        val label = when (length) {
                            PodcastLength.All -> R.string.podcasts_length_all
                            PodcastLength.Short -> R.string.podcasts_length_short
                            PodcastLength.Medium -> R.string.podcasts_length_medium
                            PodcastLength.Long -> R.string.podcasts_length_long
                        }
                        FilterChip(selected = state.length == length, onClick = { model.filter(length) }, label = { Text(stringResource(label)) })
                    }
                }
                if (playingTitle.isNotEmpty()) {
                    Text(playingTitle, maxLines = 2, style = MaterialTheme.typography.titleSmall)
                    AndroidView(
                        factory = { PlayerControlView(it).apply {
                            setShowTimeoutMs(0)
                            setShowShuffleButton(false)
                            setShowSubtitleButton(false)
                            setShowPreviousButton(false)
                            setShowNextButton(false)
                        } },
                        update = { it.player = controller },
                        onRelease = { it.player = null },
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                    )
                }
                playerError?.let { Text(stringResource(R.string.podcasts_playback_error_detail, it), color = MaterialTheme.colorScheme.error) }
                // Name the shows whose listing is stale; with one show the server names none.
                if (state.feedStale) {
                    val stale = state.shows.filter { it.feedStale }
                    Text(
                        if (stale.isEmpty()) stringResource(R.string.podcasts_cached_feed)
                        else stringResource(R.string.podcasts_cached_feed_shows, formatShowList(stale.map { it.name })),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // The server's worker prepares every lesson; when it is down or misconfigured, say so
                // with its own words before the user wonders why Prepare fails.
                state.worker?.takeIf(::podcastWorkerNeedsAttention)?.let { worker ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (!worker.alive) {
                            Text(stringResource(R.string.podcasts_worker_down), color = MaterialTheme.colorScheme.error)
                            val seen = worker.lastSeenSeconds
                            Text(
                                when {
                                    seen == null -> stringResource(R.string.podcasts_worker_never_seen)
                                    seen < 60 -> stringResource(R.string.podcasts_worker_last_seen_recent)
                                    else -> pluralStringResource(R.plurals.podcasts_worker_last_seen_minutes, seen / 60, seen / 60)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        worker.problems.mapNotNull(::podcastDisplayText).forEach { problem ->
                            Text(stringResource(R.string.podcasts_worker_problem, problem), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3)
                        }
                    }
                }
                state.errorRes?.let { message ->
                    Column {
                        Row {
                            Text(stringResource(message), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = model::retry) { Text(stringResource(R.string.podcasts_retry)) }
                        }
                        podcastDisplayText(state.errorDetail)?.let { Text(stringResource(R.string.podcasts_error_detail, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3) }
                    }
                }
                if (state.loading) CircularProgressIndicator()
                if (!state.loading && state.filtered.isEmpty() && state.errorRes == null) {
                    val hidden = state.hiddenByLength
                    if (hidden > 0) {
                        Text(pluralStringResource(R.plurals.podcasts_hidden_by_length, hidden, hidden))
                        TextButton(onClick = { model.filter(PodcastLength.All) }) {
                            Text(stringResource(R.string.podcasts_length_all))
                        }
                    } else {
                        Text(stringResource(R.string.podcasts_empty))
                    }
                }
            }
        }
        items(state.filtered, key = { it.id }) { episode ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(episode.title, style = MaterialTheme.typography.titleMedium)
                    // The show only needs naming while episodes from several of them are listed.
                    if (state.showId == null && state.shows.size > 1) {
                        state.shows.firstOrNull { it.id == episode.show }?.let {
                            Text(it.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(
                        // A feed that lists no duration leaves it unknown until the lesson is prepared.
                        if (episode.durationSeconds > 0) stringResource(R.string.podcasts_original_duration, episode.durationSeconds / 60, episode.durationSeconds % 60)
                        else stringResource(R.string.podcasts_original_duration_unknown),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val attemptsLeft = podcastAttemptsLeft(state.maxFailures, episode.failureCount)
                    if (episode.status == "failed") {
                        val reason = podcastDisplayText(episode.errorMessage) ?: episode.errorCode?.takeIf { it.isNotBlank() }
                        Text(
                            reason?.let { stringResource(R.string.podcasts_generation_failed_detail, it) } ?: stringResource(R.string.podcasts_generation_failed),
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 4,
                        )
                        // Only a server that reports its limit gets an attempt count.
                        val maxFailures = state.maxFailures
                        if (attemptsLeft != null && maxFailures != null) {
                            Text(
                                if (attemptsLeft > 0) stringResource(R.string.podcasts_attempts_left, attemptsLeft, maxFailures) else stringResource(R.string.podcasts_attempts_exhausted),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    state.downloadFailures[episode.id]?.let { code ->
                        Text(stringResource(R.string.podcasts_download_failed_detail, downloadReason(code)), color = MaterialTheme.colorScheme.error, maxLines = 3)
                    }
                    when {
                        episode.id in state.downloaded -> Button(enabled = controller != null, onClick = {
                            val account = podcastAccount(repository.credentials)
                            val id = "$account:${episode.id}"
                            if (!repository.files.audio(account, episode.id).isFile) {
                                // The file went away since the last refresh: say so instead of playing nothing.
                                playerError = fileMissingReason
                                model.retry()
                                return@Button
                            }
                            controller?.let { player ->
                                if (player.currentMediaItem?.mediaId != id) {
                                    val savedPosition = context.getSharedPreferences(PodcastKeys.POSITIONS_PREFS, Context.MODE_PRIVATE).getLong(id, 0)
                                    val position = podcastResumePosition(savedPosition, episode.lessonDurationSeconds)
                                    player.setMediaItem(MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder().setTitle(episode.title).build()).build(), position)
                                    player.prepare()
                                }
                                playerError = null
                                androidx.media3.common.util.Util.handlePlayButtonAction(player)
                            }
                        }) { Text(stringResource(R.string.podcasts_play)) }
                        episode.id in state.downloads -> Text(stringResource(R.string.podcasts_download_progress, state.downloads.getValue(episode.id)))
                        episode.id in state.waiting -> Text(stringResource(R.string.podcasts_download_waiting))
                        episode.status == "ready" -> Button(onClick = { repository.download(episode) }) { Text(stringResource(R.string.podcasts_download)) }
                        episode.status == "queued" || episode.id in state.preparing -> Text(stringResource(R.string.podcasts_queued))
                        episode.status == "preparing" -> PreparingRow(podcastProgressFor(state.generating, episode.id, state.worker))
                        // The server refuses further attempts (409); an administrator resets the count.
                        episode.status == "failed" && attemptsLeft == 0 -> Unit
                        else -> Button(onClick = { model.prepare(episode) }) { Text(stringResource(R.string.podcasts_prepare)) }
                    }
                }
            }
        }
    }
}

/** A download outcome code as the user's language; an exception name stays as the identifier it is. */
@Composable
private fun downloadReason(code: String): String = when {
    code.startsWith("http:") -> stringResource(R.string.podcasts_reason_http, code.removePrefix("http:").toIntOrNull() ?: 0)
    code.startsWith("exception:") -> code.removePrefix("exception:")
    else -> podcastDownloadReasonRes(code)?.let { stringResource(it) } ?: stringResource(R.string.podcasts_reason_unknown)
}

/**
 * Join show names the way the reader's language does: Chinese needs its enumeration comma,
 * English an "and". Kotlin's default ", " is right in neither.
 */
@Composable
private fun formatShowList(names: List<String>): String {
    val locale = LocalConfiguration.current.locales[0]
    return android.icu.text.ListFormatter.getInstance(locale).format(names)
}

/**
 * What the server is doing to this episode right now. A quarter of an hour of silence reads
 * as a hang, so the bar is determinate whenever the server says where it is, and honestly
 * indeterminate when it does not.
 */
@Composable
private fun PreparingRow(progress: PodcastGeneration?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        if (progress == null) {
            Text(stringResource(R.string.podcasts_preparing), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        val stage = when (progress.stage) {
            "download" -> R.string.podcasts_stage_download
            "transcription" -> R.string.podcasts_stage_transcription
            "segmentation" -> R.string.podcasts_stage_segmentation
            "translation" -> R.string.podcasts_stage_translation
            "vocabulary" -> R.string.podcasts_stage_vocabulary
            "speech" -> R.string.podcasts_stage_speech
            "render" -> R.string.podcasts_stage_render
            else -> R.string.podcasts_preparing
        }
        Text(
            // The count only means something where there is more than one thing to count.
            if (progress.total > 1) stringResource(R.string.podcasts_stage_counted, stringResource(stage), progress.done, progress.total)
            else stringResource(stage),
            style = MaterialTheme.typography.bodySmall,
        )
        LinearProgressIndicator({ progress.percent / 100f }, Modifier.fillMaxWidth())
        Text(stringResource(R.string.podcasts_stage_percent, progress.percent), style = MaterialTheme.typography.labelSmall)
    }
}
