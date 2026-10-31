package moe.antimony.hoshi.features.reader

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.HoshiAppContainer
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.ui.theme.AdaptiveUi
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSettingsConsolidationInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun epubFuriganaUsesPositiveSwitchWithoutChangingMangaAndRelatedLinksOpenTheirDestinations() {
        val container = HoshiAppContainer(compose.activity)
        val original = ReaderSettings(
            hideFurigana = true,
            mangaShowFurigana = false,
            mangaEnlargeSmallText = false,
            fontSize = 27,
            volumeKeysTurnPages = true,
        )
        val originalSasayaki = SasayakiSettings(showReaderToggle = false, autoPause = false)
        var settings by mutableStateOf(original)
        var sasayaki by mutableStateOf(originalSasayaki)
        val destinations = mutableListOf<String>()

        compose.setContent {
            CompositionLocalProvider(LocalHoshiAppContainer provides container) {
                HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                    AdaptiveUi {
                        EpubReaderSettingsScreen(
                            settings = settings,
                            onSettingsChange = { settings = it },
                            sasayakiSettings = sasayaki,
                            onSasayakiSettingsChange = { sasayaki = it },
                            fontManager = container.readerFontManager,
                            onClose = {},
                            onOpenSharedSettings = { destinations += "shared" },
                            onOpenCards = { destinations += "cards" },
                            onOpenSasayaki = { destinations += "sasayaki" },
                        )
                    }
                }
            }
        }

        compose.onNodeWithText(label(R.string.settings_epub)).assertExists()
        compose.onNodeWithText(label(R.string.reader_appearance_font_size)).assertExists()
        compose.onNodeWithText(label(R.string.reader_appearance_theme)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.reader_appearance_eink_mode)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.reader_behavior_manga_enlarge_small_text)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.reader_behavior_volume_keys_turn_pages)).assertDoesNotExist()

        val furigana = compose.onNode(isToggleable() and hasText(label(R.string.reader_epub_show_furigana)))
        furigana.performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(original.copy(hideFurigana = false), settings) }
        furigana.performClick().assertIsOff()
        compose.runOnIdle { assertEquals(original, settings) }

        compose.onNode(isToggleable() and hasText(label(R.string.reader_appearance_show_sasayaki_toggle)))
            .performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals(originalSasayaki.copy(showReaderToggle = true), sasayaki)
            assertEquals(original, settings)
        }

        compose.onNodeWithText(label(R.string.settings_app_and_reading)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.settings_popup_cards)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.advanced_sasayaki_audiobooks)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("shared", "cards", "sasayaki"), destinations) }
    }

    @Test
    fun sharedPageOwnsThemeAndVolumeControlsWithoutDuplicatingFormatSettings() {
        val container = HoshiAppContainer(compose.activity)
        val original = ReaderSettings(hideFurigana = true, mangaShowFurigana = false, fontSize = 31)
        var settings by mutableStateOf(original)

        compose.setContent {
            CompositionLocalProvider(LocalHoshiAppContainer provides container) {
                HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                    AdaptiveUi {
                        ReaderAppearanceScreen(
                            settings = settings,
                            onSettingsChange = { settings = it },
                            sasayakiSettings = SasayakiSettings(),
                            onSasayakiSettingsChange = {},
                            fontManager = container.readerFontManager,
                            onClose = {},
                        )
                    }
                }
            }
        }

        compose.onAllNodesWithText(label(R.string.settings_app_and_reading)).assertCountEquals(1)
        compose.onAllNodesWithText(label(R.string.reader_appearance_eink_mode)).assertCountEquals(1)
        compose.onAllNodesWithText(label(R.string.reader_behavior_volume_keys_turn_pages)).assertCountEquals(1)
        compose.onNodeWithText(label(R.string.reader_epub_show_furigana)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.reader_appearance_font_size)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.reader_appearance_sentence_translations)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.reader_behavior_manga_single_tap_lookup)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.reader_behavior_keep_screen_on)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.reader_behavior_volume_keys_seek_sasayaki)).assertDoesNotExist()

        compose.onNode(isToggleable() and hasText(label(R.string.reader_appearance_eink_mode)))
            .performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(original.copy(eInkMode = true), settings) }
        compose.onNode(isToggleable() and hasText(label(R.string.reader_behavior_volume_keys_turn_pages)))
            .performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle {
            assertEquals(original.copy(eInkMode = true, volumeKeysTurnPages = true), settings)
        }
    }

    @Test
    fun epubSheetReturnsFromRelatedSettingsAtTheSameScrollPosition() {
        val container = HoshiAppContainer(compose.activity)
        var settings by mutableStateOf(ReaderSettings())
        var dismissed = 0

        compose.setContent {
            CompositionLocalProvider(LocalHoshiAppContainer provides container) {
                HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                    AdaptiveUi {
                        ReaderAppearanceSheet(
                            settings = settings,
                            onSettingsChange = { settings = it },
                            sasayakiSettings = SasayakiSettings(),
                            onSasayakiSettingsChange = {},
                            fontManager = container.readerFontManager,
                            onDismiss = { dismissed += 1 },
                        )
                    }
                }
            }
        }

        val sharedLabel = label(R.string.settings_app_and_reading)
        compose.onNodeWithText(sharedLabel).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.reader_appearance_theme)).assertIsDisplayed()

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        // Do not scroll after returning: the related link must already be in view.
        compose.onNodeWithText(sharedLabel).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.reader_appearance_theme)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, dismissed) }

        val cardsLabel = label(R.string.settings_popup_cards)
        compose.onNodeWithText(cardsLabel).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(label(R.string.card_preview_label)).assertIsDisplayed()

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(cardsLabel).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.card_preview_label)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, dismissed) }
    }

    private fun label(resource: Int): String = compose.activity.getString(resource)
}
