package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.features.bookshelf.BookCoverCard
import moe.antimony.hoshi.features.reader.BookReadingSummary
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.ReadingStatisticsOverview
import moe.antimony.hoshi.features.reader.SystemReaderStatisticsClock
import moe.antimony.hoshi.features.reader.formatDurationSeconds
import moe.antimony.hoshi.features.reader.loadReadingStatisticsOverview
import moe.antimony.hoshi.features.settings.GroupCard
import moe.antimony.hoshi.features.settings.GroupDivider
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.features.settings.collectAsLoadedSettings
import moe.antimony.hoshi.features.sync.StatisticsSyncMode
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** Daily goal choices for the streak, in minutes. */
val STREAK_GOAL_MINUTES: List<Int> = listOf(5, 10, 15, 30)

/**
 * The Statistics screen: streak, totals, a reading heatmap, time by weekday and every book.
 * Reloads on every open and whenever a statistics sidecar changes (see
 * `BookRepository.statisticsChanges`); deliberately no ViewModel, the navigation host would
 * scope one to the Activity and freeze the first load.
 */
@Composable
fun StatisticsScreen(
    onOpenBook: (bookId: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalHoshiAppContainer.current
    val scope = rememberCoroutineScope()
    val statisticsVersion by appContainer.bookRepository.statisticsChanges.collectAsStateWithLifecycle()
    val readerSettings by appContainer.readerSettingsRepository.settings.collectAsStateWithLifecycle(initialValue = ReaderSettings())
    val syncSettings = appContainer.syncSettingsRepository.settings.collectAsLoadedSettings()
    var overview by remember { mutableStateOf<ReadingStatisticsOverview?>(null) }
    var today by remember { mutableStateOf(SystemReaderStatisticsClock.currentDate()) }
    val resumeCount = rememberResumeCount()
    LaunchedEffect(statisticsVersion, resumeCount, today) {
        today = SystemReaderStatisticsClock.currentDate()
        overview = loadReadingStatisticsOverview(appContainer.bookRepository, today.toString())
    }
    // A screen left open across midnight moves "today" (and the streak) to the new day.
    LaunchedEffect(today) {
        val untilMidnight = Duration.between(LocalDateTime.now(), today.plusDays(1).atStartOfDay()).toMillis()
        delay(untilMidnight.coerceAtLeast(1_000L))
        today = SystemReaderStatisticsClock.currentDate()
    }
    StatisticsScreenContent(
        overview = overview,
        today = today,
        minimumMinutes = readerSettings.statisticsStreakMinimumMinutes,
        onMinimumMinutesChange = { minutes ->
            scope.launch {
                appContainer.readerSettingsRepository.update { it.copy(statisticsStreakMinimumMinutes = minutes) }
            }
        },
        onOpenBook = onOpenBook,
        onClose = onClose,
        modifier = modifier,
        localDeviceId = appContainer.deviceIdentity.id,
        driveSync = if (syncSettings?.enabled == true) {
            DriveStatisticsSyncOptions(readerSettings.statisticsSyncEnabled, readerSettings.statisticsSyncMode)
        } else {
            null
        },
        onDriveSyncChange = { options ->
            scope.launch {
                appContainer.readerSettingsRepository.update {
                    it.copy(statisticsSyncEnabled = options.enabled, statisticsSyncMode = options.mode)
                }
            }
        },
    )
}

/** Google Drive (ッツ) statistics sync: only offered while Drive sync itself is configured. */
data class DriveStatisticsSyncOptions(val enabled: Boolean, val mode: StatisticsSyncMode)

@Composable
fun StatisticsScreenContent(
    overview: ReadingStatisticsOverview?,
    today: LocalDate,
    minimumMinutes: Int,
    onMinimumMinutesChange: (Int) -> Unit,
    onOpenBook: (bookId: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    driveSync: DriveStatisticsSyncOptions? = null,
    onDriveSyncChange: (DriveStatisticsSyncOptions) -> Unit = {},
    /** Marks this device's row in the "By device" card. */
    localDeviceId: String? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val streak = remember(overview, minimumMinutes, today) {
        overview?.let { computeReadingStreak(it.daily, minimumMinutes * 60.0, today) }
    }
    val heatmap = remember(overview, today) { overview?.let { buildReadingHeatmap(it.daily, today) } }
    val weekdays = remember(overview) { overview?.let { weekdayDistribution(it.daily) } }
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
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { StreakCard(streak, minimumMinutes, onMinimumMinutesChange); Spacer(Modifier.height(18.dp)) }
            item { TotalsCard(overview); Spacer(Modifier.height(18.dp)) }
            val devices = overview?.devices.orEmpty()
            if (devices.isNotEmpty()) {
                item { DevicesCard(devices, localDeviceId, showBookCount = true); Spacer(Modifier.height(18.dp)) }
            }
            item { HeatmapCard(heatmap); Spacer(Modifier.height(18.dp)) }
            item { WeekdayCard(weekdays); Spacer(Modifier.height(18.dp)) }
            if (driveSync != null) {
                item { DriveSyncCard(driveSync, onDriveSyncChange); Spacer(Modifier.height(18.dp)) }
            }
            item {
                Text(
                    text = stringResource(R.string.statistics_overview_per_book),
                    color = colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                )
            }
            // One lazy item per book: a manga library is one book per volume, and every row
            // decodes a cover, so composing them all at once would hold every bitmap.
            val books = overview?.books
            when {
                books == null -> item { GroupCard { StatisticsMessageRow(stringResource(R.string.statistics_overview_loading)) } }
                books.isEmpty() -> item { GroupCard { StatisticsMessageRow(stringResource(R.string.statistics_overview_empty)) } }
                else -> items(books, key = { it.bookId }) { book ->
                    BookRowCard { BookReadingRow(book, onClick = { onOpenBook(book.bookId) }) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun StreakCard(streak: ReadingStreak?, minimumMinutes: Int, onMinimumMinutesChange: (Int) -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val eInk = LocalHoshiEInkMode.current
    val current = streak?.currentDays ?: 0
    val atRisk = streak != null && current > 0 && !streak.todayQualifies
    GroupCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.LocalFireDepartment,
                    contentDescription = null,
                    tint = when {
                        eInk || streak == null || current == 0 -> colorScheme.onSurfaceVariant
                        atRisk -> colorScheme.tertiary
                        else -> colorScheme.primary
                    },
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (streak == null) "…" else pluralStringResource(R.plurals.statistics_streak_days, current, current),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (streak == null) {
                            "…"
                        } else {
                            pluralStringResource(R.plurals.statistics_streak_longest_days, streak.longestDays, streak.longestDays) +
                                " · " +
                                pluralStringResource(R.plurals.statistics_streak_goal_days, streak.qualifyingDays, streak.qualifyingDays)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            val goalSeconds = minimumMinutes * 60.0
            val todaySeconds = streak?.todaySeconds ?: 0.0
            val fraction = if (goalSeconds > 0.0) (todaySeconds / goalSeconds).toFloat().coerceIn(0f, 1f) else 0f
            val track = if (eInk) colorScheme.onBackground.copy(alpha = 0.08f) else colorScheme.surfaceVariant
            val fill = if (eInk) colorScheme.onBackground else colorScheme.primary
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(100))
                    .background(track),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(12.dp)
                        .clip(RoundedCornerShape(100))
                        .background(fill),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    streak == null -> "…"
                    streak.todayQualifies && current > 1 && current == streak.longestDays ->
                        stringResource(R.string.statistics_streak_today_done) + " · " + stringResource(R.string.statistics_streak_new_record)
                    streak.todayQualifies -> stringResource(R.string.statistics_streak_today_done)
                    atRisk -> stringResource(
                        R.string.statistics_streak_keep_format,
                        formatDurationSeconds((goalSeconds - todaySeconds).coerceAtLeast(0.0)),
                    )
                    todaySeconds > 0.0 -> stringResource(
                        R.string.statistics_streak_today_progress_format,
                        formatDurationSeconds(todaySeconds),
                        formatGoalDuration(goalSeconds),
                    )
                    else -> stringResource(R.string.statistics_streak_start_format, formatGoalDuration(goalSeconds))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.statistics_streak_goal),
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                STREAK_GOAL_MINUTES.forEach { minutes ->
                    FilterChip(
                        selected = minutes == minimumMinutes,
                        onClick = { onMinimumMinutesChange(minutes) },
                        label = { Text(stringResource(R.string.statistics_streak_goal_minutes_format, minutes)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TotalsCard(overview: ReadingStatisticsOverview?) {
    GroupCard {
        StatisticsValueRow(
            label = stringResource(R.string.statistics_overview_total_reading_time),
            value = overview?.let { formatDurationSeconds(it.totalSeconds) },
        )
        GroupDivider()
        StatisticsValueRow(
            label = stringResource(R.string.statistics_overview_characters_total),
            value = overview?.let { formatStatisticsCount(it.totalCharacters) },
        )
        GroupDivider()
        StatisticsValueRow(
            label = stringResource(R.string.reader_statistics_today),
            value = overview?.let { formatDurationSeconds(it.todaySeconds) },
        )
        GroupDivider()
        StatisticsValueRow(
            label = stringResource(R.string.statistics_overview_characters_today),
            value = overview?.let { formatStatisticsCount(it.todayCharacters) },
        )
    }
}

@Composable
private fun HeatmapCard(heatmap: ReadingHeatmap?) {
    GroupCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.statistics_heatmap_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            if (heatmap != null) {
                ReadingHeatmapGrid(heatmap, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                HeatmapLegend(
                    lessLabel = stringResource(R.string.statistics_heatmap_less),
                    moreLabel = stringResource(R.string.statistics_heatmap_more),
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun WeekdayCard(weekdays: List<Double>?) {
    GroupCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.statistics_weekday_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val labels = remember { weekdayLabels() }
            val max = weekdays?.maxOrNull() ?: 0.0
            labels.forEachIndexed { index, label ->
                val seconds = weekdays?.getOrNull(index) ?: 0.0
                StatisticsBarRow(
                    label = label,
                    value = formatDurationSeconds(seconds),
                    fraction = if (max > 0.0) (seconds / max).toFloat() else 0f,
                )
            }
        }
    }
}

@Composable
private fun DriveSyncCard(options: DriveStatisticsSyncOptions, onChange: (DriveStatisticsSyncOptions) -> Unit) {
    var modeMenuExpanded by remember { mutableStateOf(false) }
    GroupCard {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(stringResource(R.string.sync_ttu_sync)) },
            trailingContent = {
                Switch(
                    checked = options.enabled,
                    onCheckedChange = { onChange(options.copy(enabled = it)) },
                )
            },
        )
        GroupDivider()
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(stringResource(R.string.reader_statistics_sync_behaviour)) },
            trailingContent = {
                Box {
                    TextButton(onClick = { modeMenuExpanded = true }) {
                        Text(options.mode.rawValue)
                    }
                    DropdownMenu(
                        expanded = modeMenuExpanded,
                        onDismissRequest = { modeMenuExpanded = false },
                    ) {
                        StatisticsSyncMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.rawValue) },
                                onClick = {
                                    modeMenuExpanded = false
                                    onChange(options.copy(mode = mode))
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}

/** One book row in its own small card, so the list can stay lazy. */
@Composable
private fun BookRowCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        content()
    }
}

@Composable
private fun BookReadingRow(book: BookReadingSummary, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { BookCoverCard(book.coverSource, modifier = Modifier.width(44.dp)) },
        headlineContent = { Text(text = book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(bookReadingSubtitle(book)) },
        trailingContent = {
            Text(
                text = formatDurationSeconds(book.totalSeconds),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** "3 pages read · 282 characters read · Last read Sep 12, 2026", the same words on every screen. */
@Composable
internal fun bookReadingSubtitle(book: BookReadingSummary): String {
    val pagesRead = book.pagesRead?.let { pages ->
        pluralStringResource(R.plurals.statistics_overview_pages_read, pages, formatStatisticsCount(pages))
    }
    val charactersRead = pluralStringResource(
        R.plurals.statistics_overview_characters_read,
        book.charactersRead,
        formatStatisticsCount(book.charactersRead),
    )
    val lastRead = book.lastReadDateKey?.let { dateKey ->
        stringResource(R.string.statistics_overview_last_read_format, formatStatisticsDate(dateKey))
    }
    return listOfNotNull(pagesRead, charactersRead, lastRead).joinToString(" · ")
}

@Composable
internal fun StatisticsValueRow(label: String, value: String?) {
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
internal fun StatisticsMessageRow(message: String) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(text = message, color = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

/** A goal is whole minutes, so show "10m" rather than "10m 0s". */
internal fun formatGoalDuration(seconds: Double): String {
    val minutes = (seconds / 60.0).toInt()
    return if (minutes * 60.0 == seconds) "${minutes}m" else formatDurationSeconds(seconds)
}

internal fun bookTypeLabelRes(contentType: ContentType): Int = when (contentType) {
    ContentType.Mokuro -> R.string.statistics_book_type_manga
    ContentType.Epub -> R.string.statistics_book_type_book
}
