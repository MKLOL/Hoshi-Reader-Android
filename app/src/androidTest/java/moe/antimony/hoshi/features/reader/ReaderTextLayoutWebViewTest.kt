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
    fun aFollowingIllustrationDoesNotMakeAFittingSentenceContinueAcrossPages() {
        val illustration = "<img style='display:block' width='300' height='260' " +
            "src='data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=='>"
        val content = (120..210 step 10).joinToString("") { prefixLength ->
            "<p>${"あ".repeat(prefixLength)}。" +
                "<span data-layout-target='target-$prefixLength'>${"い".repeat(40)}。</span>" +
                illustration + "次です。</p>"
        }
        for (vertical in listOf(false, true)) {
            val result = render(ReaderSettings(verticalWriting = vertical, fontSize = 20), content)
            assertPreserved(result)
            val targets = result.getJSONArray("targetGlyphPages")
            assertEquals(10, targets.length())
            for (index in 0 until targets.length()) {
                val target = targets.getJSONObject(index)
                assertEquals("a short sentence's actual characters cross a page: $target", 1, target.getJSONArray("pages").length())
            }
        }
    }

    @Test
    fun authoredHardBreaksAndBlockSpansRetainTheirLineFlow() {
        val content = "<p data-layout-flow='br'>これは<ruby>大<rt>だい</rt></ruby><br>丈夫です。</p>" +
            "<p data-layout-flow='block'><span style='display:block'>大</span><span style='display:block'>丈夫</span>です。</p>"
        for (continuous in listOf(false, true)) for (vertical in listOf(false, true)) {
            val result = render(ReaderSettings(verticalWriting = vertical, continuousMode = continuous, fontSize = 20), content)
            assertPreserved(result)
            assertEquals("a wrapping atom must not enclose an authored hard break", 0, result.getInt("wordsWithHardBreaks"))
            val before = result.getJSONArray("beforeLineFlows")
            val after = result.getJSONArray("afterLineFlows")
            assertEquals(before.length(), after.length())
            for (index in 0 until before.length()) {
                val source = before.getJSONArray(index)
                val laidOut = after.getJSONArray(index)
                assertEquals(source.length(), laidOut.length())
                val inlineAxis = if (vertical) "y" else "x"
                val blockAxis = if (vertical) "x" else "y"
                for (character in 0 until source.length()) {
                    // A ruby inline box can increase the line's ascent. Its characters must
                    // still retain the authored position along each line and line membership.
                    assertEquals(source.getJSONObject(character).getDouble(inlineAxis), laidOut.getJSONObject(character).getDouble(inlineAxis), 1.0)
                    if (character > 0) {
                        val wasSameLine = kotlin.math.abs(source.getJSONObject(character).getDouble(blockAxis) - source.getJSONObject(character - 1).getDouble(blockAxis)) < 1.0
                        val isSameLine = kotlin.math.abs(laidOut.getJSONObject(character).getDouble(blockAxis) - laidOut.getJSONObject(character - 1).getDouble(blockAxis)) < 1.0
                        assertEquals("authored line membership changed: $result", wasSameLine, isSameLine)
                    }
                }
            }
        }
    }

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

    @Test
    fun wrappedWordsKeepLineStartRulesAndInlineDecorationsInBothDirectionsAndModes() {
        val content = "<p><span style='text-decoration:underline'>これは大丈夫な文章です</span>。</p>" +
            (0 until 36).joinToString("") { index ->
                "<p>" + "あ".repeat(index % 13) + (1..6).joinToString("") { "東京。大阪、「学校」と言葉ー" } + "</p>"
            }
        for (continuous in listOf(false, true)) for (vertical in listOf(false, true)) {
            val result = render(ReaderSettings(verticalWriting = vertical, continuousMode = continuous, fontSize = 20), content)
            assertPreserved(result)
            assertEquals("closing punctuation starts a line: $result", "[]", result.getJSONArray("lineStartPunctuation").toString())
            // Decorations such as underlines do not reach into atomic inline boxes.
            assertTrue("a wrapped word must remain an inline box", result.getBoolean("allWordsInline"))
        }
    }

    @Test
    fun aSentenceOpeningItsParagraphBreaksBeforeTheParagraphAndKeepsItsIndent() {
        val content = "<style>p { text-indent: 1em; margin: 0; }</style>" +
            (0 until 30).joinToString("") { index -> "<p>${"あ".repeat(40 + index * 7 % 50)}。</p>" }
        for (vertical in listOf(false, true)) {
            val result = render(ReaderSettings(verticalWriting = vertical, fontSize = 20), content)
            assertPreserved(result)
            val indents = result.getJSONArray("blockBreakIndents")
            assertTrue("one-sentence paragraphs must need paragraph breaks: $result", indents.length() > 0)
            assertEquals("a block marker must not open a paragraph", 0, result.getInt("markerBreaksOpeningBlocks"))
            for (index in 0 until indents.length()) {
                val indent = indents.getJSONObject(index)
                assertEquals("paragraph indent after a page break: $indent", indent.getDouble("expected"), indent.getDouble("actual"), 1.0)
            }
            val sentences = result.getJSONArray("sentences")
            for (index in 0 until sentences.length()) {
                val sentence = sentences.getJSONObject(index)
                assertEquals("a fitting sentence crosses a page: $sentence", sentence.getInt("first"), sentence.getInt("last"))
            }
        }
    }

    @Test
    fun sentenceBreaksDoNotLeaveNearlyEmptyPages() {
        val lengths = listOf(9, 31, 14, 47, 22, 6, 38, 17, 26, 11, 43, 19)
        val content = (0 until 70).joinToString("") { paragraph ->
            "<p>" + (0..paragraph % 5).joinToString("") { sentence ->
                "あ".repeat(lengths[(paragraph * 3 + sentence) % lengths.size]) + "<ruby>大丈夫<rt>だいじょうぶ</rt></ruby>です。"
            } + "</p>"
        }
        for (vertical in listOf(false, true)) {
            val result = render(ReaderSettings(verticalWriting = vertical, fontSize = 20), content)
            assertPreserved(result)
            val sentences = result.getJSONArray("sentences")
            for (index in 0 until sentences.length()) {
                val sentence = sentences.getJSONObject(index)
                assertEquals("a fitting sentence crosses a page: $sentence", sentence.getInt("first"), sentence.getInt("last"))
            }
            val counts = result.getJSONArray("pageGlyphCounts")
            val full = (0 until counts.length() - 1).map { counts.getInt(it) }
            assertTrue("fixture must exercise several pages: $result", full.size > 4)
            // Moving one whole sentence can shorten a page, but never down to a fragment.
            val median = full.sorted()[full.size / 2]
            assertTrue("a nearly empty page remains: $full", full.min() * 10 >= median * 4)
        }
    }

    @Test
    fun rubyWordsInOneLongParagraphStayWholeAndKeepTheirIds() {
        val content = "<div>" + (1..1_500).joinToString("") {
            "これは<ruby>漢字<rt>かんじ</rt></ruby>の<ruby id='study-$it'>勉強<rt>べんきょう</rt></ruby>です。"
        } + "</div>"
        for (vertical in listOf(false, true)) {
            val result = render(ReaderSettings(verticalWriting = vertical, fontSize = 20), content)
            assertPreserved(result)
            assertEquals(3_000, result.getInt("rubyCount"))
            assertEquals("every ruby stays inside one word box", 0, result.getInt("rubiesOutsideWords"))
            assertEquals(1_500, result.getInt("studyIdCount"))
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
        window.__baseGlyphs = function(root) {
          var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
            acceptNode: function(node) { return node.parentElement?.closest('rt,rp,script,style') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT; }
          });
          var result = [], node;
          while ((node = walker.nextNode())) for (var offset = 0; offset < node.length;) {
            var char = String.fromCodePoint(node.data.codePointAt(offset)), range = document.createRange();
            range.setStart(node, offset); range.setEnd(node, offset + char.length); offset += char.length;
            var rect = range.getBoundingClientRect(); result.push({text:char, x:rect.x, y:rect.y});
          }
          return result;
        };
        document.addEventListener('DOMContentLoaded', function() {
          var text = window.__baseText(document.body);
          window.__beforeLayout = { text: text,
            paragraphs: Array.from(document.querySelectorAll('p')).map(window.__baseText),
            readings: Array.from(document.querySelectorAll('rt')).map(function(rt) { return rt.textContent; }),
            lineFlows: Array.from(document.querySelectorAll('[data-layout-flow]')).map(window.__baseGlyphs),
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
            return {text:sentence.text, first:first, last:last, oversized:sentence.start.dataset.oversized === 'true', forcedPageBreak:sentence.breakTarget.classList.contains(sentence.breakClass)};
          });
          var walker = reader.createWalker(), counted = 0, node;
          while ((node = walker.nextNode())) counted += reader.countChars(node.textContent);
          var partial = document.getElementById('partial-ruby'), partialWord = partial?.closest('.hoshi-word');
          var targetGlyphPages = Array.from(document.querySelectorAll('[data-layout-target]')).map(function(target) {
            var glyphs = window.__baseGlyphs(target);
            var pages = glyphs.map(function(glyph) {
              return Math.floor(((context.vertical ? glyph.y : glyph.x) + .5) / context.pageSize);
            });
            var start = target.querySelector('.hoshi-sentence-start');
            return {target:target.dataset.layoutTarget, pages:Array.from(new Set(pages)), glyphs:glyphs,
              html:target.outerHTML, startClass:start?.className, oversized:start?.dataset.oversized,
              vertical:context.vertical, pageSize:context.pageSize};
          });
          var vertical = getComputedStyle(document.body).writingMode.indexOf('vertical') === 0;
          var glyphs = window.__baseGlyphs(document.body).filter(function(glyph) { return glyph.text.trim(); });
          function pageOf(glyph) { return context ? Math.floor(((vertical ? glyph.y : glyph.x) + .5) / context.pageSize) : 0; }
          var lineStartPunctuation = [], previousLine = null;
          glyphs.forEach(function(glyph, index) {
            var line = Math.round(vertical ? glyph.x : glyph.y) + ':' + pageOf(glyph);
            if (line !== previousLine && '。、」』）！？ー'.indexOf(glyph.text) >= 0) {
              lineStartPunctuation.push(glyphs.slice(Math.max(0, index - 3), index + 2).map(function(g) { return g.text; }).join(''));
            }
            previousLine = line;
          });
          var pageGlyphCounts = [];
          if (context) glyphs.forEach(function(glyph) { var page = pageOf(glyph); pageGlyphCounts[page] = (pageGlyphCounts[page] || 0) + 1; });
          var blockBreakIndents = Array.from(document.querySelectorAll('.hoshi-sentence-block-break')).map(function(block) {
            var glyph = window.__baseGlyphs(block).filter(function(g) { return g.text.trim(); })[0];
            var rect = block.getBoundingClientRect(), style = getComputedStyle(block);
            var origin = vertical ? rect.top + parseFloat(style.paddingTop) + parseFloat(style.borderTopWidth)
              : rect.left + parseFloat(style.paddingLeft) + parseFloat(style.borderLeftWidth);
            return {expected:parseFloat(style.textIndent) || 0, actual:glyph ? (vertical ? glyph.y : glyph.x) - origin : -1, html:block.outerHTML.slice(0, 120)};
          });
          var markerBreaksOpeningBlocks = Array.from(document.querySelectorAll('.hoshi-sentence-page-break')).filter(function(marker) {
            var block = marker.parentElement.closest('p,div,li,dd,dt,blockquote,figcaption,h1,h2,h3,h4,h5,h6,section,article');
            if (!block) return false;
            var range = document.createRange(); range.setStart(block, 0); range.setEndBefore(marker);
            return !range.toString().trim();
          }).length;
          return JSON.stringify({
            errors:window.__layoutErrors, textPreserved:window.hoshiTextLayout.textPreserved,
            lineStartPunctuation:lineStartPunctuation, pageGlyphCounts:pageGlyphCounts.map(function(count) { return count || 0; }),
            blockBreakIndents:blockBreakIndents, markerBreaksOpeningBlocks:markerBreaksOpeningBlocks,
            allWordsInline:Array.from(document.querySelectorAll('.hoshi-word')).every(function(word) { return getComputedStyle(word).display === 'inline'; }),
            rubyCount:document.querySelectorAll('ruby').length,
            rubiesOutsideWords:Array.from(document.querySelectorAll('ruby')).filter(function(ruby) { return !ruby.closest('.hoshi-word'); }).length,
            studyIdCount:document.querySelectorAll('[id^="study-"]').length,
            beforeText:before.text, afterText:afterText,
            beforeParagraphs:before.paragraphs, afterParagraphs:Array.from(document.querySelectorAll('p')).map(window.__baseText),
            beforeReadings:before.readings, afterReadings:Array.from(document.querySelectorAll('rt')).map(function(rt) { return rt.textContent; }),
            beforeLineFlows:before.lineFlows, afterLineFlows:Array.from(document.querySelectorAll('[data-layout-flow]')).map(window.__baseGlyphs),
            wordsWithHardBreaks:document.querySelectorAll('.hoshi-word br,.hoshi-word hr,.hoshi-word [style*="display:block"]').length,
            targetGlyphPages:targetGlyphPages,
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
