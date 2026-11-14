package moe.antimony.hoshi.features.mangareader

import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.features.dictionary.LookupPopupLayout
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import moe.antimony.hoshi.features.reader.ReaderSelectionBridgePayload
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
import java.util.concurrent.atomic.AtomicReference

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

    @Test fun bottomBubbleKeepsItsNormalDictionaryCardAndMovesBothTapTargets() {
        val state = measure(
            paired = true,
            setup = "box.style.cssText = 'left:200px;top:760px;width:180px;height:110px;font-size:18px';",
            hostScale = 1.8,
            exerciseSelection = true,
        )
        val selection = checkNotNull(ReaderSelectionBridgePayload.fromJson(state.getString("selection")))
        assertEquals(state.getDouble("textTop") * 1.8, selection.rect.y, 0.05)
        assertEquals(state.getDouble("textBottom") * 1.8, selection.rect.y + selection.rect.height, 0.05)
        assertEquals(state.getDouble("textLeft") * 1.8, selection.rect.x, 0.05)
        assertEquals(state.getDouble("textRight") * 1.8, selection.rect.x + selection.rect.width, 0.05)
    }

    private class SelectionRecorder {
        val latest = AtomicReference<String>()
        @JavascriptInterface fun postMessage(message: String) { latest.set(message) }
    }

    private fun measure(
        paired: Boolean,
        setup: String,
        viewportWidth: Int = 706,
        viewportHeight: Int = 935,
        hostScale: Double = 1.0,
        exerciseSelection: Boolean = false,
    ): JSONObject {
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
        val dismissed = CountDownLatch(1)
        val recorder = SelectionRecorder()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var webView: WebView
            scenario.onActivity { activity ->
                webView = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.minimumFontSize = 1
                    settings.minimumLogicalFontSize = 1
                    if (exerciseSelection) {
                        addJavascriptInterface(recorder, "HoshiTextSelection")
                        addJavascriptInterface(MangaTapBridge(onSelectedNothing = { dismissed.countDown() }), "HoshiMangaTap")
                    }
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
                      window.hoshiManga.setHostScale($hostScale);
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
                      var bubble = window.hoshiOcrBoxExtent(box);
                      window.dispatchEvent(new Event('resize'));
                      var afterPage = document.querySelector('.page').getBoundingClientRect();
                      var button = box.querySelector('button');
                      var actualButton = button.getBoundingClientRect();
                      var wasSolo = actions.classList.contains('solo');
                      actions.classList.remove('solo');
                      var pairedButton = button.getBoundingClientRect();
                      actions.classList.toggle('solo', wasSolo);
                      if ($exerciseSelection) {
                        window.hoshiSelection = {
                          selection: {startNode: box.querySelector('p').firstChild},
                          clearSelection: function() { this.selection = null; }
                        };
                        window.HoshiTextSelection.postMessage(JSON.stringify({
                          text:'いつも', sentence:'いつものやつを！？', rect:{x:0,y:0,width:1,height:1}
                        }));
                      }
                      return JSON.stringify({
                        textTop: bubble.y, textLeft: bubble.x,
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
            if (exerciseSelection) {
                val state = JSONObject(JSONTokener(result!!).nextValue() as String)
                val original = popupTestRect(
                    state.getDouble("actionLeft") * hostScale, state.getDouble("actionTop") * hostScale,
                    state.getDouble("actionRight") * hostScale, state.getDouble("actionBottom") * hostScale,
                )
                val anchor = checkNotNull(ReaderSelectionBridgePayload.fromJson(checkNotNull(recorder.latest.get()))).rect
                val card = LookupPopupLayout(
                    selectionRect = anchor,
                    screenWidth = state.getDouble("viewportWidth") * hostScale,
                    screenHeight = state.getDouble("viewportHeight") * hostScale,
                    maxWidth = 320.0, maxHeight = 300.0, isVertical = false,
                ).calculate()
                assertEquals(300.0, card.height, 0.05)
                val lookup = ReaderSelectionRect(
                    card.centerX - card.width / 2, card.centerY - card.height / 2,
                    card.width, card.height,
                )
                assertTrue(lookup.y + lookup.height <= anchor.y)
                assertTrue("The normal dictionary card must cover the original action row", lookup.overlaps(original))
                val (relocated, taps) = checkRelocatedActions(scenario, webView, lookup.jsLiteral())
                assertTrue(!relocated.overlaps(lookup))
                assertTrue(!relocated.overlaps(anchor))
                assertEquals("__ai__", taps.getString("translate"))
                assertEquals("__copied__", taps.getString("copy"))
                val nextCard = ReaderSelectionRect(relocated.x - 4, relocated.y - 4, relocated.width + 8, relocated.height + 8)
                val withNested = checkRelocatedActions(scenario, webView, lookup.jsLiteral() + "," + nextCard.jsLiteral()).first
                assertTrue(!withNested.overlaps(lookup))
                assertTrue(!withNested.overlaps(nextCard))
                assertTrue(!withNested.overlaps(anchor))
                val anchorAfterMoving = checkNotNull(ReaderSelectionBridgePayload.fromJson(checkNotNull(recorder.latest.get()))).rect
                assertEquals(anchor, anchorAfterMoving)
                val restored = checkRelocatedActions(scenario, webView, "").first
                assertEquals(original.x, restored.x, 0.05)
                assertEquals(original.y, restored.y, 0.05)
                assertEquals("Moving the row must keep the native lookup open", 1L, dismissed.count)
                checkRelocatedActions(scenario, webView, lookup.jsLiteral())
                scenario.onActivity { webView.evaluateJavascript("window.hoshiManga.setHostScale(${hostScale * 1.2})", null) }
                assertTrue("Moving the painted bubble must dismiss a stale native popup", dismissed.await(5, TimeUnit.SECONDS))
            }
            scenario.onActivity { webView.destroy() }
        }
        return JSONObject(JSONTokener(result!!).nextValue() as String).apply {
            if (exerciseSelection) put("selection", checkNotNull(recorder.latest.get()))
        }
    }

    private fun checkRelocatedActions(
        scenario: ActivityScenario<ComponentActivity>,
        webView: WebView,
        bounds: String,
    ): Pair<ReaderSelectionRect, JSONObject> {
        val finished = CountDownLatch(1)
        var result: String? = null
        scenario.onActivity {
            webView.evaluateJavascript("""
                (function() {
                  var manga = window.hoshiManga;
                  var box = document.querySelector('.ocr-box');
                  manga.setLookupPopupBounds([$bounds]);
                  manga.updateActionPlacement(box);
                  function tap(selector) {
                    var r = box.querySelector(selector).getBoundingClientRect();
                    return manga.handleTap(r.left + r.width / 2, r.top + r.height / 2, 16);
                  }
                  window.HoshiTextSelection.postMessage(JSON.stringify({
                    text:'いつも', sentence:'いつものやつを！？', rect:{x:0,y:0,width:1,height:1}
                  }));
                  return JSON.stringify({
                    row: manga.hostRectFromViewportRect(box.querySelector('.ocr-actions').getBoundingClientRect()),
                    translate: tap('.ocr-ai-btn'), copy: tap('.ocr-copy-btn')
                  });
                })();
            """.trimIndent()) { result = it; finished.countDown() }
        }
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        val state = JSONObject(JSONTokener(checkNotNull(result)).nextValue() as String)
        val row = state.getJSONObject("row")
        return ReaderSelectionRect(row.getDouble("x"), row.getDouble("y"), row.getDouble("width"), row.getDouble("height")) to state
    }

    private fun popupTestRect(left: Double, top: Double, right: Double, bottom: Double) =
        ReaderSelectionRect(left, top, right - left, bottom - top)

    private fun ReaderSelectionRect.jsLiteral(): String =
        JSONObject().put("x", x).put("y", y).put("width", width).put("height", height).toString()

    private fun ReaderSelectionRect.overlaps(other: ReaderSelectionRect): Boolean =
        x < other.x + other.width && x + width > other.x &&
            y < other.y + other.height && y + height > other.y

}
