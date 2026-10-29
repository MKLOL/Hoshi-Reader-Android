package moe.antimony.hoshi.features.reader

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
    fun furiganaStartsOnAndBehaviorSwitchEmitsBothChoices() {
        val container = HoshiAppContainer(compose.activity)
        val label = compose.activity.getString(R.string.reader_behavior_manga_show_furigana)
        var settings by mutableStateOf(ReaderSettings())
        val emittedSettings = mutableListOf<ReaderSettings>()

        compose.setContent {
            CompositionLocalProvider(LocalHoshiAppContainer provides container) {
                HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                    AdaptiveUi {
                        ReaderBehaviorScreen(
                            settings = settings,
                            onSettingsChange = {
                                emittedSettings += it
                                settings = it
                            },
                            onClose = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText(label).performScrollTo()
        // The ListItem is the common semantics parent of its label and trailing switch.
        val switch = compose.onNode(
            isToggleable() and hasParent(hasText(label) or hasAnyDescendant(hasText(label))),
        )
        switch.assertIsOn()
        compose.runOnIdle { assertTrue(settings.mangaShowFurigana) }

        switch.performClick().assertIsOff()
        compose.runOnIdle {
            assertFalse(settings.mangaShowFurigana)
            assertEquals(listOf(false), emittedSettings.map { it.mangaShowFurigana })
        }

        switch.performClick().assertIsOn()
        compose.runOnIdle {
            assertTrue(settings.mangaShowFurigana)
            assertEquals(listOf(false, true), emittedSettings.map { it.mangaShowFurigana })
        }
    }
}
