package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.GroupDivider
import moe.antimony.hoshi.features.settings.collectAsLoadedSettings
import moe.antimony.hoshi.features.update.UpdateConfig

/** These two preferences affect both readers; each has one shared editing location. */
@Composable
internal fun SharedReadingControls(settings: ReaderSettings, onSettingsChange: (ReaderSettings) -> Unit) {
    ReaderSettingsSection(stringResource(R.string.reader_settings_reading_controls)) {
        Text(stringResource(R.string.reader_settings_shared_description),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        ReaderSettingsSwitchRow(
            stringResource(R.string.reader_behavior_volume_keys_turn_pages), settings.volumeKeysTurnPages,
            { onSettingsChange(settings.copy(volumeKeysTurnPages = it)) },
        )
        GroupDivider()
        ReaderSettingsSwitchRow(
            stringResource(R.string.reader_behavior_reverse_volume_key_direction), settings.reverseVolumeKeyDirection,
            { onSettingsChange(settings.copy(reverseVolumeKeyDirection = it)) },
            stringResource(R.string.reader_settings_reverse_volume_desc),
        )
    }
    if (UpdateConfig.AUTO_UPDATE_ENABLED) {
        val appContainer = LocalHoshiAppContainer.current
        val updates = appContainer.updateSettingsRepository.settings.collectAsLoadedSettings()
        val scope = rememberCoroutineScope()
        if (updates != null) {
            ReaderSettingsSection(stringResource(R.string.reader_settings_updates)) {
                ReaderSettingsSwitchRow(
                    stringResource(R.string.reader_behavior_auto_check_updates), updates.autoCheckUpdates,
                    { enabled -> scope.launch { appContainer.updateSettingsRepository.update { it.copy(autoCheckUpdates = enabled) } } },
                )
            }
        }
    }
}

/** EPUB-only behavior. Shared volume page turning/reversal is linked from Related settings. */
@Composable
internal fun EpubReadingControls(settings: ReaderSettings, onSettingsChange: (ReaderSettings) -> Unit) {
    ReaderSettingsSection(stringResource(R.string.reader_settings_reading_controls)) {
        ReaderSettingsSwitchRow(
            stringResource(R.string.reader_behavior_keep_screen_on), settings.keepScreenOnWhileReading,
            { onSettingsChange(settings.copy(keepScreenOnWhileReading = it)) },
        )
        GroupDivider()
        ReaderSettingsSwitchRow(
            stringResource(R.string.reader_behavior_volume_keys_seek_sasayaki), settings.volumeKeysSeekSasayaki,
            { onSettingsChange(settings.copy(volumeKeysSeekSasayaki = it)) },
            stringResource(R.string.reader_settings_sasayaki_volume_desc),
        )
    }
}
