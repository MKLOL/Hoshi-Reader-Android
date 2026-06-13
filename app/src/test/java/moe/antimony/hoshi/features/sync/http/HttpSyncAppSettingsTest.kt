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
 * Tests for the cross-device ChatGPT settings sync (`model` + prompts).
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
        repo.update {
            it.copy(
                model = "custom-model",
                promptText = "Custom prompt.",
                imagePromptText = "Custom image prompt.",
            )
        }

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
        assertEquals("Custom image prompt.", blob.imagePromptText)
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
                imagePromptText = "Remote image prompt.",
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
        assertEquals("Remote image prompt.", applied.imagePromptText)
        assertEquals(remoteStamp, applied.lastEditedAt)
    }

    @Test
    fun legacyRemoteAiSettingsBlobWithoutImagePromptUsesDefaultImagePrompt() = runBlocking {
        val repo = newAiSettingsRepo()
        val transport = FakeKvTransport()
        val remoteStamp = "2030-01-01T00:00:00Z"
        transport.put(
            key = AI_CHAT_SETTINGS_KEY,
            contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
            body = """
                {
                  "model": "legacy-model",
                  "promptText": "Legacy bubble prompt.",
                  "lastModified": "$remoteStamp"
                }
            """.trimIndent().toByteArray(),
        )

        val reconciler = newReconciler(transport, repo)
        val result = reconciler.syncOnce(configured)

        assertTrue(result.downloadedAppSettings)
        assertTrue("new client should backfill the default image prompt to legacy remote blobs", result.uploadedAppSettings)
        val applied = repo.settings.first()
        assertEquals("legacy-model", applied.model)
        assertEquals("Legacy bubble prompt.", applied.promptText)
        assertEquals(AiChatSettings.DEFAULT_IMAGE_PROMPT, applied.imagePromptText)
        val backfilled = json.decodeFromString(
            HttpSyncAiChatSettingsBlob.serializer(),
            transport.kv[AI_CHAT_SETTINGS_KEY]!!.body.toString(Charsets.UTF_8),
        )
        assertEquals(AiChatSettings.DEFAULT_IMAGE_PROMPT, backfilled.imagePromptText)
    }

    @Test
    fun newerLegacyRemoteAiSettingsBlobPreservesLocalCustomImagePrompt() = runBlocking {
        val repo = newAiSettingsRepo()
        repo.update {
            it.copy(
                model = "local-model",
                promptText = "Local bubble prompt.",
                imagePromptText = "Local custom image prompt.",
            )
        }
        val transport = FakeKvTransport()
        val remoteStamp = "2099-01-01T00:00:00Z"
        transport.put(
            key = AI_CHAT_SETTINGS_KEY,
            contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
            body = """
                {
                  "model": "legacy-newer-model",
                  "promptText": "Legacy newer bubble prompt.",
                  "lastModified": "$remoteStamp"
                }
            """.trimIndent().toByteArray(),
        )

        val reconciler = newReconciler(transport, repo)
        val result = reconciler.syncOnce(configured)

        assertTrue(result.downloadedAppSettings)
        assertTrue("new client should backfill preserved local image prompt to legacy remote blobs", result.uploadedAppSettings)
        val applied = repo.settings.first()
        assertEquals("legacy-newer-model", applied.model)
        assertEquals("Legacy newer bubble prompt.", applied.promptText)
        assertEquals("Local custom image prompt.", applied.imagePromptText)
        assertEquals(remoteStamp, applied.lastEditedAt)
        val backfilled = json.decodeFromString(
            HttpSyncAiChatSettingsBlob.serializer(),
            transport.kv[AI_CHAT_SETTINGS_KEY]!!.body.toString(Charsets.UTF_8),
        )
        assertEquals("Local custom image prompt.", backfilled.imagePromptText)
    }

    @Test
    fun equalStampLegacyRemoteAiSettingsBlobIsBackfilled() = runBlocking {
        val repo = newAiSettingsRepo()
        val remoteStamp = "2030-01-01T00:00:00Z"
        val accepted = repo.applyFromSync(
            model = "same-model",
            promptText = "Same bubble prompt.",
            imagePromptText = "Same local image prompt.",
            remoteLastEditedAt = remoteStamp,
        )
        assertTrue(accepted)
        val transport = FakeKvTransport()
        transport.put(
            key = AI_CHAT_SETTINGS_KEY,
            contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
            body = """
                {
                  "model": "same-model",
                  "promptText": "Same bubble prompt.",
                  "lastModified": "$remoteStamp"
                }
            """.trimIndent().toByteArray(),
        )

        val reconciler = newReconciler(transport, repo)
        val result = reconciler.syncOnce(configured)

        assertFalse(result.downloadedAppSettings)
        assertTrue(result.uploadedAppSettings)
        val backfilled = json.decodeFromString(
            HttpSyncAiChatSettingsBlob.serializer(),
            transport.kv[AI_CHAT_SETTINGS_KEY]!!.body.toString(Charsets.UTF_8),
        )
        assertEquals("Same local image prompt.", backfilled.imagePromptText)
    }

    @Test
    fun apiKeyIsNeverTouchedBySync() = runBlocking {
        val repo = newAiSettingsRepo()
        repo.update { it.copy(model = "old", promptText = "old prompt") }
        // Keys are per-provider and written outside the synced path; the default/fallback provider
        // for these unknown model ids is OpenAI.
        repo.setApiKey(moe.antimony.hoshi.features.ai.ChatModelCatalog.openAI, "sk-local-secret")

        val transport = FakeKvTransport()
        val remoteStamp = "2099-01-01T00:00:00Z"
        transport.putJson(
            key = AI_CHAT_SETTINGS_KEY,
            serializer = HttpSyncAiChatSettingsBlob.serializer(),
            value = HttpSyncAiChatSettingsBlob(
                model = "newer-model",
                promptText = "newer prompt",
                imagePromptText = "newer image prompt",
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
        assertEquals("newer image prompt", final.imagePromptText)
    }

    @Test
    fun lwwResolvesByLastEditedAtWhenBothSidesEdited() = runBlocking {
        // Local has been edited recently; remote has an older edit.
        val repo = newAiSettingsRepo()
        repo.update { it.copy(model = "local-newer", promptText = "newer", imagePromptText = "newer image") }
        val localStamp = repo.settings.first().lastEditedAt!!

        val transport = FakeKvTransport()
        transport.putJson(
            key = AI_CHAT_SETTINGS_KEY,
            serializer = HttpSyncAiChatSettingsBlob.serializer(),
            value = HttpSyncAiChatSettingsBlob(
                model = "remote-older",
                promptText = "older",
                imagePromptText = "older image",
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
        assertEquals("newer image", applied.imagePromptText)
        assertEquals(localStamp, applied.lastEditedAt)
    }

    @Test
    fun applyFromSyncDoesNotBumpLastEditedAt() = runBlocking {
        // Direct test of the repository's split write paths.
        val repo = newAiSettingsRepo()
        val applied = repo.applyFromSync(
            model = "from-sync",
            promptText = "sync prompt",
            imagePromptText = "sync image prompt",
            remoteLastEditedAt = "2030-01-01T00:00:00Z",
        )
        assertTrue("fresh device should accept the remote write", applied)
        val settings = repo.settings.first()
        assertEquals("2030-01-01T00:00:00Z", settings.lastEditedAt)
        assertEquals("from-sync", settings.model)
        assertEquals("sync image prompt", settings.imagePromptText)
    }

    @Test
    fun applyFromSyncIsCompareAndSwap_RejectsStaleRemoteWrite() = runBlocking {
        // The race we're guarding against: reconciler reads remote (older), user edits
        // locally (newer), then reconciler's applyFromSync tries to clobber the fresh edit.
        // CAS must reject the write.
        val repo = newAiSettingsRepo()
        // User edit lands first.
        repo.update { it.copy(model = "user-fresh", promptText = "user prompt", imagePromptText = "user image") }
        val userStamp = repo.settings.first().lastEditedAt!!

        // Reconciler tries to apply an older remote value.
        val applied = repo.applyFromSync(
            model = "remote-stale",
            promptText = "remote prompt",
            imagePromptText = "remote image prompt",
            remoteLastEditedAt = "2000-01-01T00:00:00Z", // older than user's "now"
        )
        assertFalse("CAS must reject the stale write", applied)

        // User's edit survived.
        val final = repo.settings.first()
        assertEquals("user-fresh", final.model)
        assertEquals("user image", final.imagePromptText)
        assertEquals(userStamp, final.lastEditedAt)
    }

    @Test
    fun applyFromSyncAppliesWhenRemoteIsStrictlyNewer() = runBlocking {
        val repo = newAiSettingsRepo()
        repo.update { it.copy(model = "old", promptText = "old prompt", imagePromptText = "old image") }

        // Remote is much newer.
        val applied = repo.applyFromSync(
            model = "new",
            promptText = "new prompt",
            imagePromptText = "new image prompt",
            remoteLastEditedAt = "2099-01-01T00:00:00Z",
        )
        assertTrue("newer remote should win the CAS", applied)
        val final = repo.settings.first()
        assertEquals("new", final.model)
        assertEquals("new image prompt", final.imagePromptText)
        assertEquals("2099-01-01T00:00:00Z", final.lastEditedAt)
    }

    @Test
    fun userEditBumpsLastEditedAtButApiKeyEditAlone() = runBlocking {
        val repo = newAiSettingsRepo()
        assertNull(repo.settings.first().lastEditedAt)
        repo.setApiKey(moe.antimony.hoshi.features.ai.ChatModelCatalog.openAI, "secret")
        assertNull("API-key-only change must not stamp lastEditedAt", repo.settings.first().lastEditedAt)
        repo.update { it.copy(model = "new") }
        assertNotNull("model change must stamp lastEditedAt", repo.settings.first().lastEditedAt)
    }

    @Test
    fun imagePromptEditBumpsLastEditedAt() = runBlocking {
        val repo = newAiSettingsRepo()
        assertNull(repo.settings.first().lastEditedAt)

        repo.update { it.copy(imagePromptText = "Use concise screenshot translation.") }

        val settings = repo.settings.first()
        assertEquals("Use concise screenshot translation.", settings.imagePromptText)
        assertNotNull("image prompt must sync, so edits stamp lastEditedAt", settings.lastEditedAt)
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
