package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.GroupDivider
import moe.antimony.hoshi.mokuro.mangaRevealFontSize
import kotlin.math.roundToInt

@Composable
internal fun MangaSmallTextSettings(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit) {
    ReaderSettingsSection(stringResource(R.string.manga_small_text_title)) {
        ReaderSettingsSwitchRow(
            label = stringResource(R.string.reader_behavior_manga_enlarge_small_text),
            checked = settings.mangaEnlargeSmallText,
            onCheckedChange = { onChange(settings.copy(mangaEnlargeSmallText = it)) },
            description = stringResource(R.string.reader_behavior_manga_enlarge_small_text_desc),
        )
        GroupDivider()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.manga_small_text_visual_options), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple(R.string.manga_small_text_subtle, 25, 24),
                    Triple(R.string.manga_small_text_balanced, 50, 30),
                    Triple(R.string.manga_small_text_strong, 75, 40)).forEach { (label, amount, threshold) ->
                    val chosen = settings.mangaEnlargeSmallText && settings.mangaSmallTextAmount == amount &&
                        settings.mangaSmallTextThreshold == threshold
                    OutlinedCard(
                        onClick = { onChange(settings.copy(mangaEnlargeSmallText = true,
                            mangaSmallTextAmount = amount, mangaSmallTextThreshold = threshold)) },
                        border = BorderStroke(if (chosen) 2.dp else 1.dp,
                            if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.weight(1f).testTag("manga-preset-$amount").semantics {
                            selected = chosen
                            role = Role.RadioButton
                        },
                    ) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.manga_small_text_sample),
                                    fontSize = mangaRevealFontSize(12, true, amount, threshold).sp)
                            }
                            Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(R.string.manga_small_text_preset_values, amount, threshold),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            SmallTextSlider(
                stringResource(R.string.manga_small_text_amount),
                stringResource(R.string.manga_small_text_percent, settings.mangaSmallTextAmount),
                settings.mangaSmallTextAmount, 0f..100f, 19, settings.mangaEnlargeSmallText,
                "manga-small-text-amount",
            ) { onChange(settings.copy(mangaSmallTextAmount = it)) }
            SmallTextSlider(
                stringResource(R.string.manga_small_text_threshold),
                stringResource(R.string.manga_small_text_pixels, settings.mangaSmallTextThreshold),
                settings.mangaSmallTextThreshold, 10f..60f, 49, settings.mangaEnlargeSmallText,
                "manga-small-text-threshold",
            ) { onChange(settings.copy(mangaSmallTextThreshold = it)) }
            Text(stringResource(R.string.manga_small_text_explanation), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            GroupDivider()
            Text(stringResource(R.string.manga_small_text_preview), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.manga_small_text_source), Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.manga_small_text_original), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.manga_small_text_result), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            }
            listOf(12, 20, 30, 40).forEach { original ->
                val result = mangaRevealFontSize(original, settings.mangaEnlargeSmallText,
                    settings.mangaSmallTextAmount, settings.mangaSmallTextThreshold)
                val description = stringResource(R.string.manga_small_text_preview_accessibility, original, result)
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.manga_small_text_pixels, original), Modifier.width(48.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.manga_small_text_sample), Modifier.weight(1f), fontSize = original.sp)
                    Text(stringResource(R.string.manga_small_text_sample), Modifier.weight(1f), fontSize = result.sp,
                        fontWeight = if (result > original) FontWeight.Medium else FontWeight.Normal)
                }
            }
            Text(stringResource(R.string.manga_small_text_preview_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.manga_small_text_sync_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SmallTextSlider(label: String, display: String, value: Int, range: ClosedFloatingPointRange<Float>,
    steps: Int, enabled: Boolean, tag: String, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(display, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value.toFloat().coerceIn(range), onValueChange = { onChange(it.roundToInt()) },
            enabled = enabled, valueRange = range, steps = steps,
            modifier = Modifier.testTag(tag).semantics { contentDescription = label; stateDescription = display })
    }
}
