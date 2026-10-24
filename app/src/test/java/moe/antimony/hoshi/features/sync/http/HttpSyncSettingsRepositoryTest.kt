package moe.antimony.hoshi.features.sync.http

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HttpSyncSettingsRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun interruptedFirstPromptRemainsPendingAfterTheInstallationIsUpdated() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = temp.newFolder().resolve("interrupted.preferences_pb")
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val firstLaunch = HttpSyncSettingsRepository(store, isFreshInstall = true)
            assertFalse(firstLaunch.settings.first().hasCompletedOnboarding)
            assertFalse(firstLaunch.settings.first().hasCompletedDictionaryOnboarding)
            // No choice was made before process death. A later launch sees prior app files.
            val afterUpdate = HttpSyncSettingsRepository(store, isFreshInstall = false)
            assertFalse(afterUpdate.settings.first().hasCompletedOnboarding)
            assertFalse(afterUpdate.settings.first().hasCompletedDictionaryOnboarding)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun defaultsDoNotEnableSyncWithoutAToken() = withRepository { repository ->
        val settings = repository.settings.first()
        assertEquals(HttpSyncSettings.DEFAULT_BASE_URL, settings.baseUrl)
        assertFalse(settings.isConfigured)
        assertTrue(settings.useV3Sync)
        assertNull(settings.lastSyncedAt)
        assertFalse(settings.hasCompletedOnboarding)
    }

    @Test
    fun existingInstallationDoesNotShowNewInstallOnboarding() = withRepository(isFreshInstall = false) { repository ->
        assertTrue(repository.settings.first().hasCompletedOnboarding)
        assertFalse(repository.settings.first().isConfigured)
    }

    @Test
    fun onboardingCompletionSurvivesCredentialAndCursorEdits() = withRepository { repository ->
        repository.update { it.copy(hasCompletedOnboarding = true) }
        repository.update { it.copy(bearerToken = "friend-token", lastSyncedAt = "2026-10-24T00:00:00Z") }
        assertTrue(repository.settings.first().hasCompletedOnboarding)
        repository.update { it.copy(bearerToken = "") }
        assertTrue(repository.settings.first().hasCompletedOnboarding)
    }

    @Test
    fun changingServerOrTokenResetsThePreviousAccountsCursor() = withRepository { repository ->
        repository.update { it.copy(baseUrl = "https://first.test", bearerToken = "first") }
        repository.update { it.copy(lastSyncedAt = "2026-09-30T00:00:00Z") }
        repository.update { it.copy(baseUrl = "https://second.test") }
        assertNull(repository.settings.first().lastSyncedAt)
        repository.update { it.copy(lastSyncedAt = "2026-09-30T00:00:00Z") }
        repository.update { it.copy(bearerToken = "second") }
        assertNull(repository.settings.first().lastSyncedAt)
    }

    @Test
    fun lateOldAccountCompletionCannotReplaceTheNewAccountsCursor() = withRepository { repository ->
        repository.update { it.copy(baseUrl = "https://example.test", bearerToken = "first") }
        val firstAccount = repository.settings.first()
        repository.update { it.copy(bearerToken = "second") }
        val secondAccount = repository.settings.first()
        repository.recordSyncCursor(secondAccount, "2026-09-29T00:00:00Z")

        repository.recordSyncCursor(firstAccount, "2026-09-30T00:00:00Z")

        assertEquals("2026-09-29T00:00:00Z", repository.settings.first().lastSyncedAt)
        repository.update { it.copy(baseUrl = "https://other.test") }
        repository.recordSyncCursor(secondAccount, "2026-09-30T00:00:00Z")
        assertNull(repository.settings.first().lastSyncedAt)
    }

    @Test
    fun cosmeticEditsKeepTheCursorAndNormalizeOnlyPersistedCredentials() = withRepository { repository ->
        repository.update { it.copy(baseUrl = "https://example.test", bearerToken = "token") }
        repository.update { it.copy(lastSyncedAt = "2026-09-30T00:00:00Z") }
        repository.update { it.copy(baseUrl = " https://example.test/// ", bearerToken = " token ") }
        val settings = repository.settings.first()
        assertEquals("https://example.test", settings.baseUrl)
        assertEquals("token", settings.bearerToken)
        assertEquals("2026-09-30T00:00:00Z", settings.lastSyncedAt)
    }

    @Test
    fun clearingCredentialsRemainsEmptyAfterReloadAndDisablesSync() = withRepository { repository ->
        repository.update { it.copy(bearerToken = "token", baseUrl = "") }
        assertEquals("", repository.settings.first().baseUrl)
        assertFalse(repository.settings.first().isConfigured)
        repository.update { it.copy(baseUrl = HttpSyncSettings.DEFAULT_BASE_URL, bearerToken = " ") }
        assertFalse(repository.settings.first().isConfigured)
    }

    @Test
    fun cursorUpdatesPreserveCredentialsAndEngineChoice() = withRepository { repository ->
        repository.update { it.copy(baseUrl = "https://example.test", bearerToken = "token", useV3Sync = false) }
        repository.update { it.copy(lastSyncedAt = " 2026-09-30T00:00:00Z ") }
        val settings = repository.settings.first()
        assertEquals("https://example.test", settings.baseUrl)
        assertEquals("token", settings.bearerToken)
        assertFalse(settings.useV3Sync)
        assertEquals("2026-09-30T00:00:00Z", settings.lastSyncedAt)
    }

    private fun withRepository(isFreshInstall: Boolean = true, block: suspend (HttpSyncSettingsRepository) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = temp.newFolder().resolve("sync.preferences_pb")
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            block(HttpSyncSettingsRepository(store, isFreshInstall))
        } finally {
            scope.cancel()
        }
    }
}
