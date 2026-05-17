package moe.antimony.hoshi.features.sync.http

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold

/**
 * Settings screen for the Android-only HTTP sync — base URL, bearer token, enabled toggle,
 * and a manual "Sync now" button. Lives under Settings → Advanced → HTTP Sync alongside
 * (but independent of) the iOS-shared Google Drive sync.
 */
@Composable
fun HttpSyncSettingsView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalHoshiAppContainer.current
    val repository = appContainer.httpSyncSettingsRepository
    val reconciler = appContainer.httpSyncReconciler
    val scope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState(initial = null)

    // Status is transient — a sync result doesn't need to survive process death — and
    // `SyncStatus` is a sealed interface with non-Parcelable payloads, so `rememberSaveable`'s
    // default saver crashes at composition trying to validate it. Plain `remember` is fine.
    var status by remember { mutableStateOf<SyncStatus>(SyncStatus.Idle) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }

    SettingsDetailScaffold(title = "HTTP Sync", onClose = onClose, modifier = modifier) { innerPadding ->
        val loaded = settings ?: return@SettingsDetailScaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Sync your reading position and per-manga ChatGPT history to your own server " +
                    "over HTTPS, against a generic key/value blob API. Independent of the iOS-shared " +
                    "Google Drive sync; works for both EPUB and mokuro manga. Page turns auto-push " +
                    "every 5 turns and on leave; new ChatGPT replies push immediately. See " +
                    "docs/HTTP_SYNC_KV.md for the protocol spec.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = loaded.baseUrl,
                onValueChange = { value ->
                    scope.launch { repository.update { it.copy(baseUrl = value) } }
                },
                label = { Text("Base URL") },
                placeholder = { Text(HttpSyncSettings.DEFAULT_BASE_URL) },
                singleLine = true,
                supportingText = {
                    Text(
                        "Defaults to ${HttpSyncSettings.DEFAULT_BASE_URL}. No trailing slash; " +
                            "the sync paths /v1/books… are appended.",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = loaded.bearerToken,
                onValueChange = { value ->
                    scope.launch { repository.update { it.copy(bearerToken = value) } }
                },
                label = { Text("Bearer token") },
                singleLine = true,
                visualTransformation = if (tokenVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            imageVector = if (tokenVisible) {
                                Icons.Rounded.VisibilityOff
                            } else {
                                Icons.Rounded.Visibility
                            },
                            contentDescription = if (tokenVisible) "Hide token" else "Show token",
                        )
                    }
                },
                supportingText = { Text("Sent as `Authorization: Bearer …` on every request.") },
                modifier = Modifier.fillMaxWidth(),
            )
            EnabledRow(
                enabled = loaded.enabled,
                isConfigured = loaded.isConfigured,
                onChange = { enabled ->
                    scope.launch { repository.update { it.copy(enabled = enabled) } }
                },
            )
            SyncNowButton(
                enabled = loaded.isConfigured && status !is SyncStatus.Running,
                running = status is SyncStatus.Running,
                onClick = {
                    status = SyncStatus.Running(
                        HttpSyncProgress(
                            message = "Starting sync",
                            detail = "Preparing to compare this device with the server.",
                        ),
                    )
                    scope.launch {
                        status = runCatching {
                            reconciler.syncOnce(loaded) { progress ->
                                withContext(Dispatchers.Main.immediate) {
                                    status = SyncStatus.Running(progress)
                                }
                            }
                        }
                            .fold(
                                onSuccess = { result ->
                                    // Persist the inbound cursor so the next sync can use
                                    // `?since=` to skip everything we've already seen.
                                    result.newLastSyncedAt?.let { cursor ->
                                        repository.update { it.copy(lastSyncedAt = cursor) }
                                    }
                                    // Tell any active reader's circuit breaker that the
                                    // server is reachable now, so the next page turn pushes
                                    // even if the breaker was open from earlier failures.
                                    appContainer.httpSyncManualSyncSuccessAt.value =
                                        System.currentTimeMillis()
                                    SyncStatus.Done(result)
                                },
                                onFailure = {
                                    SyncStatus.Failed(it.message ?: "HTTP sync failed.")
                                },
                            )
                    }
                },
            )
            StatusLine(status)
        }
    }
}

@Composable
private fun EnabledRow(
    enabled: Boolean,
    isConfigured: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enabled", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (isConfigured) {
                        "On: page-turn and chat-entry auto-push run silently in the background. " +
                            "Off: only the Sync now button below pushes anything. Useful on " +
                            "cellular data or when you don't want the chatter."
                    } else {
                        "Fill in the base URL and bearer token first."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onChange, enabled = isConfigured)
        }
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
            Text("Syncing…")
        } else {
            Text("Sync now")
        }
    }
}

@Composable
private fun StatusLine(status: SyncStatus) {
    if (status is SyncStatus.Running) {
        SyncProgressView(status.progress)
        return
    }
    val (text, color) = when (status) {
        SyncStatus.Idle -> "" to MaterialTheme.colorScheme.onSurfaceVariant
        is SyncStatus.Running -> "" to MaterialTheme.colorScheme.onSurfaceVariant
        is SyncStatus.Done -> {
            val errorTail = if (status.result.errors.isEmpty()) "" else
                "\nErrors:\n" + status.result.errors.joinToString("\n") { " • $it" }
            "Sync complete: ${status.result.summary()}.$errorTail" to MaterialTheme.colorScheme.onSurface
        }
        is SyncStatus.Failed -> "Sync failed: ${status.message}" to MaterialTheme.colorScheme.error
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
            text = progress.message,
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
    }
}

private sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Running(val progress: HttpSyncProgress) : SyncStatus
    data class Done(val result: HttpSyncResult) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}
