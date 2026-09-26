package moe.antimony.hoshi.features.mangareader

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.mokuro.MokuroPage
import moe.antimony.hoshi.mokuro.MokuroTextBox
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MangaActionPlacementInstrumentedTest {
    @Test fun belowActionsClearOverflowingTextForSoloAndPairedButtons() {
        for (paired in listOf(false, true)) for (vertical in listOf(false, true)) {
            val state = measure(paired, """
                box.style.cssText = 'left:2px;top:0px;width:150px;height:6px;font-size:24px';
                box.classList.toggle('vertical', $vertical);
            """)
            assertTrue("fixture must overflow: $state", state.getDouble("textBottom") > state.getDouble("boxBottom"))
            assertTrue("button overlaps overflow: $state", state.getDouble("actionTop") >= state.getDouble("textBottom") + 2.9)
            assertTrue("button must be within viewport: $state", state.getDouble("actionLeft") >= -0.1)
            assertTrue("button must be within viewport: $state", state.getDouble("actionRight") <= state.getDouble("viewportWidth") + 0.1)
            assertEquals("placement must not drift: $state", 0.0, state.getDouble("drift"), 0.01)
            if (!paired) assertEquals("solo area should be 30% larger: $state", 1.3, state.getDouble("areaRatio"), 0.005)
        }
    }

    @Test fun tabletButtonsScaleWithoutChangingTextClearanceOrDrifting() {
        val state = measure(false, """
            box.style.cssText = 'left:2px;top:0px;width:150px;height:6px;font-size:24px';
            box.classList.add('vertical');
        """, viewportWidth = 1260, viewportHeight = 1680)
        assertEquals("tablet solo area should be 30% larger: $state", 1.3, state.getDouble("areaRatio"), 0.005)
        assertTrue("scaled action covers text: $state", state.getDouble("actionTop") >= state.getDouble("textBottom") + 2.9)
        assertEquals(0.0, state.getDouble("drift"), 0.01)
    }

    @Test fun normalPairedPositionAndPageSizeRemainUnchanged() {
        val state = measure(true, """
            box.style.cssText = 'left:200px;top:200px;width:180px;height:160px;font-size:18px';
        """)
        assertEquals(state.getDouble("originalLeft"), state.getDouble("actionLeft"), 0.01)
        assertEquals(state.getDouble("originalTop"), state.getDouble("actionTop"), 0.01)
        assertEquals(0.0, state.getDouble("pageResize"), 0.01)
    }

    @Test fun tallBubbleUsesFreeSideWithoutCoveringText() {
        val state = measure(false, """
            box.style.cssText = 'left:150px;top:0px;width:100px;height:' + window.innerHeight + 'px;font-size:24px';
        """)
        assertTrue("side action overlaps text: $state", state.getDouble("actionLeft") >= state.getDouble("textRight") + 2.9)
        assertTrue(state.getDouble("actionTop") >= 0)
        assertTrue(state.getDouble("actionBottom") <= state.getDouble("viewportHeight"))
    }

    private fun measure(paired: Boolean, setup: String, viewportWidth: Int = 706, viewportHeight: Int = 935): JSONObject {
        val html = MangaPageHtml.build(
            page = MokuroPage(0, "test.png", 706, 935, listOf(
                MokuroTextBox(100, 100, 200, 200, 24, false, listOf("いつものやつを！？")),
            )),
            backgroundCssColor = "#ffffff", selectionScript = "", scanNonJapaneseText = false,
            eInkMode = false, viewportCssWidth = viewportWidth, viewportCssHeight = viewportHeight, showCopyButton = paired,
        )
        var result: String? = null
        val loaded = CountDownLatch(1)
        val evaluated = CountDownLatch(1)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var webView: WebView
            scenario.onActivity { activity ->
                webView = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.minimumFontSize = 1
                    settings.minimumLogicalFontSize = 1
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
                        override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest) =
                            android.webkit.WebResourceResponse("image/png", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }
                val density = activity.resources.displayMetrics.density
                activity.setContentView(FrameLayout(activity).apply {
                    addView(webView, ViewGroup.LayoutParams((viewportWidth * density).toInt(), (viewportHeight * density).toInt()))
                })
                webView.loadDataWithBaseURL(MangaPageHtml.BASE_URL, html, "text/html", "UTF-8", null)
            }
            assertTrue(loaded.await(15, TimeUnit.SECONDS))
            scenario.onActivity {
                webView.evaluateJavascript("""
                    (function() {
                      var box = document.querySelector('.ocr-box');
                      box.classList.add('revealed');
                      $setup
                      var actions = box.querySelector('.ocr-actions');
                      var original = actions.getBoundingClientRect();
                      var page = document.querySelector('.page').getBoundingClientRect();
                      window.hoshiManga.updateActionPlacement(box);
                      var first = actions.getBoundingClientRect();
                      for (var i = 0; i < 5; i++) window.hoshiManga.updateActionPlacement(box);
                      var row = actions.getBoundingClientRect();
                      var range = document.createRange(); range.selectNodeContents(box.querySelector('p'));
                      var text = range.getBoundingClientRect(); var border = box.getBoundingClientRect();
                      window.dispatchEvent(new Event('resize'));
                      var afterPage = document.querySelector('.page').getBoundingClientRect();
                      var button = box.querySelector('button');
                      var actualButton = button.getBoundingClientRect();
                      var wasSolo = actions.classList.contains('solo');
                      actions.classList.remove('solo');
                      var pairedButton = button.getBoundingClientRect();
                      actions.classList.toggle('solo', wasSolo);
                      return JSON.stringify({
                        textBottom: Math.max(text.bottom, border.bottom), textRight: Math.max(text.right, border.right),
                        boxBottom: border.bottom, actionTop: row.top, actionBottom: row.bottom,
                        actionLeft: row.left, actionRight: row.right,
                        originalTop: original.top, originalLeft: original.left,
                        buttonWidth: actualButton.width,
                        areaRatio: (actualButton.width * actualButton.height) / (pairedButton.width * pairedButton.height),
                        viewportWidth: window.visualViewport.width, viewportHeight: window.visualViewport.height,
                        drift: Math.abs(first.top-row.top) + Math.abs(first.left-row.left),
                        pageResize: Math.abs(page.width-afterPage.width)+Math.abs(page.height-afterPage.height)
                      });
                    })();
                """.trimIndent()) { result = it; evaluated.countDown() }
            }
            assertTrue(evaluated.await(15, TimeUnit.SECONDS))
            scenario.onActivity { webView.destroy() }
        }
        return JSONObject(JSONTokener(result!!).nextValue() as String)
    }
}
