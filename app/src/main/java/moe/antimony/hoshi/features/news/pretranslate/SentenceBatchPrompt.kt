package moe.antimony.hoshi.features.news.pretranslate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import moe.antimony.hoshi.features.news.sha256Hex
import moe.antimony.hoshi.features.reader.sentence.ReaderSentence

/**
 * The batch contract with a cloud model: a fixed instruction, a JSON array of `{id, text}` items,
 * and a JSON array of `{id, translation, explanation}` back. Parsing is lenient about code fences,
 * reasoning blocks and prose around the array, salvages the complete items of a reply that was
 * cut off by an output-token cap, and matches every item by id so reordering or omissions are
 * detected instead of misattributed.
 */
object SentenceBatchPrompt {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val THINK_BLOCK = Regex("""<think>.*?(</think>|$)""", RegexOption.DOT_MATCHES_ALL)
    private val CODE_FENCE = Regex("""```[a-zA-Z]*""")

    fun instructions(includeExplanations: Boolean): String = buildString {
        append("You translate sentences from an easy Japanese news article for a language learner. ")
        append("For every item in the JSON array below, write a natural English translation")
        if (includeExplanations) {
            append(" and a short note (one or two lines, Markdown allowed) on vocabulary or grammar a learner might find tricky; ")
            append("leave the note empty when nothing is tricky")
        }
        append(". ")
        append("Reply with JSON only: an array with one object per input item, in the same order, ")
        append("shaped like {\"id\": string, \"translation\": string, \"explanation\": string}. ")
        append("Keep the id exactly as given. Do not add any text before or after the JSON.")
    }

    /** Stable identifier of the prompt, stored in the translation blob's `promptId`. */
    fun promptId(includeExplanations: Boolean): String =
        "sha256:" + sha256Hex(instructions(includeExplanations)).take(32)

    fun itemsJson(sentences: List<ReaderSentence>): String = json.encodeToString(
        JsonArray.serializer(),
        buildJsonArray {
            sentences.forEach { sentence ->
                add(buildJsonObject { put("id", sentence.id); put("text", sentence.text) })
            }
        },
    )

    /** The one-sentence prompt used when a batch reply left an item out. */
    fun singleInstructions(includeExplanations: Boolean): String = buildString {
        append("Translate this sentence from an easy Japanese news article into natural English")
        if (includeExplanations) append(", then add a short note on tricky vocabulary or grammar if any")
        append(". Reply with JSON only, shaped like {\"translation\": string, \"explanation\": string}.")
    }

    /**
     * Extracts translations from a model reply. Returns only well-formed items whose id is among
     * [expectedIds]; callers retry whatever is missing.
     */
    fun parseBatch(reply: String, expectedIds: Set<String>): Map<String, SentenceTranslation> {
        val cleaned = strip(reply)
        val array = parseArray(cleaned) ?: return emptyMap()
        val result = LinkedHashMap<String, SentenceTranslation>()
        for (element in array) {
            val item = runCatching { element.jsonObject }.getOrNull() ?: continue
            val id = item.string("id")?.trim() ?: continue
            if (id !in expectedIds || id in result) continue
            val translation = item.string("translation")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            result[id] = SentenceTranslation(id, translation, item.string("explanation")?.trim().orEmpty())
        }
        return result
    }

    /**
     * A single-sentence reply. JSON is required when the model attempted it; a plain-text reply
     * (no braces at all) is accepted as the translation because some models ignore the format
     * instruction, but a broken or truncated JSON object is rejected rather than stored as a fragment.
     */
    fun parseSingle(reply: String, sentenceId: String): SentenceTranslation? {
        val cleaned = strip(reply)
        val start = cleaned.indexOf('{')
        if (start >= 0) {
            val end = cleaned.lastIndexOf('}')
            val item = if (end > start) runCatching { json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonObject }.getOrNull() else null
            val translation = item?.string("translation")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return SentenceTranslation(sentenceId, translation, item.string("explanation")?.trim().orEmpty())
        }
        val plain = cleaned.trim()
        return plain.takeIf { it.isNotEmpty() && it.length < 2_000 }?.let { SentenceTranslation(sentenceId, it) }
    }

    private fun strip(reply: String): String = reply.replace(THINK_BLOCK, "").replace(CODE_FENCE, "")

    /**
     * Finds the reply's array. Tries the outermost `[...]` first; when that is not valid JSON
     * (typically because the output was truncated mid-item), parses the longest prefix that ends
     * at a complete object so the finished items are still used.
     */
    private fun parseArray(text: String): JsonArray? {
        val start = text.indexOf('[')
        if (start < 0) return null
        val end = text.lastIndexOf(']')
        if (end > start) {
            parseArrayOrNull(text.substring(start, end + 1))?.let { return it }
        }
        // Wrapped array: {"items": [...]} or similar.
        val objectStart = text.indexOf('{')
        if (objectStart in 0 until start) {
            runCatching { json.parseToJsonElement(text.substring(objectStart, text.lastIndexOf('}') + 1)).jsonObject }.getOrNull()
                ?.values?.firstOrNull { it is JsonArray }?.let { return it.jsonArray }
        }
        var closeBrace = text.lastIndexOf('}')
        while (closeBrace > start) {
            parseArrayOrNull(text.substring(start, closeBrace + 1) + "]")?.let { return it }
            closeBrace = text.lastIndexOf('}', closeBrace - 1)
        }
        return null
    }

    private fun parseArrayOrNull(candidate: String): JsonArray? =
        runCatching { json.parseToJsonElement(candidate) }.getOrNull()?.let { element: JsonElement -> element as? JsonArray }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
