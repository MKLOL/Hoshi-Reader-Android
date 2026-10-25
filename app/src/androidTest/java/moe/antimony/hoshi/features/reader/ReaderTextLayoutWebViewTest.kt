package moe.antimony.hoshi.features.reader

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderTextLayoutWebViewTest {
    @Test
    fun fittingSentencesStayOnOnePageAndPreserveRubyWordsAndInlineMarkupInBothDirections() {
        for (vertical in listOf(false, true)) {
            val result = render(ReaderSettings(verticalWriting = vertical, fontSize = 20), regularFixture())
            assertPreserved(result)
            assertPartialRubyWord(result)
            val sentences = result.getJSONArray("sentences")
            assertTrue("fixture must exercise several pages: $result", result.getInt("lastPage") > 0)
            for (index in 0 until sentences.length()) {
                val sentence = sentences.getJSONObject(index)
                assertFalse("a fitting sentence was treated as oversized: $sentence", sentence.getBoolean("oversized"))
                assertEquals("a fitting sentence crosses a page: $sentence", sentence.getInt("first"), sentence.getInt("last"))
            }
            assertEquals("short consecutive sentences should share a page", sentences.getJSONObject(0).getInt("first"), sentences.getJSONObject(1).getInt("first"))
            assertEquals("#end", result.getString("href"))
            assertEquals(1, result.getInt("linkIdCount"))
            assertTrue("inline emphasis survives wrapping", result.getBoolean("emphasisPreserved"))
        }
    }

    @Test
    fun onlyASentenceLargerThanAPageUsesOrdinaryContinuation() {
        val oversized = "とても長い文章の途中でも意味のある言葉を守ります、".repeat(60) + "おしまい。"
        for (vertical in listOf(false, true)) {
            val result = render(
                ReaderSettings(verticalWriting = vertical, fontSize = 20),
                "<p>短文です。次です。$oversized</p><p>最後の短文です。</p>",
            )
            assertPreserved(result)
            val sentences = result.getJSONArray("sentences")
            var continued = 0
            for (index in 0 until sentences.length()) {
                val sentence = sentences.getJSONObject(index)
                if (sentence.getBoolean("oversized")) {
                    continued++
                    assertEquals(oversized, sentence.getString("text"))
                    assertTrue("oversized sentence must remain accessible across pages", sentence.getInt("last") > sentence.getInt("first"))
                    assertFalse("oversized continuation must not insert a blank starting page", sentence.getBoolean("forcedPageBreak"))
                } else {
                    assertEquals("short sentence crosses a page: $sentence", sentence.getInt("first"), sentence.getInt("last"))
                }
            }
            assertEquals(1, continued)
        }
    }

    @Test
    fun largeFontAndHiddenFuriganaPreserveWordsAndCountsInPagedAndContinuousModes() {
        for (continuous in listOf(false, true)) for (vertical in listOf(false, true)) {
            val result = render(
                ReaderSettings(verticalWriting = vertical, continuousMode = continuous, fontSize = 42, hideFurigana = true),
                regularFixture(compactInline = true),
            )
            assertPreserved(result)
            assertPartialRubyWord(result)
            assertTrue(result.getBoolean("allWordsNoWrap"))
            assertTrue(result.getBoolean("allReadingsHidden"))
            if (continuous) {
                assertEquals(0, result.getJSONArray("sentences").length())
                assertEquals(0, result.getInt("pageMarkerCount"))
            } else {
                val sentences = result.getJSONArray("sentences")
                for (index in 0 until sentences.length()) {
                    val sentence = sentences.getJSONObject(index)
                    assertFalse("short large-font sentence incorrectly oversized: $sentence", sentence.getBoolean("oversized"))
                    assertEquals(sentence.toString(), sentence.getInt("first"), sentence.getInt("last"))
                }
            }
        }
    }

    @Test
    fun twentyThousandCharacterChapterInitializesWithoutLosingTextOrFittingBoundaries() {
        val content = regularFixture() + "<p>" + "これは大丈夫な文章です。別の短い文章です。".repeat(1_200) + "</p>"
        val result = render(ReaderSettings(verticalWriting = false, fontSize = 20), content)
        assertPreserved(result)
        assertTrue("long fixture must contain 20k counted characters: " + result.getInt("beforeCount"), result.getInt("beforeCount") >= 20_000)
        val sentences = result.getJSONArray("sentences")
        assertTrue(sentences.length() >= 2_000)
        for (index in 0 until sentences.length()) {
            val sentence = sentences.getJSONObject(index)
            assertFalse(sentence.toString(), sentence.getBoolean("oversized"))
            assertEquals(sentence.toString(), sentence.getInt("first"), sentence.getInt("last"))
        }
    }

    private fun regularFixture(compactInline: Boolean = false): String =
        "<p id='opening'>大丈夫です。次です。</p>" +
            "<p id='partial'>ああああ<ruby id='partial-ruby'>大<rt>だい</rt></ruby><em>丈夫</em>ですよ。</p>" +
            (if (compactInline) "<p id='inline'><a id='source-link' href='#end'>東京の<em>大丈夫</em>です</a>。</p>"
             else "<p id='inline'>これは<a id='source-link' href='#end'>東京の<em>大丈夫</em>な<ruby>不思議な夢<rt>ふしぎなゆめ</rt></ruby></a>を見る文章です。</p>") +
            (1..24).joinToString("") { "<p>これは<ruby>大丈夫<rt>だいじょうぶ</rt></ruby>な文章です。別の短い文章です。</p>" } +
            "<h2 id='end'>終わりの題名</h2><p>おしまいです。</p>"

    private fun assertPreserved(result: JSONObject) {
        assertEquals("WebView errors: $result", "[]", result.getJSONArray("errors").toString())
        assertTrue("layout reported changed source text: $result", result.getBoolean("textPreserved"))
        assertEquals(result.getString("beforeText"), result.getString("afterText"))
        assertEquals(result.getJSONArray("beforeParagraphs").toString(), result.getJSONArray("afterParagraphs").toString())
        assertEquals(result.getJSONArray("beforeReadings").toString(), result.getJSONArray("afterReadings").toString())
        assertEquals(result.getInt("beforeCount"), result.getInt("afterCount"))
        assertEquals(result.getInt("beforeCount"), result.getInt("readerCount"))
    }

    private fun assertPartialRubyWord(result: JSONObject) {
        assertEquals("大丈夫", result.getString("partialWord"))
        assertEquals("だい", result.getString("partialReading"))
        assertEquals(1, result.getInt("partialRubyCount"))
        assertTrue(result.getBoolean("partialEmphasisPreserved"))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun render(settings: ReaderSettings, content: String): JSONObject {
        val loaded = CountDownLatch(1)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var webView: WebView
            scenario.onActivity { activity ->
                val density = activity.resources.displayMetrics.density
                val parent = FrameLayout(activity)
                webView = WebView(activity).apply {
                    this.settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
                    }
                }
                parent.addView(webView, FrameLayout.LayoutParams((360 * density).toInt(), (300 * density).toInt()))
                activity.setContentView(parent)
                webView.loadDataWithBaseURL(
                    "https://hoshi.local/epub/layout-test.xhtml",
                    """<!doctype html><html lang="ja"><head><meta name="viewport" content="width=device-width,initial-scale=1">
                    ${ReaderContentStyles.styleTag(settings)}
                    <script>$snapshotScript</script>${ReaderPaginationScripts.shellScript(settings = settings)}
                    </head><body>$content</body></html>""",
                    "text/html", "utf-8", null,
                )
            }
            try {
                assertTrue("reader page did not load", loaded.await(15, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (System.nanoTime() < deadline) {
                    if (evaluate(scenario, webView, "JSON.stringify({ready:!!window.hoshiTextLayout?.prepared})").optBoolean("ready")) {
                        return evaluate(scenario, webView, inspectionScript)
                    }
                    Thread.sleep(25)
                }
                throw AssertionError("reader layout did not initialize")
            } finally {
                scenario.onActivity { (webView.parent as? ViewGroup)?.removeView(webView); webView.destroy() }
            }
        }
    }

    private fun evaluate(scenario: ActivityScenario<ComponentActivity>, webView: WebView, script: String): JSONObject {
        val done = CountDownLatch(1)
        var value: String? = null
        scenario.onActivity { webView.evaluateJavascript(script) { value = it; done.countDown() } }
        assertTrue("WebView evaluation timed out", done.await(10, TimeUnit.SECONDS))
        val decoded = JSONTokener(requireNotNull(value)).nextValue()
        return JSONObject(decoded as String)
    }

    private val snapshotScript = """
        window.__layoutErrors = [];
        window.addEventListener('error', function(e) { window.__layoutErrors.push(e.message); });
        window.__baseText = function(root) {
          var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
            acceptNode: function(node) { return node.parentElement?.closest('rt,rp,script,style') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT; }
          });
          var text = '', node;
          while ((node = walker.nextNode())) text += node.nodeValue;
          return text;
        };
        document.addEventListener('DOMContentLoaded', function() {
          var text = window.__baseText(document.body);
          window.__beforeLayout = { text: text,
            paragraphs: Array.from(document.querySelectorAll('p')).map(window.__baseText),
            readings: Array.from(document.querySelectorAll('rt')).map(function(rt) { return rt.textContent; }),
            count: window.hoshiReader.countChars(text) };
        });
    """.trimIndent()

    private val inspectionScript = """
        (function() {
          var before = window.__beforeLayout, reader = window.hoshiReader;
          var afterText = window.__baseText(document.body);
          var context = reader.getScrollContext ? reader.getScrollContext() : null;
          var sentences = (window.hoshiTextLayout.sentences || []).map(function(sentence) {
            var range = document.createRange(); range.setStartAfter(sentence.start); range.setEndBefore(sentence.end);
            var first = Infinity, last = -Infinity, scroll = context.vertical ? context.scrollEl.scrollTop : context.scrollEl.scrollLeft;
            Array.from(range.getClientRects()).forEach(function(rect) {
              if (!rect.width || !rect.height) return;
              var start = (context.vertical ? rect.top : rect.left) + scroll;
              var end = (context.vertical ? rect.bottom : rect.right) + scroll;
              first = Math.min(first, Math.floor((start + .5) / context.pageSize));
              last = Math.max(last, Math.floor((end - .5) / context.pageSize));
            });
            return {text:sentence.text, first:first, last:last, oversized:sentence.start.dataset.oversized === 'true', forcedPageBreak:sentence.start.classList.contains('hoshi-sentence-page-break')};
          });
          var walker = reader.createWalker(), counted = 0, node;
          while ((node = walker.nextNode())) counted += reader.countChars(node.textContent);
          var partial = document.getElementById('partial-ruby'), partialWord = partial?.closest('.hoshi-word');
          return JSON.stringify({
            errors:window.__layoutErrors, textPreserved:window.hoshiTextLayout.textPreserved,
            beforeText:before.text, afterText:afterText,
            beforeParagraphs:before.paragraphs, afterParagraphs:Array.from(document.querySelectorAll('p')).map(window.__baseText),
            beforeReadings:before.readings, afterReadings:Array.from(document.querySelectorAll('rt')).map(function(rt) { return rt.textContent; }),
            beforeCount:before.count, afterCount:reader.countChars(afterText), readerCount:counted,
            partialWord:partialWord ? window.__baseText(partialWord) : '', partialReading:partial?.querySelector('rt')?.textContent || '',
            partialRubyCount:document.querySelectorAll('#partial-ruby').length,
            partialEmphasisPreserved:!!partialWord?.querySelector('em'),
            href:document.getElementById('source-link')?.getAttribute('href') || '', linkIdCount:document.querySelectorAll('#source-link').length,
            emphasisPreserved:!!document.querySelector('#inline em'),
            allWordsNoWrap:Array.from(document.querySelectorAll('.hoshi-word')).every(function(word) { return getComputedStyle(word).whiteSpace === 'nowrap'; }),
            allReadingsHidden:Array.from(document.querySelectorAll('rt')).every(function(rt) { return getComputedStyle(rt).display === 'none'; }),
            pageMarkerCount:document.querySelectorAll('.hoshi-sentence-start,.hoshi-sentence-end').length,
            sentences:sentences, lastPage:Math.max(0,...sentences.map(function(s) { return s.last; }))
          });
        })();
    """.trimIndent()
}
