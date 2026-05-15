package moe.antimony.hoshi.features.mangareader

/**
 * Keeps page-swipe recognition scoped to single-pointer gestures.
 *
 * A pinch starts as one pointer down, then a second pointer joins. Once that happens, the
 * gesture must stay out of page navigation until the final pointer lifts; otherwise the
 * remaining finger can continue moving and accidentally complete a swipe.
 */
internal class MangaTouchNavigationGate {
    private var suppressUntilGestureEnds = false

    fun onTouch(action: MangaTouchAction, pointerCount: Int): MangaTouchNavigationDecision {
        if (action == MangaTouchAction.Down) {
            suppressUntilGestureEnds = false
        }

        if (pointerCount > 1 || action == MangaTouchAction.PointerDown) {
            suppressUntilGestureEnds = true
        }

        val suppressCurrentEvent = suppressUntilGestureEnds

        if (action == MangaTouchAction.Up || action == MangaTouchAction.Cancel) {
            suppressUntilGestureEnds = false
        }

        return if (suppressCurrentEvent) {
            MangaTouchNavigationDecision.CancelTracking
        } else {
            MangaTouchNavigationDecision.Track
        }
    }
}

internal enum class MangaTouchAction {
    Down,
    Move,
    Up,
    PointerDown,
    PointerUp,
    Cancel,
    Other,
}

internal enum class MangaTouchNavigationDecision {
    Track,
    CancelTracking,
}
