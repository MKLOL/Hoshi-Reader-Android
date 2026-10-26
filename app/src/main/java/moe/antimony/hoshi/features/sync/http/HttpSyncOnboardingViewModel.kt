package moe.antimony.hoshi.features.sync.http

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.antimony.hoshi.features.dictionary.DefaultDictionaryDownload
import moe.antimony.hoshi.features.dictionary.message
import moe.antimony.hoshi.ui.UiText

internal enum class HttpSyncOnboardingStep { Loading, Token, Later, Dictionaries, Status, Hidden }

internal data class HttpSyncOnboardingUiState(
    val step: HttpSyncOnboardingStep = HttpSyncOnboardingStep.Loading,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val installingDictionaries: Boolean = false,
    val dictionaryProgress: UiText? = null,
    val dictionaryInstallFailed: Boolean = false,
)

/**
 * Owns first-launch setup across activity recreation. Transfers belong to app owners: the shared
 * sync to [HttpSyncManualSync] and the default dictionaries to [DefaultDictionaryDownload], whose
 * progress and result this shows whenever setup is open, including after the screen was recreated.
 */
internal class HttpSyncOnboardingViewModel(
    private val repository: HttpSyncSettingsRepository,
    private val startSync: () -> Unit,
    private val dictionaries: DefaultDictionaryDownload,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HttpSyncOnboardingUiState())
    val state = mutableState.asStateFlow()
    private var dictionarySetupCompleted = false
    private var startedSync = false

    init {
        viewModelScope.launch {
            val settings = repository.settings.first()
            dictionarySetupCompleted = settings.hasCompletedDictionaryOnboarding
            show(
                if (settings.hasCompletedOnboarding || settings.isConfigured) {
                    HttpSyncOnboardingStep.Dictionaries
                } else HttpSyncOnboardingStep.Token,
            )
            if (settings.isConfigured && !settings.hasCompletedOnboarding) {
                repository.update { it.copy(hasCompletedOnboarding = true) }
            }
            dictionaries.status.collect { download ->
                if (download is DefaultDictionaryDownload.Status.Installed) {
                    // The owner recorded the finished setup; any step still to come passes it over.
                    dictionarySetupCompleted = true
                    if (state.value.step == HttpSyncOnboardingStep.Dictionaries) show(HttpSyncOnboardingStep.Dictionaries)
                } else {
                    mutableState.update { it.following(download) }
                }
            }
        }
    }

    fun useToken(token: String) {
        val current = state.value
        if (current.step != HttpSyncOnboardingStep.Token || current.saving || token.isBlank()) return
        save(HttpSyncOnboardingStep.Dictionaries) {
            repository.update {
                it.copy(
                    baseUrl = it.baseUrl.ifBlank { HttpSyncSettings.DEFAULT_BASE_URL },
                    bearerToken = token,
                    hasCompletedOnboarding = true,
                )
            }
            // The durable credentials must be available before the manual sync reads them.
            startSync()
            startedSync = true
        }
    }

    fun withoutToken() {
        if (state.value.step == HttpSyncOnboardingStep.Token && !state.value.saving) {
            show(HttpSyncOnboardingStep.Later)
        }
    }

    fun finishLater() {
        if (state.value.step != HttpSyncOnboardingStep.Later || state.value.saving) return
        // Reaching this after a saved token is only possible through Edit token, offered when that
        // token failed. Without a token, background sync must not keep retrying the abandoned one.
        val abandonedToken = startedSync
        save(HttpSyncOnboardingStep.Dictionaries) {
            repository.update {
                it.copy(bearerToken = if (abandonedToken) "" else it.bearerToken, hasCompletedOnboarding = true)
            }
            startedSync = false
        }
    }

    /** Starts (or retries) the app-owned download; leaving setup while it runs does not stop it. */
    fun downloadDictionaries() {
        val current = state.value
        if (current.step != HttpSyncOnboardingStep.Dictionaries || current.installingDictionaries || current.saving) return
        mutableState.value = current.copy(saveFailed = false)
        dictionaries.start()
        mutableState.update { it.following(dictionaries.status.value) }
    }

    fun skipDictionaries() {
        val current = state.value
        if (current.step != HttpSyncOnboardingStep.Dictionaries || current.installingDictionaries || current.saving) return
        save(HttpSyncOnboardingStep.Dictionaries) {
            repository.update { it.copy(hasCompletedDictionaryOnboarding = true) }
            dictionarySetupCompleted = true
        }
    }

    private fun afterDictionaries() = if (startedSync) HttpSyncOnboardingStep.Status else HttpSyncOnboardingStep.Hidden

    fun dismissStatus() {
        if (state.value.step == HttpSyncOnboardingStep.Status) show(HttpSyncOnboardingStep.Hidden)
    }

    fun editToken() {
        if (state.value.step == HttpSyncOnboardingStep.Status) show(HttpSyncOnboardingStep.Token)
    }

    /** Moves to [step]; a dictionary step that is already finished is passed over. */
    private fun show(step: HttpSyncOnboardingStep) {
        val download = dictionaries.status.value
        if (download is DefaultDictionaryDownload.Status.Installed) dictionarySetupCompleted = true
        val next = if (step == HttpSyncOnboardingStep.Dictionaries && dictionarySetupCompleted) afterDictionaries() else step
        mutableState.value = HttpSyncOnboardingUiState(next).following(download)
    }

    private fun save(nextStep: HttpSyncOnboardingStep, action: suspend () -> Unit) {
        mutableState.value = state.value.copy(saving = true, saveFailed = false)
        viewModelScope.launch {
            try {
                action()
                show(nextStep)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }
}

private fun HttpSyncOnboardingUiState.following(download: DefaultDictionaryDownload.Status) = copy(
    installingDictionaries = download is DefaultDictionaryDownload.Status.Running,
    dictionaryProgress = (download as? DefaultDictionaryDownload.Status.Running)?.progress?.message(),
    dictionaryInstallFailed = download is DefaultDictionaryDownload.Status.Failed,
)
