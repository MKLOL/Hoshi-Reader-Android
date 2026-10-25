package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSwipeGestureTrackerTest {
    @Test
    fun horizontalMovePastThresholdTriggersBeforePointerUp() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)

        val result = tracker.onMove(150f, 104f, eventTime = 1_120L)

        assertTrue(result == ReaderSwipeGestureTracker.Result.LeftSwipe)
    }

    @Test
    fun shortFastHorizontalMoveTriggersBeforePointerUp() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)

        val result = tracker.onMove(196f, 104f, eventTime = 1_030L)

        assertTrue(result == ReaderSwipeGestureTracker.Result.LeftSwipe)
    }

    @Test
    fun tinyFastHorizontalMoveDoesNotTriggerPageSwipe() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)

        val result = tracker.onMove(220f, 104f, eventTime = 1_010L)

        assertTrue(result == ReaderSwipeGestureTracker.Result.None)
    }

    @Test
    fun completedSwipeDoesNotTriggerAgainOnLaterMoveOrUp() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)

        assertTrue(tracker.onMove(150f, 104f, eventTime = 1_120L) == ReaderSwipeGestureTracker.Result.LeftSwipe)
        assertTrue(tracker.onMove(120f, 104f, eventTime = 1_140L) == ReaderSwipeGestureTracker.Result.None)
        assertTrue(tracker.onUp(120f, 104f, eventTime = 1_160L) == ReaderSwipeGestureTracker.Result.None)
    }

    @Test
    fun verticalMovePastThresholdDoesNotTriggerPageSwipe() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)

        val result = tracker.onMove(232f, 190f, eventTime = 1_120L)

        assertTrue(result == ReaderSwipeGestureTracker.Result.None)
    }

    @Test
    fun fastMostlyVerticalFlicksNeverTurnPages() {
        for (dx in listOf(-44f, 44f, -90f, 90f)) {
            val tracker = ReaderSwipeGestureTracker(minDistance = 72f)
            tracker.onDown(240f, 100f, eventTime = 1_000L)
            assertEquals(ReaderSwipeGestureTracker.Result.None, tracker.onMove(240f + dx, 300f, 1_030L))
            assertEquals(ReaderSwipeGestureTracker.Result.None, tracker.onUp(240f + dx, 300f, 1_050L))
        }
    }

    @Test
    fun slowHorizontalDragDoesNotTriggerPageSwipe() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)

        val result = tracker.onMove(150f, 104f, eventTime = 1_900L)

        assertTrue(result == ReaderSwipeGestureTracker.Result.None)
    }

    @Test
    fun nativeTextSelectionSuppressesCurrentReaderGesture() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)
        tracker.suppressCurrentGesture()

        assertTrue(tracker.onMove(150f, 104f, eventTime = 1_120L) == ReaderSwipeGestureTracker.Result.None)
        assertTrue(tracker.onUp(150f, 104f, eventTime = 1_160L) == ReaderSwipeGestureTracker.Result.None)
    }

    @Test
    fun horizontalDragPastThresholdCanPageEvenWhenDiagonal() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)

        val result = tracker.onMove(150f, 152f, eventTime = 1_120L)

        assertTrue(result == ReaderSwipeGestureTracker.Result.LeftSwipe)
    }

    @Test
    fun shortTapIsReportedOnPointerUp() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)

        val result = tracker.onUp(242f, 103f, eventTime = 1_120L)

        assertTrue(result is ReaderSwipeGestureTracker.Result.Tap)
        result as ReaderSwipeGestureTracker.Result.Tap
        assertEquals(242f, result.x)
        assertEquals(103f, result.y)
    }

    @Test
    fun longPressIsNotReportedAsTapOnPointerUp() {
        val tracker = ReaderSwipeGestureTracker(minDistance = 72f)

        tracker.onDown(240f, 100f, eventTime = 1_000L)

        val result = tracker.onUp(242f, 103f, eventTime = 1_800L)

        assertTrue(result == ReaderSwipeGestureTracker.Result.None)
    }
}
