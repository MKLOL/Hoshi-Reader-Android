package moe.antimony.hoshi.features.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.sync.http.HttpSyncSentenceEntry
import moe.antimony.hoshi.features.sync.http.HttpSyncSentencesBlob
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

const val EPUB_TRANSLATIONS_FILENAME: String = "sentence_translations.json"

data class EpubSentenceTranslation(
    val sentenceText: String,
    val translation: String,
    val explanation: String,
    val model: String,
) {
    val markdownResponse: String
        get() = if (explanation.isBlank()) translation else "$translation\n\n$explanation"
}

@Serializable
data class EpubSentenceAnchor(
    val id: String,
    val start: Int,
    val len: Int,
    val text: String,
)

/** Fail-closed, read-only cache for iOS-compatible EPUB sentence translations. */
object EpubTranslationStore {
    private val json = Json { ignoreUnknownKeys = true }

    private data class Loaded(
        val blob: HttpSyncSentencesBlob,
        val expectedSyncId: String,
        val expectedSpineCount: Int,
        val anchorsBySpine: Map<Int, List<EpubSentenceAnchor>>,
    )

    private val cache = ConcurrentHashMap<String, Loaded>()
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    fun invalidate(bookRoot: File) {
        val key = bookRoot.canonicalPath
        val generation = generations.computeIfAbsent(key) { AtomicLong() }
        synchronized(generation) {
            cache.remove(key)
            generation.incrementAndGet()
        }
    }

    fun invalidateAll() {
        cache.clear()
        generations.forEach { (key, generation) ->
            synchronized(generation) {
                cache.remove(key)
                generation.incrementAndGet()
            }
        }
    }

    fun preload(bookRoot: File, expectedSyncId: String, spineCount: Int): Boolean {
        val key = bookRoot.canonicalPath
        cache[key]?.let { loaded ->
            if (loaded.expectedSyncId == expectedSyncId && loaded.expectedSpineCount == spineCount) {
                return true
            }
        }
        val generationCounter = generations.computeIfAbsent(key) { AtomicLong() }
        val generation = synchronized(generationCounter) { generationCounter.get() }
        val loaded = readFromDisk(bookRoot, expectedSyncId, spineCount) ?: return false
        return synchronized(generationCounter) {
            if (generationCounter.get() != generation) {
                false
            } else {
                cache[key] = loaded
                true
            }
        }
    }

    fun anchors(
        bookRoot: File,
        expectedSyncId: String,
        spineCount: Int,
        spine: Int,
    ): List<EpubSentenceAnchor> =
        validated(bookRoot, expectedSyncId, spineCount)?.anchorsBySpine?.get(spine).orEmpty()

    fun lookup(
        renderedId: String,
        bookRoot: File,
        expectedSyncId: String,
        spineCount: Int,
    ): EpubSentenceTranslation? {
        val loaded = validated(bookRoot, expectedSyncId, spineCount) ?: return null
        val separator = renderedId.lastIndexOf('#')
        if (separator <= 0 || separator == renderedId.lastIndex) return null
        val id = renderedId.substring(0, separator)
        val renderedHash = renderedId.substring(separator + 1)
        val entry = loaded.blob.entries[id] ?: return null
        if (entry.hash != renderedHash) return null
        return EpubSentenceTranslation(
            sentenceText = entry.text,
            translation = entry.translation,
            explanation = entry.explanation,
            model = loaded.blob.model,
        )
    }

    internal fun decodeAndValidate(
        body: String,
        expectedSyncId: String,
        expectedSpineCount: Int,
    ): HttpSyncSentencesBlob {
        val blob = runCatching {
            json.decodeFromString(HttpSyncSentencesBlob.serializer(), body)
        }.getOrElse { error ->
            throw IllegalArgumentException("malformed JSON (${error.message ?: error.javaClass.simpleName})", error)
        }
        validationError(blob, expectedSyncId, expectedSpineCount)?.let { error ->
            throw IllegalArgumentException(error)
        }
        return blob
    }

    internal fun validationError(
        blob: HttpSyncSentencesBlob,
        expectedSyncId: String,
        expectedSpineCount: Int,
    ): String? {
        if (blob.version !in 1..HttpSyncSentencesBlob.SUPPORTED_VERSION) {
            return "unsupported version ${blob.version}"
        }
        if (blob.kind != HttpSyncSentencesBlob.EXPECTED_KIND) return "wrong kind ${blob.kind.ifEmpty { "(none)" }}"
        if (expectedSyncId.isEmpty() || blob.syncId != expectedSyncId) {
            return "syncId ${blob.syncId.ifEmpty { "(none)" }} does not match $expectedSyncId"
        }
        if (expectedSpineCount <= 0 || blob.spineCount != expectedSpineCount) {
            return "spineCount ${blob.spineCount} does not match EPUB spine count $expectedSpineCount"
        }
        if (blob.entries.isEmpty()) return "no entries"
        for ((id, entry) in blob.entries) {
            validateEntry(id, entry, expectedSpineCount)?.let { return it }
        }
        return null
    }

    private fun validateEntry(id: String, entry: HttpSyncSentenceEntry, spineCount: Int): String? {
        if (entry.spine !in 0 until spineCount) return "entry $id has out-of-bounds spine ${entry.spine}"
        if (entry.start < 0 || entry.len <= 0 || entry.start > Int.MAX_VALUE - entry.len) {
            return "entry $id has invalid start/len"
        }
        if (id != "c${entry.spine}s${entry.start}") return "entry $id does not match its address"
        val normalized = normalize(entry.text)
        if (normalized.codePointCount(0, normalized.length) != entry.len) {
            return "entry $id length does not match normalized text"
        }
        if (entry.hash != textHash(normalized)) return "entry $id hash does not match normalized text"
        if (entry.translation.isBlank()) return "entry $id has an empty translation"
        return null
    }

    internal fun normalize(text: String): String = buildString(text.length) {
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            if (isMatchableCodePoint(codePoint)) appendCodePoint(codePoint)
            offset += Character.charCount(codePoint)
        }
    }

    internal fun textHash(normalizedText: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(normalizedText.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }

    internal fun isMatchableCodePoint(codePoint: Int): Boolean =
        codePoint in '0'.code..'9'.code ||
            codePoint in 'A'.code..'Z'.code ||
            codePoint in 'a'.code..'z'.code ||
            codePoint == 0x25CB || codePoint == 0x25EF ||
            codePoint in 0x3005..0x3007 || codePoint == 0x303B ||
            codePoint in 0x3041..0x3096 || codePoint in 0x309D..0x309E ||
            codePoint in 0x30A1..0x30FA || codePoint == 0x30FC ||
            codePoint in 0xFF10..0xFF19 || codePoint in 0xFF21..0xFF3A ||
            codePoint in 0xFF41..0xFF5A || codePoint in 0xFF66..0xFF9D ||
            isRadical(codePoint) || isUnifiedIdeograph(codePoint)

    private fun isRadical(codePoint: Int): Boolean =
        codePoint in 0x2E80..0x2EF3 || codePoint in 0x2F00..0x2FD5

    private fun isUnifiedIdeograph(codePoint: Int): Boolean =
        codePoint in 0x3400..0x4DBF ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0x20000..0x2A6DF ||
            codePoint in 0x2A700..0x2B739 ||
            codePoint in 0x2B740..0x2B81D ||
            codePoint in 0x2B820..0x2CEA1 ||
            codePoint in 0x2CEB0..0x2EBE0 ||
            codePoint in 0x2EBF0..0x2EE5D ||
            codePoint in 0x30000..0x3134A ||
            codePoint in 0x31350..0x323AF ||
            codePoint in setOf(
                0xFA0E, 0xFA0F, 0xFA11, 0xFA13, 0xFA14, 0xFA1F,
                0xFA21, 0xFA23, 0xFA24, 0xFA27, 0xFA28, 0xFA29,
            )

    private fun validated(bookRoot: File, expectedSyncId: String, spineCount: Int): Loaded? {
        val loaded = cache[bookRoot.canonicalPath] ?: return null
        return loaded.takeIf {
            it.expectedSyncId == expectedSyncId && it.expectedSpineCount == spineCount
        }
    }

    private fun readFromDisk(bookRoot: File, expectedSyncId: String, spineCount: Int): Loaded? {
        val body = runCatching {
            File(bookRoot, EPUB_TRANSLATIONS_FILENAME).readText(Charsets.UTF_8)
        }.getOrNull() ?: return null
        val blob = runCatching { decodeAndValidate(body, expectedSyncId, spineCount) }.getOrNull() ?: return null
        val anchors = blob.entries.entries
            .groupBy(
                keySelector = { it.value.spine },
                valueTransform = { (id, entry) ->
                    EpubSentenceAnchor(
                        id = "$id#${entry.hash}",
                        start = entry.start,
                        len = entry.len,
                        text = normalize(entry.text),
                    )
                },
            )
            .mapValues { (_, entries) -> entries.sortedBy(EpubSentenceAnchor::start) }
        return Loaded(blob, expectedSyncId, spineCount, anchors)
    }
}
