package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun formatStatisticsCount(value: Int): String = NumberFormat.getIntegerInstance().format(value)

internal fun formatStatisticsDate(dateKey: String): String =
    runCatching { LocalDate.parse(dateKey).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
        .getOrDefault(dateKey)

/** Fill colour for a heatmap level 1..4; level 0 uses the empty colour. */
@Composable
internal fun heatmapFillColor(level: Int): Color {
    val base = if (LocalHoshiEInkMode.current) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.primary
    val alpha = when (level) {
        1 -> 0.3f
        2 -> 0.5f
        3 -> 0.75f
        else -> 1f
    }
    return base.copy(alpha = alpha)
}

@Composable
internal fun heatmapEmptyColor(): Color =
    if (LocalHoshiEInkMode.current) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant

/** GitHub-style grid: one column per week, Monday at the top, month labels above. */
@Composable
internal fun ReadingHeatmapGrid(heatmap: ReadingHeatmap, modifier: Modifier = Modifier) {
    val cell = 14.dp
    val gap = 3.dp
    val labelHeight = 18.dp
    val fills = (1..HEATMAP_LEVELS).map { heatmapFillColor(it) }
    val empty = heatmapEmptyColor()
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val weeks = heatmap.weeks.size
    val width = cell * weeks + gap * (weeks - 1).coerceAtLeast(0)
    Box(modifier = modifier.horizontalScroll(rememberScrollState())) {
        Canvas(modifier = Modifier.width(width).height(cell * 7 + gap * 6 + labelHeight)) {
            val c = cell.toPx()
            val g = gap.toPx()
            val top = labelHeight.toPx()
            heatmap.monthLabels.forEach { (weekIndex, label) ->
                val measured = textMeasurer.measure(label, labelStyle)
                val x = (weekIndex * (c + g)).coerceAtMost(size.width - measured.size.width).coerceAtLeast(0f)
                drawText(measured, topLeft = Offset(x, 0f))
            }
            heatmap.weeks.forEachIndexed { weekIndex, week ->
                week.cells.forEachIndexed { dayIndex, day ->
                    if (day == null) return@forEachIndexed
                    drawRoundRect(
                        color = if (day.level == 0) empty else fills[day.level - 1],
                        topLeft = Offset(weekIndex * (c + g), top + dayIndex * (c + g)),
                        size = Size(c, c),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            }
        }
    }
}

@Composable
internal fun HeatmapLegend(lessLabel: String, moreLabel: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(lessLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        (0..HEATMAP_LEVELS).forEach { level ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 1.5.dp)
                    .width(12.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (level == 0) heatmapEmptyColor() else heatmapFillColor(level)),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(moreLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A labelled horizontal bar whose length is [fraction] of the row, like the bookshelf progress pill. */
@Composable
internal fun StatisticsBarRow(label: String, value: String, fraction: Float, modifier: Modifier = Modifier) {
    val eInk = LocalHoshiEInkMode.current
    val track = if (eInk) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant
    val fill = if (eInk) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.primary
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(100))
                .background(track),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(10.dp)
                    .clip(RoundedCornerShape(100))
                    .background(fill),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = 76.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}
