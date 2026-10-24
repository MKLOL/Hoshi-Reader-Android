package moe.antimony.hoshi.features.sync.http

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import kotlinx.coroutines.flow.first
import moe.antimony.hoshi.ui.asString

@Composable
internal fun HttpSyncOnboarding() {
    val container = LocalHoshiAppContainer.current
    val model: HttpSyncOnboardingViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HttpSyncOnboardingViewModel(
                    container.httpSyncSettingsRepository,
                    container.httpSyncManualSync::start,
                    installDictionaries = { progress ->
                        container.dictionaryRepository.importDefaultDictionaries(
                            lowRamImport = container.dictionarySettingsRepository.settings.first().lowRamDictionaryImport,
                            onProgress = progress,
                        )
                    },
                ) as T
        },
    )
    val state by model.state.collectAsStateWithLifecycle()
    val status by container.httpSyncManualSync.status.collectAsStateWithLifecycle()
    HttpSyncOnboardingContent(
        state = state,
        status = status,
        onUseToken = model::useToken,
        onWithoutToken = model::withoutToken,
        onFinishLater = model::finishLater,
        onDismissStatus = model::dismissStatus,
        onEditToken = model::editToken,
        onStop = {
            container.httpSyncManualSync.cancel()
            model.dismissStatus()
        },
        onDownloadDictionaries = model::downloadDictionaries,
        onSkipDictionaries = model::skipDictionaries,
    )
}

@Composable
internal fun HttpSyncOnboardingContent(
    state: HttpSyncOnboardingUiState,
    status: SyncStatus,
    onUseToken: (String) -> Unit,
    onWithoutToken: () -> Unit,
    onFinishLater: () -> Unit,
    onDismissStatus: () -> Unit,
    onEditToken: () -> Unit,
    onStop: () -> Unit,
    onDownloadDictionaries: () -> Unit = {},
    onSkipDictionaries: () -> Unit = {},
) {
    var token by rememberSaveable { mutableStateOf("") }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    when (state.step) {
        HttpSyncOnboardingStep.Token -> AlertDialog(
            onDismissRequest = { if (!state.saving) onWithoutToken() },
            title = { Text(stringResource(R.string.http_sync_onboarding_title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.http_sync_onboarding_description))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        enabled = !state.saving,
                        label = { Text(stringResource(R.string.http_sync_bearer_token)) },
                        singleLine = true,
                        visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                Icon(
                                    if (tokenVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = stringResource(
                                        if (tokenVisible) R.string.http_sync_hide_token else R.string.http_sync_show_token,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.saveFailed) OnboardingSaveError()
                }
            },
            confirmButton = {
                TextButton(onClick = { onUseToken(token) }, enabled = token.isNotBlank() && !state.saving) {
                    Text(stringResource(R.string.http_sync_now))
                }
            },
            dismissButton = {
                TextButton(onClick = onWithoutToken, enabled = !state.saving) {
                    Text(stringResource(R.string.http_sync_onboarding_without_token))
                }
            },
        )
        HttpSyncOnboardingStep.Later -> AlertDialog(
            onDismissRequest = { if (!state.saving) onFinishLater() },
            title = { Text(stringResource(R.string.http_sync_onboarding_later_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.http_sync_onboarding_later_description))
                    if (state.saveFailed) OnboardingSaveError()
                }
            },
            confirmButton = {
                TextButton(onClick = onFinishLater, enabled = !state.saving) {
                    Text(stringResource(R.string.action_done))
                }
            },
        )
        HttpSyncOnboardingStep.Status -> HttpSyncStatusDialog(
            status, onDismissStatus, onStop,
            onEditToken = onEditToken.takeIf {
                status is SyncStatus.Failed || (status is SyncStatus.Done && status.result.errors.isNotEmpty())
            },
        )
        HttpSyncOnboardingStep.Dictionaries -> AlertDialog(
            onDismissRequest = { if (!state.installingDictionaries && !state.saving) onSkipDictionaries() },
            title = { Text(stringResource(R.string.onboarding_dictionaries_title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.onboarding_dictionaries_description))
                    if (state.installingDictionaries) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(state.dictionaryProgress?.asString() ?: stringResource(R.string.dictionary_importing_default))
                    }
                    if (state.dictionaryInstallFailed) {
                        Text(stringResource(R.string.onboarding_dictionaries_failed), color = MaterialTheme.colorScheme.error)
                    }
                    if (state.saveFailed) OnboardingSaveError()
                    if (status is SyncStatus.Running) HttpSyncStatusLine(status)
                }
            },
            confirmButton = {
                TextButton(onClick = onDownloadDictionaries, enabled = !state.installingDictionaries && !state.saving) {
                    Text(stringResource(R.string.onboarding_dictionaries_download))
                }
            },
            dismissButton = {
                TextButton(onClick = onSkipDictionaries, enabled = !state.installingDictionaries && !state.saving) {
                    Text(stringResource(R.string.onboarding_dictionaries_skip))
                }
            },
        )
        HttpSyncOnboardingStep.Loading, HttpSyncOnboardingStep.Hidden -> Unit
    }
}

@Composable
private fun OnboardingSaveError() {
    Text(stringResource(R.string.http_sync_onboarding_save_failed), color = MaterialTheme.colorScheme.error)
}
