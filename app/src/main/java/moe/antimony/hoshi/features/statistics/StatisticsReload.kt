package moe.antimony.hoshi.features.statistics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * A counter that grows every time the screen comes back to the foreground, so a statistics
 * load keyed on it re-runs after the app was in the background (a new day may have started,
 * or another screen may have written statistics).
 */
@Composable
internal fun rememberResumeCount(): Int {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumeCount by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeCount += 1
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return resumeCount
}
