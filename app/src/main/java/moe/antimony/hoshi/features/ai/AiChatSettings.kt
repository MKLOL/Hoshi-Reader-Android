package moe.antimony.hoshi.features.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * Settings for the ChatGPT speech-bubble feature: the OpenAI API key, the prompt text that is
 * sent ahead of a bubble's OCR text, and the model name.
 *
 * The model is a free-text field rather than a fixed list: the user asked for "GPT-5.5", which
 * is not a model id this code can validate, so it is editable — if OpenAI rejects it the error
 * surfaces in the chat popup and the value can be corrected here.
 *
 * **Sync behavior** (see [HttpSyncReconciler]): [model] and [promptText] sync across devices
 * via the v2 KV protocol. [apiKey] stays strictly local — pasting a key on one phone never
 * leaks it through sync to anywhere else. [lastEditedAt] is the LWW key, automatically
 * stamped on user edits via [AiChatSettingsRepository.update] but **not** on sync-applied
 * writes via [AiChatSettingsRepository.applyFromSync] (otherwise a pull would always look
 * newer than the just-pushed remote state and we'd oscillate).
 */
data class AiChatSettings(
    val apiKey: String = "",
    val promptText: String = DEFAULT_PROMPT,
    val model: String = DEFAULT_MODEL,
    /** RFC 3339 UTC timestamp of the most recent user-driven edit, or `null` if never edited. */
    val lastEditedAt: String? = null,
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

    /**
     * User-driven update. Auto-stamps [AiChatSettings.lastEditedAt] to "now" iff the
     * sync-relevant fields (`model` / `promptText`) actually changed. API-key-only edits
     * don't bump the stamp because the API key doesn't sync.
     */
    suspend fun update(transform: (AiChatSettings) -> AiChatSettings) {
        dataStore.edit { preferences ->
            val current = preferences.toAiChatSettings()
            val next = transform(current)
            val syncRelevantChanged = next.model != current.model || next.promptText != current.promptText
            val stamped = if (syncRelevantChanged) next.copy(lastEditedAt = nowRfc3339()) else next
            writeAll(preferences, stamped)
        }
    }

    /**
     * Sync-side application of a remote value. Writes [model] / [promptText] / [lastEditedAt]
     * exactly as supplied; does NOT touch [AiChatSettings.apiKey], which stays per-device.
     */
    suspend fun applyFromSync(model: String, promptText: String, lastEditedAt: String) {
        dataStore.edit { preferences ->
            preferences[KEY_PROMPT] = promptText
            preferences[KEY_MODEL] = model
            preferences[KEY_LAST_EDITED_AT] = lastEditedAt
        }
    }

    private fun writeAll(preferences: androidx.datastore.preferences.core.MutablePreferences, settings: AiChatSettings) {
        preferences[KEY_API_KEY] = settings.apiKey
        preferences[KEY_PROMPT] = settings.promptText
        preferences[KEY_MODEL] = settings.model
        val stamp = settings.lastEditedAt
        if (stamp.isNullOrBlank()) {
            preferences.remove(KEY_LAST_EDITED_AT)
        } else {
            preferences[KEY_LAST_EDITED_AT] = stamp
        }
    }

    private fun Preferences.toAiChatSettings(): AiChatSettings =
        AiChatSettings(
            apiKey = this[KEY_API_KEY] ?: "",
            promptText = this[KEY_PROMPT] ?: AiChatSettings.DEFAULT_PROMPT,
            model = this[KEY_MODEL]?.takeIf { it.isNotBlank() } ?: AiChatSettings.DEFAULT_MODEL,
            lastEditedAt = this[KEY_LAST_EDITED_AT],
        )

    private fun nowRfc3339(): String = Instant.now().toString()

    private companion object {
        val KEY_API_KEY = stringPreferencesKey("apiKey")
        val KEY_PROMPT = stringPreferencesKey("promptText")
        val KEY_MODEL = stringPreferencesKey("model")
        val KEY_LAST_EDITED_AT = stringPreferencesKey("lastEditedAt")
    }
}
