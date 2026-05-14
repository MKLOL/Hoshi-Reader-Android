package moe.antimony.hoshi.features.mangareader

import moe.antimony.hoshi.features.reader.ReaderNavigationDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MangaPageNavigationTest {
    @Test
    fun forwardAdvancesToTheNextPage() {
        assertEquals(
            3,
            MangaPageNavigation.targetIndex(
                currentIndex = 2,
                pageCount = 10,
                direction = ReaderNavigationDirection.Forward,
            ),
        )
    }

    @Test
    fun backwardMovesToThePreviousPage() {
        assertEquals(
            1,
            MangaPageNavigation.targetIndex(
                currentIndex = 2,
                pageCount = 10,
                direction = ReaderNavigationDirection.Backward,
            ),
        )
    }

    @Test
    fun forwardAtLastPageReturnsNull() {
        assertNull(
            MangaPageNavigation.targetIndex(
                currentIndex = 9,
                pageCount = 10,
                direction = ReaderNavigationDirection.Forward,
            ),
        )
    }

    @Test
    fun backwardAtFirstPageReturnsNull() {
        assertNull(
            MangaPageNavigation.targetIndex(
                currentIndex = 0,
                pageCount = 10,
                direction = ReaderNavigationDirection.Backward,
            ),
        )
    }

    @Test
    fun emptyBookNeverNavigates() {
        assertNull(
            MangaPageNavigation.targetIndex(
                currentIndex = 0,
                pageCount = 0,
                direction = ReaderNavigationDirection.Forward,
            ),
        )
    }

    @Test
    fun leftSwipeMovesForwardInRightToLeftManga() {
        assertEquals(
            ReaderNavigationDirection.Forward,
            MangaPageNavigation.directionForSwipe(MangaSwipeDirection.Left),
        )
    }

    @Test
    fun rightSwipeMovesBackwardInRightToLeftManga() {
        assertEquals(
            ReaderNavigationDirection.Backward,
            MangaPageNavigation.directionForSwipe(MangaSwipeDirection.Right),
        )
    }
}
