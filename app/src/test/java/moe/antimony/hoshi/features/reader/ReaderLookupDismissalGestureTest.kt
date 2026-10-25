package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLookupDismissalGestureTest {
    @Test
    fun firstOutsidePressOnlyDismissesAndConsumesItsEntireStream() {
        val gesture = ReaderLookupDismissalGesture()
        assertTrue(gesture.onDown(hasActiveLookup = true, hitPopup = false))
        // Removing popup state cannot release the ongoing pointer to a word or chrome button.
        assertTrue(gesture.consumesStream)
        gesture.onEnd()
        assertFalse(gesture.consumesStream)
        assertFalse(gesture.onDown(hasActiveLookup = false, hitPopup = false))
    }

    @Test
    fun pressInsideRootOrNestedDictionaryRemainsInteractive() {
        val rects = listOf(
            ReaderSelectionRect(x = 10.0, y = 20.0, width = 100.0, height = 200.0),
            ReaderSelectionRect(x = 120.0, y = 30.0, width = 80.0, height = 150.0),
        )
        val gesture = ReaderLookupDismissalGesture()
        for ((x, y) in listOf(30.0 to 50.0, 140.0 to 90.0)) {
            assertFalse(gesture.onDown(true, readerLookupContainsPoint(rects, x, y)))
            gesture.onEnd()
        }
        for ((x, y) in listOf(0.0 to 0.0, 115.0 to 50.0, 140.0 to 250.0)) {
            assertTrue(gesture.onDown(true, readerLookupContainsPoint(rects, x, y)))
            gesture.onEnd()
        }
    }

    @Test
    fun pressDuringPendingLookupIsDismissalEvenBeforePopupHasBounds() {
        val gesture = ReaderLookupDismissalGesture()
        assertTrue(gesture.onDown(hasActiveLookup = true, hitPopup = readerLookupContainsPoint(emptyList(), 30.0, 50.0)))
    }
}
