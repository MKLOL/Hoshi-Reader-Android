package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.reader.formatDurationSeconds
import moe.antimony.hoshi.features.settings.GroupCard
import moe.antimony.hoshi.features.usage.UsageDaySummary
import moe.antimony.hoshi.features.usage.UsageReadingSpan
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.ceil
import kotlin.math.floor

/** Recent reading spans listed under the timeline; older ones are still drawn on it. */
private const val TODAY_LISTED_SPANS = 5

/**
 * The top of the Statistics screen: what happened today. Reading time and characters come
 * from the same statistics as the streak; everything else (lookups, page turns, bubble and
 * screenshot translations, the timeline) comes from the usage log.
 */
@Composable
internal fun TodayCard(
    today: LocalDate,
    todaySeconds: Double?,
    todayCharacters: Int?,
    usage: UsageDaySummary?,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val colorScheme = MaterialTheme.colorScheme
    val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    fun clock(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().format(timeFormat)
    GroupCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.statistics_today_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val first = usage?.firstActivityMillis
            val last = usage?.lastActivityMillis
            Text(
                text = today.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)) +
                    if (first != null && last != null) {
                        " · " + stringResource(R.string.statistics_today_active_range_format, clock(first), clock(last))
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.statistics_today_reading_time),
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (todaySeconds == null) "…" else formatDurationSeconds(todaySeconds),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (todayCharacters != null && todayCharacters > 0) {
                Text(
                    text = stringResource(R.string.statistics_today_characters_format, formatStatisticsCount(todayCharacters)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )
            }
            val nothingYet = (todaySeconds ?: 0.0) <= 0.0 && (usage == null || usage.isEmpty)
            if (nothingYet) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.statistics_today_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )
            }
            if (usage != null && !usage.isEmpty) {
                Spacer(Modifier.height(16.dp))
                TodayCountTiles(usage)
                if (usage.spans.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    TodaySectionTitle(stringResource(R.string.statistics_today_timeline))
                    Spacer(Modifier.height(8.dp))
                    TodayTimeline(usage.spans, zone)
                    Spacer(Modifier.height(8.dp))
                    usage.spans.takeLast(TODAY_LISTED_SPANS).asReversed().forEach { span ->
                        TodaySpanRow(span, clock(span.startMillis), clock(span.endMillis))
                    }
                }
                if (usage.recentWords.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    TodaySectionTitle(stringResource(R.string.statistics_today_recent_words))
                    Spacer(Modifier.height(8.dp))
                    TodayWords(usage.recentWords)
                }
            }
        }
    }
}

@Composable
private fun TodaySectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TodayCountTiles(usage: UsageDaySummary) {
    val tiles = listOf(
        R.string.statistics_today_words to usage.wordLookups,
        R.string.statistics_today_pages to usage.pageTurns,
        R.string.statistics_today_sessions to usage.sessions,
        R.string.statistics_today_bubbles_revealed to usage.bubblesRevealed,
        R.string.statistics_today_bubble_translations to usage.bubbleTranslations,
        R.string.statistics_today_screenshots to usage.screenshotTranslations,
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (labelRes, value) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = formatStatisticsCount(value),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The day's reading spans on a strip that covers only the hours around them (at least four),
 * so a short session is still visible instead of a sliver of a 24-hour bar.
 */
@Composable
private fun TodayTimeline(spans: List<UsageReadingSpan>, zone: ZoneId) {
    val colorScheme = MaterialTheme.colorScheme
    val fill = if (LocalHoshiEInkMode.current) colorScheme.onBackground else colorScheme.primary
    val track = colorScheme.surfaceVariant
    fun hourOf(millis: Long): Double =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().toSecondOfDay() / 3600.0
    var startHour = (floor(hourOf(spans.minOf { it.startMillis })) - 1).coerceAtLeast(0.0)
    var endHour = (ceil(hourOf(spans.maxOf { it.endMillis })) + 1).coerceAtMost(24.0)
    if (endHour - startHour < 4.0) {
        endHour = (startHour + 4.0).coerceAtMost(24.0)
        startHour = (endHour - 4.0).coerceAtLeast(0.0)
    }
    val window = endHour - startHour
    val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    fun hourLabel(hour: Double): String =
        if (hour >= 24.0) LocalTime.MIDNIGHT.format(timeFormat) else LocalTime.of(hour.toInt(), 0).format(timeFormat)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .semantics { contentDescription = "${hourLabel(startHour)}–${hourLabel(endHour)}" },
    ) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color = track, cornerRadius = radius)
        spans.forEach { span ->
            val from = ((hourOf(span.startMillis) - startHour) / window).toFloat().coerceIn(0f, 1f)
            val to = ((hourOf(span.endMillis) - startHour) / window).toFloat().coerceIn(0f, 1f)
            // A span of a few seconds still gets a visible mark.
            val width = ((to - from) * size.width).coerceAtLeast(3.dp.toPx())
            drawRoundRect(
                color = fill,
                topLeft = Offset(from * size.width, 0f),
                size = Size(width.coerceAtMost(size.width - from * size.width), size.height),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(startHour, startHour + window / 2, endHour).forEach { hour ->
            Text(
                text = hourLabel(hour),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TodaySpanRow(span: UsageReadingSpan, start: String, end: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = stringResource(
                R.string.statistics_today_span_format,
                start,
                end,
                formatDurationSeconds(span.durationMillis / 1000.0),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        span.bookTitle?.let { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayWords(words: List<String>) {
    val eInk = LocalHoshiEInkMode.current
    val colorScheme = MaterialTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        words.forEach { word ->
            Surface(
                shape = RoundedCornerShape(50),
                color = if (eInk) Color.Transparent else colorScheme.surfaceVariant,
                border = if (eInk) BorderStroke(1.dp, colorScheme.onBackground) else null,
            ) {
                Text(
                    text = word,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}
