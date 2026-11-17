package moe.antimony.hoshi.features.mangareader

import moe.antimony.hoshi.epub.ReadingStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun incompleteSamplesUseTheBooksRecordedTimeAndPages() {
        val totals = ReadingStatistics(
            title = "Volume",
            dateKey = "2026-11-16",
            charactersRead = 150,
            readingTime = 6164.0,
            mangaPageSeconds = mapOf(15 to 40, 16 to 40),
        )
        val remainingPages = mangaRemainingPages(pageIndex = 129, pageCount = 233)
        assertEquals(103, remainingPages)
        for (samples in listOf(totals.mangaPageSeconds, emptyMap(), mapOf(15 to 75, 16 to 75))) {
            val pace = totals.copy(mangaPageSeconds = samples).mangaEstimatedPageSeconds()!!
            assertEquals(87.60545, 3600 / pace, 0.001)
            assertEquals(4232.61333, mangaSecondsRemaining(remainingPages, pace)!!, 0.001)
        }
    }

    @Test
    fun trimsTwoPagesFromEachEndEvenWhenTheBoundaryFallsInsideABucket() {
        val totals = ReadingStatistics(
            title = "Volume",
            dateKey = "2026-11-17",
            charactersRead = 20,
            readingTime = 1691.0,
            mangaPageSeconds = mapOf(1000 to 1, 40 to 14, 1 to 1, 10 to 3, 100 to 1),
        )
        val pace = totals.mangaEstimatedPageSeconds()!!
        assertEquals(36.25, pace, 0.0)
        assertEquals(362.5, mangaSecondsRemaining(10, pace)!!, 0.0)
        // These samples cover the time but represent fewer than 90% of the pages.
        assertEquals(67.64, totals.copy(charactersRead = 25).mangaEstimatedPageSeconds()!!, 0.001)
        assertEquals(169.1, totals.copy(charactersRead = 10).mangaEstimatedPageSeconds()!!, 0.001)
    }

    @Test
    fun remainingTimeDistinguishesMissingRecordedPaceFromFinished() {
        val empty = ReadingStatistics(title = "Volume", dateKey = "2026-11-16")
        assertNull(mangaSecondsRemaining(10, empty.copy(readingTime = 120.0).mangaEstimatedPageSeconds()))
        assertNull(mangaSecondsRemaining(10, empty.copy(charactersRead = 1).mangaEstimatedPageSeconds()))
        assertNull(mangaSecondsRemaining(10, empty.copy(charactersRead = 1, readingTime = Double.NaN).mangaEstimatedPageSeconds()))
        assertEquals(0.0, mangaSecondsRemaining(0, empty.mangaEstimatedPageSeconds())!!, 0.0)
    }

    @Test
    fun pageChangesCountAsReadOnlyWhileTrackingAndOnlyForRealTurns() {
        assertEquals(true, mangaPageChangeCountsAsRead(isTracking = true, countAsRead = true))
        assertEquals(false, mangaPageChangeCountsAsRead(isTracking = false, countAsRead = true))
        assertEquals(false, mangaPageChangeCountsAsRead(isTracking = true, countAsRead = false))
        assertEquals(false, mangaPageChangeCountsAsRead(isTracking = false, countAsRead = false))
    }
}
