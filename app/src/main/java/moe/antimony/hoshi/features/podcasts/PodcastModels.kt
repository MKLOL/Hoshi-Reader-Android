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
    @SerialName("duration_seconds") val durationSeconds: Int,
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
    All(0, 3600), Short(0, 600), Medium(601, 1200), Long(1201, 3600);
    fun includes(episode: PodcastEpisode) = episode.durationSeconds in minSeconds..maxSeconds
}

internal fun podcastAccount(settings: HttpSyncSettings): String = MessageDigest.getInstance("SHA-256")
    .digest("${settings.baseUrl.trimEnd('/')}\n${settings.bearerToken}".toByteArray())
    .joinToString("") { "%02x".format(it) }

internal fun validPodcastId(value: String) = value.matches(Regex("[a-f0-9]{64}"))

internal fun podcastResumePosition(savedMs: Long, durationSeconds: Double?): Long =
    if (savedMs < 0 || (durationSeconds != null && savedMs >= (durationSeconds * 1000).toLong() - 1000)) 0 else savedMs
