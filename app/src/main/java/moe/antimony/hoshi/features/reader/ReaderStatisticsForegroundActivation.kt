package moe.antimony.hoshi.features.reader

import androidx.lifecycle.Lifecycle

/** Returns whether a tracker loaded offscreen needs to start at the next resume. */
internal fun ReaderStatisticsTracker.startWhenForeground(
    currentCharacter: Int,
    lifecycleState: Lifecycle.State,
): Boolean {
    if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return true
    start(currentCharacter)
    return false
}
