package moe.antimony.hoshi.features.mangareader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import android.webkit.WebView
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

/** Preview owns its page and draft. Only Apply writes preferences; no reading events are emitted. */
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
    var draft by remember(source) { mutableStateOf(initial) }
    var page by remember(source) { mutableIntStateOf(source.pageIndex.coerceIn(0, source.book.pages.lastIndex)) }
    var original by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var rendering by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadedPage by remember { mutableStateOf<Int?>(null) }
    var viewport by remember { mutableStateOf(source.viewport) }
    LaunchedEffect(draft.mangaEnlargeSmallText, draft.mangaSmallTextAmount, draft.mangaSmallTextThreshold) { original = false }
    val cache = remember(source) { MangaPageRenderCache() }
    val pageBackground = Color(initial.backgroundColor(isSystemInDarkTheme()))
    val backgroundCss = "#%06X".format(initial.backgroundColor(isSystemInDarkTheme()) and 0xFFFFFF)
    val overlay = Color.Black.copy(alpha = 0.4f)
    val textButtonColors = ButtonDefaults.textButtonColors(contentColor = Color.White)
    val sliderColors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.3f))
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(Modifier.fillMaxSize().background(pageBackground).testTag("manga-page-text-preview")) {
            // Exactly the same inset handling and CSS viewport as MangaReaderScreen. Overlay
            // controls never participate in measuring the WebView, even when they are hidden.
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                val config = MangaPageRenderConfig(backgroundCssColor = backgroundCss, scanNonJapaneseText = false,
                    eInkMode = initial.eInkMode, viewportCssWidth = maxWidth.value.roundToInt(), viewportCssHeight = maxHeight.value.roundToInt(),
                    useNotoSansJpFont = initial.mangaUseNotoSansJp, enlargeSmallText = !original && draft.mangaEnlargeSmallText,
                    smallTextAmount = draft.mangaSmallTextAmount, smallTextThreshold = draft.mangaSmallTextThreshold,
                    selectionScript = "", showFurigana = initial.mangaShowFurigana, textSizePreview = true)
                LaunchedEffect(page, config) {
                    rendering = true
                    viewport = if (loadedPage == page) webView?.captureMangaPreviewViewport()
                        else if (loadedPage == null && page == source.pageIndex) source.viewport else null
                    loadedPage = page
                }
                MangaReaderWebView(book = source.book, bookRoot = source.root, pageIndex = page, renderConfig = config,
                    pageRenderCache = cache, onNavigate = {}, onTextSelected = { _, _ -> }, onSelectionCleared = {},
                    onAskAi = { _, _ -> }, onPageReady = {
                        viewport?.let { webView?.restoreMangaPreviewViewport(it) }
                        rendering = false
                    }, onWebViewReady = { webView = it }, modifier = Modifier.fillMaxSize().testTag("manga-preview-surface"))
                if (rendering) CircularProgressIndicator(Modifier.align(Alignment.Center).testTag("manga-preview-loading"), color = Color.White)
            }
            Row(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(12.dp)
                .fillMaxWidth().background(if (controlsVisible) overlay else Color.Transparent, RoundedCornerShape(16.dp)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (controlsVisible) {
                    TextButton(onClick = onClose, colors = textButtonColors) { Text(stringResource(R.string.action_back)) }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                        FilterChip(selected = original, onClick = { original = true },
                            label = { Text(stringResource(R.string.manga_small_text_original)) },
                            colors = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedLabelColor = Color.White,
                                containerColor = Color.Transparent, selectedContainerColor = Color.White.copy(alpha = 0.2f)))
                        FilterChip(selected = !original, onClick = { original = false },
                            label = { Text(stringResource(R.string.manga_preview_adjusted)) },
                            colors = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedLabelColor = Color.White,
                                containerColor = Color.Transparent, selectedContainerColor = Color.White.copy(alpha = 0.2f)))
                    }
                    TextButton(onClick = { onApply(draft) }, colors = textButtonColors, modifier = Modifier.testTag("manga-preview-apply")) {
                        Text(stringResource(R.string.manga_preview_apply))
                    }
                } else { Spacer(Modifier.weight(1f)) }
                IconButton(onClick = { controlsVisible = !controlsVisible },
                    modifier = Modifier.background(if (controlsVisible) Color.Transparent else overlay, RoundedCornerShape(24.dp))) {
                    Icon(if (controlsVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(if (controlsVisible) R.string.manga_preview_hide_controls else R.string.manga_preview_show_controls))
                }
            }
            if (controlsVisible) Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(12.dp)
                .fillMaxWidth().background(overlay, RoundedCornerShape(16.dp)).heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (source.book.pages[page].textBoxes.isEmpty()) Text(stringResource(R.string.manga_preview_no_text), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { page-- }, enabled = page > 0, colors = textButtonColors) { Text(stringResource(R.string.manga_preview_previous)) }
                    Text(stringResource(R.string.manga_preview_page, page + 1, source.book.pages.size))
                    TextButton(onClick = { page++ }, enabled = page < source.book.pages.lastIndex, colors = textButtonColors) { Text(stringResource(R.string.manga_preview_next)) }
                }
                if (source.book.pages.size > 1) {
                    val pageLabel = stringResource(R.string.manga_preview_page_label)
                    Slider(value = page.toFloat(), onValueChange = { page = it.roundToInt() }, colors = sliderColors,
                        valueRange = 0f..source.book.pages.lastIndex.toFloat(), modifier = Modifier.semantics { contentDescription = pageLabel })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.reader_behavior_manga_enlarge_small_text))
                    Switch(checked = draft.mangaEnlargeSmallText, onCheckedChange = { draft = draft.copy(mangaEnlargeSmallText = it) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(Triple(R.string.manga_small_text_subtle, 25, 24), Triple(R.string.manga_small_text_balanced, 50, 30),
                        Triple(R.string.manga_small_text_strong, 75, 40)).forEach { (label, amount, threshold) ->
                        TextButton(colors = textButtonColors, onClick = { draft = draft.copy(mangaEnlargeSmallText = true, mangaSmallTextAmount = amount,
                            mangaSmallTextThreshold = threshold); original = false }) { Text(stringResource(label)) }
                    }
                }
                Text(stringResource(R.string.manga_small_text_amount) + " · " + stringResource(R.string.manga_small_text_percent, draft.mangaSmallTextAmount))
                val amountLabel = stringResource(R.string.manga_small_text_amount)
                Slider(value = draft.mangaSmallTextAmount.toFloat(), onValueChange = { draft = draft.copy(mangaSmallTextAmount = it.roundToInt()) },
                    valueRange = 0f..100f, steps = 19, colors = sliderColors, enabled = draft.mangaEnlargeSmallText, modifier = Modifier.semantics { contentDescription = amountLabel })
                Text(stringResource(R.string.manga_small_text_threshold) + " · " + stringResource(R.string.manga_small_text_pixels, draft.mangaSmallTextThreshold))
                val thresholdLabel = stringResource(R.string.manga_small_text_threshold)
                Slider(value = draft.mangaSmallTextThreshold.toFloat(), onValueChange = { draft = draft.copy(mangaSmallTextThreshold = it.roundToInt()) },
                    valueRange = 10f..60f, steps = 49, colors = sliderColors, enabled = draft.mangaEnlargeSmallText, modifier = Modifier.semantics { contentDescription = thresholdLabel })
                Text(stringResource(R.string.manga_preview_fullscreen_note), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
