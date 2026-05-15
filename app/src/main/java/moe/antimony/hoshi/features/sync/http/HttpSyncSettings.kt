package moe.antimony.hoshi.features.sync.http

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
 * Settings for the Android-only HTTP sync (see [HttpSyncManager] and `docs/HTTP_SYNC.md`):
 * the server's base URL, the bearer token, and an enabled toggle.
 *
 * Lives alongside the existing iOS-shared Google Drive sync ([SyncSettingsRepository]).
 * The two backends are independent — having one enabled does not affect the other — and
 * neither is configured by default.
 */
data class HttpSyncSettings(
    /** Base URL of the sync server, e.g. `https://sync.example.com/hoshi`. No trailing slash. */
    val baseUrl: String = "",
    /** Bearer token sent in the `Authorization` header on every request. */
    val bearerToken: String = "",
    /** Whether HTTP sync is wired up. The Sync Now button works regardless; this gates auto-sync hooks. */
    val enabled: Boolean = false,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && bearerToken.isNotBlank()
}

private val Context.httpSyncSettingsDataStore by preferencesDataStore(name = "http-sync-settings")

fun Context.httpSyncSettingsRepository(): HttpSyncSettingsRepository =
    HttpSyncSettingsRepository(httpSyncSettingsDataStore)

class HttpSyncSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<HttpSyncSettings> = dataStore.data.map { it.toHttpSyncSettings() }

    suspend fun update(transform: (HttpSyncSettings) -> HttpSyncSettings) {
        dataStore.edit { preferences ->
            val next = transform(preferences.toHttpSyncSettings())
            preferences[KEY_BASE_URL] = next.baseUrl.trim().trimEnd('/')
            preferences[KEY_TOKEN] = next.bearerToken.trim()
            preferences[KEY_ENABLED] = next.enabled
        }
    }

    private fun Preferences.toHttpSyncSettings(): HttpSyncSettings =
        HttpSyncSettings(
            baseUrl = this[KEY_BASE_URL].orEmpty(),
            bearerToken = this[KEY_TOKEN].orEmpty(),
            enabled = this[KEY_ENABLED] ?: false,
        )

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("baseUrl")
        val KEY_TOKEN = stringPreferencesKey("bearerToken")
        val KEY_ENABLED = booleanPreferencesKey("enabled")
    }
}
