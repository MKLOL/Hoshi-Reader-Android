package moe.antimony.hoshi.features.ai

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** Thrown when an OpenAI chat-completions request fails; [message] is safe to show the user. */
class OpenAiException(message: String) : Exception(message)

/**
 * Minimal OpenAI Chat Completions client for the manga speech-bubble ChatGPT feature.
 *
 * Uses `HttpURLConnection` and `kotlinx.serialization`, matching the app's existing
 * networking style (see `GitHubReleaseUpdateRepository`). The request body is deliberately
 * just `model` + `messages` — no `temperature` / `max_tokens` — so it stays compatible across
 * model families (standard, reasoning, and whatever the user types into the model setting).
 *
 * The pure request/response helpers are `internal` so they can be unit-tested without a
 * network call; [complete] is the thin suspending wrapper that performs the HTTP request.
 */
object OpenAiChatClient {
    private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sends [prompt] followed by [bubbleText] to [model] and returns the assistant's reply.
     *
     * @throws OpenAiException on a missing key, an HTTP error (the OpenAI error message is
     *   surfaced when present), or an empty/unparseable response.
     */
    suspend fun complete(
        apiKey: String,
        model: String,
        prompt: String,
        bubbleText: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): String = withContext(dispatcher) {
        if (apiKey.isBlank()) {
            throw OpenAiException("Set your OpenAI API key in Settings → AI.")
        }
        val requestBody = buildRequestBody(model, prompt, bubbleText)
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 90_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw OpenAiException(parseErrorMessage(code, raw))
            }
            parseResponse(raw)
        } catch (e: OpenAiException) {
            throw e
        } catch (e: Exception) {
            throw OpenAiException(e.message ?: "OpenAI request failed.")
        } finally {
            connection.disconnect()
        }
    }

    /** Builds the JSON request body. The bubble text is appended after the prompt. */
    internal fun buildRequestBody(model: String, prompt: String, bubbleText: String): String {
        val content = if (prompt.isBlank()) bubbleText else "${prompt.trim()}\n\n$bubbleText"
        return json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = model.trim().ifBlank { AiChatSettings.DEFAULT_MODEL },
                messages = listOf(ChatMessage(role = "user", content = content)),
            ),
        )
    }

    /** Extracts the assistant reply text from a successful chat-completions response body. */
    internal fun parseResponse(raw: String): String {
        val response = runCatching {
            json.decodeFromString(ChatResponse.serializer(), raw)
        }.getOrNull() ?: throw OpenAiException("Could not read the OpenAI response.")
        val content = response.choices.firstOrNull()?.message?.content?.trim()
        if (content.isNullOrEmpty()) {
            throw OpenAiException("OpenAI returned an empty response.")
        }
        return content
    }

    /** Turns an error-status response body into a user-facing message. */
    internal fun parseErrorMessage(code: Int, raw: String): String {
        val apiMessage = runCatching {
            json.decodeFromString(ErrorEnvelope.serializer(), raw).error?.message
        }.getOrNull()
        return apiMessage?.takeIf { it.isNotBlank() }
            ?: "OpenAI request failed (HTTP $code)."
    }

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
    )

    @Serializable
    private data class ChatMessage(
        val role: String,
        val content: String,
    )

    @Serializable
    private data class ChatResponse(
        val choices: List<Choice> = emptyList(),
    )

    @Serializable
    private data class Choice(
        val message: ChoiceMessage? = null,
    )

    @Serializable
    private data class ChoiceMessage(
        val content: String? = null,
    )

    @Serializable
    private data class ErrorEnvelope(
        val error: ApiError? = null,
    )

    @Serializable
    private data class ApiError(
        val message: String? = null,
        val type: String? = null,
        @SerialName("code") val code: String? = null,
    )
}
