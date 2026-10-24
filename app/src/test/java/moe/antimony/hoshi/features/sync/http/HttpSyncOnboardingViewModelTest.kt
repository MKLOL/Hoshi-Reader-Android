package moe.antimony.hoshi.features.sync.http

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import moe.antimony.hoshi.dictionary.DictionaryUpdateProgress
import moe.antimony.hoshi.dictionary.DictionaryUpdateStage

@OptIn(ExperimentalCoroutinesApi::class)
class HttpSyncOnboardingViewModelTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun freshInstallPromptsAndSkipShowsGuidanceBeforePersistingCompletion() = withRepository { repository, models ->
        val model = models.create(repository) { fail("Skip must not sync") }
        advanceUntilIdle()
        assertEquals(HttpSyncOnboardingStep.Token, model.state.value.step)
        model.withoutToken()
        assertEquals(HttpSyncOnboardingStep.Later, model.state.value.step)
        assertFalse(repository.settings.first().hasCompletedOnboarding)
        model.finishLater()
        advanceUntilIdle()
        assertEquals(HttpSyncOnboardingStep.Dictionaries, model.state.value.step)
        assertFalse(repository.settings.first().hasCompletedDictionaryOnboarding)
        model.skipDictionaries()
        advanceUntilIdle()
        assertEquals(HttpSyncOnboardingStep.Hidden, model.state.value.step)
        assertTrue(repository.settings.first().hasCompletedOnboarding)
        assertFalse(repository.settings.first().isConfigured)

        val relaunched = models.create(repository) { fail("Relaunch must not sync") }
        advanceUntilIdle()
        assertEquals(HttpSyncOnboardingStep.Hidden, relaunched.state.value.step)
    }

    @Test
    fun validTokenIsSavedBeforeTheSharedSyncStartsAndDuplicateTapsStartOnlyOnce() = withRepository { repository, models ->
        var syncedSettings: HttpSyncSettings? = null
        var starts = 0
        val manualSync = HttpSyncManualSync(this) {
            starts++
            syncedSettings = repository.settings.first()
            HttpSyncResult(
                uploadedBookmarks = 0, uploadedChatEntries = 0, uploadedMetadata = 0,
                downloadedBookmarks = 0, downloadedChatEntries = 0, remoteOnlyBooks = 0, errors = emptyList(),
            )
        }
        val model = models.create(repository, start = manualSync::start)
        advanceUntilIdle()
        model.useToken("   ")
        assertEquals(HttpSyncOnboardingStep.Token, model.state.value.step)
        model.useToken("  friend-token\n")
        model.useToken("second-token")
        advanceUntilIdle()

        assertEquals(1, starts)
        assertEquals("friend-token", syncedSettings?.bearerToken)
        assertEquals(HttpSyncSettings.DEFAULT_BASE_URL, syncedSettings?.baseUrl)
        assertEquals(true, syncedSettings?.isConfigured)
        assertEquals(true, syncedSettings?.hasCompletedOnboarding)
        assertEquals(HttpSyncOnboardingStep.Dictionaries, model.state.value.step)
        model.skipDictionaries()
        advanceUntilIdle()
        assertEquals(HttpSyncOnboardingStep.Status, model.state.value.step)
        model.dismissStatus()
        assertEquals(HttpSyncOnboardingStep.Hidden, model.state.value.step)
    }

    @Test
    fun aFailedSyncCanEditAndRetryItsToken() = withRepository { repository, models ->
        val attemptedTokens = mutableListOf<String>()
        val manualSync = HttpSyncManualSync(this) {
            attemptedTokens += repository.settings.first().bearerToken
            throw IOException("Unable to connect")
        }
        val model = models.create(repository, start = manualSync::start)
        advanceUntilIdle()
        model.useToken("invalid")
        advanceUntilIdle()
        assertTrue(manualSync.status.value is SyncStatus.Failed)
        model.skipDictionaries()
        advanceUntilIdle()
        model.editToken()
        assertEquals(HttpSyncOnboardingStep.Token, model.state.value.step)
        model.useToken("replacement")
        advanceUntilIdle()
        assertEquals(listOf("invalid", "replacement"), attemptedTokens)
    }

    @Test
    fun configuredInstallsSkipOnboardingAndNeverAutomaticallyStartAnotherSync() = withRepository { repository, models ->
        repository.update { it.copy(bearerToken = "existing", hasCompletedDictionaryOnboarding = true) }
        val model = models.create(repository) { fail("Existing connection must not start sync") }
        advanceUntilIdle()
        assertEquals(HttpSyncOnboardingStep.Hidden, model.state.value.step)
        assertTrue(repository.settings.first().hasCompletedOnboarding)
    }

    @Test
    fun defaultDictionariesInstallWithoutATokenAndFailuresCanBeRetried() = withRepository { repository, models ->
        var attempts = 0
        val model = models.create(repository, install = { progress ->
            attempts++
            progress(DictionaryUpdateProgress(DictionaryUpdateStage.Downloading, "JMdict"))
            if (attempts == 1) throw IOException("Offline")
        }) { fail("Dictionaries do not require sync") }
        advanceUntilIdle()
        model.withoutToken()
        model.finishLater()
        advanceUntilIdle()
        model.downloadDictionaries()
        advanceUntilIdle()
        assertEquals(HttpSyncOnboardingStep.Dictionaries, model.state.value.step)
        assertTrue(model.state.value.dictionaryInstallFailed)
        assertFalse(repository.settings.first().hasCompletedDictionaryOnboarding)
        model.downloadDictionaries()
        model.downloadDictionaries()
        advanceUntilIdle()
        assertEquals(2, attempts)
        assertEquals(HttpSyncOnboardingStep.Hidden, model.state.value.step)
        assertTrue(repository.settings.first().hasCompletedDictionaryOnboarding)
        assertFalse(repository.settings.first().isConfigured)
    }

    @Test
    fun unfinishedDictionarySetupResumesAfterTokenSetupWasSaved() = withRepository { repository, models ->
        repository.update { it.copy(hasCompletedOnboarding = true, bearerToken = "saved") }
        val model = models.create(repository) { fail("A saved connection must not start another sync") }
        advanceUntilIdle()
        assertEquals(HttpSyncOnboardingStep.Dictionaries, model.state.value.step)
    }

    @Test
    fun savingFailureKeepsThePromptRecoverableAndDoesNotStartSync() = withRepository { _, models ->
        val repository = HttpSyncSettingsRepository(object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                val edited = transform(data.value)
                if (edited[stringPreferencesKey("bearerToken")] != null) throw IOException("Disk unavailable")
                data.value = edited
                return edited
            }
        })
        val model = models.create(repository) { fail("Unsaved credentials must not sync") }
        advanceUntilIdle()
        model.useToken("friend-token")
        advanceUntilIdle()
        assertEquals(HttpSyncOnboardingStep.Token, model.state.value.step)
        assertTrue(model.state.value.saveFailed)
        assertFalse(model.state.value.saving)
    }

    private fun withRepository(block: suspend TestScope.(HttpSyncSettingsRepository, Models) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val storeScope = CoroutineScope(SupervisorJob() + dispatcher)
        val models = Models()
        try {
            val file = temp.newFolder().resolve("sync.preferences_pb")
            val store = PreferenceDataStoreFactory.create(scope = storeScope) { file }
            block(HttpSyncSettingsRepository(store), models)
        } finally {
            models.clear()
            storeScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private class Models {
        private val stores = mutableListOf<ViewModelStore>()
        fun create(
            repository: HttpSyncSettingsRepository,
            install: suspend ((DictionaryUpdateProgress) -> Unit) -> Unit = {},
            start: () -> Unit,
        ): HttpSyncOnboardingViewModel {
            val store = ViewModelStore().also(stores::add)
            return ViewModelProvider.create(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HttpSyncOnboardingViewModel(repository, start, install, Dispatchers.Main) as T
            })[HttpSyncOnboardingViewModel::class.java]
        }
        fun clear() = stores.forEach(ViewModelStore::clear)
    }
}
