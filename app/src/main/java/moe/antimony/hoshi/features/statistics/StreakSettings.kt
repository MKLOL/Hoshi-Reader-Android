package moe.antimony.hoshi.features.statistics

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import java.util.Date
import java.util.TimeZone
import kotlin.math.roundToInt

@Composable
internal fun StreakSettings(
    minimumMinutes: Int,
    onMinimumMinutesChange: (Int) -> Unit,
    resetHour: Int,
    onResetHourChange: (Int) -> Unit,
) {
    var draftMinutes by remember(minimumMinutes) { mutableIntStateOf(minimumMinutes) }
    val goalLabel = stringResource(R.string.statistics_streak_goal)
    val goalValue = stringResource(R.string.statistics_streak_goal_minutes_format, draftMinutes)
    // Keep any larger goal restored from a previous settings backup reachable.
    val maximumMinutes = maxOf(120, minimumMinutes)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(goalLabel, style = MaterialTheme.typography.labelLarge)
            Text(goalValue)
        }
        Slider(
            value = draftMinutes.toFloat(),
            onValueChange = { draftMinutes = it.roundToInt() },
            onValueChangeFinished = { onMinimumMinutesChange(draftMinutes) },
            valueRange = 1f..maximumMinutes.toFloat(),
            steps = maximumMinutes - 2,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = goalLabel; stateDescription = goalValue },
        )
        var expanded by remember { mutableStateOf(false) }
        // Format a time of day, independent of today's nonexistent/repeated DST hours.
        val utc = TimeZone.getTimeZone("UTC")
        val timeFormat = DateFormat.getTimeFormat(LocalContext.current).apply { timeZone = utc }
        fun hourLabel(hour: Int): String = timeFormat.format(Date(hour * 3_600_000L))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.statistics_streak_reset), modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { expanded = true }) { Text(hourLabel(resetHour)) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                    (0..23).forEach { hour ->
                        DropdownMenuItem(
                            text = { Text(hourLabel(hour)) },
                            onClick = { expanded = false; onResetHourChange(hour) },
                        )
                    }
                }
            }
        }
        Text(
            stringResource(R.string.statistics_streak_reset_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
