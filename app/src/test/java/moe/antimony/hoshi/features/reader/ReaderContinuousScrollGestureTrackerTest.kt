package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderContinuousScrollGestureTrackerTest {
    private fun tracker() = ReaderContinuousScrollGestureTracker(tapSlop = 12f, maxTapDurationMs = 500L)

    private fun ReaderContinuousScrollGestureTracker.upVertical(
        x: Float,
        y: Float,
        eventTime: Long,
    ) = onUp(
        x = x,
        y = y,
        eventTime = eventTime,
        verticalWriting = true,
        chapterSwipeDistancePx = CHAPTER_SWIPE_DISTANCE_PX,
    )

    private fun ReaderContinuousScrollGestureTracker.upHorizontal(
        x: Float,
        y: Float,
        eventTime: Long,
    ) = onUp(
        x = x,
        y = y,
        eventTime = eventTime,
        verticalWriting = false,
        chapterSwipeDistancePx = CHAPTER_SWIPE_DISTANCE_PX,
    )

    @Test
    fun verticalWritingDragToTheRightTurnsForward() {
        val tracker = tracker()

        tracker.onDown(200f, 400f, eventTime = 1_000L)
        assertTrue(tracker.onMove(280f, 402f))

        assertEquals(
            ReaderContinuousScrollGestureTracker.Result.ChapterSwipe(ReaderNavigationDirection.Forward),
            tracker.upVertical(500f, 405f, eventTime = 1_400L),
        )
    }

    @Test
    fun verticalWritingDragToTheLeftTurnsBackward() {
        val tracker = tracker()

        tracker.onDown(600f, 400f, eventTime = 1_000L)
        assertTrue(tracker.onMove(520f, 402f))

        assertEquals(
            ReaderContinuousScrollGestureTracker.Result.ChapterSwipe(ReaderNavigationDirection.Backward),
            tracker.upVertical(300f, 405f, eventTime = 1_400L),
        )
    }

    @Test
    fun reversingDirectionMidDragUsesTheDirectionTheFingerEndedUpGoing() {
        val tracker = tracker()

        // Scroll to the left, then reverse and scroll back to the right within one gesture.
        tracker.onDown(300f, 400f, eventTime = 1_000L)
        assertTrue(tracker.onMove(120f, 402f))
        assertFalse(tracker.onMove(400f, 404f))

        assertEquals(
            ReaderContinuousScrollGestureTracker.Result.ChapterSwipe(ReaderNavigationDirection.Forward),
            tracker.upVertical(600f, 405f, eventTime = 1_600L),
        )
    }

    /**
     * The reader recomposes while a continuous drag is in flight, because scrolling reports reading
     * progress. If that rebuilds the touch listener, the second half of the drag is measured
     * against a zeroed pointer-down origin: every lift then looks like a drag from the top-left
     * corner of the reader, i.e. always to the right (and always downwards), so a backward chapter
     * turn can never be recognised and the reader stays stuck at the start of the chapter.
     */
    @Test
    fun aDragThatStartedBeforeTheTrackerExistedIsIgnoredInsteadOfMeasuredFromTheOrigin() {
        val replacement = tracker()

        assertFalse(replacement.onMove(520f, 100f))

        assertEquals(
            ReaderContinuousScrollGestureTracker.Result.None,
            replacement.upVertical(300f, 100f, eventTime = 1_400L),
        )
    }

    @Test
    fun backwardChapterTurnSurvivesAScrollProgressUpdateMidDrag() {
        val tracker = tracker()

        // Pointer goes down at the right edge and drags left, back towards the chapter start.
        tracker.onDown(600f, 100f, eventTime = 1_000L)
        assertTrue(tracker.onMove(500f, 101f))

        // Progress is reported and the reader recomposes here; the same tracker must stay in
        // charge, so the lift is still measured against the real pointer-down origin.
        assertEquals(
            ReaderContinuousScrollGestureTracker.Result.ChapterSwipe(ReaderNavigationDirection.Backward),
            tracker.upVertical(300f, 100f, eventTime = 1_400L),
        )
    }

    @Test
    fun horizontalWritingUsesTheVerticalAxis() {
        val forward = tracker()
        forward.onDown(400f, 600f, eventTime = 1_000L)
        assertEquals(
            ReaderContinuousScrollGestureTracker.Result.ChapterSwipe(ReaderNavigationDirection.Forward),
            forward.upHorizontal(402f, 300f, eventTime = 1_400L),
        )

        val backward = tracker()
        backward.onDown(400f, 300f, eventTime = 1_000L)
        assertEquals(
            ReaderContinuousScrollGestureTracker.Result.ChapterSwipe(ReaderNavigationDirection.Backward),
            backward.upHorizontal(402f, 600f, eventTime = 1_400L),
        )
    }

    @Test
    fun dragAlongTheCrossAxisDoesNotTurnTheChapter() {
        val tracker = tracker()

        tracker.onDown(400f, 400f, eventTime = 1_000L)

        assertEquals(
            ReaderContinuousScrollGestureTracker.Result.None,
            tracker.upVertical(480f, 900f, eventTime = 1_400L),
        )
    }

    @Test
    fun shortDragDoesNotTurnTheChapter() {
        val tracker = tracker()

        tracker.onDown(400f, 400f, eventTime = 1_000L)

        assertEquals(
            ReaderContinuousScrollGestureTracker.Result.None,
            tracker.upVertical(440f, 402f, eventTime = 1_400L),
        )
    }

    @Test
    fun quickGestureWithinTheSlopIsATap() {
        val tracker = tracker()

        tracker.onDown(400f, 400f, eventTime = 1_000L)

        val result = tracker.upVertical(404f, 403f, eventTime = 1_200L)

        assertEquals(ReaderContinuousScrollGestureTracker.Result.Tap(404f, 403f), result)
    }

    @Test
    fun longPressWithinTheSlopIsNotATap() {
        val tracker = tracker()

        tracker.onDown(400f, 400f, eventTime = 1_000L)

        assertEquals(
            ReaderContinuousScrollGestureTracker.Result.None,
            tracker.upVertical(404f, 403f, eventTime = 1_900L),
        )
    }

    @Test
    fun scrollGestureIsReportedOncePerDragAndOnlyAfterAPointerDown() {
        val tracker = tracker()

        assertFalse(tracker.onMove(500f, 500f))

        tracker.onDown(400f, 400f, eventTime = 1_000L)
        assertFalse(tracker.onMove(402f, 402f))
        assertTrue(tracker.onMove(412f, 401f))
        assertFalse(tracker.onMove(500f, 401f))

        tracker.onCancel()
        assertFalse(tracker.onMove(600f, 401f))

        tracker.onDown(400f, 400f, eventTime = 2_000L)
        assertTrue(tracker.onMove(400f, 388f))
    }

    @Test
    fun suppressedGestureReportsNothingUntilTheNextPointerDown() {
        val tracker = tracker()

        tracker.onDown(600f, 100f, eventTime = 1_000L)
        tracker.suppressCurrentGesture()

        assertFalse(tracker.onMove(500f, 101f))
        assertEquals(
            ReaderContinuousScrollGestureTracker.Result.None,
            tracker.upVertical(300f, 100f, eventTime = 1_400L),
        )

        tracker.onDown(600f, 100f, eventTime = 2_000L)
        assertEquals(
            ReaderContinuousScrollGestureTracker.Result.ChapterSwipe(ReaderNavigationDirection.Backward),
            tracker.upVertical(300f, 100f, eventTime = 2_400L),
        )
    }

    private companion object {
        const val CHAPTER_SWIPE_DISTANCE_PX = 60f
    }
}
