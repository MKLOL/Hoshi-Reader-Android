package moe.antimony.hoshi.features.reader

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import moe.antimony.hoshi.HoshiAppContainer
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.ui.theme.AdaptiveUi
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class PopupCardsSettingsSyncEditTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun unchangedEditorDisposalPreservesRemoteMangaAndCardUpdates() {
        val container = HoshiAppContainer(compose.activity)
        var settings by mutableStateOf(ReaderSettings())
        var visible by mutableStateOf(true)
        var saves = 0
        compose.setContent {
            CompositionLocalProvider(LocalHoshiAppContainer provides container) {
                HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                    AdaptiveUi {
                        if (visible) PopupCardsSettingsScreen(settings, { settings = it; saves++ }, {})
                    }
                }
            }
        }
        compose.waitForIdle()
        val remote = ReaderSettings(mangaShowFurigana = false, dictionaryCard = PopupCardStyle(height = 610.0))
        compose.runOnIdle { settings = remote }
        compose.runOnIdle { visible = false }
        compose.runOnIdle {
            assertEquals(remote, settings)
            assertEquals(0, saves)
        }
    }

    @Test
    fun cardEditAndLaterDisposalPreserveRemoteChangesAfterTheEdit() {
        val container = HoshiAppContainer(compose.activity)
        var settings by mutableStateOf(ReaderSettings())
        var visible by mutableStateOf(true)
        var saves = 0
        compose.setContent {
            CompositionLocalProvider(LocalHoshiAppContainer provides container) {
                HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                    AdaptiveUi {
                        if (visible) PopupCardsSettingsScreen(settings, { settings = it; saves++ }, {})
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            settings = settings.copy(mangaShowFurigana = false, dictionaryCard = PopupCardStyle(height = 610.0))
        }
        compose.onNodeWithTag("card-width").performSemanticsAction(SemanticsActions.SetProgress) { it(300f) }
        compose.runOnIdle {
            assertFalse(settings.mangaShowFurigana)
            assertEquals(PopupCardStyle(width = 300.0, height = 610.0), settings.dictionaryCard)
            settings = settings.copy(fontSize = 30, dictionaryCard = settings.dictionaryCard.copy(width = 620.0))
        }
        compose.runOnIdle { visible = false }
        compose.runOnIdle {
            assertFalse(settings.mangaShowFurigana)
            assertEquals(30, settings.fontSize)
            assertEquals(PopupCardStyle(width = 620.0, height = 610.0), settings.dictionaryCard)
            assertEquals(1, saves)
        }
    }
}
