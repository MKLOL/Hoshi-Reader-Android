package moe.antimony.hoshi.features.mangareader

import androidx.compose.foundation.layout.*
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

internal data class MangaTextPreviewSource(val book: MokuroBook, val root: File, val pageIndex: Int)

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
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
    onApply: (ReaderSettings) -> Unit, onClose: () -> Unit) {
    var draft by remember(source) { mutableStateOf(initial) }
    var page by remember(source) { mutableIntStateOf(source.pageIndex.coerceIn(0, source.book.pages.lastIndex)) }
    var original by remember { mutableStateOf(false) }
    LaunchedEffect(draft.mangaEnlargeSmallText, draft.mangaSmallTextAmount, draft.mangaSmallTextThreshold) { original = false }
    var rendering by remember { mutableStateOf(true) }
    val cache = remember(source) { MangaPageRenderCache() }
    SettingsDetailScaffold(title = stringResource(R.string.manga_preview_title), onClose = onClose,
        actions = { TextButton(onClick = { onApply(draft) }, modifier = Modifier.testTag("manga-preview-apply")) {
            Text(stringResource(R.string.manga_preview_apply))
        } }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().testTag("manga-page-text-preview")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                FilterChip(selected = original, onClick = { original = true }, label = { Text(stringResource(R.string.manga_small_text_original)) })
                FilterChip(selected = !original, onClick = { original = false }, label = { Text(stringResource(R.string.manga_preview_adjusted)) })
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val config = MangaPageRenderConfig(backgroundCssColor = "#000", scanNonJapaneseText = false,
                    eInkMode = initial.eInkMode, viewportCssWidth = maxWidth.value.roundToInt(), viewportCssHeight = maxHeight.value.roundToInt(),
                    useNotoSansJpFont = initial.mangaUseNotoSansJp, enlargeSmallText = !original && draft.mangaEnlargeSmallText,
                    smallTextAmount = draft.mangaSmallTextAmount, smallTextThreshold = draft.mangaSmallTextThreshold,
                    selectionScript = "", showFurigana = initial.mangaShowFurigana, textSizePreview = true)
                LaunchedEffect(page, config) { rendering = true }
                MangaReaderWebView(book = source.book, bookRoot = source.root, pageIndex = page, renderConfig = config,
                    pageRenderCache = cache, onNavigate = {}, onTextSelected = { _, _ -> }, onSelectionCleared = {},
                    onAskAi = { _, _ -> }, onPageReady = { rendering = false }, onWebViewReady = {}, modifier = Modifier.fillMaxSize())
                if (rendering) CircularProgressIndicator(Modifier.align(androidx.compose.ui.Alignment.Center).testTag("manga-preview-loading"))
            }
            Column(Modifier.height(245.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (source.book.pages[page].textBoxes.isEmpty()) Text(stringResource(R.string.manga_preview_no_text), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { page-- }, enabled = page > 0) { Text(stringResource(R.string.manga_preview_previous)) }
                    Text(stringResource(R.string.manga_preview_page, page + 1, source.book.pages.size))
                    TextButton(onClick = { page++ }, enabled = page < source.book.pages.lastIndex) { Text(stringResource(R.string.manga_preview_next)) }
                }
                if (source.book.pages.size > 1) {
                    val pageLabel = stringResource(R.string.manga_preview_page_label)
                    Slider(value = page.toFloat(), onValueChange = { page = it.roundToInt() },
                        valueRange = 0f..source.book.pages.lastIndex.toFloat(), modifier = Modifier.semantics { contentDescription = pageLabel })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.reader_behavior_manga_enlarge_small_text))
                    Switch(checked = draft.mangaEnlargeSmallText, onCheckedChange = { draft = draft.copy(mangaEnlargeSmallText = it) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(Triple(R.string.manga_small_text_subtle, 25, 24), Triple(R.string.manga_small_text_balanced, 50, 30),
                        Triple(R.string.manga_small_text_strong, 75, 40)).forEach { (label, amount, threshold) ->
                        OutlinedButton(onClick = { draft = draft.copy(mangaEnlargeSmallText = true, mangaSmallTextAmount = amount,
                            mangaSmallTextThreshold = threshold); original = false }) { Text(stringResource(label)) }
                    }
                }
                Text(stringResource(R.string.manga_small_text_amount) + " · " + stringResource(R.string.manga_small_text_percent, draft.mangaSmallTextAmount))
                val amountLabel = stringResource(R.string.manga_small_text_amount)
                Slider(value = draft.mangaSmallTextAmount.toFloat(), onValueChange = { draft = draft.copy(mangaSmallTextAmount = it.roundToInt()) },
                    valueRange = 0f..100f, steps = 19, enabled = draft.mangaEnlargeSmallText, modifier = Modifier.semantics { contentDescription = amountLabel })
                Text(stringResource(R.string.manga_small_text_threshold) + " · " + stringResource(R.string.manga_small_text_pixels, draft.mangaSmallTextThreshold))
                val thresholdLabel = stringResource(R.string.manga_small_text_threshold)
                Slider(value = draft.mangaSmallTextThreshold.toFloat(), onValueChange = { draft = draft.copy(mangaSmallTextThreshold = it.roundToInt()) },
                    valueRange = 10f..60f, steps = 49, enabled = draft.mangaEnlargeSmallText, modifier = Modifier.semantics { contentDescription = thresholdLabel })
                Text(stringResource(R.string.manga_preview_note), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
