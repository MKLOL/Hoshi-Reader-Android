package moe.antimony.hoshi.features.ai

import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.sync.http.PretranslationEntryBlob
import moe.antimony.hoshi.features.sync.http.PretranslationsBlob
import java.io.File
import java.security.MessageDigest

/** On-disk name of a book's offline translation cache, synced from `books/{syncId}/pretranslations`. */
const val PRETRANSLATIONS_FILENAME: String = "pretranslations.json"

/** One resolved offline translation for a bubble. */
data class Pretranslation(
    val bubbleText: String,
    val translation: String,
    val explanation: String,
    /** The model that produced it, so a cached reply is never mistaken for a live one. */
    val model: String,
) {
    /**
     * The popup renders Markdown, so present the translation and explanation the same way the
     * online tutor prompt does.
     */
    val markdownResponse: String
        get() = if (explanation.isBlank()) translation else "$translation\n\n$explanation"
}

/**
 * Reads the per-volume offline translation cache and resolves a tapped speech bubble to its
 * pre-computed translation + explanation.
 *
 * These are produced ahead of time by the desktop tool (tools/pretranslate in the iOS repo) and
 * are strictly a read-only cache: nothing here writes to `ai_chat_log.json`, because a cache hit is
 * not a conversation the user had.
 *
 * Bubble addressing: the primary key is the bubble's mokuro address, `p{page}b{block}`, carried
 * through the page HTML as `data-hoshi-block`. Because that address comes from the OCR file it is
 * identical on every device and survives re-imports. The stored text hash guards against a re-OCR
 * having changed what the address points at; when it has, a whole-text match is tried instead.
 *
 * Mirrors the iOS `PretranslationStore`.
 */
object PretranslationStore {

    /** Highest blob version this build understands; see the Python writer's BLOB_VERSION. */
    const val SUPPORTED_BLOB_VERSION: Int = 1

    private class Loaded(
        val blob: PretranslationsBlob,
        /** Secondary index: source-text hash -> entry, for when block indices have shifted. */
        val byHash: Map<String, PretranslationEntryBlob>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Keyed by book directory path. A volume's blob is a few hundred KB and only one book is open
     * at a time, so caching the parsed form avoids re-reading on every bubble tap.
     */
    private val cache = HashMap<String, Loaded?>()

    /** Drops the cached parse for a book (called after sync writes a fresh blob). */
    @Synchronized
    fun invalidate(bookRoot: File) {
        cache.remove(bookRoot.absolutePath)
    }

    @Synchronized
    fun invalidateAll() {
        cache.clear()
    }

    /**
     * Parses and caches a book's blob ahead of time.
     *
     * Call this off the main thread when the reader opens: the blob can be a few MB, and
     * [lookup] is invoked synchronously from the bubble-tap handler on the UI thread.
     */
    @Synchronized
    fun preload(bookRoot: File) {
        load(bookRoot)
    }

    /** True when this book has any offline translations available. */
    @Synchronized
    fun hasPretranslations(bookRoot: File): Boolean = load(bookRoot) != null

    /** Number of cached bubbles for the book, for settings/diagnostics display. */
    @Synchronized
    fun count(bookRoot: File): Int = load(bookRoot)?.blob?.entries?.size ?: 0

    /**
     * Resolves a tapped bubble.
     *
     * @param blockId the bubble's `p{page}b{block}` address, when the reader knew it.
     * @param bubbleText the OCR text actually tapped; validates the hit and acts as a fallback key.
     */
    @Synchronized
    fun lookup(bookRoot: File, blockId: String?, bubbleText: String): Pretranslation? {
        val loaded = load(bookRoot) ?: return null
        val trimmed = bubbleText.trim()
        if (trimmed.isEmpty()) return null
        val wantedHash = textHash(trimmed)

        if (!blockId.isNullOrEmpty()) {
            val entry = loaded.blob.entries[blockId]
            // Trust the address only while the text still matches. A blob generated before a
            // re-OCR would otherwise hand back a neighbouring line with total confidence.
            if (entry != null && (entry.hash == wantedHash || entry.text == trimmed)) {
                return entry.toPretranslation(loaded.blob.model)
            }
        }
        // Either no address (an older page render) or the address went stale — fall back to the
        // text itself. Identical lines elsewhere in the volume share a translation, which is the
        // right answer for a bubble whose entire content is "うん".
        return loaded.byHash[wantedHash]?.toPretranslation(loaded.blob.model)
    }

    private fun PretranslationEntryBlob.toPretranslation(model: String) = Pretranslation(
        bubbleText = text,
        translation = translation,
        explanation = explanation,
        model = model,
    )

    private fun load(bookRoot: File): Loaded? {
        val key = bookRoot.absolutePath
        if (cache.containsKey(key)) return cache[key]
        val loaded = readFromDisk(bookRoot)
        cache[key] = loaded
        return loaded
    }

    private fun readFromDisk(bookRoot: File): Loaded? {
        val file = File(bookRoot, PRETRANSLATIONS_FILENAME)
        if (!file.isFile) return null
        val blob = runCatching {
            json.decodeFromString(PretranslationsBlob.serializer(), file.readText(Charsets.UTF_8))
        }.getOrNull() ?: return null
        // Every field decodes leniently, so an unknown future shape would otherwise present as a
        // blob with zero entries and silently serve nothing instead of falling back to the live
        // path. Reject it outright instead.
        if (blob.version > SUPPORTED_BLOB_VERSION || blob.entries.isEmpty()) return null
        val byHash = HashMap<String, PretranslationEntryBlob>(blob.entries.size)
        for (entry in blob.entries.values) {
            byHash.putIfAbsent(entry.hash, entry)
        }
        return Loaded(blob, byHash)
    }

    /**
     * Short sha256 of the source Japanese. Must match the writer in
     * tools/pretranslate/hoshi_pretranslate/blob.py (`sha256(...).hexdigest()[:16]`) and the iOS
     * reader's `PretranslationStore.textHash`.
     */
    fun textHash(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
}
