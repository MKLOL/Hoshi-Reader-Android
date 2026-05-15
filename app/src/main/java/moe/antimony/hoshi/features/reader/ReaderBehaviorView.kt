package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.features.settings.collectAsLoadedSettings
import moe.antimony.hoshi.features.update.UpdateConfig

@Composable
fun ReaderBehaviorScreen(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The "Automatically Check for Updates" row is only mounted when UpdateConfig.AUTO_UPDATE_ENABLED
    // is on, so the rest of the screen stays free of network-update concerns when the
    // updater is dormant.
    val appContainer = LocalHoshiAppContainer.current
    val updateSettings = if (UpdateConfig.AUTO_UPDATE_ENABLED) {
        appContainer.updateSettingsRepository.settings.collectAsLoadedSettings()
    } else {
        null
    }
    val scope = rememberCoroutineScope()
    SettingsDetailScaffold(
        title = "Behavior",
        onClose = onClose,
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        ) {
            item {
                BehaviorSettingsCard {
                    BehaviorSwitchRow(
                        label = ReaderBehaviorRow.DisablePageTurnAnimation.label,
                        checked = settings.disablePageTurnAnimation,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(disablePageTurnAnimation = it))
                        },
                        description = "Skips the manga page-turn slide and swaps pages instantly.",
                    )
                    BehaviorDivider()
                    BehaviorSwitchRow(
                        label = ReaderBehaviorRow.VolumeKeysTurnPages.label,
                        checked = settings.volumeKeysTurnPages,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(volumeKeysTurnPages = it))
                        },
                    )
                    readerBehaviorSasayakiRows().forEach { label ->
                        BehaviorDivider()
                        BehaviorSwitchRow(
                            label = label,
                            checked = settings.volumeKeysSeekSasayaki,
                            onCheckedChange = {
                                onSettingsChange(settings.copy(volumeKeysSeekSasayaki = it))
                            },
                        )
                    }
                    BehaviorDivider()
                    BehaviorSwitchRow(
                        label = ReaderBehaviorRow.ReverseVolumeKeyDirection.label,
                        checked = settings.reverseVolumeKeyDirection,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(reverseVolumeKeyDirection = it))
                        },
                    )
                    BehaviorDivider()
                    BehaviorSwitchRow(
                        label = ReaderBehaviorRow.KeepScreenOn.label,
                        checked = settings.keepScreenOnWhileReading,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(keepScreenOnWhileReading = it))
                        },
                    )
                    // The auto-updater is opt-in at compile time (see UpdateConfig); when
                    // it is off, the toggle and its dependencies are entirely absent from
                    // the screen — no settings flicker while update-settings load.
                    val loadedUpdateSettings = updateSettings
                    if (UpdateConfig.AUTO_UPDATE_ENABLED && loadedUpdateSettings != null) {
                        BehaviorDivider()
                        BehaviorSwitchRow(
                            label = ReaderBehaviorRow.AutomaticallyCheckForUpdates.label,
                            checked = loadedUpdateSettings.autoCheckUpdates,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    appContainer.updateSettingsRepository.update {
                                        it.copy(autoCheckUpdates = enabled)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

internal fun readerBehaviorSasayakiRows(): List<String> =
    listOf(ReaderBehaviorRow.VolumeKeysSeekSasayaki.label)

internal fun readerBehaviorRows(): List<String> = ReaderBehaviorRow.entries.map { it.label }

private enum class ReaderBehaviorRow(val label: String) {
    DisablePageTurnAnimation("Disable Page-Turn Animation"),
    VolumeKeysTurnPages("Volume Keys Turn Pages"),
    VolumeKeysSeekSasayaki("Volume Keys Seek Sasayaki"),
    ReverseVolumeKeyDirection("Reverse Volume Key Direction"),
    KeepScreenOn("Keep Screen On"),
    AutomaticallyCheckForUpdates("Automatically Check for Updates"),
}

@Composable
private fun BehaviorSettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column(content = { content() })
    }
}

@Composable
private fun BehaviorSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        headlineContent = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = description?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

@Composable
private fun BehaviorDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
