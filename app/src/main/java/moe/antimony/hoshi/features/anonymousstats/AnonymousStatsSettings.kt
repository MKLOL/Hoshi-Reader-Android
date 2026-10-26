package moe.antimony.hoshi.features.anonymousstats

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AnonymousStatsSettings(
    // Opt-out. The choice is backed up like other settings, so someone who turned it off stays
    // opted out on a restored or transferred install; the install ID itself never travels.
    val enabled: Boolean = true,
)

private val Context.anonymousStatsSettingsDataStore by preferencesDataStore(name = "anonymous-stats-settings")

fun Context.anonymousStatsSettingsRepository(): AnonymousStatsSettingsRepository =
    AnonymousStatsSettingsRepository(anonymousStatsSettingsDataStore)

class AnonymousStatsSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AnonymousStatsSettings> = dataStore.data.map { preferences ->
        AnonymousStatsSettings(enabled = preferences[KEY_ENABLED] ?: true)
    }

    suspend fun update(transform: (AnonymousStatsSettings) -> AnonymousStatsSettings) {
        dataStore.edit { preferences ->
            val current = AnonymousStatsSettings(enabled = preferences[KEY_ENABLED] ?: true)
            preferences[KEY_ENABLED] = transform(current).enabled
        }
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("shareAnonymousUsageStatistics")
    }
}
