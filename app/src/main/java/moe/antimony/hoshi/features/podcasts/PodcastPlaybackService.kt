package moe.antimony.hoshi.features.podcasts

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.antimony.hoshi.MainActivity

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PodcastPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val positions by lazy { getSharedPreferences(PodcastKeys.POSITIONS_PREFS, MODE_PRIVATE) }
    private val repository by lazy { PodcastRepository.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).setSeekBackIncrementMs(15_000).setSeekForwardIncrementMs(15_000)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { savePosition() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { savePosition() }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // A seek within the lesson must remember where it landed, not where it left.
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) savePosition()
                else oldPosition.mediaItem?.mediaId?.let { positions.edit().putLong(it, oldPosition.positionMs).apply() }
            }
        })
        val activity = PendingIntent.getActivity(this, 902, Intent(this, MainActivity::class.java).putExtra(PodcastKeys.OPEN_EXTRA, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                    val account = repository.account.value
                    val accepted = mediaItems.mapNotNull { item ->
                        val parts = item.mediaId.split(':')
                        if (account == null || parts.size != 2 || parts[0] != account || !validPodcastId(parts[1])) return@mapNotNull null
                        val file = repository.files.audio(account, parts[1])
                        // Only the id and title come from the controller; the file and everything else are ours.
                        if (!file.isFile) null
                        else MediaItem.Builder().setMediaId(item.mediaId).setUri(android.net.Uri.fromFile(file))
                            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(item.mediaMetadata.title).build()).build()
                    }
                    return Futures.immediateFuture(accepted)
                }
            }).build()
        scope.launch {
            repository.account.collect { account ->
                val currentAccount = player.currentMediaItem?.mediaId?.substringBefore(':')
                if (account == null || (currentAccount != null && currentAccount != account)) {
                    savePosition()
                    player.stop()
                    player.clearMediaItems()
                }
            }
        }
        scope.launch { while (true) { delay(5_000); savePosition() } }
    }

    private fun savePosition() {
        val player = session?.player ?: return
        val id = player.currentMediaItem?.mediaId ?: return
        positions.edit().putLong(id, if (player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition).apply()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (controllerInfo.packageName == packageName || controllerInfo.isTrusted) session else null

    override fun onDestroy() {
        savePosition()
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
