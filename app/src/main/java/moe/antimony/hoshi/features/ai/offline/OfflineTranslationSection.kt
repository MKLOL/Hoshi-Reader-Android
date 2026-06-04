package moe.antimony.hoshi.features.ai.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.ai.offline.OfflineLlmManager.ModelDownloadState
import java.util.Locale
import kotlin.math.max

/**
 * Settings UI for the offline, on-device translation feature.
 *
 * Self-contained section intended to be dropped into the existing ChatGPT settings [Column]
 * (the caller just invokes `OfflineTranslationSection()`). It owns its own observation of the
 * [OfflineTranslationSettings] and [OfflineLlmManager.downloadState] flows, so it needs no
 * parameters beyond an optional [modifier].
 *
 * Writes are launched on [LocalHoshiAppContainer]'s `appScope` (matching
 * [moe.antimony.hoshi.features.ai.AiChatSettingsScreen]) so a toggle or model switch survives the
 * settings screen leaving composition.
 */
@Composable
fun OfflineTranslationSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appScope = LocalHoshiAppContainer.current.appScope

    val settingsRepo = remember { context.applicationContext.offlineTranslationSettingsRepository() }
    val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = null)
    val downloadState by OfflineLlmManager.downloadState.collectAsStateWithLifecycle()
    // Re-checks of on-disk model presence key on this so they refresh after a download/delete.
    val downloadedRevision by OfflineLlmManager.downloadedRevision.collectAsStateWithLifecycle()

    val loaded = settings ?: return

    val selectedModel = remember(loaded.activeModelId) {
        LlmModelCatalog.byId(loaded.activeModelId) ?: LlmModelCatalog.DEFAULT
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Offline translation (no internet)",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Translate speech bubbles fully on-device using a downloaded model. No API " +
                "key or connection needed. Quality is lower than ChatGPT.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Use on-device translation",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = loaded.useOnDeviceTranslation,
                onCheckedChange = { checked ->
                    appScope.launch {
                        settingsRepo.update { it.copy(useOnDeviceTranslation = checked) }
                    }
                },
            )
        }

        Text(
            text = "Model",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LlmModelCatalog.ALL.forEach { model ->
            val downloaded = remember(downloadState, downloadedRevision, model) {
                OfflineLlmManager.isDownloaded(context, model)
            }
            ModelRow(
                model = model,
                selected = model.id == selectedModel.id,
                downloaded = downloaded,
                onSelect = {
                    appScope.launch {
                        settingsRepo.update { it.copy(activeModelId = model.id) }
                    }
                },
            )
        }

        val selectedDownloaded = remember(downloadState, downloadedRevision, selectedModel) {
            OfflineLlmManager.isDownloaded(context, selectedModel)
        }
        DownloadControl(
            selectedModel = selectedModel,
            downloaded = selectedDownloaded,
            downloadState = downloadState,
            onDownload = {
                OfflineLlmManager.startDownload(context.applicationContext, selectedModel)
            },
            onCancel = { OfflineLlmManager.cancelDownload(context.applicationContext) },
            onDelete = {
                OfflineLlmManager.deleteDownloadedModel(context.applicationContext, selectedModel)
            },
        )

        Text(
            text = "Downloads once over the network — it keeps going with the screen off and " +
                "resumes from where it left off if interrupted — then works offline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Build identifier so you can confirm which APK is installed (commit hash).
        Text(
            text = "App build ${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelRow(
    model: LlmModel,
    selected: Boolean,
    downloaded: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
                if (downloaded) {
                    Text(
                        text = "Downloaded",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatBytes(model.approxSizeBytes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadControl(
    selectedModel: LlmModel,
    downloaded: Boolean,
    downloadState: ModelDownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val isDownloadingThis = downloadState is ModelDownloadState.Downloading &&
        downloadState.model.id == selectedModel.id
    val failedThis = downloadState as? ModelDownloadState.Failed
    val isFailedThis = failedThis != null && failedThis.model.id == selectedModel.id

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            isDownloadingThis -> {
                val downloading = downloadState as ModelDownloadState.Downloading
                val total = max(1L, downloading.totalBytes)
                val progress = (downloading.downloadedBytes.toFloat() / total.toFloat())
                    .coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${formatBytes(downloading.downloadedBytes)} / " +
                        formatBytes(downloading.totalBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }

            downloaded -> {
                TextButton(onClick = onDelete) {
                    Text("Delete")
                }
            }

            downloadState is ModelDownloadState.Downloading -> {
                // Only one download runs at a time; a different model is currently downloading.
                Text(
                    text = "Another model is downloading…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            isFailedThis -> {
                Text(
                    text = failedThis.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onDownload) {
                    Text("Retry")
                }
            }

            else -> {
                Button(onClick = onDownload) {
                    Text("Download")
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.1f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.0f MB", mb)
    }
}
