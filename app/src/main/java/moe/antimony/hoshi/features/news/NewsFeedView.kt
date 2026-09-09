package moe.antimony.hoshi.features.news

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.features.ai.ModelPricing
import moe.antimony.hoshi.features.bookshelf.MainShellLayoutSpec
import moe.antimony.hoshi.features.news.pretranslate.PretranslationEngine
import moe.antimony.hoshi.features.news.pretranslate.PretranslationJobState
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.resolve
import java.text.NumberFormat

@Composable
internal fun NewsFeedView(
    onOpenReader: (bookId: String, contentType: ContentType) -> Unit,
    layoutSpec: MainShellLayoutSpec,
    pendingSharedUrl: String? = null,
    onPendingSharedUrlConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val viewModel: NewsViewModel = viewModel(
        factory = remember(appContainer) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = NewsViewModel(
                    appContext = context.applicationContext,
                    repository = appContainer.newsRepository,
                    settingsRepository = appContainer.newsSettingsRepository,
                    aiSettings = appContainer.aiChatSettingsRepository,
                    offlineSettings = appContainer.offlineTranslationSettingsRepository,
                    syncSettings = appContainer.httpSyncSettingsRepository,
                    bookRepository = appContainer.bookRepository,
                ) as T
            }
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    LaunchedEffect(pendingSharedUrl) {
        val url = pendingSharedUrl ?: return@LaunchedEffect
        onPendingSharedUrlConsumed()
        viewModel.importSharedUrl(url)
    }
    state.openBookId?.let { bookId ->
        LaunchedEffect(bookId) {
            viewModel.consumeOpenBook()
            onOpenReader(bookId, ContentType.Epub)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            NewsHeader(
                refreshing = state.feed.refreshing.isNotEmpty(),
                onRefresh = viewModel::refresh,
                onSources = viewModel::openSources,
                horizontalPadding = layoutSpec.pageHorizontalPaddingDp.dp,
            )
            SourceChips(
                sources = state.settings.enabledSources,
                selectedId = state.selectedSourceId,
                errors = state.feed.sourceErrors,
                onSelect = viewModel::selectSource,
                horizontalPadding = layoutSpec.pageHorizontalPaddingDp.dp,
            )
            val articles = state.visibleArticles
            when {
                !state.feed.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                articles.isEmpty() -> EmptyNews(
                    noSources = state.settings.enabledSources.isEmpty(),
                    refreshing = state.feed.refreshing.isNotEmpty(),
                    errors = state.feed.sourceErrors.entries.associate { (id, message) -> state.sourceName(id) to message.resolve(resources) },
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = layoutSpec.pageHorizontalPaddingDp.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(articles, key = { it.id }) { article ->
                        NewsArticleCard(
                            article = article,
                            sourceName = state.sourceName(article.sourceId),
                            saved = state.feed.saved[article.id],
                            saving = article.id in state.feed.saving,
                            job = state.jobFor(article),
                            onSave = { viewModel.save(article) },
                            onOpen = { bookId -> onOpenReader(bookId, ContentType.Epub) },
                            onPretranslate = { viewModel.preparePretranslate(article) },
                            onCancelJob = viewModel::cancelPretranslate,
                            onDismissJob = viewModel::dismissJob,
                        )
                    }
                }
            }
        }
        state.message?.let { message ->
            LaunchedEffect(message) {
                delay(4_000)
                viewModel.dismissMessage()
            }
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.action_dismiss)) } },
            ) { Text(message.resolve(resources)) }
        }
    }

    if (state.sourcesDialogOpen) {
        NewsSourcesDialog(
            settings = state.settings,
            onToggle = viewModel::setSourceEnabled,
            onAddRss = viewModel::addRss,
            onRemove = viewModel::removeSource,
            onClose = viewModel::closeSources,
        )
    }
    state.dialog?.let { dialog ->
        PretranslateDialog(
            state = dialog,
            onSelectEngine = { engine -> viewModel.updatePretranslateConfig { it.copy(engine = engine) } },
            onToggleNotes = { include -> viewModel.updatePretranslateConfig { it.copy(includeExplanations = include) } },
            onStart = viewModel::startPretranslate,
            onDismiss = viewModel::dismissPretranslate,
        )
    }
}

@Composable
private fun NewsHeader(refreshing: Boolean, onRefresh: () -> Unit, onSources: () -> Unit, horizontalPadding: androidx.compose.ui.unit.Dp) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.news_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = onSources) {
            Icon(Icons.Rounded.RssFeed, contentDescription = stringResource(R.string.news_sources))
        }
        IconButton(onClick = onRefresh, enabled = !refreshing) {
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.news_refresh))
            }
        }
    }
}

@Composable
private fun SourceChips(
    sources: List<NewsSource>,
    selectedId: String?,
    errors: Map<String, UiText>,
    onSelect: (String?) -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp,
) {
    if (sources.size < 2) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(selected = selectedId == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.news_filter_all)) })
        }
        items(sources, key = { it.id }) { source ->
            FilterChip(
                selected = selectedId == source.id,
                onClick = { onSelect(source.id) },
                label = {
                    Text(if (source.id in errors) stringResource(R.string.news_source_chip_error_format, source.name) else source.name)
                },
            )
        }
    }
}

@Composable
private fun EmptyNews(noSources: Boolean, refreshing: Boolean, errors: Map<String, String>) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (refreshing) {
            CircularProgressIndicator()
        } else {
            Text(
                stringResource(if (noSources) R.string.news_empty_no_sources else R.string.news_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            errors.forEach { (name, message) ->
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.news_source_error_format, name, message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun NewsArticleCard(
    article: NewsArticle,
    sourceName: String,
    saved: SavedNewsArticle?,
    saving: Boolean,
    job: PretranslationJobState?,
    onSave: () -> Unit,
    onOpen: (bookId: String) -> Unit,
    onPretranslate: () -> Unit,
    onCancelJob: (bookId: String) -> Unit,
    onDismissJob: (bookId: String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            val date = article.publishedAt?.let { millis ->
                DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
            }
            Text(
                listOfNotNull(sourceName, date).joinToString(stringResource(R.string.news_meta_separator)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(article.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            article.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Spacer(Modifier.height(4.dp))
                Text(summary, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    saving -> OutlinedButton(onClick = {}, enabled = false) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.news_saving))
                    }
                    saved != null -> Button(onClick = { onOpen(saved.bookId) }) {
                        Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.news_open))
                    }
                    else -> OutlinedButton(onClick = onSave) {
                        Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.news_want_to_read))
                    }
                }
                val jobActive = job?.isActive == true
                OutlinedButton(onClick = onPretranslate, enabled = !saving && !jobActive) {
                    Icon(Icons.Rounded.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.news_pretranslate))
                }
            }
            JobStatus(job = job, saved = saved, onCancel = onCancelJob, onDismiss = onDismissJob)
        }
    }
}

@Composable
private fun JobStatus(
    job: PretranslationJobState?,
    saved: SavedNewsArticle?,
    onCancel: (bookId: String) -> Unit,
    onDismiss: (bookId: String) -> Unit,
) {
    when (job) {
        is PretranslationJobState.Queued -> StatusRow(stringResource(R.string.news_pretranslate_queued)) {
            TextButton(onClick = { onCancel(job.bookId) }) { Text(stringResource(R.string.action_cancel)) }
        }
        is PretranslationJobState.Running -> {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (job.total > 0) job.completed.toFloat() / job.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            val progress = stringResource(R.string.news_pretranslate_progress_format, job.completed, job.total)
            if (job.cancelling) {
                StatusRow(progress + "\n" + stringResource(R.string.news_pretranslate_cancelling)) {}
            } else {
                StatusRow(progress) {
                    TextButton(onClick = { onCancel(job.bookId) }) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
        is PretranslationJobState.Uploading -> StatusRow(stringResource(R.string.news_pretranslate_uploading)) {}
        is PretranslationJobState.Finished -> StatusRow(
            stringResource(R.string.news_pretranslate_done_format, job.translated, job.total) +
                if (job.uploadAttempted && !job.uploaded) "\n" + stringResource(R.string.news_pretranslate_done_not_uploaded) else "",
        ) {
            IconButton(onClick = { onDismiss(job.bookId) }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_dismiss)) }
        }
        is PretranslationJobState.Failed -> {
            val resources = LocalResources.current
            val saved = if (job.savedTranslations > 0) "\n" + stringResource(R.string.news_pretranslate_partial_saved_format, job.savedTranslations) else ""
            StatusRow(stringResource(R.string.news_pretranslate_failed_format, job.message.resolve(resources)) + saved, error = true) {
                IconButton(onClick = { onDismiss(job.bookId) }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_dismiss)) }
            }
        }
        is PretranslationJobState.Cancelled -> {
            val saved = if (job.savedTranslations > 0) "\n" + stringResource(R.string.news_pretranslate_partial_saved_format, job.savedTranslations) else ""
            StatusRow(stringResource(R.string.news_pretranslate_cancelled) + saved) {
                IconButton(onClick = { onDismiss(job.bookId) }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_dismiss)) }
            }
        }
        null -> saved?.translation?.let { record ->
            val text = if (record.translatedCount >= record.sentenceCount) {
                stringResource(R.string.news_pretranslated_format, record.model)
            } else {
                stringResource(R.string.news_pretranslated_partial_format, record.translatedCount, record.sentenceCount, record.model)
            }
            StatusRow(text) {}
        }
    }
}

@Composable
private fun StatusRow(text: String, error: Boolean = false, trailing: @Composable () -> Unit) {
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@Composable
private fun NewsSourcesDialog(
    settings: NewsSettings,
    onToggle: (NewsSource, Boolean) -> Unit,
    onAddRss: (name: String, url: String) -> Unit,
    onRemove: (NewsSource) -> Unit,
    onClose: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.news_sources)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.news_builtin_sources), style = MaterialTheme.typography.labelLarge)
                NewsSourceCatalog.builtIn.forEach { source -> SourceToggleRow(source, settings.isEnabled(source), onToggle, onRemove = null) }
                if (settings.customSources.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.news_custom_sources), style = MaterialTheme.typography.labelLarge)
                    settings.customSources.forEach { source -> SourceToggleRow(source, settings.isEnabled(source), onToggle, onRemove) }
                }
                HorizontalDivider()
                Text(stringResource(R.string.news_add_rss), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.news_rss_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.news_rss_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(
                    onClick = { onAddRss(name, url); name = ""; url = "" },
                    enabled = url.trim().startsWith("http", ignoreCase = true),
                ) { Text(stringResource(R.string.news_add_rss)) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_done)) } },
    )
}

@Composable
private fun SourceToggleRow(source: NewsSource, enabled: Boolean, onToggle: (NewsSource, Boolean) -> Unit, onRemove: ((NewsSource) -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(source.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onRemove != null) {
            IconButton(onClick = { onRemove(source) }) { Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_delete)) }
        }
        Switch(checked = enabled, onCheckedChange = { onToggle(source, it) })
    }
}

@Composable
private fun PretranslateDialog(
    state: PretranslateDialogState,
    onSelectEngine: (PretranslationEngine) -> Unit,
    onToggleNotes: (Boolean) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    var menuOpen by remember { mutableStateOf(false) }
    val selected = state.choices.firstOrNull { it.engine == state.config.engine }
    val canStart = !state.preparing && state.error == null && state.plan != null && state.apiKeyMissingFor == null &&
        (state.estimate?.sentenceCount ?: 0) > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.news_pretranslate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.article.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.news_pretranslate_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { Text(it.resolve(resources), color = MaterialTheme.colorScheme.error) }
                if (state.preparing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.news_pretranslate_planning))
                    }
                }
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, enabled = state.choices.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.news_pretranslate_model_format, selected?.label ?: state.config.engine.label),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        state.choices.forEach { choice ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(choice.label)
                                        val detail = listOfNotNull(choice.providerName, choice.note).joinToString(stringResource(R.string.news_meta_separator))
                                        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = { menuOpen = false; onSelectEngine(choice.engine) },
                            )
                        }
                    }
                }
                if (state.config.engine !is PretranslationEngine.OnDevice) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.news_pretranslate_include_notes), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = state.config.includeExplanations, onCheckedChange = onToggleNotes)
                    }
                }
                state.estimate?.let { estimate ->
                    val numbers = NumberFormat.getIntegerInstance()
                    Text(stringResource(R.string.news_pretranslate_sentences_format, estimate.sentenceCount, estimate.characterCount), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.news_pretranslate_tokens_format, numbers.format(estimate.inputTokens), numbers.format(estimate.outputTokens)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        state.config.engine is PretranslationEngine.OnDevice -> Text(stringResource(R.string.news_pretranslate_cost_on_device), style = MaterialTheme.typography.bodyMedium)
                        estimate.costUsd != null -> Text(stringResource(R.string.news_pretranslate_cost_format, ModelPricing.formatUsd(estimate.costUsd)), style = MaterialTheme.typography.titleMedium)
                        else -> Text(stringResource(R.string.news_pretranslate_cost_unknown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.config.engine is PretranslationEngine.Cloud) {
                        Text(stringResource(R.string.news_pretranslate_estimate_note, ModelPricing.PRICES_AS_OF), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.apiKeyMissingFor?.let { provider ->
                    Text(stringResource(R.string.news_pretranslate_api_key_missing_format, provider), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (!state.syncConfigured) {
                    Text(stringResource(R.string.news_pretranslate_sync_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { Button(onClick = onStart, enabled = canStart) { Text(stringResource(R.string.news_pretranslate_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
