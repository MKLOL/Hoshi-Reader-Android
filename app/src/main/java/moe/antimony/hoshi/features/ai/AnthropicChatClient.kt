package moe.antimony.hoshi.features.ai

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/** Thrown when an Anthropic request fails; [message] is safe to show the user. */
class AnthropicException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Minimal Anthropic Messages API client for the manga translation feature, mirroring the surface
 * of [OpenAiChatClient] ([complete] for text, [completeImage] for a vision crop). Claude has no
 * official Kotlin SDK, so this calls the Messages API over raw HTTPS:
 *   POST https://api.anthropic.com/v1/messages
 *   headers: x-api-key, anthropic-version: 2023-06-01
 *   body:    { model, max_tokens (required), messages:[{role:"user", content: ...}] }
 *   reply:   { content:[{type:"text", text:"…"}], stop_reason, … }
 */
object AnthropicChatClient {
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val API_VERSION = "2023-06-01"
    /** Anthropic requires `max_tokens`; a translation + short note fits comfortably here. */
    private const val MAX_TOKENS = 2048
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun complete(
        apiKey: String,
        model: String,
        prompt: String,
        bubbleText: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): String = withContext(dispatcher) {
        if (apiKey.isBlank()) {
            throw AnthropicException("Set your Anthropic API key in Settings → Translation model.")
        }
        completeRequest(apiKey, buildRequestBody(model, prompt, bubbleText))
    }

    suspend fun completeImage(
        apiKey: String,
        model: String,
        prompt: String,
        imageBase64: String,
        imageMimeType: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): String = withContext(dispatcher) {
        if (apiKey.isBlank()) {
            throw AnthropicException("Set your Anthropic API key in Settings → Translation model.")
        }
        completeRequest(apiKey, buildImageRequestBody(model, prompt, imageBase64, imageMimeType))
    }

    private fun completeRequest(apiKey: String, requestBody: String): String {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 90_000
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", API_VERSION)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw AnthropicException(parseErrorMessage(code, raw))
            }
            parseResponse(raw)
        } catch (e: AnthropicException) {
            throw e
        } catch (e: Exception) {
            // Keep the cause: NetworkReachability.isNetworkFailure walks the chain for an
            // IOException to decide whether to serve the offline pre-translation.
            throw AnthropicException(e.message ?: "Anthropic request failed.", e)
        } finally {
            connection.disconnect()
        }
    }

    /** Builds the JSON request body. The bubble text is appended after the prompt. */
    internal fun buildRequestBody(model: String, prompt: String, bubbleText: String): String {
        val content = if (prompt.isBlank()) bubbleText else "${prompt.trim()}\n\n$bubbleText"
        return json.encodeToString(
            MessagesRequest.serializer(),
            MessagesRequest(
                model = model.trim().ifBlank { DEFAULT_MODEL },
                maxTokens = MAX_TOKENS,
                messages = listOf(Message(role = "user", content = JsonPrimitive(content))),
            ),
        )
    }

    /** Builds the JSON request body for a single image crop plus text prompt (Anthropic vision). */
    internal fun buildImageRequestBody(
        model: String,
        prompt: String,
        imageBase64: String,
        imageMimeType: String,
    ): String {
        val mimeType = imageMimeType.trim().ifBlank { "image/png" }
        val contentParts = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "image")
                    put(
                        "source",
                        buildJsonObject {
                            put("type", "base64")
                            put("media_type", mimeType)
                            put("data", imageBase64.trim())
                        },
                    )
                },
                buildJsonObject {
                    put("type", "text")
                    put("text", prompt.trim().ifBlank { DEFAULT_IMAGE_PROMPT })
                },
            ),
        )
        return json.encodeToString(
            MessagesRequest.serializer(),
            MessagesRequest(
                model = model.trim().ifBlank { DEFAULT_MODEL },
                maxTokens = MAX_TOKENS,
                messages = listOf(Message(role = "user", content = contentParts)),
            ),
        )
    }

    /** Concatenates the `text` blocks of a successful Messages response. */
    internal fun parseResponse(raw: String): String {
        val response = runCatching {
            json.decodeFromString(MessagesResponse.serializer(), raw)
        }.getOrNull() ?: throw AnthropicException("Could not read the Anthropic response.")
        val text = response.content
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("")
            .trim()
        if (text.isEmpty()) {
            if (response.stopReason == "refusal") {
                throw AnthropicException("Claude declined to answer this request.")
            }
            throw AnthropicException("Anthropic returned an empty response.")
        }
        return text
    }

    /** Turns an error-status response body into a user-facing message. */
    internal fun parseErrorMessage(code: Int, raw: String): String {
        val apiMessage = runCatching {
            json.decodeFromString(ErrorEnvelope.serializer(), raw).error?.message
        }.getOrNull()
        return apiMessage?.takeIf { it.isNotBlank() }
            ?: "Anthropic request failed (HTTP $code)."
    }

    private const val DEFAULT_MODEL = "claude-haiku-4-5"
    private const val DEFAULT_IMAGE_PROMPT =
        "Transcribe any Japanese text visible in this image crop and translate it into " +
            "natural English. If useful, include a short vocabulary or grammar note. If no " +
            "readable Japanese text is visible, say so."

    @Serializable
    private data class MessagesRequest(
        val model: String,
        @kotlinx.serialization.SerialName("max_tokens") val maxTokens: Int,
        val messages: List<Message>,
    )

    @Serializable
    private data class Message(
        val role: String,
        val content: JsonElement,
    )

    @Serializable
    private data class MessagesResponse(
        val content: List<ContentBlock> = emptyList(),
        @kotlinx.serialization.SerialName("stop_reason") val stopReason: String? = null,
    )

    @Serializable
    private data class ContentBlock(
        val type: String = "",
        val text: String? = null,
    )

    @Serializable
    private data class ErrorEnvelope(
        val error: ApiError? = null,
    )

    @Serializable
    private data class ApiError(
        val type: String? = null,
        val message: String? = null,
    )
}
