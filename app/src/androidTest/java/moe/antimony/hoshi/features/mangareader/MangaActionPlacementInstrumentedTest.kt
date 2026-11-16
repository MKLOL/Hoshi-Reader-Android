package moe.antimony.hoshi.features.mangareader

import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.util.concurrent.LinkedBlockingQueue
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

    @Test fun pairedButtonsUseTheOtherSideWithoutChangingPageSize() {
        val state = measure(true, """
            box.style.cssText = 'left:200px;top:200px;width:180px;height:160px;font-size:18px';
        """)
        assertTrue(state.getDouble("actionRight") <= state.getDouble("textLeft") - 2.9)
        assertEquals(state.getDouble("textTop"), state.getDouble("actionTop"), 0.05)
        assertEquals(0.0, state.getDouble("pageResize"), 0.01)
    }

    @Test fun tallBubbleUsesFreeSideWithoutCoveringText() {
        val state = measure(false, """
            box.style.cssText = 'left:150px;top:0px;width:100px;height:' + window.innerHeight + 'px;font-size:24px';
        """)
        assertTrue("side action overlaps text: $state", state.getDouble("actionRight") <= state.getDouble("textLeft") - 2.9)
        assertTrue(state.getDouble("actionTop") >= 0)
        assertTrue(state.getDouble("actionBottom") <= state.getDouble("viewportHeight"))
    }

    @Test fun horizontalBottomBubblePicksRightForDictionaryAndKeepsActionsOnLeft() {
        val state = measure(
            paired = true,
            setup = "box.style.cssText = 'left:200px;top:760px;width:180px;height:110px;font-size:18px';",
            hostScale = 1.8,
            checkLookupActions = true,
        )
        val selection = checkNotNull(ReaderSelectionBridgePayload.fromJson(state.getString("selection")))
        assertEquals(false, selection.mangaPopupOnLeft)
        assertTrue(state.getDouble("actionRight") <= state.getDouble("textLeft") - 2.9)
        val glyph = checkNotNull(selection.mangaLookupGlyphRect)
        assertEquals(state.getDouble("glyphLeft") * 1.8, glyph.x, 0.05)
        assertEquals(state.getDouble("glyphTop") * 1.8, glyph.y, 0.05)
        assertEquals(state.getDouble("glyphWidth") * 1.8, glyph.width, 0.05)
        assertEquals(state.getDouble("glyphHeight") * 1.8, glyph.height, 0.05)
        assertEquals(state.getDouble("textTop") * 1.8, selection.rect.y, 0.05)
        assertEquals(state.getDouble("textBottom") * 1.8, selection.rect.y + selection.rect.height, 0.05)
        assertEquals(state.getDouble("textLeft") * 1.8, selection.rect.x, 0.05)
        assertEquals(state.getDouble("textRight") * 1.8, selection.rect.x + selection.rect.width, 0.05)
    }

    @Test fun squatVerticalBubblePicksLeftForDictionaryAndKeepsActionsOnRight() {
        val state = measure(
            paired = true,
            setup = """
                box.style.cssText = 'left:520px;top:580px;width:120px;height:65px;font-size:16px';
                box.querySelector('p').textContent = 'いつも';
            """.trimIndent(),
            vertical = true,
            checkLookupActions = true,
        )
        val selection = checkNotNull(ReaderSelectionBridgePayload.fromJson(state.getString("selection")))
        assertTrue("fixture must be wider than tall: ${selection.rect}", selection.rect.width > selection.rect.height)
        assertEquals(true, selection.mangaPopupOnLeft)
        assertTrue(state.getDouble("actionLeft") >= state.getDouble("textRight") + 2.9)
    }

    private class SelectionRecorder {
        private val messages = LinkedBlockingQueue<String>()
        @JavascriptInterface fun postMessage(message: String) { messages.add(message) }
        fun nextMessage(): String = checkNotNull(messages.poll(5, TimeUnit.SECONDS))
    }

    private fun measure(
        paired: Boolean,
        setup: String,
        viewportWidth: Int = 706,
        viewportHeight: Int = 935,
        hostScale: Double = 1.0,
        vertical: Boolean = false,
        checkLookupActions: Boolean = false,
    ): JSONObject {
        val html = MangaPageHtml.build(
            page = MokuroPage(0, "test.png", 706, 935, listOf(
                MokuroTextBox(100, 100, 200, 200, 24, vertical, listOf("いつものやつを！？")),
            )),
            backgroundCssColor = "#ffffff", selectionScript = "", scanNonJapaneseText = false,
            eInkMode = false, viewportCssWidth = viewportWidth, viewportCssHeight = viewportHeight, showCopyButton = paired,
        )
        var result: String? = null
        val loaded = CountDownLatch(1)
        val evaluated = CountDownLatch(1)
        val dismissed = CountDownLatch(1)
        val recorder = SelectionRecorder()
        lateinit var selectionJson: String
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var webView: WebView
            scenario.onActivity { activity ->
                webView = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.minimumFontSize = 1
                    settings.minimumLogicalFontSize = 1
                    addJavascriptInterface(recorder, "HoshiTextSelection")
                    if (checkLookupActions) {
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
                      window.hoshiSelection = {
                        selection: {startNode: box.querySelector('p').firstChild},
                        clearSelection: function() { this.selection = null; }
                      };
                      var node = box.querySelector('p').firstChild;
                      window.hoshiMangaTestLookup = function(offset) {
                        var glyphRange = document.createRange();
                        glyphRange.setStart(node, offset);
                        glyphRange.setEnd(node, offset + 1);
                        var glyph = glyphRange.getBoundingClientRect();
                        window.hoshiSelection.selection = {startNode: node, startOffset: offset};
                        window.HoshiTextSelection.postMessage(JSON.stringify({
                          text:node.textContent.slice(offset), sentence:node.textContent,
                          rect:{x:glyph.left,y:glyph.top,width:glyph.width,height:glyph.height}
                        }));
                        return glyph;
                      };
                      var glyph = window.hoshiMangaTestLookup(0);
                      return JSON.stringify({
                        textTop: bubble.y, textLeft: bubble.x,
                        textBottom: Math.max(text.bottom, border.bottom), textRight: Math.max(text.right, border.right),
                        boxBottom: border.bottom, actionTop: row.top, actionBottom: row.bottom,
                        actionLeft: row.left, actionRight: row.right,
                        glyphLeft: glyph.left, glyphTop: glyph.top, glyphWidth: glyph.width, glyphHeight: glyph.height,
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
            selectionJson = recorder.nextMessage()
            if (checkLookupActions) {
                val initial = JSONObject(JSONTokener(result!!).nextValue() as String)
                val selection = checkNotNull(ReaderSelectionBridgePayload.fromJson(selectionJson))
                for (glyphIndex in 1..2) {
                    val current = repeatLookupAndTapActions(scenario, webView, glyphIndex)
                    for (key in listOf("actionLeft", "actionTop", "actionRight", "actionBottom")) {
                        assertEquals("lookup moved the action row ($key)", initial.getDouble(key), current.getDouble(key), 0.05)
                    }
                    assertEquals("__ai__", current.getString("translate"))
                    assertEquals("__copied__", current.getString("copy"))
                    val next = checkNotNull(ReaderSelectionBridgePayload.fromJson(recorder.nextMessage()))
                    assertEquals(selection.rect, next.rect)
                    assertEquals(selection.mangaPopupOnLeft, next.mangaPopupOnLeft)
                    assertTrue("tapping another glyph must keep its own anchor",
                        next.mangaLookupGlyphRect != selection.mangaLookupGlyphRect)
                }
                assertEquals("Repeating a lookup must keep its selection", 1L, dismissed.count)
                scenario.onActivity { webView.evaluateJavascript("window.hoshiManga.setHostScale(${hostScale * 1.2})", null) }
                assertTrue("Moving the painted bubble must dismiss a stale native popup", dismissed.await(5, TimeUnit.SECONDS))
            }
            scenario.onActivity { webView.destroy() }
        }
        return JSONObject(JSONTokener(result!!).nextValue() as String).apply {
            put("selection", selectionJson)
        }
    }

    private fun repeatLookupAndTapActions(
        scenario: ActivityScenario<ComponentActivity>,
        webView: WebView,
        glyphIndex: Int,
    ): JSONObject {
        val finished = CountDownLatch(1)
        var result: String? = null
        scenario.onActivity {
            webView.evaluateJavascript("""
                (function() {
                  var manga = window.hoshiManga;
                  var box = document.querySelector('.ocr-box');
                  window.hoshiMangaTestLookup($glyphIndex);
                  manga.updateActionPlacement(box);
                  function tap(selector) {
                    var r = box.querySelector(selector).getBoundingClientRect();
                    return manga.handleTap(r.left + r.width / 2, r.top + r.height / 2, 16);
                  }
                  var row = box.querySelector('.ocr-actions').getBoundingClientRect();
                  return JSON.stringify({
                    actionLeft: row.left, actionTop: row.top, actionRight: row.right, actionBottom: row.bottom,
                    translate: tap('.ocr-ai-btn'), copy: tap('.ocr-copy-btn')
                  });
                })();
            """.trimIndent()) { result = it; finished.countDown() }
        }
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        return JSONObject(JSONTokener(checkNotNull(result)).nextValue() as String)
    }
}
