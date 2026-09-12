package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.GroupCard
import moe.antimony.hoshi.features.settings.GroupDivider
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.features.settings.collectAsLoadedSettings
import moe.antimony.hoshi.features.sync.StatisticsSyncMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Settings -> Statistics. Reading statistics are always on, so this page only reports them:
 * overall and today's reading time, then every book and manga with the time spent in it,
 * plus the ッツ sync options when Google Drive sync is configured.
 */
@Composable
fun ReaderStatisticsSettingsView(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalHoshiAppContainer.current
    val syncSettings = appContainer.syncSettingsRepository.settings.collectAsLoadedSettings()
    // Reload whenever a statistics file changes (and on every open): no ViewModel here on
    // purpose, the navigation host would scope it to the Activity and freeze the first load.
    val statisticsVersion by appContainer.bookRepository.statisticsChanges.collectAsStateWithLifecycle()
    var overview by remember { mutableStateOf<ReadingStatisticsOverview?>(null) }
    LaunchedEffect(statisticsVersion) {
        overview = loadReadingStatisticsOverview(
            bookRepository = appContainer.bookRepository,
            todayKey = SystemReaderStatisticsClock.currentDate().toString(),
        )
    }
    var syncModeMenuExpanded by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme
    SettingsDetailScaffold(
        title = stringResource(R.string.reader_statistics),
        onClose = onClose,
        modifier = modifier.fillMaxSize(),
        containerColor = colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                GroupCard {
                    StatisticsTotalRow(
                        label = stringResource(R.string.statistics_overview_total_reading_time),
                        value = overview?.let { formatDurationSeconds(it.totalSeconds) },
                    )
                    GroupDivider()
                    StatisticsTotalRow(
                        label = stringResource(R.string.statistics_overview_characters_total),
                        value = overview?.let { formatCount(it.totalCharacters) },
                    )
                    GroupDivider()
                    StatisticsTotalRow(
                        label = stringResource(R.string.reader_statistics_today),
                        value = overview?.let { formatDurationSeconds(it.todaySeconds) },
                    )
                    GroupDivider()
                    StatisticsTotalRow(
                        label = stringResource(R.string.statistics_overview_characters_today),
                        value = overview?.let { formatCount(it.todayCharacters) },
                    )
                }
                Text(
                    text = stringResource(R.string.statistics_overview_always_on),
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.statistics_overview_per_book),
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                )
                GroupCard {
                    val books = overview?.books
                    when {
                        books == null -> StatisticsMessageRow(stringResource(R.string.statistics_overview_loading))
                        books.isEmpty() -> StatisticsMessageRow(stringResource(R.string.statistics_overview_empty))
                        else -> books.forEachIndexed { index, book ->
                            BookReadingTimeRow(book)
                            if (index != books.lastIndex) {
                                GroupDivider()
                            }
                        }
                    }
                }
            }
            val loadedSyncSettings = syncSettings
            if (loadedSyncSettings != null && loadedSyncSettings.enabled) {
                item {
                    GroupCard {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.sync_ttu_sync)) },
                            trailingContent = {
                                Switch(
                                    checked = settings.statisticsSyncEnabled,
                                    onCheckedChange = {
                                        onSettingsChange(settings.copy(statisticsSyncEnabled = it))
                                    },
                                )
                            },
                        )
                        GroupDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.reader_statistics_sync_behaviour)) },
                            trailingContent = {
                                Box {
                                    TextButton(onClick = { syncModeMenuExpanded = true }) {
                                        Text(settings.statisticsSyncMode.rawValue)
                                    }
                                    DropdownMenu(
                                        expanded = syncModeMenuExpanded,
                                        onDismissRequest = { syncModeMenuExpanded = false },
                                    ) {
                                        StatisticsSyncMode.entries.forEach { mode ->
                                            DropdownMenuItem(
                                                text = { Text(mode.rawValue) },
                                                onClick = {
                                                    syncModeMenuExpanded = false
                                                    onSettingsChange(settings.copy(statisticsSyncMode = mode))
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.reader_statistics_settings_hint),
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun StatisticsTotalRow(label: String, value: String?) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(label) },
        trailingContent = {
            Text(
                text = value ?: "…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

@Composable
private fun StatisticsMessageRow(message: String) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(text = message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

@Composable
private fun BookReadingTimeRow(book: BookReadingSummary) {
    val pagesRead = book.pagesRead?.let { pages ->
        pluralStringResource(R.plurals.statistics_overview_pages_read, pages, formatCount(pages))
    }
    val charactersRead = pluralStringResource(
        R.plurals.statistics_overview_characters_read,
        book.charactersRead,
        formatCount(book.charactersRead),
    )
    val lastRead = book.lastReadDateKey?.let { dateKey ->
        stringResource(R.string.statistics_overview_last_read_format, formatStatisticsDate(dateKey))
    }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(text = book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(listOfNotNull(pagesRead, charactersRead, lastRead).joinToString(" · ")) },
        trailingContent = {
            Text(
                text = formatDurationSeconds(book.totalSeconds),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

private fun formatCount(value: Int): String = NumberFormat.getIntegerInstance().format(value)

private fun formatStatisticsDate(dateKey: String): String =
    runCatching { LocalDate.parse(dateKey).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
        .getOrDefault(dateKey)
