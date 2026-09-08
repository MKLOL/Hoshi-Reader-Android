package moe.antimony.hoshi.features.reader

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReaderSelectionUnicodeWebViewTest {
    @Test
    fun readerSelectionPreservesSupplementaryCharactersAndDomOffsets() {
        assertUnicodeSelection(ReaderSelectionScripts.source())
    }

    @Test
    fun popupSelectionPreservesSupplementaryCharactersAndDomOffsets() {
        val source = InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("hoshi-popup/selection.js").bufferedReader().use { it.readText() }
        assertUnicodeSelection(source)
    }

    private fun assertUnicodeSelection(source: String) {
        val result = runInWebView(source)
        val boundary = result.getJSONObject("boundary")
        assertEquals("食べる" + "あ".repeat(12) + "𠮷", boundary.getString("text"))
        assertEquals(boundary.getString("text"), boundary.getString("messageText"))
        assertEquals(16, boundary.getInt("characters"))
        assertEquals(17, boundary.getInt("endOffset"))
        assertEquals(17, boundary.getInt("highlightEndOffset"))

        val secondHalf = result.getJSONObject("secondHalf")
        assertEquals("𠮷野", secondHalf.getString("text"))
        assertEquals(1, secondHalf.getInt("startOffset"))
        assertEquals(4, secondHalf.getInt("endOffset"))
        assertEquals(1, secondHalf.getInt("normalizedOffset"))
        assertEquals(1, secondHalf.getInt("sentenceOffset"))
        assertTrue(secondHalf.getBoolean("sameCharacterClearsSelection"))

        val japaneseOnly = result.getJSONObject("japaneseOnly")
        assertEquals("𠮷野", japaneseOnly.getString("text"))
        assertEquals(japaneseOnly.getString("text"), japaneseOnly.getString("messageText"))
        assertEquals(3, japaneseOnly.getInt("endOffset"))

        assertEquals("食べる", result.getJSONObject("delimiter").getString("text"))
    }

    private fun runInWebView(source: String): JSONObject {
        val loaded = CountDownLatch(1)
        val evaluated = CountDownLatch(1)
        var result: String? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var webView: WebView
            scenario.onActivity { activity ->
                webView = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            loaded.countDown()
                        }
                    }
                }
                activity.setContentView(webView)
                webView.loadDataWithBaseURL(
                    "https://hoshi.local/",
                    """<!doctype html><html lang="ja"><head>
                    <meta name="viewport" content="width=device-width,initial-scale=1">
                    <style>body { margin: 0; font-size: 16px; } p { white-space: pre; }</style>
                    </head><body><p id="target"></p><script>$source</script></body></html>""",
                    "text/html",
                    "utf-8",
                    null,
                )
            }
            try {
                assertTrue("Selection page did not load", loaded.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    webView.evaluateJavascript(
                        """
                        (() => {
                          let message;
                          window.HoshiTextSelection = { postMessage: value => { message = JSON.parse(value); } };
                          window.webkit = { messageHandlers: { textSelected: { postMessage: value => { message = value; } } } };
                          function select(text, caretOffset, characterStart, maxLength, scanNonJapaneseText) {
                            const paragraph = document.getElementById('target');
                            paragraph.textContent = text;
                            const node = paragraph.firstChild;
                            const selection = window.hoshiSelection;
                            selection.clearSelection();
                            window.scanNonJapaneseText = scanNonJapaneseText;
                            window.hoshiReader = { nodeStartOffsets: new Map([[node, 0]]), isMatchableChar: () => true };
                            // A caret may land between a surrogate pair's two UTF-16 units.
                            // Keep hit testing, DOM range geometry, scanning, and message creation real.
                            selection.getCaretRange = () => {
                              const caret = document.createRange();
                              caret.setStart(node, caretOffset);
                              caret.collapse(true);
                              return caret;
                            };
                            const character = document.createRange();
                            character.setStart(node, characterStart);
                            character.setEnd(node, characterStart + String.fromCodePoint(text.codePointAt(characterStart)).length);
                            const rect = character.getBoundingClientRect();
                            const x = rect.left + rect.width / 2;
                            const y = rect.top + rect.height / 2;
                            const selected = selection.selectText(x, y, maxLength);
                            const range = selection.selection.ranges[0];
                            const highlightRanges = selection.selectionCharacterRanges(Array.from(selected).length);
                            const result = {
                              text: selected, messageText: message.text,
                              characters: Array.from(selected).length,
                              startOffset: range.start, endOffset: range.end,
                              highlightEndOffset: highlightRanges[highlightRanges.length - 1].endOffset,
                              normalizedOffset: message.normalizedOffset, sentenceOffset: message.sentenceOffset
                            };
                            result.sameCharacterClearsSelection = selection.selectText(x, y, maxLength) === null && selection.selection === null;
                            return result;
                          }
                          return {
                            boundary: select('食べる' + 'あ'.repeat(12) + '𠮷野', 0, 0, 16, true),
                            secondHalf: select('あ𠮷野', 2, 1, 16, true),
                            japaneseOnly: select('𠮷野A', 1, 0, 16, false),
                            delimiter: select('食べる。飲む', 0, 0, 16, true)
                          };
                        })()
                        """.trimIndent(),
                    ) { value ->
                        result = value
                        evaluated.countDown()
                    }
                }
                assertTrue("Selection evaluation did not complete", evaluated.await(10, TimeUnit.SECONDS))
            } finally {
                scenario.onActivity {
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.destroy()
                }
            }
        }
        return JSONObject(requireNotNull(result))
    }
}
