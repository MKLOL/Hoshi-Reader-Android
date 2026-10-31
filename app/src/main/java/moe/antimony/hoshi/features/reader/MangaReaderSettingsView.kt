package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.GroupDivider
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold

@Composable
internal fun MangaReaderSettingsScreen(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onClose: () -> Unit,
    onOpenSharedSettings: () -> Unit,
    onOpenCards: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsDetailScaffold(
        title = stringResource(R.string.settings_manga),
        onClose = onClose,
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                ReaderSettingsSection(stringResource(R.string.reader_settings_recognized_text)) {
                    ReaderSettingsSwitchRow(
                        label = stringResource(R.string.reader_behavior_manga_show_furigana),
                        checked = settings.mangaShowFurigana,
                        onCheckedChange = { onSettingsChange(settings.copy(mangaShowFurigana = it)) },
                        description = stringResource(R.string.reader_behavior_manga_show_furigana_desc),
                    )
                    GroupDivider()
                    ReaderSettingsSwitchRow(
                        label = stringResource(R.string.reader_behavior_manga_enlarge_small_text),
                        checked = settings.mangaEnlargeSmallText,
                        onCheckedChange = { onSettingsChange(settings.copy(mangaEnlargeSmallText = it)) },
                        description = stringResource(R.string.reader_behavior_manga_enlarge_small_text_desc),
                    )
                    GroupDivider()
                    ReaderSettingsSwitchRow(
                        label = stringResource(R.string.reader_behavior_manga_use_noto_sans_jp),
                        checked = settings.mangaUseNotoSansJp,
                        onCheckedChange = { onSettingsChange(settings.copy(mangaUseNotoSansJp = it)) },
                        description = stringResource(R.string.reader_behavior_manga_use_noto_sans_jp_desc),
                    )
                }
            }
            item {
                ReaderSettingsSection(stringResource(R.string.reader_settings_bubble_actions)) {
                    ReaderSettingsSwitchRow(
                        label = stringResource(R.string.reader_behavior_manga_single_tap_lookup),
                        checked = settings.mangaSingleTapLookup,
                        onCheckedChange = { onSettingsChange(settings.copy(mangaSingleTapLookup = it)) },
                        description = stringResource(R.string.reader_behavior_manga_single_tap_lookup_desc),
                    )
                    GroupDivider()
                    ReaderSettingsSwitchRow(
                        label = stringResource(R.string.reader_behavior_manga_show_copy_button),
                        checked = settings.mangaShowCopyButton,
                        onCheckedChange = { onSettingsChange(settings.copy(mangaShowCopyButton = it)) },
                        description = stringResource(R.string.reader_behavior_manga_show_copy_button_desc),
                    )
                }
            }
            item {
                ReaderSettingsSection(stringResource(R.string.reader_settings_page_turning)) {
                    ReaderSettingsSwitchRow(
                        label = stringResource(R.string.reader_behavior_disable_page_turn_animation),
                        checked = settings.disablePageTurnAnimation,
                        onCheckedChange = { onSettingsChange(settings.copy(disablePageTurnAnimation = it)) },
                        description = stringResource(R.string.reader_behavior_disable_page_turn_animation_desc),
                    )
                }
            }
            item {
                ReaderSettingsSection(stringResource(R.string.reader_settings_related)) {
                    ReaderSettingsLinkRow(
                        label = stringResource(R.string.settings_app_and_reading),
                        onClick = onOpenSharedSettings,
                        description = stringResource(R.string.reader_settings_shared_description),
                    )
                    GroupDivider()
                    ReaderSettingsLinkRow(
                        label = stringResource(R.string.settings_popup_cards),
                        onClick = onOpenCards,
                    )
                }
            }
        }
    }
}
