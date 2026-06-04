package moe.antimony.hoshi.features.ai.offline

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Settings for the offline on-device translation feature: whether to route translations through
 * the bundled local model instead of the OpenAI client, and which downloaded model is active.
 *
 * These live in their own DataStore — separate from [moe.antimony.hoshi.features.ai.AiChatSettings]
 * — and deliberately do **not** sync across devices: downloaded models and device capability are
 * inherently per-device, so the chosen model id is local state only.
 */
data class OfflineTranslationSettings(
    /** When `true`, the reader prefers the on-device model over the OpenAI chat client. */
    val useOnDeviceTranslation: Boolean = false,
    /** Id of the active catalog model (see [LlmModelCatalog]). */
    val activeModelId: String = LlmModelCatalog.DEFAULT.id,
)

private val Context.offlineTranslationSettingsDataStore by preferencesDataStore(
    name = "offline-translation-settings",
)

fun Context.offlineTranslationSettingsRepository(): OfflineTranslationSettingsRepository =
    OfflineTranslationSettingsRepository(offlineTranslationSettingsDataStore)

class OfflineTranslationSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<OfflineTranslationSettings> = dataStore.data.map { it.toSettings() }

    /** Read-modify-write update applied atomically inside the DataStore transaction. */
    suspend fun update(transform: (OfflineTranslationSettings) -> OfflineTranslationSettings) {
        dataStore.edit { preferences ->
            val next = transform(preferences.toSettings())
            preferences[KEY_USE_ON_DEVICE] = next.useOnDeviceTranslation
            preferences[KEY_ACTIVE_MODEL_ID] = next.activeModelId
        }
    }

    private fun Preferences.toSettings(): OfflineTranslationSettings =
        OfflineTranslationSettings(
            useOnDeviceTranslation = this[KEY_USE_ON_DEVICE] ?: false,
            activeModelId = this[KEY_ACTIVE_MODEL_ID]?.takeIf { it.isNotBlank() }
                ?: LlmModelCatalog.DEFAULT.id,
        )

    private companion object {
        val KEY_USE_ON_DEVICE = booleanPreferencesKey("useOnDeviceTranslation")
        val KEY_ACTIVE_MODEL_ID = stringPreferencesKey("activeModelId")
    }
}
