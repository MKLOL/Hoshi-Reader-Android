package moe.antimony.hoshi.features.news

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class NewsSettings(
    /** Source ids the user switched off explicitly. */
    val disabledSourceIds: Set<String> = emptySet(),
    /** Source ids the user switched on explicitly (needed for built-ins that start disabled). */
    val enabledSourceIds: Set<String> = emptySet(),
    val customSources: List<NewsSource> = emptyList(),
) {
    /** Every listable source; the shared-link pseudo-source is not part of this. */
    val sources: List<NewsSource>
        get() = NewsSourceCatalog.builtIn + customSources

    val enabledSources: List<NewsSource>
        get() = sources.filter(::isEnabled)

    fun isEnabled(source: NewsSource): Boolean = when (source.id) {
        in disabledSourceIds -> false
        in enabledSourceIds -> true
        else -> source.enabledByDefault
    }
}

private val Context.newsSettingsDataStore by preferencesDataStore(name = "news-settings")

fun Context.newsSettingsRepository(): NewsSettingsRepository = NewsSettingsRepository(newsSettingsDataStore)

class NewsSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<NewsSettings> = dataStore.data.map { it.toSettings() }

    suspend fun current(): NewsSettings = settings.first()

    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        dataStore.edit { preferences ->
            val settings = preferences.toSettings()
            val disabled = settings.disabledSourceIds.toMutableSet()
            val enabledIds = settings.enabledSourceIds.toMutableSet()
            if (enabled) { disabled -= sourceId; enabledIds += sourceId } else { disabled += sourceId; enabledIds -= sourceId }
            preferences[KEY_DISABLED] = json.encodeToString(ListSerializer(kotlinx.serialization.serializer<String>()), disabled.sorted())
            preferences[KEY_ENABLED] = json.encodeToString(ListSerializer(kotlinx.serialization.serializer<String>()), enabledIds.sorted())
        }
    }

    suspend fun addCustomRss(name: String, url: String): NewsSource {
        val source = NewsSourceCatalog.customRss(name, url)
        dataStore.edit { preferences ->
            val existing = preferences.toSettings().customSources.filterNot { it.id == source.id }
            preferences[KEY_CUSTOM] = json.encodeToString(ListSerializer(NewsSource.serializer()), existing + source)
        }
        return source
    }

    suspend fun removeCustomSource(sourceId: String) {
        dataStore.edit { preferences ->
            val settings = preferences.toSettings()
            preferences[KEY_CUSTOM] = json.encodeToString(
                ListSerializer(NewsSource.serializer()),
                settings.customSources.filterNot { it.id == sourceId },
            )
            preferences[KEY_DISABLED] = json.encodeToString(ListSerializer(kotlinx.serialization.serializer<String>()), (settings.disabledSourceIds - sourceId).sorted())
            preferences[KEY_ENABLED] = json.encodeToString(ListSerializer(kotlinx.serialization.serializer<String>()), (settings.enabledSourceIds - sourceId).sorted())
        }
    }

    private fun Preferences.toSettings(): NewsSettings = NewsSettings(
        disabledSourceIds = this[KEY_DISABLED]
            ?.let { raw -> runCatching { json.decodeFromString(ListSerializer(kotlinx.serialization.serializer<String>()), raw) }.getOrNull() }
            ?.toSet()
            .orEmpty(),
        enabledSourceIds = this[KEY_ENABLED]
            ?.let { raw -> runCatching { json.decodeFromString(ListSerializer(kotlinx.serialization.serializer<String>()), raw) }.getOrNull() }
            ?.toSet()
            .orEmpty(),
        customSources = this[KEY_CUSTOM]
            ?.let { raw -> runCatching { json.decodeFromString(ListSerializer(NewsSource.serializer()), raw) }.getOrNull() }
            .orEmpty(),
    )

    private companion object {
        val KEY_DISABLED = stringPreferencesKey("disabledSourceIds")
        val KEY_ENABLED = stringPreferencesKey("enabledSourceIds")
        val KEY_CUSTOM = stringPreferencesKey("customSources")
    }
}
