package moe.antimony.hoshi.features.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Direct coverage of [AiChatSettingsRepository] mutation semantics:
 *  - `update {}` only bumps `lastEditedAt` when sync-relevant fields (model / prompts) change.
 *  - API-key edits stay strictly local and don't touch the stamp.
 *  - `update {}` is Lamport-monotonic — a future stamp still gets superseded by the next edit.
 *  - `applyFromSync` is a CAS against the local stamp: older remote losses, newer remote wins.
 *
 * Setup borrows the in-memory `DataStore<Preferences>` pattern from
 * [moe.antimony.hoshi.features.sync.http.HttpSyncAppSettingsTest].
 */
class AiChatSettingsRepositoryTest {

    @Test
    fun editingApiKeyOnlyDoesNotBumpLastEditedAt() = runBlocking {
        val repo = newRepo()
        // Keys are now per-provider and written outside the synced `update` path; the default model
        // is an OpenAI one, so `snapshot.apiKey` reflects the OpenAI provider's key.
        repo.setApiKey(ChatModelCatalog.openAI, "sk-deadbeef")

        val snapshot = repo.settings.first()
        assertEquals("sk-deadbeef", snapshot.apiKey)
        // The API key intentionally never syncs, so it must not stamp lastEditedAt — otherwise
        // a key-only edit would shadow a real sync-relevant edit on the next reconcile.
        assertNull(snapshot.lastEditedAt)
    }

    @Test
    fun editingModelBumpsLastEditedAt() = runBlocking {
        val repo = newRepo()
        repo.update { it.copy(model = "custom") }

        val stamp = repo.settings.first().lastEditedAt
        assertNotNull("model change must stamp lastEditedAt", stamp)
        // Stamp must be a parseable RFC 3339 instant.
        val parsed = runCatching { Instant.parse(stamp) }.getOrNull()
        assertNotNull("lastEditedAt must be RFC 3339 parseable, was '$stamp'", parsed)
    }

    @Test
    fun editingPromptBumpsLastEditedAt() = runBlocking {
        val repo = newRepo()
        repo.update { it.copy(promptText = "different prompt") }
        assertNotNull(repo.settings.first().lastEditedAt)
    }

    @Test
    fun editingImagePromptBumpsLastEditedAt() = runBlocking {
        val repo = newRepo()
        repo.update { it.copy(imagePromptText = "different image prompt") }
        assertNotNull(repo.settings.first().lastEditedAt)
    }

    @Test
    fun noopUpdateDoesNotBumpLastEditedAt() = runBlocking {
        val repo = newRepo()
        // Establish a stamp.
        repo.update { it.copy(model = "gpt-foo") }
        val firstStamp = repo.settings.first().lastEditedAt!!
        // Identity transform: nothing sync-relevant changes.
        repo.update { it }
        val secondStamp = repo.settings.first().lastEditedAt
        assertEquals(firstStamp, secondStamp)
    }

    @Test
    fun secondaryEditAdvancesStampWhenSyncRelevantFieldsChange() = runBlocking {
        val repo = newRepo()
        repo.update { it.copy(model = "first") }
        val first = repo.settings.first().lastEditedAt!!
        repo.update { it.copy(promptText = "another prompt") }
        val second = repo.settings.first().lastEditedAt!!
        assertTrue("second stamp must be strictly newer", second > first)
    }

    @Test
    fun futurePulledStampIsSupersededByNextLocalEdit() = runBlocking {
        val repo = newRepo()
        // Simulate a "maliciously-future" stamp landing on this device via sync.
        val future = Instant.now().plusSeconds(60L * 60L * 24L * 365L * 50L).toString() // ~50 years out
        val applied = repo.applyFromSync(
            model = "from-future",
            promptText = "future prompt",
            imagePromptText = "future image prompt",
            remoteLastEditedAt = future,
        )
        assertTrue(applied)
        // A normal user edit must still take effect — even though "now" is before `future`.
        // The Lamport bump guarantees stampStrictlyNewerThan picks future+1ms.
        repo.update { it.copy(model = "user-override") }
        val after = repo.settings.first()
        assertEquals("user-override", after.model)
        val stamp = after.lastEditedAt!!
        assertTrue(
            "local edit must strictly exceed the future stamp; was $stamp vs $future",
            stamp > future,
        )
    }

    @Test
    fun applyFromSyncAcceptsNewerStampAndPreservesLocalApiKey() = runBlocking {
        val repo = newRepo()
        repo.setApiKey(ChatModelCatalog.openAI, "sk-local") // local, per-provider key
        val now = Instant.now().toString()
        val applied = repo.applyFromSync(
            model = "synced-model",
            promptText = "synced prompt",
            imagePromptText = "synced image prompt",
            remoteLastEditedAt = now,
        )
        assertTrue(applied)
        val snapshot = repo.settings.first()
        assertEquals("synced-model", snapshot.model)
        assertEquals("synced prompt", snapshot.promptText)
        assertEquals("synced image prompt", snapshot.imagePromptText)
        assertEquals(now, snapshot.lastEditedAt)
        // The remote write must NOT clobber the per-device API key.
        assertEquals("sk-local", snapshot.apiKey)
    }

    @Test
    fun applyFromSyncRejectsOlderStampAndKeepsLocalValues() = runBlocking {
        val repo = newRepo()
        // Local edit at "now" lands first.
        repo.update { it.copy(model = "local-model", promptText = "local prompt") }
        val localStamp = repo.settings.first().lastEditedAt!!

        val older = Instant.parse(localStamp).minusSeconds(60).toString()
        val applied = repo.applyFromSync(
            model = "older-model",
            promptText = "older prompt",
            imagePromptText = "older image prompt",
            remoteLastEditedAt = older,
        )
        assertFalse("older remote must lose the LWW compare", applied)
        val snapshot = repo.settings.first()
        assertEquals("local-model", snapshot.model)
        assertEquals("local prompt", snapshot.promptText)
        assertEquals(localStamp, snapshot.lastEditedAt)
    }

    @Test
    fun applyFromSyncAcceptsFirstWriteWhenLocalHasNeverEdited() = runBlocking {
        val repo = newRepo()
        // No local edit yet, so currentStamp is null and any remote stamp is "newer".
        val now = Instant.now().toString()
        val applied = repo.applyFromSync(
            model = "first-model",
            promptText = "first prompt",
            imagePromptText = "first image prompt",
            remoteLastEditedAt = now,
        )
        assertTrue(applied)
        assertEquals("first-model", repo.settings.first().model)
    }

    @Test
    fun applyFromSyncOrdersFractionalSecondsChronologically() = runBlocking {
        val repo = newRepo()
        repo.applyFromSync("first", "first", "first", "2026-09-10T12:00:00Z")

        assertTrue(repo.applyFromSync("newer", "newer", "newer", "2026-09-10T12:00:00.123Z"))
        assertFalse(repo.applyFromSync("older", "older", "older", "2026-09-10T12:00:00Z"))
        assertEquals("newer", repo.settings.first().model)
    }

    @Test
    fun applyFromSyncDoesNotReplaceAnEqualInstantWithDifferentFormatting() = runBlocking {
        val repo = newRepo()
        repo.applyFromSync("first", "first", "first", "2026-09-10T12:00:00.000Z")

        assertFalse(repo.applyFromSync("equal", "equal", "equal", "2026-09-10T12:00:00Z"))
        assertFalse(repo.applyFromSync("offset", "offset", "offset", "2026-09-10T14:00:00+02:00"))
        assertEquals("first", repo.settings.first().model)
    }

    private fun newRepo(): AiChatSettingsRepository =
        AiChatSettingsRepository(InMemoryPreferencesDataStore())

    /** Minimal in-memory DataStore so the test doesn't need an Android Context. */
    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(mutablePreferencesOf())
        override val data: Flow<Preferences> get() = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.update { next }
            return next
        }
    }
}
