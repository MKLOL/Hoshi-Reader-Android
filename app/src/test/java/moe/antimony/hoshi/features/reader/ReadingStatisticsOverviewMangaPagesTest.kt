package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.readingTotals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The manga sheet's "All Time" shows the pages from `statistics.json` whether or not any
 * time or OCR text was recorded with them. A manga the sheet shows pages for must therefore be
 * a row on the Statistics page too.
 */
class ReadingStatisticsOverviewMangaPagesTest {
    @Test
    fun aMangaWithPagesButNoTimeAndNoTextIsListedWithThosePages() {
        // A sidecar written elsewhere (another platform, a hand-edited file): pages recorded,
        // no time, and no manga_statistics.json next to it.
        val statistics = listOf(
            ReadingStatistics(title = "Manga", dateKey = "2026-09-12", charactersRead = 12, readingTime = 0.0, lastStatisticModified = 1),
        )
        // What ReaderStatisticsTracker.state.allTime is built from: 12 pages.
        assertEquals(12, statistics.readingTotals().charactersRead)

        val overview = summarizeReadingStatistics(
            listOf(BookStatisticsInput("manga-a", "Manga", ContentType.Mokuro, statistics)),
            todayKey = "2026-09-12",
        )

        val row = overview.books.singleOrNull()
            ?: error("the sheet shows 12 pages all time, the page lists no row for the manga")
        assertEquals(12, row.pagesRead)
        assertEquals(0.0, row.totalSeconds, 0.0)
        assertEquals(0, row.charactersRead)
    }
}
