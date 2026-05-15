package moe.antimony.hoshi.features.sync.http

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.ai.AiChatSettings
import moe.antimony.hoshi.features.ai.AiChatSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for the cross-device ChatGPT settings sync (`model` + `promptText`).
 *
 * Setup uses an in-memory [DataStore] stand-in so we can drive [AiChatSettingsRepository]
 * directly without spinning up Android Context. The repository's `update` / `applyFromSync`
 * are exercised end-to-end.
 */
class HttpSyncAppSettingsTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val configured = HttpSyncSettings("https://x", "t", enabled = true)

    @Test
    fun localEditedSettingsArePushedWhenServerIsEmpty() = runBlocking {
        val repo = newAiSettingsRepo()
        repo.update { it.copy(model = "custom-model", promptText = "Custom prompt.") }

        val transport = FakeKvTransport()
        val reconciler = newReconciler(transport, repo)
        val result = reconciler.syncOnce(configured)

        assertTrue("expected AI settings to be uploaded", result.uploadedAppSettings)
        assertFalse(result.downloadedAppSettings)

        val stored = transport.kv[AI_CHAT_SETTINGS_KEY]
        assertNotNull("AI settings blob must exist on the server", stored)
        val blob = json.decodeFromString(
            HttpSyncAiChatSettingsBlob.serializer(),
            stored!!.body.toString(Charsets.UTF_8),
        )
        assertEquals("custom-model", blob.model)
        assertEquals("Custom prompt.", blob.promptText)
        assertNotNull(blob.lastModified)
    }

    @Test
    fun freshDeviceDownloadsAiSettingsAndAppliesThem() = runBlocking {
        val repo = newAiSettingsRepo() // fresh device — never edited locally
        val transport = FakeKvTransport()
        // Stage a remote settings blob (as if pushed from another device).
        val remoteStamp = "2030-01-01T00:00:00Z"
        transport.putJson(
            key = AI_CHAT_SETTINGS_KEY,
            serializer = HttpSyncAiChatSettingsBlob.serializer(),
            value = HttpSyncAiChatSettingsBlob(
                model = "remote-model",
                promptText = "Remote system prompt.",
                lastModified = remoteStamp,
            ),
            json = json,
            lastModified = remoteStamp,
        )

        val reconciler = newReconciler(transport, repo)
        val result = reconciler.syncOnce(configured)

        assertFalse("nothing to push from a fresh device", result.uploadedAppSettings)
        assertTrue("fresh device should download remote AI settings", result.downloadedAppSettings)

        val applied = repo.settings.first()
        assertEquals("remote-model", applied.model)
        assertEquals("Remote system prompt.", applied.promptText)
        assertEquals(remoteStamp, applied.lastEditedAt)
    }

    @Test
    fun apiKeyIsNeverTouchedBySync() = runBlocking {
        val repo = newAiSettingsRepo()
        repo.update { it.copy(apiKey = "sk-local-secret", model = "old", promptText = "old prompt") }

        val transport = FakeKvTransport()
        val remoteStamp = "2099-01-01T00:00:00Z"
        transport.putJson(
            key = AI_CHAT_SETTINGS_KEY,
            serializer = HttpSyncAiChatSettingsBlob.serializer(),
            value = HttpSyncAiChatSettingsBlob(
                model = "newer-model",
                promptText = "newer prompt",
                lastModified = remoteStamp,
            ),
            json = json,
            lastModified = remoteStamp,
        )

        val reconciler = newReconciler(transport, repo)
        reconciler.syncOnce(configured)

        val final = repo.settings.first()
        assertEquals("API key must survive sync untouched", "sk-local-secret", final.apiKey)
        assertEquals("newer-model", final.model)
        assertEquals("newer prompt", final.promptText)
    }

    @Test
    fun lwwResolvesByLastEditedAtWhenBothSidesEdited() = runBlocking {
        // Local has been edited recently; remote has an older edit.
        val repo = newAiSettingsRepo()
        repo.update { it.copy(model = "local-newer", promptText = "newer") }
        val localStamp = repo.settings.first().lastEditedAt!!

        val transport = FakeKvTransport()
        transport.putJson(
            key = AI_CHAT_SETTINGS_KEY,
            serializer = HttpSyncAiChatSettingsBlob.serializer(),
            value = HttpSyncAiChatSettingsBlob(
                model = "remote-older",
                promptText = "older",
                lastModified = "2000-01-01T00:00:00Z",
            ),
            json = json,
            lastModified = "2000-01-01T00:00:00Z",
        )

        val reconciler = newReconciler(transport, repo)
        val result = reconciler.syncOnce(configured)

        assertTrue("local is newer → push wins", result.uploadedAppSettings)
        assertFalse(result.downloadedAppSettings)

        val applied = repo.settings.first()
        assertEquals("local-newer", applied.model)
        assertEquals(localStamp, applied.lastEditedAt)
    }

    @Test
    fun applyFromSyncDoesNotBumpLastEditedAt() = runBlocking {
        // Direct test of the repository's split write paths.
        val repo = newAiSettingsRepo()
        repo.applyFromSync(model = "from-sync", promptText = "sync prompt", lastEditedAt = "2030-01-01T00:00:00Z")
        val settings = repo.settings.first()
        assertEquals("2030-01-01T00:00:00Z", settings.lastEditedAt)
        assertEquals("from-sync", settings.model)
    }

    @Test
    fun userEditBumpsLastEditedAtButApiKeyEditAlone() = runBlocking {
        val repo = newAiSettingsRepo()
        assertNull(repo.settings.first().lastEditedAt)
        repo.update { it.copy(apiKey = "secret") }
        assertNull("API-key-only change must not stamp lastEditedAt", repo.settings.first().lastEditedAt)
        repo.update { it.copy(model = "new") }
        assertNotNull("model change must stamp lastEditedAt", repo.settings.first().lastEditedAt)
    }

    // --- helpers --------------------------------------------------------------------------

    private fun newReconciler(
        transport: HttpSyncKvTransport,
        aiRepo: AiChatSettingsRepository,
    ): HttpSyncReconciler {
        val filesDir = tempFolder.newFolder()
        return HttpSyncReconciler(
            bookRepository = BookRepository(filesDir),
            aiSettingsRepository = aiRepo,
            transportFactory = { transport },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun newAiSettingsRepo(): AiChatSettingsRepository {
        return AiChatSettingsRepository(HttpSyncAppSettingsTestSupport.inMemoryPreferencesDataStore())
    }

}

/** Test helpers shared across sync test files. */
internal object HttpSyncAppSettingsTestSupport {
    fun inMemoryPreferencesDataStore(): DataStore<Preferences> = InMemoryPreferencesDataStore()
}

/** Minimal in-memory DataStore<Preferences> so tests don't need an Android Context. */
private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(mutablePreferencesOf())
    override val data: Flow<Preferences> get() = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val next = transform(state.value)
        state.update { next }
        return next
    }
}
