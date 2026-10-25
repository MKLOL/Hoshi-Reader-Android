package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates

/** Coordinates are measured after the reader's chrome and continuous-mode padding. */
internal class ReaderLookupTouchTarget {
    var rootCoordinates: LayoutCoordinates? = null
    var popupCoordinates: LayoutCoordinates? = null
    var popupRects: List<ReaderSelectionRect> = emptyList()

    fun containsRootPoint(point: Offset, density: Float): Boolean {
        val root = rootCoordinates?.takeIf { it.isAttached } ?: return false
        val popup = popupCoordinates?.takeIf { it.isAttached } ?: return false
        val local = popup.localPositionOf(root, point) / density
        return readerLookupContainsPoint(popupRects, local.x.toDouble(), local.y.toDouble())
    }
}

internal fun readerLookupContainsPoint(rects: List<ReaderSelectionRect>, x: Double, y: Double): Boolean =
    rects.any { rect ->
        x >= rect.x && x < rect.x + rect.width && y >= rect.y && y < rect.y + rect.height
    }

/** Keep consuming the original stream even after dismissal removes the popup from composition. */
internal class ReaderLookupDismissalGesture {
    var consumesStream: Boolean = false
        private set

    fun onDown(hasActiveLookup: Boolean, hitPopup: Boolean): Boolean {
        consumesStream = hasActiveLookup && !hitPopup
        return consumesStream
    }

    fun onEnd() {
        consumesStream = false
    }
}

internal fun Modifier.dismissReaderLookupOnOutsideTouch(
    hasActiveLookup: () -> Boolean,
    hitPopup: (Offset) -> Boolean,
    dismissLookup: () -> Unit,
): Modifier = pointerInput(Unit) {
    val gesture = ReaderLookupDismissalGesture()
    awaitEachGesture {
        // Initial runs before both Compose controls and the Android WebView's input handler.
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (gesture.onDown(hasActiveLookup(), hitPopup(down.position))) {
            down.consume()
            dismissLookup()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
        gesture.onEnd()
    }
}
