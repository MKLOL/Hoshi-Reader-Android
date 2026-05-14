package moe.antimony.hoshi.features.ai

import kotlinx.serialization.Serializable

/**
 * One ChatGPT exchange about a manga speech bubble: the bubble's OCR text, the prompt and
 * model used, and the model's reply. Persisted per-manga in `ai_chat_log.json` in the book
 * directory (see [moe.antimony.hoshi.epub.BookRepository.loadAiChatLog]).
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
)

/** The per-manga ChatGPT history, newest entries last. */
@Serializable
data class AiChatLog(
    val entries: List<AiChatEntry> = emptyList(),
)
