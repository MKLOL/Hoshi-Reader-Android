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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
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
        title = stringResource(R.string.settings_behavior),
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
                        label = stringResource(ReaderBehaviorRow.DisablePageTurnAnimation.labelRes),
                        checked = settings.disablePageTurnAnimation,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(disablePageTurnAnimation = it))
                        },
                        description = stringResource(R.string.reader_behavior_disable_page_turn_animation_desc),
                    )
                    BehaviorDivider()
                    BehaviorSwitchRow(
                        label = stringResource(ReaderBehaviorRow.VolumeKeysTurnPages.labelRes),
                        checked = settings.volumeKeysTurnPages,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(volumeKeysTurnPages = it))
                        },
                    )
                    readerBehaviorSasayakiRows().forEach { labelRes ->
                        BehaviorDivider()
                        BehaviorSwitchRow(
                            label = stringResource(labelRes),
                            checked = settings.volumeKeysSeekSasayaki,
                            onCheckedChange = {
                                onSettingsChange(settings.copy(volumeKeysSeekSasayaki = it))
                            },
                        )
                    }
                    BehaviorDivider()
                    BehaviorSwitchRow(
                        label = stringResource(ReaderBehaviorRow.ReverseVolumeKeyDirection.labelRes),
                        checked = settings.reverseVolumeKeyDirection,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(reverseVolumeKeyDirection = it))
                        },
                    )
                    BehaviorDivider()
                    BehaviorSwitchRow(
                        label = stringResource(ReaderBehaviorRow.KeepScreenOn.labelRes),
                        checked = settings.keepScreenOnWhileReading,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(keepScreenOnWhileReading = it))
                        },
                    )
                    // Manga-only behavior toggles. These used to live in the manga
                    // reader's overflow (⋯) menu, but they're stable preferences not
                    // per-session knobs — so they belong here in Settings.
                    BehaviorDivider()
                    BehaviorSwitchRow(
                        label = stringResource(ReaderBehaviorRow.MangaSingleTapLookup.labelRes),
                        checked = settings.mangaSingleTapLookup,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(mangaSingleTapLookup = it))
                        },
                        description = stringResource(R.string.reader_behavior_manga_single_tap_lookup_desc),
                    )
                    BehaviorDivider()
                    BehaviorSwitchRow(
                        label = stringResource(ReaderBehaviorRow.MangaUseNotoSansJp.labelRes),
                        checked = settings.mangaUseNotoSansJp,
                        onCheckedChange = {
                            onSettingsChange(settings.copy(mangaUseNotoSansJp = it))
                        },
                        description = stringResource(R.string.reader_behavior_manga_use_noto_sans_jp_desc),
                    )
                    // The auto-updater is opt-in at compile time (see UpdateConfig); when
                    // it is off, the toggle and its dependencies are entirely absent from
                    // the screen — no settings flicker while update-settings load.
                    val loadedUpdateSettings = updateSettings
                    if (UpdateConfig.AUTO_UPDATE_ENABLED && loadedUpdateSettings != null) {
                        BehaviorDivider()
                        BehaviorSwitchRow(
                            label = stringResource(ReaderBehaviorRow.AutomaticallyCheckForUpdates.labelRes),
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

internal fun readerBehaviorSasayakiRows(): List<Int> =
    listOf(ReaderBehaviorRow.VolumeKeysSeekSasayaki.labelRes)

internal fun readerBehaviorRows(): List<Int> = ReaderBehaviorRow.entries.map { it.labelRes }

private enum class ReaderBehaviorRow(val labelRes: Int) {
    DisablePageTurnAnimation(R.string.reader_behavior_disable_page_turn_animation),
    VolumeKeysTurnPages(R.string.reader_behavior_volume_keys_turn_pages),
    VolumeKeysSeekSasayaki(R.string.reader_behavior_volume_keys_seek_sasayaki),
    ReverseVolumeKeyDirection(R.string.reader_behavior_reverse_volume_key_direction),
    KeepScreenOn(R.string.reader_behavior_keep_screen_on),
    MangaSingleTapLookup(R.string.reader_behavior_manga_single_tap_lookup),
    MangaUseNotoSansJp(R.string.reader_behavior_manga_use_noto_sans_jp),
    AutomaticallyCheckForUpdates(R.string.reader_behavior_auto_check_updates),
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
