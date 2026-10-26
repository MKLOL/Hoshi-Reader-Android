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
import kotlinx.coroutines.CompletableDeferred
import moe.antimony.hoshi.RestoredInstallMarker
import moe.antimony.hoshi.dictionary.DictionaryUpdateProgress
import moe.antimony.hoshi.dictionary.DictionaryUpdateStage
import moe.antimony.hoshi.features.dictionary.DefaultDictionaryDownload
import moe.antimony.hoshi.features.dictionary.message

@OptIn(ExperimentalCoroutinesApi::class)
class HttpSyncOnboardingViewModelTest {
    @get:Rule val temp = TemporaryFolder()
    private val downloading = DictionaryUpdateProgress(DictionaryUpdateStage.Downloading, "JMdict")

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
    fun choosingNoTokenAfterAFailedOneStopsSyncingWithIt() = withRepository { repository, models ->
        val manualSync = HttpSyncManualSync(this) { throw IOException("Unauthorized") }
        val model = models.create(repository, start = manualSync::start)
        advanceUntilIdle()
        model.useToken("rejected")
        advanceUntilIdle()
        model.skipDictionaries()
        advanceUntilIdle()
        model.editToken()
        model.withoutToken()
        model.finishLater()
        advanceUntilIdle()

        assertEquals(HttpSyncOnboardingStep.Hidden, model.state.value.step)
        assertFalse(repository.settings.first().isConfigured)
        assertTrue(repository.settings.first().hasCompletedOnboarding)
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

    @Test
    fun aDownloadKeepsGoingAfterSetupClosesAndRecordsItsResult() = withRepository { repository, models ->
        val gate = CompletableDeferred<Unit>()
        var attempts = 0
        val download = models.download(repository) { progress ->
            attempts++
            progress(downloading)
            gate.await()
        }
        val setup = models.create(repository, download) { fail("Dictionaries do not require sync") }
        advanceUntilIdle()
        setup.withoutToken()
        setup.finishLater()
        advanceUntilIdle()
        setup.downloadDictionaries()
        advanceUntilIdle()
        assertTrue(setup.state.value.installingDictionaries)

        // The activity finished (or was recreated) while the dictionaries were downloading.
        models.close(setup)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(DefaultDictionaryDownload.Status.Installed, download.status.value)
        assertTrue(repository.settings.first().hasCompletedDictionaryOnboarding)
        val relaunched = models.create(repository, download) { fail("Relaunch must not sync") }
        advanceUntilIdle()
        assertEquals(HttpSyncOnboardingStep.Hidden, relaunched.state.value.step)
        assertEquals(1, attempts)
    }

    @Test
    fun setupReopenedDuringTheDownloadShowsItsProgressAndMovesOnWhenItEnds() = withRepository { repository, models ->
        val gate = CompletableDeferred<Unit>()
        var attempts = 0
        val download = models.download(repository) { progress ->
            attempts++
            progress(downloading)
            gate.await()
        }
        val setup = models.create(repository, download) { fail("Dictionaries do not require sync") }
        advanceUntilIdle()
        setup.withoutToken()
        setup.finishLater()
        advanceUntilIdle()
        setup.downloadDictionaries()
        advanceUntilIdle()
        models.close(setup)

        val reopened = models.create(repository, download) { fail("Dictionaries do not require sync") }
        advanceUntilIdle()
        assertEquals(HttpSyncOnboardingStep.Dictionaries, reopened.state.value.step)
        assertTrue(reopened.state.value.installingDictionaries)
        assertEquals(downloading.message(), reopened.state.value.dictionaryProgress)
        reopened.downloadDictionaries()
        reopened.skipDictionaries()
        advanceUntilIdle()
        assertEquals(HttpSyncOnboardingStep.Dictionaries, reopened.state.value.step)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(HttpSyncOnboardingStep.Hidden, reopened.state.value.step)
        assertEquals(1, attempts)
    }

    @Test
    fun setupReopenedAfterAFailedDownloadShowsTheFailureAndCanRetry() = withRepository { repository, models ->
        var attempts = 0
        val download = models.download(repository) { if (++attempts == 1) throw IOException("Offline") }
        val setup = models.create(repository, download) { fail("Dictionaries do not require sync") }
        advanceUntilIdle()
        setup.withoutToken()
        setup.finishLater()
        advanceUntilIdle()
        setup.downloadDictionaries()
        advanceUntilIdle()
        models.close(setup)

        val reopened = models.create(repository, download) { fail("Dictionaries do not require sync") }
        advanceUntilIdle()
        assertEquals(HttpSyncOnboardingStep.Dictionaries, reopened.state.value.step)
        assertTrue(reopened.state.value.dictionaryInstallFailed)
        assertFalse(reopened.state.value.installingDictionaries)
        reopened.downloadDictionaries()
        advanceUntilIdle()
        assertEquals(HttpSyncOnboardingStep.Hidden, reopened.state.value.step)
        assertEquals(2, attempts)
    }

    @Test
    fun anotherSetupPromptPassesOverDictionariesTheFirstOneInstalled() = withRepository { repository, models ->
        val gate = CompletableDeferred<Unit>()
        val download = models.download(repository) { gate.await() }
        val other = models.create(repository, download) { fail("No token was entered") }
        val setup = models.create(repository, download) { fail("No token was entered") }
        advanceUntilIdle()
        setup.withoutToken()
        setup.finishLater()
        advanceUntilIdle()
        setup.downloadDictionaries()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(HttpSyncOnboardingStep.Hidden, setup.state.value.step)

        assertEquals(HttpSyncOnboardingStep.Token, other.state.value.step)
        other.withoutToken()
        other.finishLater()
        advanceUntilIdle()
        assertEquals(HttpSyncOnboardingStep.Hidden, other.state.value.step)
    }

    @Test
    fun aRestoredLibraryIsAskedOnlyForTheTokenTheBackupCouldNotCarry() {
        val marker = RestoredInstallMarker.inDirectory(temp.newFolder("no_backup")).apply { record() }
        withRepository(restoredInstall = marker, isFreshInstall = false, hasTermDictionary = { true }) { repository, models ->
            val setup = models.create(repository) { fail("No token was entered") }
            advanceUntilIdle()
            assertEquals(HttpSyncOnboardingStep.Token, setup.state.value.step)
            setup.withoutToken()
            setup.finishLater()
            advanceUntilIdle()

            assertEquals(HttpSyncOnboardingStep.Hidden, setup.state.value.step)
            assertFalse(marker.isPresent)
            val relaunched = models.create(repository) { fail("Relaunch must not sync") }
            advanceUntilIdle()
            assertEquals(HttpSyncOnboardingStep.Hidden, relaunched.state.value.step)
        }
    }

    private fun withRepository(
        restoredInstall: RestoredInstallMarker? = null,
        isFreshInstall: Boolean = true,
        hasTermDictionary: () -> Boolean = { false },
        block: suspend TestScope.(HttpSyncSettingsRepository, Models) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val storeScope = CoroutineScope(SupervisorJob() + dispatcher)
        // Outlives every setup ViewModel, as the app scope does.
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        val models = Models(appScope)
        try {
            val file = temp.newFolder().resolve("sync.preferences_pb")
            val store = PreferenceDataStoreFactory.create(scope = storeScope) { file }
            block(HttpSyncSettingsRepository(store, isFreshInstall, restoredInstall, hasTermDictionary), models)
        } finally {
            models.clear()
            appScope.cancel()
            storeScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private class Models(private val appScope: CoroutineScope) {
        private val stores = mutableMapOf<HttpSyncOnboardingViewModel, ViewModelStore>()

        /** The app-owned download, wired to record its result as HoshiAppContainer does. */
        fun download(
            repository: HttpSyncSettingsRepository,
            install: suspend ((DictionaryUpdateProgress) -> Unit) -> Unit = {},
        ) = DefaultDictionaryDownload(
            scope = appScope,
            install = install,
            onInstalled = { repository.update { it.copy(hasCompletedDictionaryOnboarding = true) } },
            ioDispatcher = Dispatchers.Main,
        )

        fun create(
            repository: HttpSyncSettingsRepository,
            install: suspend ((DictionaryUpdateProgress) -> Unit) -> Unit = {},
            start: () -> Unit,
        ): HttpSyncOnboardingViewModel = create(repository, download(repository, install), start)

        fun create(
            repository: HttpSyncSettingsRepository,
            download: DefaultDictionaryDownload,
            start: () -> Unit,
        ): HttpSyncOnboardingViewModel {
            val store = ViewModelStore()
            val model = ViewModelProvider.create(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HttpSyncOnboardingViewModel(repository, start, download) as T
            })[HttpSyncOnboardingViewModel::class.java]
            stores[model] = store
            return model
        }

        /** The setup screen went away: its activity finished or was recreated. */
        fun close(model: HttpSyncOnboardingViewModel) {
            stores.remove(model)?.clear()
        }

        fun clear() = stores.values.forEach(ViewModelStore::clear)
    }
}
