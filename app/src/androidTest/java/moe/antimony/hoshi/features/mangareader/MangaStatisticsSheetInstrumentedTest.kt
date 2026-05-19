package moe.antimony.hoshi.features.mangareader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.reader.ReaderStatisticsState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaStatisticsSheetInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun enabledSheetShowsMangaPageUnitsAndTracksToggleClicks() {
        var toggleClicks = 0
        composeRule.setContent {
            MaterialTheme {
                MangaStatisticsSheet(
                    state = ReaderStatisticsState(
                        isTracking = false,
                        session = ReadingStatistics(
                            title = "Volume",
                            dateKey = "2026-05-19",
                            charactersRead = 7,
                            readingTime = 1400.0,
                            lastReadingSpeed = 18,
                        ),
                        today = ReadingStatistics(
                            title = "Volume",
                            dateKey = "2026-05-19",
                            charactersRead = 11,
                            readingTime = 2200.0,
                            lastReadingSpeed = 18,
                        ),
                        allTime = ReadingStatistics(
                            title = "Volume",
                            dateKey = "2026-05-19",
                            charactersRead = 31,
                            readingTime = 6200.0,
                            lastReadingSpeed = 18,
                        ),
                    ),
                    statisticsEnabled = true,
                    pageIndex = 2,
                    pageCount = 12,
                    onEnableStatistics = {},
                    onToggleTracking = { toggleClicks += 1 },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Manga Statistics").assertIsDisplayed()
        composeRule.onNodeWithText("Page 3 of 12").assertIsDisplayed()
        composeRule.onAllNodesWithText("Pages Read")[0].assertIsDisplayed()
        composeRule.onAllNodesWithText("18 pages / h")[0].assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Start statistics").performClick()
        composeRule.runOnIdle {
            assertEquals(1, toggleClicks)
        }
    }

    @Test
    fun disabledSheetOffersEnableAction() {
        var enableClicks = 0
        composeRule.setContent {
            MaterialTheme {
                MangaStatisticsSheet(
                    state = null,
                    statisticsEnabled = false,
                    pageIndex = 0,
                    pageCount = 4,
                    onEnableStatistics = { enableClicks += 1 },
                    onToggleTracking = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Statistics are off.").assertIsDisplayed()
        composeRule.onNodeWithText("Enable").performClick()
        composeRule.runOnIdle {
            assertEquals(1, enableClicks)
        }
    }

    @Test
    fun enabledSheetShowsLoadingStateBeforeStatisticsLoad() {
        composeRule.setContent {
            MaterialTheme {
                MangaStatisticsSheet(
                    state = null,
                    statisticsEnabled = true,
                    pageIndex = 0,
                    pageCount = 4,
                    onEnableStatistics = {},
                    onToggleTracking = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Manga Statistics").assertIsDisplayed()
        composeRule.onNodeWithText("Loading statistics...").assertIsDisplayed()
    }
}
