package moe.antimony.hoshi.features.mangareader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.reader.ReaderBottomPanel
import moe.antimony.hoshi.features.reader.ReaderStatisticsState
import moe.antimony.hoshi.features.reader.formatDurationSeconds
import moe.antimony.hoshi.features.reader.readerSheetDensityMetrics
import moe.antimony.hoshi.features.reader.readerSheetStyle
import java.util.Locale
import kotlin.math.max

@Composable
internal fun MangaStatisticsSheet(
    state: ReaderStatisticsState?,
    textState: MangaTextReadState?,
    pageIndex: Int,
    pageCount: Int,
    onToggleTracking: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetStyle = readerSheetStyle()
    ReaderBottomPanel(
        sheetStyle = sheetStyle,
        onDismiss = onDismiss,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                MangaStatisticsHeader(
                    pageIndex = pageIndex,
                    pageCount = pageCount,
                    isTracking = state?.isTracking == true,
                    showToggle = state != null,
                    onToggleTracking = onToggleTracking,
                )
            }
            if (state == null) {
                item {
                    MangaStatisticsLoadingCard()
                }
            } else {
                item {
                    MangaStatisticsSection(
                        title = stringResource(R.string.reader_statistics_session),
                        icon = Icons.Rounded.Timer,
                        statistic = state.session,
                        charactersRead = textState?.sessionCharacters,
                        accentColor = MaterialTheme.colorScheme.primary,
                        extraRows = listOf(
                            stringResource(R.string.manga_statistics_pages_remaining) to
                                mangaRemainingPages(pageIndex, pageCount).toString(),
                            stringResource(R.string.manga_statistics_time_to_finish) to
                                formatDurationSeconds(
                                    mangaSecondsRemaining(
                                        remainingPages = mangaRemainingPages(pageIndex, pageCount),
                                        speed = state.session.lastReadingSpeed,
                                    ),
                                ),
                        ),
                    )
                }
                item {
                    MangaStatisticsSection(
                        title = stringResource(R.string.reader_statistics_today),
                        icon = Icons.Rounded.QueryStats,
                        statistic = state.today,
                        charactersRead = textState?.todayCharacters,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                    )
                }
                item {
                    MangaStatisticsSection(
                        title = stringResource(R.string.reader_statistics_all_time),
                        icon = Icons.Rounded.QueryStats,
                        statistic = state.allTime,
                        charactersRead = textState?.allTimeCharacters,
                        accentColor = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun MangaStatisticsHeader(
    pageIndex: Int,
    pageCount: Int,
    isTracking: Boolean,
    showToggle: Boolean,
    onToggleTracking: () -> Unit,
) {
    val progress = mangaPageProgress(pageIndex, pageCount)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.manga_statistics_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            R.string.manga_statistics_page_of_format,
                            (pageIndex + 1).coerceIn(1, pageCount.coerceAtLeast(1)),
                            pageCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showToggle) {
                    IconButton(onClick = onToggleTracking) {
                        Icon(
                            imageVector = if (isTracking) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isTracking) {
                                stringResource(R.string.reader_statistics_pause)
                            } else {
                                stringResource(R.string.reader_statistics_start)
                            },
                        )
                    }
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun MangaStatisticsLoadingCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.manga_statistics_loading),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}


@Composable
private fun MangaStatisticsSection(
    title: String,
    icon: ImageVector,
    statistic: ReadingStatistics,
    accentColor: Color,
    charactersRead: Int? = null,
    extraRows: List<Pair<String, String>> = emptyList(),
) {
    val metrics = readerSheetDensityMetrics()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            MangaStatisticRow(
                stringResource(R.string.manga_statistics_pages_read),
                statistic.charactersRead.toString(),
            )
            if (charactersRead != null) {
                MangaStatisticsDivider()
                MangaStatisticRow(
                    stringResource(R.string.manga_statistics_characters_read),
                    charactersRead.toString(),
                )
            }
            MangaStatisticsDivider()
            MangaStatisticRow(
                stringResource(R.string.manga_statistics_pace),
                formatMangaReadingPace(statistic.lastReadingSpeed),
            )
            MangaStatisticsDivider()
            MangaStatisticRow(
                stringResource(R.string.manga_statistics_reading_time),
                formatDurationSeconds(statistic.readingTime),
            )
            extraRows.forEach { (label, value) ->
                MangaStatisticsDivider()
                MangaStatisticRow(label, value)
            }
            Spacer(modifier = Modifier.height(metrics.statisticsSectionBottomPaddingDp.dp))
        }
    }
}

@Composable
private fun MangaStatisticRow(label: String, value: String) {
    val metrics = readerSheetDensityMetrics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = metrics.statisticsRowVerticalPaddingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MangaStatisticsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

internal fun mangaStatisticsPosition(pageIndex: Int): Int =
    (pageIndex + 1).coerceAtLeast(0)

internal fun mangaStatisticsCounterAfterPageChange(
    currentCounter: Int,
    fromPageIndex: Int,
    toPageIndex: Int,
): Int =
    currentCounter.coerceAtLeast(0) + max(toPageIndex - fromPageIndex, 0)

internal fun mangaRemainingPages(pageIndex: Int, pageCount: Int): Int =
    max(pageCount - mangaStatisticsPosition(pageIndex), 0)

internal fun mangaSecondsRemaining(remainingPages: Int, speed: Int): Double {
    if (speed <= 0) return 0.0
    return max(remainingPages, 0).toDouble() / (speed.toDouble() / 3600.0)
}

internal fun mangaPageProgress(pageIndex: Int, pageCount: Int): Float {
    if (pageCount <= 0) return 0f
    return (mangaStatisticsPosition(pageIndex).toFloat() / pageCount.toFloat()).coerceIn(0f, 1f)
}

internal fun formatMangaReadingPace(pagesPerHour: Int): String =
    String.format(Locale.US, "%d pages / h", pagesPerHour.coerceAtLeast(0))
