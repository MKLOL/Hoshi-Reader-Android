package moe.antimony.hoshi.features.reader

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.HoshiAppContainer
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.theme.AdaptiveUi
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaFuriganaSettingsInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun mangaFuriganaStartsOnAndChangesWithoutChangingEpubOrOtherPreferences() {
        val container = HoshiAppContainer(compose.activity)
        val label = compose.activity.getString(R.string.reader_behavior_manga_show_furigana)
        val original = ReaderSettings(
            hideFurigana = true,
            fontSize = 29,
            volumeKeysTurnPages = true,
            mangaSingleTapLookup = true,
        )
        var settings by mutableStateOf(original)
        val emittedSettings = mutableListOf<ReaderSettings>()
        val destinations = mutableListOf<String>()

        compose.setContent {
            CompositionLocalProvider(LocalHoshiAppContainer provides container) {
                HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                    AdaptiveUi {
                        MangaReaderSettingsScreen(
                            settings = settings,
                            onSettingsChange = {
                                emittedSettings += it
                                settings = it
                            },
                            onClose = {},
                            onOpenSharedSettings = { destinations += "shared" },
                            onOpenCards = { destinations += "cards" },
                        )
                    }
                }
            }
        }

        compose.onNodeWithText(label).performScrollTo()
        val switch = compose.onNode(isToggleable() and hasText(label))
        switch.assertIsOn()
        compose.runOnIdle { assertTrue(settings.mangaShowFurigana) }

        switch.performClick().assertIsOff()
        compose.runOnIdle {
            assertFalse(settings.mangaShowFurigana)
            assertEquals(original.copy(mangaShowFurigana = false), settings)
            assertEquals(listOf(original.copy(mangaShowFurigana = false)), emittedSettings)
        }

        switch.performClick().assertIsOn()
        compose.runOnIdle {
            assertTrue(settings.mangaShowFurigana)
            assertEquals(original, settings)
            assertEquals(listOf(false, true), emittedSettings.map { it.mangaShowFurigana })
        }

        compose.onNodeWithText(compose.activity.getString(R.string.reader_appearance_font_size)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.reader_appearance_theme)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.reader_behavior_volume_keys_seek_sasayaki)).assertDoesNotExist()
        val sharedLabel = compose.activity.getString(R.string.settings_app_and_reading)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(sharedLabel))
        compose.onNodeWithText(sharedLabel).performClick()
        val cardsLabel = compose.activity.getString(R.string.settings_popup_cards)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(cardsLabel))
        compose.onNodeWithText(cardsLabel).performClick()
        compose.runOnIdle { assertEquals(listOf("shared", "cards"), destinations) }
    }
}
