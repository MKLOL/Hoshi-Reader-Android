package moe.antimony.hoshi.features.reader

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import moe.antimony.hoshi.HoshiAppContainer
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.ui.theme.AdaptiveUi
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class PopupCardsSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun editsLiveWithoutReloadAndKeepsTheTwoCardsIndependent() {
        val profile = InstrumentationRegistry.getArguments().getString("profile") ?: "fold"
        val activity = compose.activity
        val container = HoshiAppContainer(activity)
        var settings by mutableStateOf(ReaderSettings(theme = ReaderTheme.Light, eInkMode = profile == "boox"))
        compose.runOnUiThread { activity.enableEdgeToEdge() }
        compose.setContent {
            CompositionLocalProvider(LocalHoshiAppContainer provides container) {
                HoshiReaderTheme(darkTheme = false, eInkMode = settings.eInkMode, dynamicColor = false) {
                    AdaptiveUi { PopupCardsSettingsScreen(settings, { settings = it }, {}) }
                }
            }
        }
        waitForDictionary()
        capture("$profile-editor-dictionary")
        val firstWebView = webView()
        evaluate("window.previewReloadSentinel = 73; 73")
        compose.onNodeWithTag("card-width").performSemanticsAction(SemanticsActions.SetProgress) { it(600f) }
        compose.onNodeWithTag("card-font").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1.45f) }
        compose.waitForIdle()
        assertEquals(600.0, settings.dictionaryCard.width!!, 0.001)
        assertEquals(1.45, settings.dictionaryCard.fontScale!!, 0.001)
        assertEquals(PopupCardStyle(), settings.translationCard)
        assertSame(firstWebView, webView())
        assertEquals("73", evaluate("window.previewReloadSentinel"))
        assertEquals(26 * 1.45, evaluate("parseFloat(getComputedStyle(document.querySelector('.expression')).fontSize)").toDouble(), 0.05)
        capture("$profile-editor-dictionary-adjusted")
        compose.onNodeWithTag("translation-tab").performClick()
        compose.waitForIdle()
        capture("$profile-editor-translation")
        compose.onNodeWithTag("card-font").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.waitForIdle()
        assertEquals(2.0, settings.translationCard.fontScale!!, 0.001)
        assertEquals(1.45, settings.dictionaryCard.fontScale!!, 0.001)
        compose.onNodeWithTag("reset-card").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(PopupCardStyle(), settings.translationCard)
        assertEquals(600.0, settings.dictionaryCard.width!!, 0.001)
        compose.onNodeWithTag("full-preview").performClick()
        compose.waitForIdle()
        capture("$profile-editor-full-translation")
    }

    private fun waitForDictionary() {
        compose.waitUntil(20_000) {
            runCatching { evaluate("document.querySelector('.expression') != null") == "true" }.getOrDefault(false)
        }
        compose.waitForIdle()
    }

    private fun evaluate(script: String): String {
        val result = AtomicReference<String?>(null)
        compose.runOnIdle { webView().evaluateJavascript(script, result::set) }
        compose.waitUntil(5_000) { result.get() != null }
        return result.get()!!
    }

    private fun webView(): WebView = descendants(compose.activity.window.decorView).filterIsInstance<WebView>().first()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(500) // Wait for the independent WebView compositor before taking a screenshot.
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
