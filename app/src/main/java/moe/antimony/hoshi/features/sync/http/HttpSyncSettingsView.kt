package moe.antimony.hoshi.features.sync.http

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.ui.asString

/**
 * Settings screen for the Android-only HTTP sync — base URL, bearer token, and a manual
 * "Sync now" button. Sync (including auto-push) is active whenever both fields are set.
 * Lives under Settings → Advanced → HTTP Sync alongside (but independent of) the
 * iOS-shared Google Drive sync.
 */
@Composable
fun HttpSyncSettingsView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalHoshiAppContainer.current
    val repository = appContainer.httpSyncSettingsRepository
    val manualSync = appContainer.httpSyncManualSync
    val scope = appContainer.appScope
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = null)

    val status by manualSync.status.collectAsStateWithLifecycle()

    SettingsDetailScaffold(title = stringResource(R.string.http_sync_title), onClose = onClose, modifier = modifier) { innerPadding ->
        val loaded = settings ?: return@SettingsDetailScaffold
        HttpSyncSettingsContent(
            settings = loaded,
            status = status,
            innerPadding = innerPadding,
            onCredentialsChange = { baseUrl, token ->
                scope.launch { repository.update { it.copy(baseUrl = baseUrl, bearerToken = token) } }
            },
            onSync = { baseUrl, token ->
                scope.launch {
                    // Persist the text visible at the tap before sync reads its settings.
                    repository.update { it.copy(baseUrl = baseUrl, bearerToken = token) }
                    manualSync.start()
                }
            },
        )
    }
}

@Composable
internal fun HttpSyncSettingsContent(
    settings: HttpSyncSettings,
    status: SyncStatus,
    onCredentialsChange: (String, String) -> Unit,
    onSync: (String, String) -> Unit,
    innerPadding: PaddingValues = PaddingValues(),
) {
    // Editing belongs to the UI. Feeding asynchronous DataStore echoes back into a text
    // field drops keystrokes and moves its cursor while a previous edit is being saved.
    var baseUrl by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var token by rememberSaveable { mutableStateOf(settings.bearerToken) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.http_sync_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { value ->
                baseUrl = value
                onCredentialsChange(value, token)
            },
            label = { Text(stringResource(R.string.http_sync_base_url)) },
            placeholder = { Text(HttpSyncSettings.DEFAULT_BASE_URL) },
            singleLine = true,
            supportingText = { Text(stringResource(R.string.http_sync_base_url_hint, HttpSyncSettings.DEFAULT_BASE_URL)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { value ->
                token = value
                onCredentialsChange(baseUrl, value)
            },
            label = { Text(stringResource(R.string.http_sync_bearer_token)) },
            singleLine = true,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                    Icon(
                        imageVector = if (tokenVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(
                            if (tokenVisible) R.string.http_sync_hide_token else R.string.http_sync_show_token,
                        ),
                    )
                }
            },
            supportingText = { Text(stringResource(R.string.http_sync_token_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        SyncNowButton(
            enabled = baseUrl.isNotBlank() && token.isNotBlank() && status !is SyncStatus.Running,
            running = status is SyncStatus.Running,
            onClick = { onSync(baseUrl, token) },
        )
        HttpSyncStatusLine(status)
    }
}

@Composable
private fun SyncNowButton(
    enabled: Boolean,
    running: Boolean,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        if (running) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.http_sync_running))
        } else {
            Text(stringResource(R.string.http_sync_now))
        }
    }
}

@Composable
internal fun HttpSyncStatusLine(status: SyncStatus) {
    if (status is SyncStatus.Running) {
        val progress = status.progress
        if (progress == null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.http_sync_starting))
            }
        } else {
            SyncProgressView(progress)
        }
        return
    }
    val (text, color) = when (status) {
        SyncStatus.Idle -> "" to MaterialTheme.colorScheme.onSurfaceVariant
        is SyncStatus.Running -> "" to MaterialTheme.colorScheme.onSurfaceVariant
        is SyncStatus.Done -> {
            val errorTail = if (status.result.errors.isEmpty()) "" else
                "\n" + stringResource(R.string.http_sync_errors) + "\n" + status.result.errors.joinToString("\n") { " • $it" }
            stringResource(R.string.http_sync_complete_format, status.result.summary()) + errorTail to MaterialTheme.colorScheme.onSurface
        }
        is SyncStatus.Failed -> stringResource(
            R.string.http_sync_failed_format,
            status.messageResource?.let { stringResource(it) }
                ?: status.message ?: stringResource(R.string.http_sync_unknown_error),
        ) to MaterialTheme.colorScheme.error
    }
    if (text.isNotEmpty()) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun SyncProgressView(progress: HttpSyncProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val fraction = progress.fraction
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = progress.messageResource?.let { stringResource(it) } ?: progress.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        progress.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        progress.transfer?.let { transfer ->
            Text(transfer.volumeText().asString(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(transfer.speedText().asString(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
