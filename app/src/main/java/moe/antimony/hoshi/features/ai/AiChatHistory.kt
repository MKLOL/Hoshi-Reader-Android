package moe.antimony.hoshi.features.ai

import kotlinx.serialization.Serializable

/**
 * Image bytes attached to a ChatGPT exchange. Screenshot translation entries store the same
 * cropped PNG data URL payload that was sent to OpenAI, without the `data:` prefix.
 */
@Serializable
data class AiChatImage(
    val mimeType: String,
    val base64Data: String,
)

/**
 * One ChatGPT exchange about a manga bubble or screenshot: the text/label, prompt and model
 * used, optional screenshot image, and the model's reply. Persisted per-manga in
 * `ai_chat_log.json` in the book directory.
 *
 * [timestampSeconds] is in the same Apple-reference-date epoch the rest of the app's sidecar
 * files use (see `BookRepository.currentAppleReferenceDateSeconds`).
 */
@Serializable
data class AiChatEntry(
    val bubbleText: String,
    val prompt: String,
    val model: String,
    val response: String,
    val timestampSeconds: Double,
    val screenshotImage: AiChatImage? = null,
    val dictionaryLookup: AiChatDictionaryLookup? = null,
)

/** The per-manga ChatGPT history, newest entries last. */
@Serializable
data class AiChatLog(
    val entries: List<AiChatEntry> = emptyList(),
)

/** Compact Yomitan-style lookup context stored beside a ChatGPT exchange. */
@Serializable
data class AiChatDictionaryLookup(
    val query: String,
    val results: List<AiChatDictionaryLookupResult> = emptyList(),
)

@Serializable
data class AiChatDictionaryLookupResult(
    val expression: String,
    val reading: String,
    val matched: String,
    val deinflectionTrace: List<AiChatDeinflectionStep> = emptyList(),
    val glossaries: List<AiChatGlossary> = emptyList(),
    val frequencies: List<AiChatFrequencyGroup> = emptyList(),
    val pitches: List<AiChatPitchGroup> = emptyList(),
    val rules: List<String> = emptyList(),
)

@Serializable
data class AiChatDeinflectionStep(
    val name: String,
    val description: String,
)

@Serializable
data class AiChatGlossary(
    val dictionary: String,
    val content: String,
    val definitionTags: String,
    val termTags: String,
)

@Serializable
data class AiChatFrequencyGroup(
    val dictionary: String,
    val frequencies: List<AiChatFrequency> = emptyList(),
)

@Serializable
data class AiChatFrequency(
    val value: Int,
    val displayValue: String,
)

@Serializable
data class AiChatPitchGroup(
    val dictionary: String,
    val pitchPositions: List<Int> = emptyList(),
)
