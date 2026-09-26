package moe.antimony.hoshi.features.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.TermResult
import moe.antimony.hoshi.R
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.features.ai.AiChatCard
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatPopupView
import moe.antimony.hoshi.features.ai.AiChatUiState
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.dictionary.LookupPopupAndroidStack
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupState
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.ui.theme.PlatformReaderUi
import moe.antimony.hoshi.ui.theme.currentLargeScreenUiScale
import moe.antimony.hoshi.ui.theme.currentPopupReadability
import kotlin.math.roundToInt

@Composable
internal fun PopupCardsSettingsScreen(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The drag state is immediate. Commit once at the end of a gesture, or on leaving,
    // instead of letting asynchronous disk emissions pull the thumb back mid-drag.
    var draft by rememberSaveable(stateSaver = cardEditorSaver(settings)) { mutableStateOf(settings) }
    var dictionary by rememberSaveable { mutableStateOf(true) }
    var fullPreview by rememberSaveable { mutableStateOf(false) }
    val latestSave by rememberUpdatedState(onSettingsChange)
    val save = { onSettingsChange(draft) }
    DisposableEffect(Unit) { onDispose { latestSave(draft) } }
    val defaults = currentPopupReadability()
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val windowWidth = window.width / density.density.toDouble()
    val windowHeight = window.height / density.density.toDouble()
    val style = if (dictionary) draft.dictionaryCardStyle(defaults) else
        draft.translationCard.resolveTranslation(defaults, windowWidth, windowHeight)
    val overrides = if (dictionary) draft.dictionaryCard else draft.translationCard
    val update: (PopupCardStyle) -> Unit = {
        draft = if (dictionary) draft.copy(dictionaryCard = it) else draft.copy(translationCard = it)
    }
    val source = stringResource(R.string.card_preview_source)
    val response = stringResource(R.string.card_preview_translation)
    val translation = remember(source, response) {
        AiChatUiState.Loaded(AiChatEntry(source, "", "", response, 0.0), pretranslated = true)
    }

    Box(modifier.fillMaxSize()) {
        SettingsDetailScaffold(
            title = stringResource(R.string.settings_popup_cards),
            onClose = { save(); onClose() },
        ) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val previewHeight = (maxHeight * 0.40f).coerceAtMost(420.dp)
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = dictionary, onClick = { save(); dictionary = true },
                            label = { Text(stringResource(R.string.card_dictionary)) }, modifier = Modifier.testTag("dictionary-tab"))
                        FilterChip(selected = !dictionary, onClick = { save(); dictionary = false },
                            label = { Text(stringResource(R.string.card_translation)) }, modifier = Modifier.testTag("translation-tab"))
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.card_preview_label), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { fullPreview = true }, modifier = Modifier.testTag("full-preview")) {
                            Text(stringResource(R.string.card_preview_full))
                        }
                    }
                    // A viewport onto the actual-size card, never a thumbnail. Keep it pinned
                    // while the controls scroll; large cards can be inspected in both directions.
                    Box(
                        Modifier.fillMaxWidth().height(previewHeight)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow).clipToBounds()
                            .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        if (!fullPreview) {
                        if (dictionary) DictionaryCardPreview(draft, style, windowWidth, windowHeight)
                        else AiChatCard(translation, style, onDismiss = {}, onRetry = {}, onAskLive = {},
                            modifier = Modifier.padding(16.dp).size(
                                minOf(style.width, windowWidth - 32).coerceAtLeast(1.0).dp,
                                minOf(style.height, windowHeight - 80).coerceAtLeast(1.0).dp,
                            ))
                        }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.card_defaults_description), style = MaterialTheme.typography.bodySmall)
                        CardStyleSliders(style, overrides, onChange = update, onFinished = save, widthLimit = maxOf(style.width, windowWidth - 12), heightLimit = maxOf(style.height, windowHeight - 80))
                        TextButton(onClick = {
                            // Reset the legacy dimensions as well, so old custom values don't
                            // become the device defaults again after tapping Reset.
                            draft = if (dictionary) draft.resetDictionaryCard()
                                else draft.copy(translationCard = PopupCardStyle())
                            save()
                        }, modifier = Modifier.testTag("reset-card")) { Text(stringResource(R.string.card_reset)) }
                        if (dictionary) DictionaryCardBehaviorControls(draft) { next ->
                            draft = next
                            save()
                        }
                    }
                }
            }
        }
        if (fullPreview) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = { fullPreview = false },
            )) {
                if (dictionary) {
                    DictionaryCardPreview(draft, style, windowWidth, windowHeight, fullscreen = true, onDismiss = { fullPreview = false })
                    TextButton(onClick = { fullPreview = false }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
                        Text(stringResource(R.string.action_done))
                    }
                } else AiChatPopupView(translation, onDismiss = { fullPreview = false }, onRetry = {}, onAskLive = {}, cardStyle = draft.translationCard)
            }
            BackHandler { fullPreview = false }
        }
    }
}

@Composable
internal fun CardStyleSliders(
    style: ResolvedPopupCardStyle,
    overrides: PopupCardStyle,
    onChange: (PopupCardStyle) -> Unit,
    onFinished: () -> Unit = {},
    widthLimit: Double = 1600.0,
    heightLimit: Double = 2000.0,
) {
    CardSlider(R.string.reader_appearance_width, "card-width", style.width, 100f..widthLimit.coerceAtLeast(100.0).toFloat(),
        stringResource(R.string.card_dp_format, style.width.roundToInt()), 10.0, { onChange(overrides.copy(width = it)) }, onFinished)
    CardSlider(R.string.reader_appearance_height, "card-height", style.height, 100f..heightLimit.coerceAtLeast(100.0).toFloat(),
        stringResource(R.string.card_dp_format, style.height.roundToInt()), 10.0, { onChange(overrides.copy(height = it)) }, onFinished)
    CardSlider(R.string.card_content_scale, "card-scale", style.scale, 0.5f..3f,
        stringResource(R.string.card_percent_format, (style.scale * 100).roundToInt()), 0.05, { onChange(overrides.copy(scale = it)) }, onFinished)
    CardSlider(R.string.card_font_size, "card-font", style.fontScale, 0.5f..3f,
        stringResource(R.string.card_percent_format, (style.fontScale * 100).roundToInt()), 0.05, { onChange(overrides.copy(fontScale = it)) }, onFinished)
}

@Composable
private fun CardSlider(label: Int, tag: String, value: Double, range: ClosedFloatingPointRange<Float>, display: String,
    step: Double, onChange: (Double) -> Unit, onFinished: () -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
            Text(display, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value.toFloat().coerceIn(range), onValueChange = { onChange((it / step).roundToInt() * step) },
            onValueChangeFinished = onFinished, valueRange = range, modifier = Modifier.testTag(tag))
    }
}

@Composable
internal fun DictionaryCardBehaviorControls(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit) {
    CardSwitch(R.string.reader_appearance_full_width, settings.popupFullWidth) { onChange(settings.copy(popupFullWidth = it)) }
    CardSwitch(R.string.reader_appearance_show_action_bar, settings.popupActionBar) { onChange(settings.copy(popupActionBar = it)) }
    CardSwitch(R.string.reader_appearance_swipe_to_dismiss, settings.popupSwipeToDismiss) { onChange(settings.copy(popupSwipeToDismiss = it)) }
    if (settings.popupSwipeToDismiss) CardSlider(R.string.reader_appearance_swipe_threshold, "card-swipe", settings.popupSwipeThreshold.toDouble(), 20f..60f,
        settings.popupSwipeThreshold.toString(), 5.0, { onChange(settings.copy(popupSwipeThreshold = it.toInt())) }, {})
    CardSwitch(R.string.reader_appearance_reduced_motion_scrolling, settings.popupReducedMotionScrolling) { onChange(settings.copy(popupReducedMotionScrolling = it)) }
    if (settings.popupReducedMotionScrolling) {
        CardSlider(R.string.reader_appearance_scroll_amount, "card-scroll", settings.popupReducedMotionScrollPercent.toDouble(), 40f..100f,
            stringResource(R.string.card_percent_format, settings.popupReducedMotionScrollPercent), 10.0, { onChange(settings.copy(popupReducedMotionScrollPercent = it.toInt())) }, {})
        CardSlider(R.string.reader_appearance_scroll_swipe_threshold, "card-scroll-swipe", settings.popupReducedMotionSwipeThreshold.toDouble(), 0f..100f,
            settings.popupReducedMotionSwipeThreshold.toString(), 10.0, { onChange(settings.copy(popupReducedMotionSwipeThreshold = it.toInt())) }, {})
    }
}

@Composable
private fun CardSwitch(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun DictionaryCardPreview(
    settings: ReaderSettings, style: ResolvedPopupCardStyle, windowWidth: Double, windowHeight: Double,
    fullscreen: Boolean = false, onDismiss: () -> Unit = {},
) {
    val dictionarySettings by LocalHoshiAppContainer.current.dictionarySettingsRepository.settings.collectAsStateWithLifecycle(initialValue = DictionarySettings())
    val word = stringResource(R.string.card_preview_word)
    val reading = stringResource(R.string.card_preview_reading)
    val glossary = stringResource(R.string.card_preview_glossary)
    val result = remember(word, reading, glossary) { LookupResult(word, word, emptyArray(), TermResult(
        expression = word, reading = reading, rules = "", glossaries = arrayOf(GlossaryEntry("JMdict", glossary, "", "")),
        frequencies = emptyArray(), pitches = emptyArray(),
    ), 0) }
    val uiScale = currentLargeScreenUiScale()
    val width = if (settings.popupFullWidth) windowWidth - 12 else minOf(style.width, windowWidth - 12)
    val height = minOf(style.height, windowHeight - 80)
    val darkMode = settings.usesDarkInterface(androidx.compose.foundation.isSystemInDarkTheme())
    PlatformReaderUi {
        LookupPopupAndroidStack(
            popups = listOf(LookupPopupItem(id = "settings-preview", state = LookupPopupState(
                previewUiScale = uiScale,
                selection = ReaderSelectionData(word, word, ReaderSelectionRect(6.0, 0.0, 0.0, 0.0), null, null),
                dictionarySettings = dictionarySettings,
                results = listOf(result), isVertical = false, isFullWidth = !fullscreen || settings.popupFullWidth,
                cardStyle = style.copy(width = width, height = height).asOverrides(),
                darkMode = darkMode, eInkMode = settings.eInkMode, popupActionBar = settings.popupActionBar,
                swipeToDismiss = settings.popupSwipeToDismiss, swipeThreshold = settings.popupSwipeThreshold,
                reducedMotionScrolling = settings.popupReducedMotionScrolling,
                reducedMotionScrollPercent = settings.popupReducedMotionScrollPercent,
                reducedMotionSwipeThreshold = settings.popupReducedMotionSwipeThreshold,
                audioSettings = AudioSettings(),
            ))),
            onPopupsChange = { if (it.isEmpty()) onDismiss() }, lookupChildPopup = { null },
            modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.size((width * uiScale + 12).dp, (height * uiScale + 12).dp),
        )
    }
}

/** Preserve an in-progress edit on rotation without serializing unrelated reader settings. */
private fun cardEditorSaver(base: ReaderSettings) = mapSaver(
    save = { value: ReaderSettings -> mapOf(
        "dictionary" to value.dictionaryCard.encode(), "translation" to value.translationCard.encode(),
        "width" to value.popupWidth, "height" to value.popupHeight, "scale" to value.popupScale,
        "fullWidth" to value.popupFullWidth, "actionBar" to value.popupActionBar,
        "swipe" to value.popupSwipeToDismiss, "swipeThreshold" to value.popupSwipeThreshold,
        "reducedMotion" to value.popupReducedMotionScrolling,
        "scrollPercent" to value.popupReducedMotionScrollPercent,
        "scrollThreshold" to value.popupReducedMotionSwipeThreshold,
    ) },
    restore = { base.copy(
        dictionaryCard = PopupCardStyle.decode(it["dictionary"] as String),
        translationCard = PopupCardStyle.decode(it["translation"] as String),
        popupWidth = it["width"] as Int, popupHeight = it["height"] as Int, popupScale = it["scale"] as Double,
        popupFullWidth = it["fullWidth"] as Boolean, popupActionBar = it["actionBar"] as Boolean,
        popupSwipeToDismiss = it["swipe"] as Boolean, popupSwipeThreshold = it["swipeThreshold"] as Int,
        popupReducedMotionScrolling = it["reducedMotion"] as Boolean,
        popupReducedMotionScrollPercent = it["scrollPercent"] as Int,
        popupReducedMotionSwipeThreshold = it["scrollThreshold"] as Int,
    ) },
)
