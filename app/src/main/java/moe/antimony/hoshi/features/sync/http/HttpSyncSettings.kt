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
 * Settings + runtime cursor for the v2 KV HTTP sync (see [HttpSyncManager] and
 * `docs/HTTP_SYNC_KV.md`):
 *
 *  - [baseUrl] and [bearerToken] — what the user pastes once.
 *  - [enabled] — gates future auto-sync hooks. The manual Sync now button always works.
 *  - [lastSyncedAt] — RFC 3339 cursor for the inbound `list?since=` filter. Managed by
 *    [HttpSyncManager], not the UI; lives in the same DataStore so it survives uninstalls
 *    / clears the way the rest of the settings do.
 */
data class HttpSyncSettings(
    /** Base URL of the sync server, e.g. `https://dragos.games/api/book_sync`. No trailing slash. */
    val baseUrl: String = DEFAULT_BASE_URL,
    /** Bearer token sent in the `Authorization` header on every request. */
    val bearerToken: String = "",
    /** Whether HTTP sync is wired up. The Sync Now button works regardless; this gates auto-sync hooks. */
    val enabled: Boolean = false,
    /**
     * Highest `lastModified` (RFC 3339 UTC) the client has observed from the server. The
     * inbound `GET /v1/kv?since=...` filter uses this so we never re-fetch unchanged keys.
     * `null` until the first successful [HttpSyncManager.syncOnce].
     */
    val lastSyncedAt: String? = null,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && bearerToken.isNotBlank()

    companion object {
        /**
         * Default base URL the screen pre-fills with. Points at the fork owner's own server;
         * users can still type anything else into the field and that value gets persisted to
         * the DataStore.
         */
        const val DEFAULT_BASE_URL: String = "https://dragos.games/api/book_sync"
    }
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
            val cursor = next.lastSyncedAt?.trim()
            if (cursor.isNullOrEmpty()) {
                preferences.remove(KEY_LAST_SYNCED_AT)
            } else {
                preferences[KEY_LAST_SYNCED_AT] = cursor
            }
        }
    }

    private fun Preferences.toHttpSyncSettings(): HttpSyncSettings =
        HttpSyncSettings(
            // Fall back to the default URL only when nothing was ever written — once the
            // user has typed (even cleared the field to ""), that explicit value wins.
            baseUrl = this[KEY_BASE_URL] ?: HttpSyncSettings.DEFAULT_BASE_URL,
            bearerToken = this[KEY_TOKEN].orEmpty(),
            enabled = this[KEY_ENABLED] ?: false,
            lastSyncedAt = this[KEY_LAST_SYNCED_AT],
        )

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("baseUrl")
        val KEY_TOKEN = stringPreferencesKey("bearerToken")
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_LAST_SYNCED_AT = stringPreferencesKey("lastSyncedAt")
    }
}
