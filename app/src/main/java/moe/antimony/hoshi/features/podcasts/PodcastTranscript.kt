package moe.antimony.hoshi.features.podcasts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One timed line of an episode's original recording; times are seconds into that recording. */
@Serializable
internal data class PodcastTranscriptLine(
    val start: Double,
    val end: Double,
    val text: String,
    val english: String? = null,
)

/** The server's timed Japanese transcript of an episode's original recording (not the lesson). */
@Serializable
internal data class PodcastTranscript(
    val version: Int = 1,
    val episode: String = "",
    val duration: Double = 0.0,
    val lines: List<PodcastTranscriptLine> = emptyList(),
)

private val transcriptJson = Json { ignoreUnknownKeys = true }

/**
 * A transcript worth showing for [episodeId], or null. Another episode's transcript, one with
 * no usable line, or one whose lines run backwards in time is refused rather than shown wrong.
 */
internal fun parsePodcastTranscript(text: String, episodeId: String): PodcastTranscript? {
    val decoded = runCatching { transcriptJson.decodeFromString<PodcastTranscript>(text) }.getOrNull() ?: return null
    if (decoded.episode != episodeId) return null
    val lines = decoded.lines.filter { line ->
        line.text.isNotBlank() && line.start.isFinite() && line.end.isFinite() && line.start >= 0 && line.end >= line.start
    }
    if (lines.isEmpty() || lines.zipWithNext().any { (before, after) -> after.start < before.start }) return null
    return decoded.copy(lines = lines)
}

/**
 * The line being spoken at [positionMs]: the last one that has started, or -1 before the first.
 * Between two lines the earlier one stays current, which reads better than the highlight
 * vanishing in every pause.
 */
internal fun podcastTranscriptLineAt(lines: List<PodcastTranscriptLine>, positionMs: Long): Int {
    val seconds = positionMs / 1000.0
    var low = 0
    var high = lines.lastIndex
    var found = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (lines[middle].start <= seconds) {
            found = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return found
}

/** What the playback service plays: an episode's lesson, or its original recording. */
internal data class PodcastMediaRef(val account: String, val episode: String, val original: Boolean)

internal fun podcastMediaId(account: String, episode: String, original: Boolean = false): String =
    if (original) "$account:$episode:original" else "$account:$episode"

/** The controller's media id, or null for anything this app did not make. */
internal fun parsePodcastMediaId(mediaId: String): PodcastMediaRef? {
    val parts = mediaId.split(':')
    val original = when {
        parts.size == 2 -> false
        parts.size == 3 && parts[2] == "original" -> true
        else -> return null
    }
    if (!validPodcastId(parts[0]) || !validPodcastId(parts[1])) return null
    return PodcastMediaRef(parts[0], parts[1], original)
}
