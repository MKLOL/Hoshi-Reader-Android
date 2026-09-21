package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.features.bookshelf.BookCoverCard
import moe.antimony.hoshi.features.bookshelf.bookshelfProgressText
import moe.antimony.hoshi.features.reader.BookReadingSummary
import moe.antimony.hoshi.features.reader.ReadingStatisticsOverview
import moe.antimony.hoshi.features.reader.SystemReaderStatisticsClock
import moe.antimony.hoshi.features.reader.formatDurationSeconds
import moe.antimony.hoshi.features.reader.loadReadingStatisticsOverview
import moe.antimony.hoshi.features.settings.GroupCard
import moe.antimony.hoshi.features.settings.GroupDivider
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One book's statistics: dates, totals, pace and the per-day history. Reloads like [StatisticsScreen]. */
@Composable
fun BookStatisticsScreen(
    bookId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalHoshiAppContainer.current
    val statisticsVersion by appContainer.bookRepository.statisticsChanges.collectAsStateWithLifecycle()
    var overview by remember { mutableStateOf<ReadingStatisticsOverview?>(null) }
    val resumeCount = rememberResumeCount()
    LaunchedEffect(statisticsVersion, resumeCount) {
        overview = loadReadingStatisticsOverview(
            appContainer.bookRepository,
            SystemReaderStatisticsClock.currentDate().toString(),
        )
    }
    BookStatisticsContent(
        summary = overview?.books?.firstOrNull { it.bookId == bookId },
        loaded = overview != null,
        onClose = onClose,
        modifier = modifier,
        localDeviceId = appContainer.deviceIdentity.id,
    )
}

@Composable
fun BookStatisticsContent(
    summary: BookReadingSummary?,
    loaded: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Marks this device's row in the "By device" card. */
    localDeviceId: String? = null,
) {
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
            when {
                !loaded -> item { GroupCard { StatisticsMessageRow(stringResource(R.string.statistics_overview_loading)) } }
                summary == null -> item { GroupCard { StatisticsMessageRow(stringResource(R.string.statistics_book_not_found)) } }
                else -> {
                    val pace = bookPace(summary)
                    item { BookHeader(summary) }
                    item { DatesCard(summary) }
                    item { BookTotalsCard(summary, pace) }
                    if (summary.devices.isNotEmpty()) {
                        item { DevicesCard(summary.devices, localDeviceId, showBookCount = false) }
                    }
                    item { PaceCard(summary, pace) }
                    item { HistoryCard(summary) }
                }
            }
        }
    }
}

@Composable
private fun BookHeader(summary: BookReadingSummary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BookCoverCard(summary.coverSource, modifier = Modifier.width(96.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = summary.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(bookTypeLabelRes(summary.contentType)) + " · " +
                    if (summary.finished) stringResource(R.string.statistics_book_finished) else bookshelfProgressText(summary.progress),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatDurationSeconds(summary.totalSeconds),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DatesCard(summary: BookReadingSummary) {
    GroupCard {
        StatisticsValueRow(
            label = stringResource(R.string.statistics_book_started),
            value = summary.startedDateKey?.let(::formatStatisticsDate) ?: "—",
        )
        GroupDivider()
        StatisticsValueRow(
            label = stringResource(R.string.statistics_book_last_read),
            value = summary.lastReadDateKey?.let(::formatStatisticsDate) ?: "—",
        )
        GroupDivider()
        StatisticsValueRow(
            label = stringResource(R.string.statistics_book_finished),
            value = if (summary.finished) {
                summary.lastReadDateKey?.let(::formatStatisticsDate) ?: "—"
            } else {
                stringResource(R.string.statistics_book_not_finished_format, bookshelfProgressText(summary.progress))
            },
        )
    }
}

@Composable
private fun BookTotalsCard(summary: BookReadingSummary, pace: BookPace) {
    GroupCard {
        StatisticsValueRow(
            label = stringResource(R.string.statistics_book_reading_time),
            value = formatDurationSeconds(summary.totalSeconds),
        )
        summary.pagesRead?.let { pages ->
            GroupDivider()
            StatisticsValueRow(
                label = stringResource(R.string.statistics_book_pages_read),
                value = formatStatisticsCount(pages),
            )
        }
        GroupDivider()
        StatisticsValueRow(
            label = stringResource(R.string.statistics_overview_characters_total),
            value = formatStatisticsCount(summary.charactersRead),
        )
        GroupDivider()
        StatisticsValueRow(
            label = stringResource(R.string.statistics_book_days_read),
            value = formatStatisticsCount(summary.daysRead),
        )
        GroupDivider()
        StatisticsValueRow(
            label = stringResource(R.string.statistics_book_average_per_day),
            value = formatDurationSeconds(pace.averageSecondsPerDay),
        )
    }
}

@Composable
private fun PaceCard(summary: BookReadingSummary, pace: BookPace) {
    GroupCard {
        StatisticsValueRow(
            label = stringResource(R.string.statistics_book_reading_speed),
            value = when (summary.contentType) {
                ContentType.Mokuro -> stringResource(R.string.statistics_book_speed_pages_format, formatStatisticsCount(pace.unitsPerHour))
                ContentType.Epub -> stringResource(R.string.statistics_book_speed_characters_format, formatStatisticsCount(pace.unitsPerHour))
            },
        )
        GroupDivider()
        StatisticsValueRow(
            label = when (summary.contentType) {
                ContentType.Mokuro -> stringResource(R.string.statistics_book_per_page)
                ContentType.Epub -> stringResource(R.string.statistics_book_per_hundred_characters)
            },
            value = pace.secondsPerUnit?.let(::formatDurationSeconds) ?: "—",
        )
        GroupDivider()
        StatisticsValueRow(
            label = stringResource(R.string.statistics_book_best_day),
            value = pace.bestDay?.let { "${formatStatisticsDate(it.dateKey)} · ${formatDurationSeconds(it.seconds)}" } ?: "—",
        )
    }
}

@Composable
private fun HistoryCard(summary: BookReadingSummary) {
    val formatter = remember {
        val locale = Locale.getDefault()
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
    }
    val max = summary.days.maxOfOrNull { it.seconds } ?: 0.0
    GroupCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.statistics_book_history),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            summary.days.take(MAX_HISTORY_DAYS).forEach { day ->
                val label = runCatching { LocalDate.parse(day.dateKey).format(formatter) }.getOrDefault(day.dateKey)
                StatisticsBarRow(
                    label = label,
                    value = formatDurationSeconds(day.seconds),
                    fraction = if (max > 0.0) (day.seconds / max).toFloat() else 0f,
                )
            }
        }
    }
}

private const val MAX_HISTORY_DAYS = 60
