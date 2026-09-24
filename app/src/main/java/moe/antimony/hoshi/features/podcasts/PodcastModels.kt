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
    /** The server's one-sentence reason for the last failed preparation (stage and cause). */
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("failure_count") val failureCount: Int = 0,
    /** The show this episode belongs to; empty when the server predates shows. */
    val show: String = "",
    /** False when the server needs an administrator to resolve and reset this failure. */
    val retryable: Boolean = true,
)

/** The server's lesson worker as seen by the web tier: heartbeat liveness and start-up problems. */
@Serializable
internal data class PodcastWorkerStatus(
    val alive: Boolean = true,
    @SerialName("last_seen_seconds") val lastSeenSeconds: Int? = null,
    val problems: List<String> = emptyList(),
)

/** One podcast the server prepares lessons from; [feedStale] is that show's own listing. */
@Serializable
internal data class PodcastShow(
    val id: String,
    val name: String,
    @SerialName("feed_stale") val feedStale: Boolean = false,
)

/** How far the server has got with the one lesson it is generating. */
@Serializable
internal data class PodcastGeneration(
    val episode: String = "",
    val stage: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val percent: Int = 0,
    /** Seconds since the worker last reported; null when the server did not say. */
    @SerialName("age_seconds") val ageSeconds: Int? = null,
)

@Serializable
internal data class PodcastCatalogue(
    val episodes: List<PodcastEpisode>,
    /** Empty when the server predates shows; the app then lists every episode together. */
    val shows: List<PodcastShow> = emptyList(),
    @SerialName("feed_stale") val feedStale: Boolean = false,
    val worker: PodcastWorkerStatus? = null,
    /** Null when the server did not say; the app then shows no attempt count. */
    @SerialName("max_failures") val maxFailures: Int? = null,
    /** Null on a server without progress reporting, or when nothing is being generated. */
    val generating: PodcastGeneration? = null,
)

/** Attempts left before the server refuses Prepare, or null when the server did not say. */
internal fun podcastAttemptsLeft(maxFailures: Int?, failureCount: Int): Int? =
    maxFailures?.let { (it - failureCount).coerceAtLeast(0) }

internal fun podcastNeedsAdministrator(episode: PodcastEpisode, maxFailures: Int?): Boolean =
    episode.status == "failed" && (!episode.retryable || podcastAttemptsLeft(maxFailures, episode.failureCount) == 0)

/**
 * Server-supplied text as one bounded line, or null when there is nothing to show. Unicode
 * separators, control and format characters (ideographic space, zero-width joiners, bidi
 * overrides) collapse too, so a sentence cannot reorder or hide the text around it.
 */
internal fun podcastDisplayText(text: String?): String? =
    text?.replace(Regex("[\\p{Z}\\p{Cc}\\p{Cf}]+"), " ")?.trim()?.take(300)?.takeIf { it.isNotEmpty() }

/**
 * Progress worth drawing for this episode, or null to fall back to an indeterminate wait.
 * A record for another episode, a dead worker or a nonsensical count is never drawn: an
 * invented bar is worse than an honest spinner.
 */
internal fun podcastProgressFor(
    generating: PodcastGeneration?,
    episodeId: String,
    worker: PodcastWorkerStatus?,
    secondsSinceReceived: Long = 0,
): PodcastGeneration? = generating
    ?.takeIf { it.episode == episodeId && it.total >= 1 && worker?.alive != false }
    ?.takeIf { (it.ageSeconds ?: 0) >= 0 && (it.ageSeconds ?: 0).toLong() + secondsSinceReceived.coerceAtLeast(0) < 90 }
    ?.let { it.copy(percent = it.percent.coerceIn(0, 100), done = it.done.coerceIn(0, it.total)) }

/** The worker banner appears when the worker is down or recorded start-up problems. */
internal fun podcastWorkerNeedsAttention(status: PodcastWorkerStatus?): Boolean =
    status != null && (!status.alive || status.problems.isNotEmpty())

/** Download outcome codes the worker reports (see [DownloadProblem]); prose lives in resources. */
internal fun podcastDownloadReasonRes(code: String): Int? = when (code) {
    "account_changed" -> moe.antimony.hoshi.R.string.podcasts_reason_account_changed
    "content_type" -> moe.antimony.hoshi.R.string.podcasts_reason_content_type
    "empty" -> moe.antimony.hoshi.R.string.podcasts_reason_empty
    "too_large" -> moe.antimony.hoshi.R.string.podcasts_reason_too_large
    "incomplete" -> moe.antimony.hoshi.R.string.podcasts_reason_incomplete
    "save_failed" -> moe.antimony.hoshi.R.string.podcasts_reason_save_failed
    else -> null
}

@Serializable
internal data class PodcastAccess(val enabled: Boolean = false)

/** A chip has to stay readable, so a show name gets far less room than an error sentence. */
private const val MAX_SHOW_NAME = 60

/** Shows whose name and id are safe to display, in the server's order. */
internal fun podcastVisibleShows(shows: List<PodcastShow>): List<PodcastShow> =
    shows.filter { it.id.isNotBlank() }.mapNotNull { show ->
        podcastDisplayText(show.name)?.take(MAX_SHOW_NAME)?.let { show.copy(name = it) }
    }

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
    const val INPUT_ACCOUNT = "account"
    const val INPUT_EPISODE = "episode"
    /** Why a download finally failed, for the episode row. */
    const val OUTPUT_REASON = "reason"
    fun accountTag(account: String) = "podcast-$account"
    fun workName(account: String, episode: String) = "podcast-$account-$episode"
}

internal fun podcastAccount(settings: HttpSyncSettings): String = MessageDigest.getInstance("SHA-256")
    .digest("${settings.baseUrl.trimEnd('/')}\n${settings.bearerToken}".toByteArray())
    .joinToString("") { "%02x".format(it) }

internal fun validPodcastId(value: String) = value.matches(Regex("[a-f0-9]{64}"))

internal fun podcastResumePosition(savedMs: Long, durationSeconds: Double?): Long =
    if (savedMs < 0 || (durationSeconds != null && savedMs >= (durationSeconds * 1000).toLong() - 1000)) 0 else savedMs
