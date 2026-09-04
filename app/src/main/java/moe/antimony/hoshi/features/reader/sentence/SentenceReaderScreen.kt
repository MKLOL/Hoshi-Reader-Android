package moe.antimony.hoshi.features.reader.sentence

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.ai.MarkdownText
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.dictionary.LookupPopupAndroidStack
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupOptions
import moe.antimony.hoshi.features.dictionary.createLookupPopupItem
import moe.antimony.hoshi.features.reader.ReaderAppearanceSheet
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings

/** Longest query handed to the dictionary from a tap; the scan-length setting trims it further. */
private const val MAX_TAP_QUERY_CHARS = 32
private const val FONT_SIZE_STEP = 2
private const val MIN_FONT_SIZE = 16
private const val MAX_FONT_SIZE = 60

/**
 * Sentence mode: one sentence of an EPUB at a time, laid out horizontally, with its stored
 * pre-translation folded away underneath. Swipe or use the arrows to move a sentence at a time;
 * tap a word to open the same dictionary popup the reader uses.
 */
@Composable
internal fun SentenceReaderScreen(
    bookId: String,
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val positionStore = remember(context) { SentenceReaderPositionStore(context.filesDir) }
    val loadState by produceState<SentenceReaderLoadState>(SentenceReaderLoadState.Loading, bookId) {
        value = runCatching {
            withContext(Dispatchers.IO) { appContainer.dictionaryRepository.rebuildLookupQuery() }
            SentenceReaderLoader(appContainer.readerRouteStateHolder(), positionStore).load(bookId)
        }.getOrElse { error ->
            SentenceReaderLoadState.Error(error.localizedMessage ?: error.javaClass.simpleName)
        }
    }
    val systemDark = isSystemInDarkTheme()
    val background = Color(readerSettings.backgroundColor(systemDark))
    val foreground = Color(readerSettings.textColor(systemDark))

    Box(modifier = modifier.fillMaxSize().background(background)) {
        when (val state = loadState) {
            SentenceReaderLoadState.Loading -> CircularProgressIndicator(
                color = foreground,
                modifier = Modifier.align(Alignment.Center),
            )
            is SentenceReaderLoadState.Error -> Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.message, color = foreground, textAlign = TextAlign.Center)
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
            }
            is SentenceReaderLoadState.Ready -> SentenceReaderContent(
                bookId = bookId,
                state = state,
                readerSettings = readerSettings,
                onReaderSettingsChange = onReaderSettingsChange,
                positionStore = positionStore,
                foreground = foreground,
                background = background,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun SentenceReaderContent(
    bookId: String,
    state: SentenceReaderLoadState.Ready,
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    positionStore: SentenceReaderPositionStore,
    foreground: Color,
    background: Color,
    onClose: () -> Unit,
) {
    val appContainer = LocalHoshiAppContainer.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val book = state.book
    var position by remember(bookId) { mutableStateOf(state.initialPosition) }
    var translationExpanded by remember { mutableStateOf(false) }
    var popups by remember { mutableStateOf<List<LookupPopupItem>>(emptyList()) }
    var highlight by remember { mutableStateOf<IntRange?>(null) }
    // The lookup in flight, so a swipe or a dismissal cancels it instead of letting a stale
    // result open the previous sentence's popup over the new one.
    var lookupJob by remember { mutableStateOf<Job?>(null) }
    var showAppearance by remember { mutableStateOf(false) }
    val sasayakiSettings by appContainer.sasayakiSettingsRepository.settings
        .collectAsState(initial = SasayakiSettings())
    val sentence = position?.let(book::sentenceAt)
    val translation = remember(sentence, state.translations) {
        sentence?.let { state.translations?.translationFor(it) }
    }
    val dictionarySettings by appContainer.dictionarySettingsRepository.settings
        .collectAsState(initial = DictionarySettings())
    val audioSettings by appContainer.audioSettingsRepository.settings
        .collectAsState(initial = AudioSettings())
    val darkMode = background.luminance() < 0.5f
    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(this).toDp().value }
    val lookupOptions = LookupPopupOptions(
        isVertical = false,
        isFullWidth = readerSettings.popupFullWidth,
        width = readerSettings.popupWidth,
        height = readerSettings.popupHeight,
        swipeToDismiss = readerSettings.popupSwipeToDismiss,
        swipeThreshold = readerSettings.popupSwipeThreshold,
        reducedMotionScrolling = readerSettings.popupReducedMotionScrolling,
        reducedMotionScrollPercent = readerSettings.popupReducedMotionScrollPercent,
        reducedMotionSwipeThreshold = readerSettings.popupReducedMotionSwipeThreshold,
        popupScale = readerSettings.popupScale,
        popupActionBar = false,
        topInset = statusBarTop.toDouble(),
        dictionarySettings = dictionarySettings.normalized(),
        darkMode = darkMode,
        eInkMode = readerSettings.eInkMode,
        audioSettings = audioSettings,
        documentTitle = state.title,
    )
    val fontFamily = remember(readerSettings.selectedFont) {
        appContainer.readerFontManager.storedFont(readerSettings.selectedFont)?.file
            ?.let { file -> runCatching { FontFamily(Font(file)) }.getOrNull() }
            ?: FontFamily.Serif
    }

    LaunchedEffect(position) {
        position?.let { positionStore.save(bookId, it) }
    }
    LaunchedEffect(popups.isEmpty()) {
        if (popups.isEmpty()) highlight = null
    }

    // What a tap anywhere outside the popup does: the reader's "tap outside", minus the web view.
    fun dismissPopups() {
        lookupJob?.cancel()
        lookupJob = null
        popups = emptyList()
        highlight = null
    }
    fun move(next: SentencePosition?) {
        if (next == null) return
        position = next
        translationExpanded = false
        dismissPopups()
    }
    val next = position?.let { SentenceNavigation.next(book, it) }
    val previous = position?.let { SentenceNavigation.previous(book, it) }
    BackHandler {
        when {
            popups.isNotEmpty() -> dismissPopups()
            showAppearance -> showAppearance = false
            else -> onClose()
        }
    }
    fun stepFontSize(delta: Int) {
        onReaderSettingsChange(readerSettings.copy(fontSize = (readerSettings.fontSize + delta).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)))
    }

    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { screenOrigin = it.positionInRoot() }
            // Only taps no child claimed reach here: empty header space, the page beside the
            // sentence, the translation panel. Buttons, words and the popup itself keep priority.
            .pointerInput(Unit) { detectTapGestures { dismissPopups() } },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            SentenceHeader(
                title = state.title,
                chapterTitle = position?.let { book.chapters.getOrNull(it.chapter)?.title },
                counter = position?.let { stringResource(R.string.sentence_mode_counter, book.ordinal(it), book.totalSentences) },
                foreground = foreground,
                onClose = onClose,
                onAppearance = { showAppearance = true },
            )
            val swipeThresholdPx = with(density) { 56.dp.toPx() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(book) {
                        var travel = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { travel = 0f },
                            onDragEnd = {
                                // Read the live position: this handler outlives many recompositions.
                                val current = position ?: return@detectHorizontalDragGestures
                                if (travel <= -swipeThresholdPx) {
                                    move(SentenceNavigation.next(book, current))
                                } else if (travel >= swipeThresholdPx) {
                                    move(SentenceNavigation.previous(book, current))
                                }
                            },
                            onDragCancel = { travel = 0f },
                        ) { change, delta ->
                            travel += delta
                            change.consume()
                        }
                    }
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (sentence == null) {
                    Text(
                        text = stringResource(R.string.sentence_mode_empty),
                        color = foreground.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    SentenceText(
                        sentence = sentence,
                        highlight = highlight,
                        foreground = foreground,
                        fontFamily = fontFamily,
                        fontSizeSp = readerSettings.fontSize * 1.1f,
                        lineHeightMultiplier = readerSettings.lineHeight.toFloat(),
                        screenOrigin = { screenOrigin },
                        onTapOutside = ::dismissPopups,
                        onWordTap = { selection ->
                            lookupJob?.cancel()
                            lookupJob = scope.launch {
                                val lookup = withContext(Dispatchers.IO) {
                                    createLookupPopupItem(selection = selection, options = lookupOptions)
                                }
                                if (lookup == null) {
                                    popups = emptyList()
                                    highlight = null
                                } else {
                                    val (popup, matchedCodePoints) = lookup
                                    val start = selection.sentenceOffset ?: 0
                                    val end = sentence.text.offsetByCodePoints(start, matchedCodePoints.coerceAtMost(
                                        sentence.text.codePointCount(start, sentence.text.length),
                                    ))
                                    highlight = start until end
                                    popups = listOf(popup)
                                }
                            }
                        },
                    )
                }
            }
            TranslationPanel(
                translation = translation?.markdownResponse,
                expanded = translationExpanded,
                onToggle = { translationExpanded = !translationExpanded },
                foreground = foreground,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { move(previous) }, enabled = previous != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.sentence_mode_previous),
                        tint = foreground.copy(alpha = if (previous != null) 1f else 0.3f),
                    )
                }
                IconButton(onClick = { stepFontSize(-FONT_SIZE_STEP) }, enabled = readerSettings.fontSize > MIN_FONT_SIZE) {
                    Icon(
                        imageVector = Icons.Rounded.TextDecrease,
                        contentDescription = stringResource(R.string.sentence_mode_font_smaller),
                        tint = foreground.copy(alpha = if (readerSettings.fontSize > MIN_FONT_SIZE) 0.8f else 0.3f),
                    )
                }
                Text(
                    text = position?.let {
                        stringResource(
                            R.string.sentence_mode_chapter_progress,
                            book.readableChapterOrdinal(it),
                            book.readableChapterCount,
                        )
                    }.orEmpty(),
                    color = foreground.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { stepFontSize(FONT_SIZE_STEP) }, enabled = readerSettings.fontSize < MAX_FONT_SIZE) {
                    Icon(
                        imageVector = Icons.Rounded.TextIncrease,
                        contentDescription = stringResource(R.string.sentence_mode_font_larger),
                        tint = foreground.copy(alpha = if (readerSettings.fontSize < MAX_FONT_SIZE) 0.8f else 0.3f),
                    )
                }
                IconButton(onClick = { move(next) }, enabled = next != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.sentence_mode_next),
                        tint = foreground.copy(alpha = if (next != null) 1f else 0.3f),
                    )
                }
            }
        }
        LookupPopupAndroidStack(
            popups = popups,
            onPopupsChange = { popups = it },
            lookupChildPopup = { selection -> createLookupPopupItem(selection = selection, options = lookupOptions) },
            modifier = Modifier.fillMaxSize(),
        )
        if (showAppearance) {
            // The reader's own appearance sheet: font, size, theme and spacing are shared settings,
            // so a change here also changes the reader, exactly as the user would expect.
            ReaderAppearanceSheet(
                settings = readerSettings,
                onSettingsChange = onReaderSettingsChange,
                sasayakiSettings = sasayakiSettings,
                onSasayakiSettingsChange = { updated ->
                    scope.launch { appContainer.sasayakiSettingsRepository.update { updated } }
                },
                fontManager = appContainer.readerFontManager,
                onDismiss = { showAppearance = false },
            )
        }
    }
}

@Composable
private fun SentenceHeader(
    title: String,
    chapterTitle: String?,
    counter: String?,
    foreground: Color,
    onClose: () -> Unit,
    onAppearance: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.action_close),
                tint = foreground,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = foreground,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!chapterTitle.isNullOrBlank()) {
                Text(
                    text = chapterTitle,
                    color = foreground.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (counter != null) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = counter,
                color = foreground.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        IconButton(onClick = onAppearance) {
            Icon(
                imageVector = Icons.Rounded.Palette,
                contentDescription = stringResource(R.string.settings_appearance),
                tint = foreground,
            )
        }
    }
}

@Composable
private fun SentenceText(
    sentence: ReaderSentence,
    highlight: IntRange?,
    foreground: Color,
    fontFamily: FontFamily,
    fontSizeSp: Float,
    lineHeightMultiplier: Float,
    screenOrigin: () -> Offset,
    /** A tap inside the text's box that lands on no word: margin, the gap after the last line. */
    onTapOutside: () -> Unit,
    onWordTap: (ReaderSelectionData) -> Unit,
) {
    val density = LocalDensity.current
    // The pointerInput block below is keyed on the sentence and outlives recompositions, so it
    // must read the newest callbacks (and through them the newest lookup options), not the ones
    // captured when the sentence first appeared.
    val currentOnTapOutside by rememberUpdatedState(onTapOutside)
    val currentOnWordTap by rememberUpdatedState(onWordTap)
    var layout by remember(sentence) { mutableStateOf<TextLayoutResult?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val text = sentence.text
    val annotated: AnnotatedString = remember(text, highlight, foreground) {
        buildAnnotatedString {
            append(text)
            highlight?.let { range ->
                if (range.first in text.indices && range.last < text.length) {
                    addStyle(SpanStyle(background = foreground.copy(alpha = 0.18f)), range.first, range.last + 1)
                }
            }
        }
    }
    Text(
        text = annotated,
        style = TextStyle(
            color = foreground,
            fontFamily = fontFamily,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp * lineHeightMultiplier).sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Start,
        ),
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(sentence) {
                detectTapGestures { tap ->
                    val current = layout
                    if (current == null || text.isEmpty()) {
                        currentOnTapOutside()
                        return@detectTapGestures
                    }
                    val line = current.getLineForVerticalPosition(tap.y)
                    if (tap.x > current.getLineRight(line) || tap.x < current.getLineLeft(line)) {
                        currentOnTapOutside()
                        return@detectTapGestures
                    }
                    val offset = current.getOffsetForPosition(tap).coerceIn(0, text.length - 1)
                    val query = text.substring(offset).take(MAX_TAP_QUERY_CHARS)
                    if (query.isBlank()) {
                        currentOnTapOutside()
                        return@detectTapGestures
                    }
                    val box = current.getBoundingBox(offset)
                    val shift = origin - screenOrigin()
                    val rect = with(density) {
                        ReaderSelectionRect(
                            x = (box.left + shift.x).toDp().value.toDouble(),
                            y = (box.top + shift.y).toDp().value.toDouble(),
                            width = box.width.toDp().value.toDouble(),
                            height = box.height.toDp().value.toDouble(),
                        )
                    }
                    currentOnWordTap(
                        ReaderSelectionData(
                            text = query,
                            sentence = text,
                            rect = rect,
                            normalizedOffset = sentence.start + EpubSentenceSegmenter.matchableCount(text, offset),
                            sentenceOffset = offset,
                        ),
                    )
                }
            },
    )
}

@Composable
private fun TranslationPanel(
    translation: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    foreground: Color,
) {
    Surface(
        color = foreground.copy(alpha = 0.06f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = stringResource(R.string.sentence_mode_toggle_translation),
                    tint = foreground,
                )
                Text(
                    text = stringResource(R.string.sentence_mode_translation),
                    color = foreground,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (translation != null) {
                        MarkdownText(markdown = translation, color = foreground)
                    } else {
                        Text(
                            text = stringResource(R.string.sentence_mode_translation_missing),
                            color = foreground.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}
