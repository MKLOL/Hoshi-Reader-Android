package moe.antimony.hoshi.features.mangareader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import moe.antimony.hoshi.ui.theme.PlatformReaderUi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.mokuro.MokuroBookParser
import java.io.File
import kotlin.math.roundToInt

internal data class MangaTextPreviewSource(val book: MokuroBook, val root: File, val pageIndex: Int,
    val viewport: MangaPreviewViewport? = null)

/** Preview owns its page and draft. Only Save settings writes preferences; no reading events are emitted. */
@Composable
internal fun MangaTextPreviewDialog(
    initialSource: MangaTextPreviewSource? = null,
    settings: ReaderSettings,
    onApply: (ReaderSettings) -> Unit,
    onClose: () -> Unit,
) {
    val repository = LocalHoshiAppContainer.current.bookRepository
    var source by remember { mutableStateOf(initialSource) }
    var entries by remember { mutableStateOf<List<BookEntry>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (source == null) {
            try {
                entries = withContext(Dispatchers.IO) {
                    repository.loadAllBooks().filter { it.resolve("mokuro.json").isFile }.mapNotNull { root ->
                        repository.loadMetadata(root)?.let { BookEntry(root, it) }
                    }.sortedByDescending { it.metadata.lastAccess }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { failed = true; entries = emptyList() }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            val selected = source
            if (selected != null) {
                MangaTextSizePreview(selected, settings, onApply, onClose)
            } else {
                SettingsDetailScaffold(title = stringResource(R.string.manga_preview_choose), onClose = onClose) { padding ->
                    LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                        if (entries == null || loading) item { CircularProgressIndicator() }
                        if (failed) item { Text(stringResource(R.string.manga_preview_error)) }
                        if (entries?.isEmpty() == true && !failed) item { Text(stringResource(R.string.manga_preview_empty)) }
                        items(entries.orEmpty(), key = { it.root.path }) { entry ->
                            TextButton(enabled = !loading, onClick = {
                                loading = true; failed = false
                                scope.launch {
                                    try {
                                        val parsed = withContext(Dispatchers.IO) { MokuroBookParser().parse(entry.root) }
                                        source = MangaTextPreviewSource(parsed, entry.root, repository.loadBookmark(entry.root)?.chapterIndex ?: 0)
                                    } catch (error: CancellationException) { throw error }
                                    catch (_: Exception) { failed = true }
                                    finally { loading = false }
                                }
                            }) { Text(entry.displayTitle) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaTextSizePreview(source: MangaTextPreviewSource, initial: ReaderSettings,
    onApply: (ReaderSettings) -> Unit, onClose: () -> Unit) = PlatformReaderUi {
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    MangaReaderSystemBarsEffect(overrideWindow = dialogWindow)
    androidx.activity.compose.BackHandler(onBack = onClose)
    val baseline = remember(source) { initial }
    var draft by remember(source) { mutableStateOf(baseline) }
    var page by remember(source) { mutableIntStateOf(source.pageIndex.coerceIn(0, source.book.pages.lastIndex)) }
    var original by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var rendering by remember { mutableStateOf(true) }
    val scroll = rememberScrollState()
    var headerHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    LaunchedEffect(draft.mangaEnlargeSmallText, draft.mangaSmallTextAmount, draft.mangaSmallTextThreshold) { original = false }
    val cache = remember(source) { MangaPageRenderCache() }
    val background = initial.backgroundColor(isSystemInDarkTheme())
    val backgroundCss = "#%06X".format(background and 0xFFFFFF)
    val scrim = Color.Black.copy(alpha = 0.62f)
    val textButtonColors = ButtonDefaults.textButtonColors(contentColor = Color.White)
    val sliderColors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.3f), activeTickColor = Color.Black.copy(alpha = 0.22f),
        inactiveTickColor = Color.White.copy(alpha = 0.65f))
    val save: () -> Unit = { if (draft == baseline) onClose() else onApply(draft) }
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(background)).testTag("manga-page-text-preview")) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                val config = MangaPageRenderConfig(backgroundCssColor = backgroundCss, scanNonJapaneseText = false,
                    eInkMode = initial.eInkMode, viewportCssWidth = maxWidth.value.roundToInt(), viewportCssHeight = maxHeight.value.roundToInt(),
                    useNotoSansJpFont = initial.mangaUseNotoSansJp, showCopyButton = initial.mangaShowCopyButton,
                    enlargeSmallText = !original && draft.mangaEnlargeSmallText,
                    smallTextAmount = draft.mangaSmallTextAmount, smallTextThreshold = draft.mangaSmallTextThreshold,
                    selectionScript = "", showFurigana = initial.mangaShowFurigana, textSizePreview = true)
                MangaReaderWebView(book = source.book, bookRoot = source.root, pageIndex = page, renderConfig = config,
                    pageRenderCache = cache, onNavigate = {}, onTextSelected = { _, _ -> }, onSelectionCleared = {},
                    onAskAi = { _, _ -> }, onPageReady = { rendering = false }, onWebViewReady = {},
                    initialViewport = source.viewport, onPageLoadStarted = { rendering = true },
                    modifier = Modifier.fillMaxSize().testTag("manga-preview-surface"))
                if (rendering) CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("manga-preview-loading"), color = Color.White)
            }
            val panelHeight = (maxHeight - with(density) { headerHeight.toDp() } - 40.dp).coerceIn(0.dp, 350.dp)
            val wide = maxWidth >= 600.dp && density.fontScale <= 1.15f
            val title = stringResource(if (controlsVisible) R.string.manga_preview_hide_controls else R.string.manga_preview_show_controls)
            val toggleControls: @Composable () -> Unit = {
                TextButton(onClick = { controlsVisible = !controlsVisible }, colors = textButtonColors,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(title) }
            }
            val comparison: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewChoice(stringResource(R.string.manga_preview_original_size), original) { original = true }
                    PreviewChoice(stringResource(R.string.manga_preview_adjusted), !original) { original = false }
                }
            }
            val cancelButton: @Composable () -> Unit = {
                TextButton(onClick = onClose, colors = textButtonColors) { Text(stringResource(R.string.manga_preview_cancel)) }
            }
            val saveButton: @Composable () -> Unit = {
                TextButton(onClick = save, colors = textButtonColors, modifier = Modifier.testTag("manga-preview-apply")) {
                    Text(stringResource(R.string.manga_preview_apply))
                }
            }
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().zIndex(1f)
                .onSizeChanged { headerHeight = it.height }.safeDrawingPadding().padding(8.dp)) {
                if (controlsVisible) Column(Modifier.fillMaxWidth().background(scrim, RoundedCornerShape(16.dp)).padding(horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        cancelButton()
                        Spacer(Modifier.weight(1f))
                        if (wide) { comparison(); Spacer(Modifier.weight(1f)) }
                        saveButton()
                        toggleControls()
                    }
                    if (!wide) comparison()
                    if (original && draft != baseline) Text(stringResource(R.string.manga_preview_save_note),
                        Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.bodySmall)
                } else Box(Modifier.align(Alignment.TopEnd).background(scrim, RoundedCornerShape(24.dp))) { toggleControls() }
            }
            if (controlsVisible && panelHeight > 0.dp) Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(8.dp)
                .fillMaxWidth().background(scrim, RoundedCornerShape(16.dp)).heightIn(max = panelHeight)
                .verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { page-- }, enabled = page > 0, colors = textButtonColors) { Text(stringResource(R.string.manga_preview_previous)) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.manga_preview_page, page + 1, source.book.pages.size))
                        if (source.book.pages.size > 1) {
                            val pageLabel = stringResource(R.string.manga_preview_page_label)
                            Slider(value = page.toFloat(), onValueChange = { page = it.roundToInt() }, colors = sliderColors,
                                valueRange = 0f..source.book.pages.lastIndex.toFloat(), modifier = Modifier.semantics { contentDescription = pageLabel })
                        }
                    }
                    TextButton(onClick = { page++ }, enabled = page < source.book.pages.lastIndex, colors = textButtonColors) { Text(stringResource(R.string.manga_preview_next)) }
                }
                if (source.book.pages[page].textBoxes.isEmpty()) Text(stringResource(R.string.manga_preview_no_text), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = draft.mangaEnlargeSmallText, role = Role.Switch,
                    onValueChange = { draft = draft.copy(mangaEnlargeSmallText = it) }),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.reader_behavior_manga_enlarge_small_text), Modifier.weight(1f))
                    Switch(checked = draft.mangaEnlargeSmallText, onCheckedChange = null)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Triple(R.string.manga_small_text_subtle, 25, 24), Triple(R.string.manga_small_text_balanced, 50, 30),
                        Triple(R.string.manga_small_text_strong, 75, 40)).forEach { (label, amount, threshold) ->
                        val selected = draft.mangaEnlargeSmallText && draft.mangaSmallTextAmount == amount && draft.mangaSmallTextThreshold == threshold
                        PreviewChoice(stringResource(label), selected, Modifier.weight(1f)) {
                            draft = draft.copy(mangaEnlargeSmallText = true, mangaSmallTextAmount = amount, mangaSmallTextThreshold = threshold)
                            original = false
                        }
                    }
                }
                val amountLabel = stringResource(R.string.manga_small_text_amount)
                val amountValue = stringResource(R.string.manga_small_text_percent, draft.mangaSmallTextAmount)
                Text("$amountLabel · $amountValue")
                Slider(value = draft.mangaSmallTextAmount.toFloat(), onValueChange = { draft = draft.copy(mangaSmallTextAmount = it.roundToInt()) },
                    valueRange = 0f..100f, steps = 19, colors = sliderColors, enabled = draft.mangaEnlargeSmallText,
                    modifier = Modifier.semantics { contentDescription = amountLabel; stateDescription = amountValue })
                val thresholdLabel = stringResource(R.string.manga_small_text_threshold)
                val thresholdValue = stringResource(R.string.manga_small_text_pixels, draft.mangaSmallTextThreshold)
                Text("$thresholdLabel · $thresholdValue")
                Slider(value = draft.mangaSmallTextThreshold.toFloat(), onValueChange = { draft = draft.copy(mangaSmallTextThreshold = it.roundToInt()) },
                    valueRange = 10f..60f, steps = 49, colors = sliderColors, enabled = draft.mangaEnlargeSmallText,
                    modifier = Modifier.semantics { contentDescription = thresholdLabel; stateDescription = thresholdValue })
                Text(stringResource(R.string.manga_preview_tuning_note), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PreviewChoice(label: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.semantics { selected = isSelected },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, Color.White.copy(alpha = if (isSelected) 1f else 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text(label) }
}
