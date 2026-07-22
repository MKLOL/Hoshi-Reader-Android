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
 * Settings + runtime cursor for the v2 KV HTTP sync (see [HttpSyncReconciler] and
 * `docs/HTTP_SYNC_KV.md`):
 *
 *  - [baseUrl] and [bearerToken] — what the user pastes once. Sync (including the
 *    reader-side auto-push hooks, [HttpSyncReaderHooks]) is active whenever both are set.
 *  - [lastSyncedAt] — RFC 3339 cursor for the inbound `list?since=` filter. Managed by
 *    [HttpSyncReconciler], not the UI; lives in the same DataStore so it survives
 *    uninstalls / clears the way the rest of the settings do.
 */
data class HttpSyncSettings(
    /** Base URL of the sync server, e.g. `https://dragos.games/api/book_sync`. No trailing slash. */
    val baseUrl: String = DEFAULT_BASE_URL,
    /** Bearer token sent in the `Authorization` header on every request. */
    val bearerToken: String = "",
    /**
     * Highest `lastModified` (RFC 3339 UTC) the client has observed from the server. The
     * inbound `GET /v1/kv?since=...` filter uses this so we never re-fetch unchanged keys.
     * `null` until the first successful [HttpSyncReconciler.syncOnce].
     */
    val lastSyncedAt: String? = null,
    /**
     * Selects the "Sync now" backend. `true` (default) runs the v3 engine
     * ([moe.antimony.hoshi.features.sync.v3.V3SyncEngine]); `false` falls back to the
     * legacy [HttpSyncReconciler] (kept as a rollback path).
     *
     * Both engines write the same on-disk and remote state (shared sidecar files + KV
     * keys), so the flag can be flipped at any time without data migration. There is no
     * UI to flip it yet; flip via dev tools / debug menu / an `adb` DataStore write if
     * you need to roll back to v2 on a specific device.
     *
     * Reader-side fire-and-forget pushes ([HttpSyncReaderHooks] / [HttpSyncPusher]) DO
     * NOT consult this flag — they always use the v2 push path. See
     * [moe.antimony.hoshi.features.sync.http.HttpSyncEngineDispatcher] for the rationale
     * and a TODO if/when we want page-turn pushes to honor the flag too.
     */
    val useV3Sync: Boolean = true,
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
            preferences[KEY_USE_V3_SYNC] = next.useV3Sync
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
            lastSyncedAt = this[KEY_LAST_SYNCED_AT],
            // Default = v3 (the production engine). Devices that pinned v2 will keep that
            // setting; everyone else gets v3 on next launch. Flip back to v2 only as a
            // device-local rollback if v3 misbehaves.
            useV3Sync = this[KEY_USE_V3_SYNC] ?: true,
        )

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("baseUrl")
        val KEY_TOKEN = stringPreferencesKey("bearerToken")
        val KEY_LAST_SYNCED_AT = stringPreferencesKey("lastSyncedAt")
        val KEY_USE_V3_SYNC = booleanPreferencesKey("useV3Sync")
    }
}
