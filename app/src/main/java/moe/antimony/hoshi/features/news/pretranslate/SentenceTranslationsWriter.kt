package moe.antimony.hoshi.features.news.pretranslate

import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.ai.EPUB_TRANSLATIONS_FILENAME
import moe.antimony.hoshi.features.ai.EpubTranslationStore
import moe.antimony.hoshi.features.sync.http.HttpSyncSentenceEntry
import moe.antimony.hoshi.features.sync.http.HttpSyncSentencesBlob

/**
 * Builds the `sentence_translations.json` blob the reader and sync already understand and writes
 * it into the book directory. The blob is validated with the same rules the sync importer applies
 * to a server copy, so a device that downloads it later cannot reject what this device wrote.
 */
object SentenceTranslationsWriter {
    private val json = Json { encodeDefaults = true }

    fun buildBlob(
        plan: PretranslationPlan,
        translations: Map<String, SentenceTranslation>,
        model: String,
        promptId: String,
        generatedAt: Instant = Instant.now(),
    ): HttpSyncSentencesBlob {
        val entries = LinkedHashMap<String, HttpSyncSentenceEntry>()
        for (sentence in plan.sentences) {
            val translation = translations[sentence.id] ?: continue
            val normalized = EpubTranslationStore.normalize(sentence.text)
            if (normalized.isEmpty()) continue
            entries[sentence.id] = HttpSyncSentenceEntry(
                spine = sentence.spine,
                start = sentence.start,
                len = normalized.codePointCount(0, normalized.length),
                text = sentence.text,
                hash = EpubTranslationStore.textHash(normalized),
                translation = translation.translation,
                explanation = translation.explanation,
            )
        }
        return HttpSyncSentencesBlob(
            version = 1,
            kind = HttpSyncSentencesBlob.EXPECTED_KIND,
            syncId = plan.syncId,
            title = plan.title,
            model = model,
            promptId = promptId,
            generatedAt = generatedAt.truncatedTo(ChronoUnit.SECONDS).toString(),
            spineCount = plan.spineCount,
            entries = entries,
        )
    }

    /** Returns the validation error for [blob], or null when the sync importer would accept it. */
    fun validationError(blob: HttpSyncSentencesBlob): String? =
        EpubTranslationStore.validationError(blob, blob.syncId, blob.spineCount)

    fun encode(blob: HttpSyncSentencesBlob): ByteArray =
        json.encodeToString(HttpSyncSentencesBlob.serializer(), blob).toByteArray(Charsets.UTF_8)

    /** Keeps another configuration's translations until the replacement covers the whole plan. */
    fun writeResult(bookRoot: File, blob: HttpSyncSentencesBlob, sentenceCount: Int): Boolean {
        if (blob.entries.size < sentenceCount &&
            hasSidecarForOtherConfiguration(bookRoot, blob.syncId, blob.model, blob.promptId)
        ) return false
        write(bookRoot, encode(blob))
        return true
    }

    /** Writes [bytes] atomically as the book's sidecar and drops the reader's cached copy. */
    fun write(bookRoot: File, bytes: ByteArray) {
        val target = File(bookRoot, EPUB_TRANSLATIONS_FILENAME)
        val temp = File(bookRoot, "$EPUB_TRANSLATIONS_FILENAME.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Unable to write $EPUB_TRANSLATIONS_FILENAME" }
        }
        EpubTranslationStore.invalidate(bookRoot)
    }

    /**
     * Whether the book already has a sidecar for another model/prompt combination. A partial
     * result must never replace such a complete blob; a same-configuration or missing sidecar may.
     */
    fun hasSidecarForOtherConfiguration(bookRoot: File, syncId: String, model: String, promptId: String): Boolean {
        val file = File(bookRoot, EPUB_TRANSLATIONS_FILENAME)
        if (!file.isFile) return false
        val blob = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(HttpSyncSentencesBlob.serializer(), file.readText()) }
            .getOrNull() ?: return false
        return blob.syncId == syncId && (blob.model != model || blob.promptId != promptId)
    }

    /**
     * Sentences the existing sidecar already covers with the same model and prompt, so a re-run
     * only pays for what is missing while a run with a different model or note setting starts over.
     */
    fun existingTranslations(bookRoot: File, plan: PretranslationPlan, model: String, promptId: String): Map<String, SentenceTranslation> {
        val file = File(bookRoot, EPUB_TRANSLATIONS_FILENAME)
        if (!file.isFile) return emptyMap()
        val blob = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(HttpSyncSentencesBlob.serializer(), file.readText()) }
            .getOrNull() ?: return emptyMap()
        if (blob.syncId != plan.syncId || blob.model != model || blob.promptId != promptId) return emptyMap()
        val byId = plan.sentences.associateBy { it.id }
        return blob.entries.mapNotNull { (id, entry) ->
            val sentence = byId[id] ?: return@mapNotNull null
            if (entry.hash != EpubTranslationStore.textHash(EpubTranslationStore.normalize(sentence.text))) return@mapNotNull null
            id to SentenceTranslation(id, entry.translation, entry.explanation)
        }.toMap()
    }
}
