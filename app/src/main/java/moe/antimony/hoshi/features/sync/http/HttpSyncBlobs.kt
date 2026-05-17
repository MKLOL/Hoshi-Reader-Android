package moe.antimony.hoshi.features.sync.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatSettings
import java.security.MessageDigest
import java.time.Instant

/**
 * The Hoshi-specific JSON schemas that get serialized into the bytes we PUT to each KV key.
 *
 * The server is content-blind (see [HttpSyncKvTransport]); these shapes are an
 * **Android-client-only** contract. If iOS ever adopts the same protocol, it has to agree
 * with these field names; for now this is the source of truth.
 *
 * Key layout (also documented in `docs/HTTP_SYNC_KV.md`):
 *
 *   books/{syncId}/metadata          → [HttpSyncMetadataBlob]    JSON, overwrite
 *   books/{syncId}/bookmark          → [HttpSyncBookmarkBlob]    JSON, overwrite per page-turn batch
 *   books/{syncId}/chat/{ts}-{hash}  → [HttpSyncChatEntryBlob]   JSON, write-once
 *   books/{syncId}/payload.zip       → bytes                    application/zip, follow-up scope
 *   books/{syncId}/payload.manifest  → JSON                     follow-up scope
 *
 * The `{ts}-{hash}` suffix for chat keys is built by [chatEntryKeySuffix] — RFC 3339
 * timestamp + a short content hash, so two devices that produced the same entry collide
 * onto the same key (idempotent) and different entries never collide.
 */

// ----- Per-key wire shapes ---------------------------------------------------------------

@Serializable
data class HttpSyncMetadataBlob(
    val title: String,
    val contentType: HttpSyncContentType,
    /** RFC 3339 UTC — when the book was first imported on this device. Optional. */
    val importedAt: String? = null,
    /** RFC 3339 UTC — set when the user deletes the book; other devices honour it. */
    val deletedAt: String? = null,
)

@Serializable
data class HttpSyncBookmarkBlob(
    val chapterIndex: Int,
    val progress: Double,
    val characterCount: Int,
    /** RFC 3339 UTC — used by the client to last-write-wins when pulling from the server. */
    val lastModified: String,
)

@Serializable
data class HttpSyncChatEntryBlob(
    val bubbleText: String,
    val prompt: String,
    val model: String,
    val response: String,
    /** Apple-reference seconds, the same epoch used by the on-disk `ai_chat_log.json`. */
    val timestampSeconds: Double,
)

/**
 * Cross-device ChatGPT settings: the model, speech-bubble prompt, and screenshot image prompt.
 * **The API key is NOT in here on purpose** — it stays per-device for security; pasting a key
 * on one phone never leaks it through sync to anywhere else. `lastModified` is the LWW
 * tiebreaker.
 */
@Serializable
data class HttpSyncAiChatSettingsBlob(
    val model: String,
    val promptText: String,
    val imagePromptText: String = AiChatSettings.DEFAULT_IMAGE_PROMPT,
    val lastModified: String,
)

@Serializable
enum class HttpSyncContentType {
    @SerialName("epub") Epub,
    @SerialName("mokuro") Mokuro;

    fun toLocal(): ContentType = when (this) {
        Epub -> ContentType.Epub
        Mokuro -> ContentType.Mokuro
    }

    companion object {
        fun fromLocal(content: ContentType): HttpSyncContentType = when (content) {
            ContentType.Epub -> Epub
            ContentType.Mokuro -> Mokuro
        }
    }
}

// ----- Key builders ----------------------------------------------------------------------

/**
 * Computes a stable, server-safe sync id from a book's title. Lowercase, replace non-ASCII-
 * alphanumerics with `_`, collapse runs, trim — same rule as the legacy v1 protocol while
 * the result fits the v2 server's 64-character key-segment limit.
 *
 * Titles that produce no ASCII slug (for example Japanese-only titles), or an overlong slug,
 * get a deterministic short hash suffix. This keeps normal v1/v2 ASCII sync ids unchanged
 * while avoiding `400 invalid key` responses from the v2 KV server.
 *
 * Returns `null` for a blank title; the manager skips books that can't compute a syncId.
 */
internal fun deriveSyncId(title: String?): String? {
    val raw = title?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val sanitized = buildString {
        for (ch in raw.lowercase()) {
            if (ch in 'a'..'z' || ch in '0'..'9') append(ch) else append('_')
        }
    }
        .replace(Regex("_+"), "_")
        .trim('_')
    if (sanitized.isEmpty()) return "book_${shortTitleHash(raw)}"
    if (sanitized.length <= SYNC_ID_MAX_SEGMENT_LENGTH) return sanitized

    val hash = shortTitleHash(raw)
    val prefixLength = SYNC_ID_MAX_SEGMENT_LENGTH - hash.length - 1
    val prefix = sanitized.take(prefixLength).trim('_').ifEmpty { "book" }
    return "${prefix}_$hash"
}

private const val SYNC_ID_MAX_SEGMENT_LENGTH = 64
private const val SYNC_ID_HASH_HEX_LENGTH = 16

private fun shortTitleHash(title: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(title.toByteArray(Charsets.UTF_8))
        .take(SYNC_ID_HASH_HEX_LENGTH / 2)
        .joinToString("") { "%02x".format(it) }

internal fun bookmarkKey(syncId: String): String = "books/$syncId/bookmark"
internal fun metadataKey(syncId: String): String = "books/$syncId/metadata"
internal fun chatPrefixForBook(syncId: String): String = "books/$syncId/chat/"
internal fun chatKey(syncId: String, suffix: String): String = "books/$syncId/chat/$suffix"
internal const val ALL_BOOKS_PREFIX: String = "books/"

/** Single key for the cross-device ChatGPT settings (model + prompts). */
internal const val AI_CHAT_SETTINGS_KEY: String = "app/ai_chat_settings"

/**
 * Builds the suffix of a chat entry's KV key: `{rfc3339_utc}-{8-hex-content-hash}`.
 *
 * The content hash is the first 8 hex chars of sha256("bubbleText|response"). Two devices
 * that produce the same entry (same bubble, same response, same wallclock second) generate
 * the same suffix and converge on one server-side blob; truly different entries never
 * collide. The timestamp prefix makes the lexicographic key order roughly chronological,
 * which matters because the server-side `?since=` filter compares `lastModified` and the
 * fallback list-paginate compares keys.
 */
internal fun chatEntryKeySuffix(timestampAppleSeconds: Double, bubbleText: String, response: String): String {
    val rfc3339 = appleSecondsToRfc3339(timestampAppleSeconds).replace(':', '-')
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$bubbleText|$response".toByteArray(Charsets.UTF_8))
    val short = digest.copyOfRange(0, 4).joinToString("") { "%02x".format(it) }
    return "$rfc3339-$short"
}

// ----- Timestamp helpers (Apple-reference seconds ⇄ RFC 3339) -----------------------------

/** Apple's reference date in Unix epoch seconds (2001-01-01T00:00:00Z). */
private const val APPLE_REFERENCE_EPOCH: Long = 978_307_200L

internal fun appleSecondsToRfc3339(appleSeconds: Double): String {
    val unixSeconds = appleSeconds + APPLE_REFERENCE_EPOCH
    val instant = Instant.ofEpochMilli((unixSeconds * 1000.0).toLong())
    return instant.toString()
}

internal fun rfc3339ToAppleSeconds(rfc3339: String): Double {
    val instant = runCatching { Instant.parse(rfc3339) }.getOrNull() ?: return 0.0
    val unixMillis = instant.toEpochMilli()
    return unixMillis / 1000.0 - APPLE_REFERENCE_EPOCH
}

internal fun compareRfc3339(a: String?, b: String?): Int {
    val leftInstant = a?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val rightInstant = b?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return if (leftInstant != null && rightInstant != null) {
        leftInstant.compareTo(rightInstant)
    } else {
        (a ?: "").compareTo(b ?: "")
    }
}

/** Returns whichever of two RFC 3339 timestamps is later, or `null` if both are null. */
internal fun maxRfc(left: String?, right: String?): String? = when {
    left == null -> right
    right == null -> left
    compareRfc3339(left, right) >= 0 -> left
    else -> right
}

// ----- Local ⇄ wire conversions ----------------------------------------------------------
// Lives here (next to the wire schemas) rather than in HttpSyncManager so the manager
// doesn't have to know about the Bookmark / AiChatEntry shapes beyond what these helpers
// hide. The `internal` visibility keeps these out of public API.

internal fun Bookmark.toBlob(): HttpSyncBookmarkBlob = HttpSyncBookmarkBlob(
    chapterIndex = chapterIndex,
    progress = progress,
    characterCount = characterCount,
    lastModified = lastModified?.let(::appleSecondsToRfc3339)
        ?: appleSecondsToRfc3339(0.0),
)

internal fun AiChatEntry.toBlob(): HttpSyncChatEntryBlob = HttpSyncChatEntryBlob(
    bubbleText = bubbleText,
    prompt = prompt,
    model = model,
    response = response,
    timestampSeconds = timestampSeconds,
)

/** Same-entry detection for inbound dedup: mirrors the content-addressed chat key shape. */
internal fun AiChatEntry.matchesEntry(other: AiChatEntry): Boolean =
    bubbleText == other.bubbleText &&
        timestampSeconds == other.timestampSeconds &&
        response == other.response
