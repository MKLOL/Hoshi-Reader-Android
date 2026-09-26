package moe.antimony.hoshi.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatPopupView
import moe.antimony.hoshi.features.ai.AiChatUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AdaptiveUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun foldDensityIsIdenticalTabletScalesOnceAndReaderCoordinatesStayNative() {
        var windowSize by mutableStateOf(IntSize(1412, 1870))
        val platform = Density(2f, 1.3f)
        var adaptive: Density? = null
        var nested: Density? = null
        var reader: Density? = null
        compose.setContent {
            val realWindow = LocalWindowInfo.current
            val window = object : WindowInfo by realWindow { override val containerSize = windowSize }
            CompositionLocalProvider(LocalDensity provides platform, LocalWindowInfo provides window) {
                AdaptiveUi {
                    val current = LocalDensity.current
                    SideEffect { adaptive = current }
                    AdaptiveUi {
                        val currentNested = LocalDensity.current
                        SideEffect { nested = currentNested }
                        PlatformReaderUi {
                            val currentReader = LocalDensity.current
                            SideEffect { reader = currentReader }
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertTrue("Fold retains the exact platform Density", adaptive === platform)
            assertTrue(nested === platform)
            assertTrue(reader === platform)
            windowSize = IntSize(2520, 3360)
        }
        compose.runOnIdle {
            assertEquals(3f, adaptive!!.density, 0f)
            assertEquals(3f, nested!!.density, 0f)
            assertEquals(1.3f, adaptive!!.fontScale, 0f)
            assertTrue(reader === platform)
            windowSize = IntSize(1260, 3360)
        }
        compose.runOnIdle { assertTrue("Split-screen returns to original UI", adaptive === platform) }
    }

    @Test fun translationTextAndCloseTargetGrowTogetherAndRemainUsable() {
        var windowSize by mutableStateOf(IntSize(706, 935))
        var dismissed = false
        compose.setContent {
            val realWindow = LocalWindowInfo.current
            val window = object : WindowInfo by realWindow { override val containerSize = windowSize }
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalWindowInfo provides window) {
                MaterialTheme {
                    Box(Modifier.requiredSize(windowSize.width.dp, windowSize.height.dp).background(Color.White).testTag("sample-window")) {
                        AiChatPopupView(
                            state = AiChatUiState.Loaded(AiChatEntry(
                                "いつものやつをもらおうか", "", "", "I'll have the usual.\n\n| Word | Meaning |\n| --- | --- |\n| いつも | always |", 0.0,
                            ), pretranslated = true),
                            onDismiss = { dismissed = true }, onRetry = {}, onAskLive = {},
                        )
                    }
                }
            }
        }
        val foldClose = compose.onNodeWithContentDescription("Close").fetchSemanticsNode().boundsInRoot
        val foldTitle = compose.onNodeWithText("Pre-translated", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        capture("adaptive-ai-fold")
        compose.runOnIdle { windowSize = IntSize(1260, 1680) }
        val tabletClose = compose.onNodeWithContentDescription("Close").fetchSemanticsNode().boundsInRoot
        val tabletTitle = compose.onNodeWithText("Pre-translated", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(foldClose.width * 1.5f, tabletClose.width, 1f)
        assertEquals(foldTitle.height * 1.5f, tabletTitle.height, 2f)
        compose.onNodeWithText("I'll have the usual.").assertIsDisplayed()
        compose.onNodeWithText("Ask ChatGPT instead").assertIsDisplayed()
        capture("adaptive-ai-tablet")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }
    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("sample-window").captureToImage().asAndroidBitmap()
        java.io.File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

}
