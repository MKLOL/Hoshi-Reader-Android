package moe.antimony.hoshi.features.sync.http

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R

@Composable
internal fun HttpSyncBookshelfButton(enabled: Boolean) {
    val container = LocalHoshiAppContainer.current
    val settings by container.httpSyncSettingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
    val sync = container.httpSyncManualSync
    val status by sync.status.collectAsStateWithLifecycle()
    var showStatus by rememberSaveable { mutableStateOf(false) }

    if (settings?.bearerToken?.isNotBlank() == true) {
        IconButton(
            enabled = enabled,
            onClick = {
                showStatus = true
                sync.start()
            },
        ) {
            Icon(Icons.Rounded.Cloud, contentDescription = stringResource(R.string.http_sync_now))
            if (status is SyncStatus.Running) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
            }
        }
    }
    if (showStatus) {
        AlertDialog(
            onDismissRequest = { showStatus = false },
            title = { Text(stringResource(R.string.http_sync_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HttpSyncStatusLine(status)
                }
            },
            confirmButton = {
                TextButton(onClick = { showStatus = false }) {
                    Text(stringResource(R.string.action_done))
                }
            },
        )
    }
}
