package moe.antimony.hoshi.features.settings

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.R
import moe.antimony.hoshi.HoshiAppContainer
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.dictionary.DictionaryView
import moe.antimony.hoshi.features.reader.ReaderAppearanceScreen
import moe.antimony.hoshi.features.reader.ReaderFontManager
import moe.antimony.hoshi.features.reader.ReaderSettings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDetailLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dictionariesTitleUsesCompactHeader() {
        val container = HoshiAppContainer(ApplicationProvider.getApplicationContext<Context>())
        composeRule.setContent {
            CompositionLocalProvider(LocalHoshiAppContainer provides container) {
                MaterialTheme {
                    Box(
                        modifier = Modifier
                            .requiredSize(width = 360.dp, height = 780.dp)
                            .testTag(RootTag),
                    ) {
                        DictionaryView(
                            onClose = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        val titleBounds = composeRule.onNodeWithText("Dictionaries")
            .getUnclippedBoundsInRoot()
        val rootBounds = composeRule.onNodeWithTag(RootTag).getUnclippedBoundsInRoot()

        assertTrue(
            "Dictionaries title should be centered in the compact header, but was at $titleBounds",
            titleBounds.left > (rootBounds.right - rootBounds.left) * 0.2f,
        )
    }

    @Test
    fun appAndReadingContentStartsDirectlyBelowCompactHeader() {
        val container = HoshiAppContainer(ApplicationProvider.getApplicationContext<Context>())
        composeRule.setContent {
            CompositionLocalProvider(LocalHoshiAppContainer provides container) {
                MaterialTheme {
                    Box(
                        modifier = Modifier
                            .requiredSize(width = 360.dp, height = 780.dp)
                            .testTag(RootTag),
                    ) {
                        ReaderAppearanceScreen(
                            settings = ReaderSettings(),
                            onSettingsChange = {},
                            sasayakiSettings = moe.antimony.hoshi.features.sasayaki.SasayakiSettings(),
                            onSasayakiSettingsChange = {},
                            fontManager = ReaderFontManager(
                                ApplicationProvider.getApplicationContext<Context>().filesDir,
                            ),
                            onClose = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        val themeBounds = composeRule.onNodeWithText("Theme").getUnclippedBoundsInRoot()

        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.onAllNodesWithText(context.getString(R.string.settings_app_and_reading)).assertCountEquals(1)
        composeRule.onAllNodesWithText(context.getString(R.string.settings_appearance)).assertCountEquals(1)
        assertTrue(
            "App & reading content should start directly below the compact header, but Theme was at $themeBounds",
            themeBounds.top < 160.dp,
        )
    }

    private companion object {
        const val RootTag = "settings-detail-root"
    }
}
