package moe.antimony.hoshi.features.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class UpdateSettings(
    // This fork does not track upstream releases. Auto-download is off by default and there
    // is no longer a settings toggle or startup scheduler that turns it on; the default
    // also neutralises any periodic WorkManager job a previous build may have persisted.
    val autoDownloadUpdates: Boolean = false,
)

private val Context.updateSettingsDataStore by preferencesDataStore(name = "update-settings")

fun Context.updateSettingsRepository(): UpdateSettingsRepository =
    UpdateSettingsRepository(updateSettingsDataStore)

class UpdateSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<UpdateSettings> = dataStore.data.map { preferences ->
        UpdateSettings(
            autoDownloadUpdates = preferences[KEY_AUTO_DOWNLOAD_UPDATES] ?: false,
        )
    }

    suspend fun update(transform: (UpdateSettings) -> UpdateSettings) {
        dataStore.edit { preferences ->
            val current = UpdateSettings(
                autoDownloadUpdates = preferences[KEY_AUTO_DOWNLOAD_UPDATES] ?: false,
            )
            val next = transform(current)
            preferences[KEY_AUTO_DOWNLOAD_UPDATES] = next.autoDownloadUpdates
        }
    }

    companion object {
        private val KEY_AUTO_DOWNLOAD_UPDATES = booleanPreferencesKey("autoDownloadUpdates")
    }
}
