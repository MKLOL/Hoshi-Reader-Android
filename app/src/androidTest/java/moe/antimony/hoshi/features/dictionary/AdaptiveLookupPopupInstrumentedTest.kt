package moe.antimony.hoshi.features.dictionary

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import moe.antimony.hoshi.HoshiAppContainer
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class AdaptiveLookupPopupInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeFrameAndWebContentScaleTogetherWithoutChangingUserZoom() {
        var tablet by mutableStateOf(false)
        val popup = LookupPopupItem(state = LookupPopupState(
            selection = ReaderSelectionData("いつも", "いつものやつ", ReaderSelectionRect(100.0, 100.0, 50.0, 80.0), null, null),
            results = emptyList(), isVertical = false, popupActionBar = true, popupScale = 1.25,
        ))
        val app = HoshiAppContainer(compose.activity)
        compose.setContent {
            CompositionLocalProvider(LocalHoshiAppContainer provides app) {
                MaterialTheme {
                    LookupPopupAndroidStack(
                        popups = listOf(popup), onPopupsChange = {}, lookupChildPopup = { null },
                        modifier = Modifier.requiredSize(if (tablet) 1260.dp else 706.dp, if (tablet) 1680.dp else 935.dp),
                    )
                }
            }
        }
        verifyPopup(expectedWidthDp = 320.0, expectedHeightDp = 250.0, expectedZoom = 1.25)
        compose.runOnIdle { tablet = true }
        verifyPopup(expectedWidthDp = 480.0, expectedHeightDp = 375.0, expectedZoom = 1.875)
        compose.runOnIdle { tablet = false }
        verifyPopup(expectedWidthDp = 320.0, expectedHeightDp = 250.0, expectedZoom = 1.25)
    }

    private fun verifyPopup(expectedWidthDp: Double, expectedHeightDp: Double, expectedZoom: Double) {
        val result = AtomicReference<JSONObject?>()
        compose.waitUntil(15_000) {
            compose.runOnIdle {
                val webView = descendants(compose.activity.window.decorView).filterIsInstance<WebView>().firstOrNull()
                val host = (webView?.parent as? View)?.parent as? View
                val density = host?.resources?.displayMetrics?.density ?: 1f
                if (webView != null && host != null && kotlin.math.abs(host.width - expectedWidthDp * density) <= 1 &&
                    kotlin.math.abs(host.height - expectedHeightDp * density) <= 1) {
                    webView.evaluateJavascript("JSON.stringify({zoom:parseFloat(getComputedStyle(document.documentElement).zoom),ready:document.readyState})") {
                        val state = runCatching { JSONObject(JSONTokener(it).nextValue() as String) }.getOrNull()
                        if (state?.optString("ready") == "complete" && kotlin.math.abs(state.optDouble("zoom") - expectedZoom) <= 0.0051) result.set(state)
                    }
                }
            }
            result.get() != null
        }
        compose.runOnIdle {
            val webView = descendants(compose.activity.window.decorView).filterIsInstance<WebView>().first()
            val host = (webView.parent as View).parent as View
            val density = host.resources.displayMetrics.density
            assertEquals(expectedWidthDp * density, host.width.toDouble(), 1.0)
            assertEquals(expectedHeightDp * density, host.height.toDouble(), 1.0)
            assertTrue("controls must leave space for dictionary content", webView.height > host.height / 2)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
