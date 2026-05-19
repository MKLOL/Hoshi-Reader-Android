package moe.antimony.hoshi.features.mangareader

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaStatisticsSheetTest {
    @Test
    fun pageIndexMapsToOneBasedStatisticsPosition() {
        assertEquals(1, mangaStatisticsPosition(0))
        assertEquals(12, mangaStatisticsPosition(11))
    }

    @Test
    fun statisticsCounterCountsForwardPagesWithoutSubtractingBackwardNavigation() {
        val afterForward = mangaStatisticsCounterAfterPageChange(
            currentCounter = 0,
            fromPageIndex = 2,
            toPageIndex = 5,
        )
        val afterBackward = mangaStatisticsCounterAfterPageChange(
            currentCounter = afterForward,
            fromPageIndex = 5,
            toPageIndex = 4,
        )
        val afterForwardAgain = mangaStatisticsCounterAfterPageChange(
            currentCounter = afterBackward,
            fromPageIndex = 4,
            toPageIndex = 5,
        )

        assertEquals(3, afterForward)
        assertEquals(3, afterBackward)
        assertEquals(4, afterForwardAgain)
    }

    @Test
    fun pageProgressAndRemainingPagesClampToVolumeBounds() {
        assertEquals(0.25f, mangaPageProgress(pageIndex = 0, pageCount = 4), 0f)
        assertEquals(1.0f, mangaPageProgress(pageIndex = 9, pageCount = 4), 0f)
        assertEquals(0f, mangaPageProgress(pageIndex = 0, pageCount = 0), 0f)
        assertEquals(3, mangaRemainingPages(pageIndex = 0, pageCount = 4))
        assertEquals(0, mangaRemainingPages(pageIndex = 9, pageCount = 4))
    }

    @Test
    fun remainingTimeUsesPagesPerHourPace() {
        assertEquals(1800.0, mangaSecondsRemaining(remainingPages = 10, speed = 20), 0.0)
        assertEquals(0.0, mangaSecondsRemaining(remainingPages = 10, speed = 0), 0.0)
        assertEquals(0.0, mangaSecondsRemaining(remainingPages = -1, speed = 20), 0.0)
    }

    @Test
    fun paceTextUsesMangaPageUnits() {
        assertEquals("42 pages / h", formatMangaReadingPace(42))
        assertEquals("0 pages / h", formatMangaReadingPace(-10))
    }
}
