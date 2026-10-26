package moe.antimony.hoshi.features.sync.http

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.dictionary.DictionaryUpdateProgress
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

/** Owns first-launch setup across activity recreation; transfers use the existing app owner. */
internal class HttpSyncOnboardingViewModel(
    private val repository: HttpSyncSettingsRepository,
    private val startSync: () -> Unit,
    private val installDictionaries: suspend ((DictionaryUpdateProgress) -> Unit) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HttpSyncOnboardingUiState())
    val state = mutableState.asStateFlow()
    private var dictionarySetupCompleted = false
    private var startedSync = false

    init {
        viewModelScope.launch {
            val settings = repository.settings.first()
            dictionarySetupCompleted = settings.hasCompletedDictionaryOnboarding
            mutableState.value = HttpSyncOnboardingUiState(
                step = if (settings.hasCompletedOnboarding || settings.isConfigured) {
                    if (dictionarySetupCompleted) HttpSyncOnboardingStep.Hidden else HttpSyncOnboardingStep.Dictionaries
                } else HttpSyncOnboardingStep.Token,
            )
            if (settings.isConfigured && !settings.hasCompletedOnboarding) {
                repository.update { it.copy(hasCompletedOnboarding = true) }
            }
        }
    }

    fun useToken(token: String) {
        val current = state.value
        if (current.step != HttpSyncOnboardingStep.Token || current.saving || token.isBlank()) return
        save(if (dictionarySetupCompleted) HttpSyncOnboardingStep.Status else HttpSyncOnboardingStep.Dictionaries) {
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
            mutableState.value = HttpSyncOnboardingUiState(HttpSyncOnboardingStep.Later)
        }
    }

    fun finishLater() {
        if (state.value.step != HttpSyncOnboardingStep.Later || state.value.saving) return
        // Reaching this after a saved token is only possible through Edit token, offered when that
        // token failed. Without a token, background sync must not keep retrying the abandoned one.
        val abandonedToken = startedSync
        save(if (dictionarySetupCompleted) HttpSyncOnboardingStep.Hidden else HttpSyncOnboardingStep.Dictionaries) {
            repository.update {
                it.copy(bearerToken = if (abandonedToken) "" else it.bearerToken, hasCompletedOnboarding = true)
            }
            startedSync = false
        }
    }

    fun downloadDictionaries() {
        val current = state.value
        if (current.step != HttpSyncOnboardingStep.Dictionaries || current.installingDictionaries || current.saving) return
        mutableState.value = current.copy(installingDictionaries = true, dictionaryInstallFailed = false, saveFailed = false)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    installDictionaries { progress ->
                        mutableState.value = state.value.copy(dictionaryProgress = progress.message())
                    }
                }
                finishDictionaries()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(
                    installingDictionaries = false, dictionaryProgress = null, dictionaryInstallFailed = true,
                )
            }
        }
    }

    fun skipDictionaries() {
        if (state.value.step != HttpSyncOnboardingStep.Dictionaries || state.value.installingDictionaries || state.value.saving) return
        save(afterDictionaries()) { finishDictionaries() }
    }

    private suspend fun finishDictionaries() {
        repository.update { it.copy(hasCompletedDictionaryOnboarding = true) }
        dictionarySetupCompleted = true
        mutableState.value = HttpSyncOnboardingUiState(afterDictionaries())
    }

    private fun afterDictionaries() = if (startedSync) HttpSyncOnboardingStep.Status else HttpSyncOnboardingStep.Hidden

    fun dismissStatus() {
        if (state.value.step == HttpSyncOnboardingStep.Status) {
            mutableState.value = HttpSyncOnboardingUiState(HttpSyncOnboardingStep.Hidden)
        }
    }

    fun editToken() {
        if (state.value.step == HttpSyncOnboardingStep.Status) {
            mutableState.value = HttpSyncOnboardingUiState(HttpSyncOnboardingStep.Token)
        }
    }

    private fun save(nextStep: HttpSyncOnboardingStep, action: suspend () -> Unit) {
        mutableState.value = state.value.copy(saving = true, saveFailed = false)
        viewModelScope.launch {
            try {
                action()
                mutableState.value = HttpSyncOnboardingUiState(nextStep)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(saving = false, saveFailed = true)
            }
        }
    }
}
