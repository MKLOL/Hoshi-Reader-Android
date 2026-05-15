package moe.antimony.hoshi.features.mangareader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaTouchNavigationGateTest {
    @Test
    fun singleFingerGestureTracksUntilUp() {
        val gate = MangaTouchNavigationGate()

        assertEquals(
            MangaTouchNavigationDecision.Track,
            gate.onTouch(MangaTouchAction.Down, pointerCount = 1),
        )
        assertEquals(
            MangaTouchNavigationDecision.Track,
            gate.onTouch(MangaTouchAction.Move, pointerCount = 1),
        )
        assertEquals(
            MangaTouchNavigationDecision.Track,
            gate.onTouch(MangaTouchAction.Up, pointerCount = 1),
        )
    }

    @Test
    fun secondPointerSuppressesNavigationUntilAllPointersLift() {
        val gate = MangaTouchNavigationGate()

        assertEquals(
            MangaTouchNavigationDecision.Track,
            gate.onTouch(MangaTouchAction.Down, pointerCount = 1),
        )
        assertEquals(
            MangaTouchNavigationDecision.CancelTracking,
            gate.onTouch(MangaTouchAction.PointerDown, pointerCount = 2),
        )
        assertEquals(
            MangaTouchNavigationDecision.CancelTracking,
            gate.onTouch(MangaTouchAction.Move, pointerCount = 2),
        )
        assertEquals(
            MangaTouchNavigationDecision.CancelTracking,
            gate.onTouch(MangaTouchAction.PointerUp, pointerCount = 2),
        )
        assertEquals(
            MangaTouchNavigationDecision.CancelTracking,
            gate.onTouch(MangaTouchAction.Move, pointerCount = 1),
        )
        assertEquals(
            MangaTouchNavigationDecision.CancelTracking,
            gate.onTouch(MangaTouchAction.Up, pointerCount = 1),
        )
    }

    @Test
    fun freshGestureTracksAfterSuppressedGestureEnds() {
        val gate = MangaTouchNavigationGate()

        gate.onTouch(MangaTouchAction.Down, pointerCount = 1)
        gate.onTouch(MangaTouchAction.PointerDown, pointerCount = 2)
        gate.onTouch(MangaTouchAction.Up, pointerCount = 1)

        assertEquals(
            MangaTouchNavigationDecision.Track,
            gate.onTouch(MangaTouchAction.Down, pointerCount = 1),
        )
        assertEquals(
            MangaTouchNavigationDecision.Track,
            gate.onTouch(MangaTouchAction.Move, pointerCount = 1),
        )
    }

    @Test
    fun cancelEndsSuppressionForNextGesture() {
        val gate = MangaTouchNavigationGate()

        gate.onTouch(MangaTouchAction.Down, pointerCount = 1)
        gate.onTouch(MangaTouchAction.PointerDown, pointerCount = 2)
        assertEquals(
            MangaTouchNavigationDecision.CancelTracking,
            gate.onTouch(MangaTouchAction.Cancel, pointerCount = 1),
        )

        assertEquals(
            MangaTouchNavigationDecision.Track,
            gate.onTouch(MangaTouchAction.Down, pointerCount = 1),
        )
    }

    @Test
    fun zoomedPageSuppressesSwipeNavigation() {
        assertTrue(shouldDispatchMangaSwipeAtScale(1f))
        assertTrue(shouldDispatchMangaSwipeAtScale(1.01f))
        assertFalse(shouldDispatchMangaSwipeAtScale(1.1f))
    }
}
