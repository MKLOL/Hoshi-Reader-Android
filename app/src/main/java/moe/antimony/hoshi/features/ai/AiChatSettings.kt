package moe.antimony.hoshi.features.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Settings for the ChatGPT speech-bubble feature: the OpenAI API key, the prompt text that is
 * sent ahead of a bubble's OCR text, and the model name.
 *
 * The model is a free-text field rather than a fixed list: the user asked for "GPT-5.5", which
 * is not a model id this code can validate, so it is editable — if OpenAI rejects it the error
 * surfaces in the chat popup and the value can be corrected here.
 */
data class AiChatSettings(
    val apiKey: String = "",
    val promptText: String = DEFAULT_PROMPT,
    val model: String = DEFAULT_MODEL,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL: String = "gpt-5.5"
        const val DEFAULT_PROMPT: String =
            "You are a helpful Japanese reading tutor. For the manga speech bubble below, " +
                "give a natural English translation, then a short, concise breakdown of any " +
                "tricky vocabulary or grammar."
    }
}

private val Context.aiChatSettingsDataStore by preferencesDataStore(name = "ai-chat-settings")

fun Context.aiChatSettingsRepository(): AiChatSettingsRepository =
    AiChatSettingsRepository(aiChatSettingsDataStore)

class AiChatSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AiChatSettings> = dataStore.data.map { it.toAiChatSettings() }

    suspend fun update(transform: (AiChatSettings) -> AiChatSettings) {
        dataStore.edit { preferences ->
            val next = transform(preferences.toAiChatSettings())
            preferences[KEY_API_KEY] = next.apiKey
            preferences[KEY_PROMPT] = next.promptText
            preferences[KEY_MODEL] = next.model
        }
    }

    private fun Preferences.toAiChatSettings(): AiChatSettings =
        AiChatSettings(
            apiKey = this[KEY_API_KEY] ?: "",
            promptText = this[KEY_PROMPT] ?: AiChatSettings.DEFAULT_PROMPT,
            model = this[KEY_MODEL]?.takeIf { it.isNotBlank() } ?: AiChatSettings.DEFAULT_MODEL,
        )

    private companion object {
        val KEY_API_KEY = stringPreferencesKey("apiKey")
        val KEY_PROMPT = stringPreferencesKey("promptText")
        val KEY_MODEL = stringPreferencesKey("model")
    }
}
