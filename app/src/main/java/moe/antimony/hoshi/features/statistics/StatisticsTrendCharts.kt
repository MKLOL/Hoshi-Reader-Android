package moe.antimony.hoshi.features.statistics

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.reader.DailyReading
import moe.antimony.hoshi.features.reader.formatDurationSeconds
import moe.antimony.hoshi.features.settings.GroupCard
import moe.antimony.hoshi.features.usage.UsageStatistics
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reading time, characters read and words looked up per day, each with its trailing 3-day
 * average, under one range selector. One measure per chart: they never share an axis.
 */
@Composable
internal fun TrendsSection(
    daily: List<DailyReading>?,
    usage: UsageStatistics?,
    today: LocalDate,
) {
    var range by rememberSaveable { mutableIntStateOf(TrendRange.Month.ordinal) }
    val days = TrendRange.entries[range].days
    Column {
        Text(
            text = stringResource(R.string.statistics_trends_title),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 8.dp),
        ) {
            TrendRange.entries.forEach { option ->
                FilterChip(
                    selected = option.ordinal == range,
                    onClick = { range = option.ordinal },
                    label = { Text(pluralStringResource(R.plurals.statistics_trends_range_days, option.days, option.days)) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val dailyByDate = remember(daily) {
            daily.orEmpty().mapNotNull { day ->
                runCatching { LocalDate.parse(day.dateKey) }.getOrNull()?.let { it to day }
            }.toMap()
        }
        val minutes = remember(dailyByDate, days, today) {
            rollingAverageSeries(dailyByDate.mapValues { it.value.seconds / 60.0 }, today, days)
        }
        val characters = remember(dailyByDate, days, today) {
            rollingAverageSeries(dailyByDate.mapValues { it.value.characters.toDouble() }, today, days)
        }
        TrendChartCard(
            title = stringResource(R.string.statistics_trend_reading_time),
            points = minutes,
            formatValue = { formatDurationSeconds(it * 60.0) },
            formatTick = ::formatMinutesTick,
        )
        Spacer(Modifier.height(12.dp))
        TrendChartCard(
            title = stringResource(R.string.statistics_trend_characters),
            points = characters,
            formatValue = { formatStatisticsCount(it.roundToInt()) },
            formatTick = { formatCompactCount(it) },
        )
        if (usage != null) {
            Spacer(Modifier.height(12.dp))
            val since = usage.firstLoggedDate ?: today
            val lookups = remember(usage, days, today) {
                rollingAverageSeries(usage.lookupsByDay.mapValues { it.value.toDouble() }, today, days, since = since)
            }
            TrendChartCard(
                title = stringResource(R.string.statistics_trend_lookups),
                subtitle = stringResource(R.string.statistics_trend_lookups_since_format, formatStatisticsDate(since.toString())),
                points = lookups,
                formatValue = { formatStatisticsCount(it.roundToInt()) },
                formatAverage = { formatAverageCount(it) },
                formatTick = { formatCompactCount(it) },
            )
        }
    }
}

@Composable
private fun TrendChartCard(
    title: String,
    points: List<TrendPoint>,
    formatValue: (Double) -> String,
    formatTick: (Double) -> String,
    formatAverage: (Double) -> String = formatValue,
    subtitle: String? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
    val dayFormat = remember { shortDayFormatter() }
    GroupCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant)
            }
            val point = points.getOrNull(selected)
            if (point != null) {
                Text(
                    text = stringResource(
                        R.string.statistics_trend_readout_format,
                        point.date.format(dayFormat),
                        formatValue(point.value),
                        formatAverage(point.rollingAverage),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            if (points.isNotEmpty()) {
                val description = pluralStringResource(
                    R.plurals.statistics_trend_chart_description,
                    points.size,
                    title,
                    points.size,
                    formatAverage(points.last().rollingAverage),
                )
                RollingAverageChart(
                    points = points,
                    selectedIndex = selected,
                    onSelect = { selected = it },
                    formatTick = formatTick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .semantics { contentDescription = description },
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    points.map { it.date }.distinct().let { listOf(it.first(), it.last()).distinct() }.forEach { date ->
                        Text(
                            text = date.format(dayFormat),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TrendLegend()
        }
    }
}

@Composable
private fun trendColors(): Pair<Color, Color> {
    val colorScheme = MaterialTheme.colorScheme
    val line = if (LocalHoshiEInkMode.current) colorScheme.onBackground else colorScheme.primary
    return line to line.copy(alpha = 0.3f)
}

/** Daily bars with the trailing average drawn over them, on one axis starting at zero. */
@Composable
private fun RollingAverageChart(
    points: List<TrendPoint>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    formatTick: (Double) -> String,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val (lineColor, barColor) = trendColors()
    val selectedBarColor = lineColor.copy(alpha = 0.6f)
    val gridColor = colorScheme.outlineVariant
    val surface = colorScheme.surface
    val tickStyle = MaterialTheme.typography.labelSmall.copy(color = colorScheme.onSurfaceVariant)
    val measurer: TextMeasurer = rememberTextMeasurer()
    val axisMax = remember(points) { niceAxisMax(points.maxOf { maxOf(it.value, it.rollingAverage) }) }
    val ticks = remember(axisMax, tickStyle) {
        listOf(axisMax, axisMax / 2).map { measurer.measure(formatTick(it), tickStyle) }
    }
    val density = LocalDensity.current
    val plotLeft = ticks.maxOf { it.size.width } + with(density) { 6.dp.toPx() }
    Canvas(
        modifier = modifier.pointerInput(points, plotLeft) {
            detectTapGestures { offset ->
                val slot = (size.width - plotLeft) / points.size
                if (slot > 0f) onSelect(((offset.x - plotLeft) / slot).toInt().coerceIn(0, points.lastIndex))
            }
        },
    ) {
        val top = ticks.first().size.height / 2f
        val bottom = size.height - 1.dp.toPx()
        val plotHeight = bottom - top
        val plotWidth = size.width - plotLeft
        fun yOf(value: Double): Float = bottom - (value / axisMax * plotHeight).toFloat()

        listOf(0.0, axisMax / 2, axisMax).forEach { value ->
            val y = yOf(value)
            drawLine(gridColor, Offset(plotLeft, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        ticks.zip(listOf(axisMax, axisMax / 2)).forEach { (layout, value) ->
            drawText(
                layout,
                topLeft = Offset(plotLeft - 6.dp.toPx() - layout.size.width, yOf(value) - layout.size.height / 2f),
            )
        }

        val slot = plotWidth / points.size
        val barWidth = (slot - 2.dp.toPx()).coerceIn(1f, 24.dp.toPx())
        points.forEachIndexed { index, point ->
            val height = bottom - yOf(point.value)
            if (height <= 0f) return@forEachIndexed
            val color = if (index == selectedIndex) selectedBarColor else barColor
            val left = plotLeft + index * slot + (slot - barWidth) / 2f
            val radius = CornerRadius(min(4.dp.toPx(), min(barWidth / 2f, height)))
            // Rounded at the data end, square on the baseline; one shape, so a translucent
            // fill never doubles up where two pieces would overlap.
            drawPath(
                Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = left,
                            top = bottom - height,
                            right = left + barWidth,
                            bottom = bottom,
                            topLeftCornerRadius = radius,
                            topRightCornerRadius = radius,
                        ),
                    )
                },
                color,
            )
        }

        val line = Path()
        points.forEachIndexed { index, point ->
            val x = plotLeft + index * slot + slot / 2f
            val y = yOf(point.rollingAverage)
            if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        drawPath(line, lineColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        points.getOrNull(selectedIndex)?.let { point ->
            val center = Offset(plotLeft + selectedIndex * slot + slot / 2f, yOf(point.rollingAverage))
            drawCircle(surface, radius = 6.dp.toPx(), center = center)
            drawCircle(lineColor, radius = 4.dp.toPx(), center = center)
        }
    }
}

@Composable
private fun TrendLegend() {
    val (lineColor, barColor) = trendColors()
    val labelStyle = MaterialTheme.typography.labelMedium
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(barColor))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.statistics_trend_legend_daily), style = labelStyle, color = labelColor)
        Spacer(Modifier.width(16.dp))
        Canvas(Modifier.width(16.dp).height(10.dp)) {
            drawLine(
                lineColor,
                Offset(0f, size.height / 2f),
                Offset(size.width, size.height / 2f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.statistics_trend_legend_average), style = labelStyle, color = labelColor)
    }
}

/** Minutes as an axis label: 30m, 1h, 1h 30m. */
internal fun formatMinutesTick(minutes: Double): String {
    val total = minutes.roundToInt()
    val hours = total / 60
    val rest = total % 60
    return when {
        hours == 0 -> "${rest}m"
        rest == 0 -> "${hours}h"
        else -> "${hours}h ${rest}m"
    }
}

/** A count as a short axis label: 900, 1.5K, 12K. */
private fun formatCompactCount(value: Double): String =
    android.icu.text.CompactDecimalFormat
        .getInstance(Locale.getDefault(), android.icu.text.CompactDecimalFormat.CompactStyle.SHORT)
        .format(value)

/** An average of small counts keeps one decimal: 2.3 lookups a day. */
private fun formatAverageCount(value: Double): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(value)

private fun shortDayFormatter(): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMd"))
