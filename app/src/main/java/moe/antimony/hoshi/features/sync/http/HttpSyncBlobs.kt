package moe.antimony.hoshi.features.sync.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatDictionaryLookup
import moe.antimony.hoshi.features.ai.AiChatImage
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
 *   books/{syncId}/pretranslations   → [PretranslationsBlob]    JSON, one per book, download-only
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
    /**
     * Bookshelf shelf/folder placement. `null` means the book is intentionally unshelved.
     * Older blobs may omit this field entirely; pull code treats omission as "leave the
     * local shelf alone" so legacy sync metadata cannot wipe local organization.
     */
    val shelfName: String? = null,
    /**
     * RFC 3339 UTC — when local shelf organization last changed. Used to avoid applying
     * stale remote shelf placement over a newer local move. Optional for older blobs.
     */
    val shelfUpdatedAt: String? = null,
    /** RFC 3339 UTC — when the book was first imported on this device. Optional. */
    val importedAt: String? = null,
    /** RFC 3339 UTC — set when the user deletes the book; other devices honour it. */
    val deletedAt: String? = null,
    /**
     * Edit-depth revision (Lamport counter). Each deliberate local edit sets
     * `rev = max(localRev, lastSeenRemoteRev) + 1`. The event timestamp chooses the winner;
     * `rev` is the deterministic tie-breaker. `null` (legacy blobs) is treated as 0.
     * See [compareRevisioned] and [HttpSyncRevisionStore].
     */
    val rev: Int? = null,
)

@Serializable
data class HttpSyncBookmarkBlob(
    val chapterIndex: Int,
    val progress: Double,
    val characterCount: Int,
    /** RFC 3339 UTC — primary last-write-wins timestamp when pulling. */
    val lastModified: String,
    /** Edit-depth revision — see [HttpSyncMetadataBlob.rev]. `null` (legacy) == 0. */
    val rev: Int? = null,
)

@Serializable
data class HttpSyncChatEntryBlob(
    val bubbleText: String,
    val prompt: String,
    val model: String,
    val response: String,
    /** Apple-reference seconds, the same epoch used by the on-disk `ai_chat_log.json`. */
    val timestampSeconds: Double,
    /** Optional cropped screenshot attached to screenshot-translation entries. */
    val screenshotImage: AiChatImage? = null,
    /** Optional compact Yomitan-style dictionary context captured with the chat entry. */
    val dictionaryLookup: AiChatDictionaryLookup? = null,
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
    /** Edit-depth revision — see [HttpSyncMetadataBlob.rev]. `null` (legacy) == 0. */
    val rev: Int? = null,
)

/** One pre-computed sentence translation inside [HttpSyncSentencesBlob]. */
@Serializable
data class HttpSyncSentenceEntry(
    val spine: Int,
    val start: Int,
    val len: Int,
    val text: String,
    val hash: String,
    val translation: String,
    val explanation: String = "",
)

/**
 * Offline sentence translations for one EPUB, stored at `books/{syncId}/sentences` and installed
 * locally as `sentence_translations.json`. Field names and defaults match the iOS/Python writer.
 */
@Serializable
data class HttpSyncSentencesBlob(
    val version: Int = 1,
    val kind: String = "",
    val syncId: String = "",
    val title: String = "",
    val model: String = "",
    val promptId: String = "",
    val generatedAt: String = "",
    val spineCount: Int = 0,
    val entries: Map<String, HttpSyncSentenceEntry> = emptyMap(),
) {
    companion object {
        const val SUPPORTED_VERSION: Int = 1
        const val EXPECTED_KIND: String = "epub"
    }
}

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

/**
 * iOS-compatible identity for a new local import. A uniquified folder makes duplicate-title
 * books distinct while preserving the historical title-only ID for ordinary imports.
 */
internal fun deriveSyncId(title: String?, folderName: String?): String? {
    val base = deriveSyncId(title) ?: return null
    val folder = folderName?.trim().orEmpty()
    if (folder.isEmpty()) return base
    val folderDerived = deriveSyncId(folder)
    if (folderDerived == null || folderDerived == base) return base
    val hash = shortTitleHash(folder)
    val prefixLength = SYNC_ID_MAX_SEGMENT_LENGTH - hash.length - 1
    val prefix = base.take(prefixLength.coerceAtLeast(1)).trim('_').ifEmpty { "book" }
    return "${prefix}_$hash"
}

internal fun syncIdForMetadata(metadata: BookMetadata): String? =
    metadata.syncId?.takeIf(::isValidSyncId) ?: deriveSyncId(metadata.title, metadata.folder)

internal fun isValidSyncId(syncId: String): Boolean =
    syncId.length in 1..SYNC_ID_MAX_SEGMENT_LENGTH && syncId.all {
        it in 'a'..'z' ||
            it in 'A'..'Z' ||
            it in '0'..'9' ||
            it == '_' ||
            it == '.' ||
            it == '-'
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

/**
 * One blob per book holding every bubble's pre-computed offline translation. Deliberately a
 * single key rather than one per bubble: a volume has thousands of bubbles and the server is
 * rate-limited, so per-bubble keys would take minutes per book to pull.
 */
internal fun pretranslationsKey(syncId: String): String = "books/$syncId/pretranslations"
internal fun sentencesKey(syncId: String): String = "books/$syncId/sentences"
internal const val MAX_EPUB_SENTENCES_BLOB_BYTES: Int = 32 * 1024 * 1024
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

// ----- Edit-depth revision comparison (cross-device-critical; mirror of iOS SyncCore) -----

/** Which side of a sync comparison should win for a revisioned blob. */
enum class SyncComparison { LOCAL_WINS, REMOTE_WINS, TIE }

/**
 * Compares two revisioned blob states by the user's event timestamp first. The revision is a
 * deterministic tie-breaker only. This is intentionally safe for an upgraded client whose
 * already-downloaded bookmark has no revision sidecar: a stale high server revision must never
 * move a later local reading position backward.
 *
 * This stops an upgraded device with no revision sidecar from losing a genuinely later bookmark
 * to an older server blob that happens to carry a larger historical revision.
 *
 * Mirror of iOS `SyncCore.compareRevisioned` — both platforms must agree byte-for-byte on the
 * decision table (see SyncConformanceTest).
 */
internal fun compareRevisioned(
    localRev: Int?,
    remoteRev: Int?,
    localStamp: String?,
    remoteStamp: String?,
): SyncComparison {
    val cmp = compareRfc3339(localStamp, remoteStamp)
    if (cmp != 0) return if (cmp > 0) SyncComparison.LOCAL_WINS else SyncComparison.REMOTE_WINS
    val lr = localRev ?: 0
    val rr = remoteRev ?: 0
    return when {
        lr > rr -> SyncComparison.LOCAL_WINS
        lr < rr -> SyncComparison.REMOTE_WINS
        else -> SyncComparison.TIE
    }
}

/**
 * Shelf-placement LWW: apply the remote placement iff it is at least as fresh as the local
 * one (ties go to remote). Shared by the v2 reconciler, the v3 planner, and the
 * fire-and-forget metadata push. Mirror of iOS `SyncCore.shouldApplyRemoteShelfPlacement`.
 */
internal fun shouldApplyRemoteShelfPlacement(
    remoteShelfUpdatedAt: String?,
    localShelvesUpdatedAt: String?,
): Boolean {
    if (localShelvesUpdatedAt == null) return true
    if (remoteShelfUpdatedAt == null) return false
    return compareRfc3339(remoteShelfUpdatedAt, localShelvesUpdatedAt) >= 0
}

/**
 * Re-import-after-tombstone guard shared by the v2 reconciler and the v3 planner.
 *
 * Returns `true` iff the local `BookMetadata.importedAt` is **strictly later** than the
 * remote `metadata.deletedAt`, in which case the user re-imported the book after the
 * tombstone was published and the live local copy wins (the tombstone gets overwritten
 * with `deletedAt = null` on the next push).
 *
 * Conservative on missing data:
 *  - If [localImportedAt] is null (legacy book written before this field existed) or fails
 *    RFC 3339 parsing, we cannot prove a post-tombstone import and the tombstone wins.
 *  - If [remoteDeletedAt] is null, the call site already skipped the tombstone branch — we
 *    return `false` for safety.
 *  - Strict `>` (not `>=`): a tie on the wallclock isn't strong enough evidence that the
 *    import happened after the deletion. Both engines stamp with RFC 3339 millisecond
 *    precision, so true ties only happen for hand-crafted timestamps.
 */
internal fun localImportedAtOverridesRemoteDeletion(
    localImportedAt: String?,
    remoteDeletedAt: String?,
): Boolean {
    if (localImportedAt == null || remoteDeletedAt == null) return false
    val localInstant = runCatching { Instant.parse(localImportedAt) }.getOrNull() ?: return false
    val remoteInstant = runCatching { Instant.parse(remoteDeletedAt) }.getOrNull() ?: return false
    return localInstant.isAfter(remoteInstant)
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
    screenshotImage = screenshotImage,
    dictionaryLookup = dictionaryLookup,
)

/** Same-entry detection for inbound dedup: mirrors the content-addressed chat key shape. */
internal fun AiChatEntry.matchesEntry(other: AiChatEntry): Boolean =
    bubbleText == other.bubbleText &&
        timestampSeconds == other.timestampSeconds &&
        response == other.response


/**
 * One pre-computed translation + explanation for a single speech bubble.
 *
 * [hash] is a short sha256 of the source Japanese. The reader compares it against the bubble it
 * actually tapped, so a re-OCR that shifted the text is detected instead of silently serving the
 * wrong line.
 */
@Serializable
internal data class PretranslationEntryBlob(
    val text: String = "",
    val hash: String = "",
    val translation: String = "",
    val explanation: String = "",
)

/**
 * Every bubble's offline translation for one volume, produced by the desktop pre-translation tool
 * (tools/pretranslate in the iOS repo) and stored at `books/{syncId}/pretranslations`.
 *
 * [entries] is keyed by the bubble's mokuro address, `p{pageIndex}b{blockIndex}` — derived from the
 * OCR file itself, so it is identical on every device and survives re-imports. A flat map keeps a
 * lookup to one hash-map hit.
 *
 * Field names MUST match the iOS `HttpSyncPretranslationsBlob` and the Python writer in
 * tools/pretranslate/hoshi_pretranslate/blob.py.
 */
@Serializable
internal data class PretranslationsBlob(
    val version: Int = 1,
    val syncId: String = "",
    val title: String = "",
    /** Which model produced these, surfaced in the popup and used to invalidate after a re-run. */
    val model: String = "",
    val promptId: String = "",
    val generatedAt: String = "",
    val entries: Map<String, PretranslationEntryBlob> = emptyMap(),
)
