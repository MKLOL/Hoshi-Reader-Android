package moe.antimony.hoshi.features.podcasts

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings

@Serializable
data class PodcastEpisode(
    val id: String,
    val title: String,
    @SerialName("published_at") val publishedAt: String,
    /** Original recording length; 0 (or a missing field) means unknown. */
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    val status: String,
    @SerialName("lesson_duration_seconds") val lessonDurationSeconds: Double? = null,
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
internal data class PodcastCatalogue(
    val episodes: List<PodcastEpisode>,
    @SerialName("feed_stale") val feedStale: Boolean = false,
)

@Serializable
internal data class PodcastAccess(val enabled: Boolean = false)

enum class PodcastLength(val minSeconds: Int, val maxSeconds: Int) {
    All(1, Int.MAX_VALUE), Short(1, 600), Medium(601, 1200), Long(1201, Int.MAX_VALUE);
    /** An unknown duration (missing or non-positive) is listed under [All] only. */
    fun includes(episode: PodcastEpisode) =
        if (episode.durationSeconds <= 0) this == All else episode.durationSeconds in minSeconds..maxSeconds
}

/** Names shared between the repository, worker, service, view and activity. */
internal object PodcastKeys {
    const val ACCESS_PREFS = "podcast-access"
    const val VALIDATED_ACCOUNT = "validatedAccount"
    const val POSITIONS_PREFS = "podcast-positions"
    const val OPEN_EXTRA = "openPodcasts"
    const val EPISODE_TAG_PREFIX = "episode-"
    const val PROGRESS_PERCENT = "percent"
    fun accountTag(account: String) = "podcast-$account"
}

internal fun podcastAccount(settings: HttpSyncSettings): String = MessageDigest.getInstance("SHA-256")
    .digest("${settings.baseUrl.trimEnd('/')}\n${settings.bearerToken}".toByteArray())
    .joinToString("") { "%02x".format(it) }

internal fun validPodcastId(value: String) = value.matches(Regex("[a-f0-9]{64}"))

internal fun podcastResumePosition(savedMs: Long, durationSeconds: Double?): Long =
    if (savedMs < 0 || (durationSeconds != null && savedMs >= (durationSeconds * 1000).toLong() - 1000)) 0 else savedMs
