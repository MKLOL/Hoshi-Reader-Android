package moe.antimony.hoshi.features.statistics

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.mangareader.MangaStatisticsSheet
import moe.antimony.hoshi.features.mangareader.MangaTextReadState
import moe.antimony.hoshi.features.reader.BookStatisticsInput
import moe.antimony.hoshi.features.reader.ReaderStatisticsClock
import moe.antimony.hoshi.features.reader.ReaderStatisticsTracker
import moe.antimony.hoshi.features.reader.formatDurationSeconds
import moe.antimony.hoshi.features.reader.summarizeReadingStatistics
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The reader's Statistics sheet, the Statistics screen and a book's detail screen are rendered
 * from the same persisted numbers here, and every displayed total must be the same string.
 */
@RunWith(AndroidJUnit4::class)
class StatisticsScreensInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 9, 12)
    private val statistics = listOf(
        ReadingStatistics(title = "Shirokuma", dateKey = "2026-09-10", charactersRead = 40, readingTime = 1_500.0, lastStatisticModified = 1),
        ReadingStatistics(title = "Shirokuma", dateKey = "2026-09-12", charactersRead = 12, readingTime = 615.0, lastStatisticModified = 2),
    )
    private val mangaText = listOf(
        MangaTextStatistic("2026-09-10", 3_100, lastModified = 1),
        MangaTextStatistic("2026-09-12", 950, lastModified = 2),
    )
    private val overview = summarizeReadingStatistics(
        listOf(BookStatisticsInput("m", "Shirokuma", ContentType.Mokuro, statistics, mangaText, progress = 0.3)),
        todayKey = today.toString(),
    )
    private val clock = object : ReaderStatisticsClock {
        override fun currentTimeMillis(): Long = 0L
        override fun currentDate(): LocalDate = today
    }

    @Test
    fun statisticsScreenAndBookDetailShowTheSameTotalsAsTheReaderSheet() {
        val tracker = ReaderStatisticsTracker("Shirokuma", statistics, enabled = true, clock = clock)
        val sheetAllTime = formatDurationSeconds(tracker.state.allTime.readingTime)
        val row = overview.books.single()
        assertEquals(sheetAllTime, formatDurationSeconds(row.totalSeconds))
        assertEquals("35m 15s", sheetAllTime)

        var opened: String? = null
        composeRule.setContent {
            MaterialTheme {
                StatisticsScreenContent(
                    overview = overview,
                    today = today,
                    minimumMinutes = 10,
                    onMinimumMinutesChange = {},
                    onOpenBook = { opened = it },
                    onClose = {},
                )
            }
        }
        composeRule.onNodeWithText("1 day streak").assertIsDisplayed()
        composeRule.onNodeWithText("Today's goal reached").assertIsDisplayed()
        composeRule.onAllNodesWithText("35m 15s")[0].assertIsDisplayed() // totals card
        composeRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Shirokuma"))
        composeRule.onNodeWithText("Shirokuma").assertIsDisplayed()
        composeRule.onNodeWithText("52 pages read · 4,050 characters read · Last read Sep 12, 2026").assertIsDisplayed()
        composeRule.onAllNodesWithText("35m 15s")[1].assertIsDisplayed() // the book row
        composeRule.onNodeWithText("Shirokuma").performClick()
        composeRule.runOnIdle { assertEquals("m", opened) }
    }

    @Test
    fun bookDetailUsesTheSameNumbersAndShowsDatesAndPace() {
        composeRule.setContent {
            MaterialTheme {
                BookStatisticsContent(summary = overview.books.single(), loaded = true, onClose = {})
            }
        }
        composeRule.onAllNodesWithText("35m 15s")[0].assertIsDisplayed()
        composeRule.onAllNodesWithText("Sep 10, 2026")[0].assertIsDisplayed() // started
        composeRule.onAllNodesWithText("Sep 12, 2026")[0].assertIsDisplayed() // last read
        composeRule.onNodeWithText("Not finished · 30.0%").assertIsDisplayed()
        val list = composeRule.onAllNodes(hasScrollAction()).onFirst()
        list.performScrollToNode(hasText("52"))
        composeRule.onNodeWithText("52").assertIsDisplayed() // pages read
        composeRule.onNodeWithText("4,050").assertIsDisplayed() // characters read
        list.performScrollToNode(hasText("88 pages / h"))
        composeRule.onNodeWithText("88 pages / h").assertIsDisplayed()
    }

    @Test
    fun readerSheetAllTimeMatchesTheStatisticsScreenRow() {
        val tracker = ReaderStatisticsTracker("Shirokuma", statistics, enabled = true, clock = clock)
        composeRule.setContent {
            MaterialTheme {
                MangaStatisticsSheet(
                    state = tracker.state,
                    textState = MangaTextReadState(sessionCharacters = 0, todayCharacters = 950, allTimeCharacters = 4_050),
                    pageIndex = 51,
                    pageCount = 171,
                    onToggleTracking = {},
                    onDismiss = {},
                )
            }
        }
        val row = overview.books.single()
        composeRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("All Time"))
        composeRule.onAllNodesWithText(formatDurationSeconds(row.totalSeconds))[0].assertIsDisplayed()
        composeRule.onAllNodesWithText(row.pagesRead.toString())[0].assertIsDisplayed()
        composeRule.onAllNodesWithText(row.charactersRead.toString())[0].assertIsDisplayed()
    }
}
