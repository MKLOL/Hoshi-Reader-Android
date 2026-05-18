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
)

/** The per-manga ChatGPT history, newest entries last. */
@Serializable
data class AiChatLog(
    val entries: List<AiChatEntry> = emptyList(),
)
