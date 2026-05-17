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
 * Settings for the ChatGPT manga features: the OpenAI API key, the prompt text that is sent
 * ahead of a bubble's OCR text, the prompt text sent with a screenshot crop, and the model name.
 *
 * The model is a free-text field rather than a fixed list: the user asked for "GPT-5.5", which
 * is not a model id this code can validate, so it is editable — if OpenAI rejects it the error
 * surfaces in the chat popup and the value can be corrected here.
 *
 * **Sync behavior** (see [HttpSyncReconciler]): [model], [promptText], and [imagePromptText]
 * sync across devices via the v2 KV protocol. [apiKey] stays strictly local — pasting a key
 * on one phone never leaks it through sync to anywhere else. [lastEditedAt] is the LWW key,
 * automatically stamped on user edits via [AiChatSettingsRepository.update] but **not** on
 * sync-applied writes via [AiChatSettingsRepository.applyFromSync] (otherwise a pull would
 * always look newer than the just-pushed remote state and we'd oscillate).
 */
data class AiChatSettings(
    val apiKey: String = "",
    val promptText: String = DEFAULT_PROMPT,
    val imagePromptText: String = DEFAULT_IMAGE_PROMPT,
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
        const val DEFAULT_IMAGE_PROMPT: String =
            "Transcribe any Japanese text visible in this image crop and translate it into " +
                "natural English. If useful, include a short vocabulary or grammar note. If no " +
                "readable Japanese text is visible, say so."
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
     * User-driven update. Auto-stamps [AiChatSettings.lastEditedAt] iff the sync-relevant
     * fields (`model` / `promptText` / `imagePromptText`) actually changed. API-key-only
     * edits don't bump the stamp because the API key doesn't sync.
     *
     * **Lamport-monotonic stamping.** The new stamp is `max(now, currentStamp + 1ms)`.
     * That guarantees every user-driven edit is strictly newer than any prior stamp the
     * device has ever seen — including a stale or maliciously-future stamp pulled from
     * the server. Without this, a single bad blob (e.g. a smoke test that uploaded a
     * "2099-01-01" stamp) would wedge the user permanently: every real edit would lose
     * the LWW compare against the future timestamp and get overwritten on the next pull.
     * With this, the user's first edit after the bad blob lands at `2099-01-01T...001Z`,
     * gets pushed, and from then on the user owns the settings again.
     */
    suspend fun update(transform: (AiChatSettings) -> AiChatSettings) {
        dataStore.edit { preferences ->
            val current = preferences.toAiChatSettings()
            val next = transform(current)
            val syncRelevantChanged = next.model != current.model ||
                next.promptText != current.promptText ||
                next.imagePromptText != current.imagePromptText
            val stamped = if (syncRelevantChanged) {
                next.copy(lastEditedAt = stampStrictlyNewerThan(current.lastEditedAt))
            } else {
                next
            }
            writeAll(preferences, stamped)
        }
    }

    /** Returns an RFC 3339 stamp strictly later than [previous]. Defaults to "now". */
    private fun stampStrictlyNewerThan(previous: String?): String {
        val now = Instant.now()
        val prev = previous?.let { runCatching { Instant.parse(it) }.getOrNull() }
        // `max(now, prev + 1ms)` — picks `now` in the common case (clock advanced past the
        // previous stamp normally), or `prev + 1ms` when something dragged the previous
        // stamp into the future.
        val next = if (prev != null && !prev.isBefore(now)) prev.plusMillis(1) else now
        return next.toString()
    }

    /**
     * Sync-side application of a remote value. Writes [model] / [promptText] /
     * [imagePromptText] / [lastEditedAt] exactly as supplied; does NOT touch
     * [AiChatSettings.apiKey], which stays per-device.
     *
     * **Returns** `true` if the write was performed, `false` if the local store already had
     * a [lastEditedAt] strictly newer than [remoteLastEditedAt] at write time. This is the
     * CAS that closes the race between a sync-side apply and a user-driven edit happening
     * in the same window: the comparison and write happen atomically inside the DataStore
     * transaction, so a concurrent `update { ... }` either lands before this read (and
     * loses the LWW comparison) or after this write (and stamps a fresh `lastEditedAt`
     * that the next sync will push back up).
     */
    suspend fun applyFromSync(
        model: String,
        promptText: String,
        imagePromptText: String,
        remoteLastEditedAt: String,
    ): Boolean {
        var applied = false
        dataStore.edit { preferences ->
            val currentStamp = preferences[KEY_LAST_EDITED_AT]
            val remoteIsNewer = currentStamp == null ||
                compareRfc3339String(remoteLastEditedAt, currentStamp) > 0
            if (remoteIsNewer) {
                preferences[KEY_PROMPT] = promptText
                preferences[KEY_IMAGE_PROMPT] = imagePromptText
                preferences[KEY_MODEL] = model
                preferences[KEY_LAST_EDITED_AT] = remoteLastEditedAt
                applied = true
            }
        }
        return applied
    }

    /**
     * Lexicographic comparison of RFC 3339 UTC strings — chronological order for Z-suffixed
     * timestamps. Lives here (instead of in [moe.antimony.hoshi.features.sync.http]) so the
     * settings module doesn't need a sync dependency for an atomic LWW decision.
     */
    private fun compareRfc3339String(a: String, b: String): Int = a.compareTo(b)

    private fun writeAll(preferences: androidx.datastore.preferences.core.MutablePreferences, settings: AiChatSettings) {
        preferences[KEY_API_KEY] = settings.apiKey
        preferences[KEY_PROMPT] = settings.promptText
        preferences[KEY_IMAGE_PROMPT] = settings.imagePromptText
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
            imagePromptText = this[KEY_IMAGE_PROMPT] ?: AiChatSettings.DEFAULT_IMAGE_PROMPT,
            model = this[KEY_MODEL]?.takeIf { it.isNotBlank() } ?: AiChatSettings.DEFAULT_MODEL,
            lastEditedAt = this[KEY_LAST_EDITED_AT],
        )

    private companion object {
        val KEY_API_KEY = stringPreferencesKey("apiKey")
        val KEY_PROMPT = stringPreferencesKey("promptText")
        val KEY_IMAGE_PROMPT = stringPreferencesKey("imagePromptText")
        val KEY_MODEL = stringPreferencesKey("model")
        val KEY_LAST_EDITED_AT = stringPreferencesKey("lastEditedAt")
    }
}
