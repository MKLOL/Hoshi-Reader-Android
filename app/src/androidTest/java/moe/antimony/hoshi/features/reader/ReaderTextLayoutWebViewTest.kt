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
        val content = (0 until 30).joinToString("") { index -> "<p>${"あ".repeat(40 + index * 7 % 50)}。</p>" }
        for (vertical in listOf(false, true)) {
            val result = render(ReaderSettings(verticalWriting = vertical, fontSize = 20), content, css = "p { text-indent: 1em; margin: 0; }")
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

    @Test
    fun hangingIndentsSettleWithoutTreatingParagraphsAsOversized() {
        val paragraphs = (0 until 60).joinToString("") { index ->
            "<p>「" + "東京の学校です。".repeat(listOf(1, 9, 3, 14, 2, 6)[index % 6]) + "」</p>"
        }
        // A hanging indent written for the other writing direction leaves each paragraph's first
        // glyph outside the column, in the gap before it.
        for ((vertical, inset) in listOf(true to "padding-left", false to "padding-top")) {
            val result = render(
                ReaderSettings(verticalWriting = vertical, fontSize = 20),
                paragraphs,
                css = "p { text-indent: -1em; $inset: 1em; }",
            )
            assertPreserved(result)
            assertTrue("the estimate must settle without the page-by-page fallback: $result", result.getInt("passes") < 12)
            val sentences = result.getJSONArray("sentences")
            for (index in 0 until sentences.length()) {
                assertFalse("a fitting sentence was treated as oversized", sentences.getJSONObject(index).getBoolean("oversized"))
            }
        }
    }

    @Test
    fun noForcedBreakLeavesANearlyEmptyPageItsSentenceCouldShare() {
        val lengths = listOf(9, 31, 14, 47, 22, 6, 38, 17, 26, 11, 43, 19)
        val content = (0 until 120).joinToString("") { paragraph ->
            "<p>" + (0..paragraph % 6).joinToString("") { sentence ->
                "東京".repeat(lengths[(paragraph * 5 + sentence) % lengths.size] / 4) +
                    "あ".repeat(lengths[(paragraph + sentence * 7) % lengths.size] / 2) + "<ruby>魔法<rt>まほう</rt></ruby>。"
            } + "</p>"
        }
        for (fontSize in listOf(20, 26)) for (vertical in listOf(false, true)) {
            val result = render(ReaderSettings(verticalWriting = vertical, fontSize = fontSize), content)
            assertPreserved(result)
            assertEquals("a forced break leaves a short page its sentence fits on: $result", "[]", result.getJSONArray("staleBreaks").toString())
        }
    }

    @Test
    fun anOpeningBracketStartsTheSentenceItOpensAsInSentenceMode() {
        val content = (0 until 40).joinToString("") { index ->
            "<p>" + "あ".repeat(10 + index * 7 % 30) + "いた。「" + "猫目アメ".repeat(1 + index % 3) + "」、と書いてある。『" +
                "う".repeat(5 + index % 9) + "』（" + "え".repeat(3 + index % 4) + "）。</p>"
        }
        for (vertical in listOf(false, true)) {
            val result = render(ReaderSettings(verticalWriting = vertical, fontSize = 20), content)
            assertPreserved(result)
            val sentences = result.getJSONArray("sentences")
            for (index in 0 until sentences.length()) {
                val text = sentences.getJSONObject(index).getString("text").trim()
                assertFalse("a sentence ends with an opening bracket: $text", text.last() in "「『（【〈《〔［｛“‘(")
                assertTrue("a sentence ends at a terminator and its closing brackets: $text", text.last() in "。！？!?」』）)】〉》〕｝}］]、…‥")
            }
        }
    }

    @Test
    fun aForcedBreakNeverLeavesAPageHoldingOnlyAnEmptySeparator() {
        for (separator in listOf("<p><br/></p>", "<p>　</p>", "<p></p>")) {
            val content = (0 until 40).joinToString("") { index -> separator + "<p>" + "あ".repeat(50 + index * 13 % 70) + "。</p>" }
            for (vertical in listOf(false, true)) {
                val result = render(ReaderSettings(verticalWriting = vertical, fontSize = 20), content)
                assertPreserved(result)
                val counts = result.getJSONArray("pageGlyphCounts")
                for (page in 0 until counts.length()) {
                    assertTrue("page $page holds no text: $separator $result", counts.getInt(page) > 0)
                }
            }
        }
    }

    @Test
    fun aBreakInsideAPublisherSpanLeavesTheTextBeforeItOnThePreviousPage() {
        val lengths = listOf(9, 31, 14, 47, 22, 6, 38, 17, 26, 11, 43, 19)
        fun content(open: String) = (0 until 60).joinToString("") { paragraph ->
            "<p><span class='koboSpan' id='kobo.$paragraph.1'>" + "う".repeat(5 + paragraph % 7) + "。</span>" + open +
                (0..paragraph % 4).joinToString("") { sentence -> "あ".repeat(lengths[(paragraph * 3 + sentence) % lengths.size]) + "大丈夫です。" } +
                "</span></p>"
        }
        for (vertical in listOf(false, true)) {
            val plain = render(ReaderSettings(verticalWriting = vertical, fontSize = 20), content("<span class='koboSpan'>"))
            assertPreserved(plain)
            val sentences = plain.getJSONArray("sentences")
            var inside = 0
            for (index in 1 until sentences.length()) {
                val sentence = sentences.getJSONObject(index)
                if (!sentence.getBoolean("forcedPageBreak") || !sentence.getBoolean("insideInline")) continue
                inside++
                assertTrue("a break inside a span moved the text before it: $sentence",
                    sentences.getJSONObject(index - 1).getInt("last") < sentence.getInt("first"))
            }
            assertTrue("no break fell inside a span: $plain", inside > 0)

            val decorated = render(ReaderSettings(verticalWriting = vertical, fontSize = 20),
                content("<span data-keep-box style='text-decoration: underline; background: #eee'>"))
            assertPreserved(decorated)
            assertTrue("a span that draws its own box gave it up: $decorated", decorated.getBoolean("keptBoxes"))
        }
    }

    @Test
    fun rubyInAParagraphsFirstLineStaysWithItsTextAndLeavesNoNearlyEmptyPage() {
        // WebView 133 lays out such a line taller but places it as if it were not, so it can
        // overhang a page or leave its ruby on the page before.
        val lengths = listOf(9, 31, 14, 47, 22, 6, 38, 17, 26, 11, 43, 19)
        val content = (0 until 120).joinToString("") { paragraph ->
            "<p>" + "う".repeat(2 + paragraph % 5) + "。<ruby>魔法<rt>まほう</rt></ruby>" + "あ".repeat(lengths[paragraph % lengths.size]) + "。" +
                "東京".repeat(lengths[(paragraph * 7) % lengths.size] / 3) + "です。</p>"
        }
        for (fontSize in listOf(20, 26)) for (vertical in listOf(false, true)) {
            val result = render(ReaderSettings(verticalWriting = vertical, fontSize = fontSize), content)
            assertPreserved(result)
            assertEquals("ruby shown on another page than its text: $result", "[]", result.getJSONArray("splitRubies").toString())
            assertTrue("breaks did not settle: $result", result.getInt("passes") < 12)
            val sentences = result.getJSONArray("sentences")
            for (index in 0 until sentences.length()) {
                val sentence = sentences.getJSONObject(index)
                assertEquals("a fitting sentence crosses a page: $sentence", sentence.getInt("first"), sentence.getInt("last"))
            }
            val counts = result.getJSONArray("pageGlyphCounts")
            val full = (0 until counts.length() - 1).map { counts.getInt(it) }.sorted()
            for (page in 0 until counts.length() - 1) {
                assertTrue("page $page is nearly empty: $result", counts.getInt(page) * 3 >= full[full.size / 2])
            }
        }
    }

    @Test
    fun translationButtonsArrivingAfterLayoutPlaceTheBreaksAgainAtTheSamePosition() {
        val lengths = listOf(9, 31, 14, 47, 22, 6, 38, 17, 26, 11, 43, 19)
        val buttonCss = ".hoshi-tl { display: inline-block; width: .6em; height: .6em; margin-inline-end: .45em; }"
        val content = (0 until 70).joinToString("") { paragraph ->
            "<p>" + (0..paragraph % 5).joinToString("") { sentence ->
                "あ".repeat(lengths[(paragraph * 3 + sentence) % lengths.size]) + "大丈夫です。"
            } + "</p>"
        }
        val lateButtons = """
            (function() {
              var reader = window.hoshiReader, context = reader.getScrollContext();
              // The page holding a character, counted the way reading positions are.
              function pageOfCharacter(count) {
                var walker = reader.createWalker(), node, running = 0;
                while ((node = walker.nextNode())) {
                  var length = reader.countChars(node.textContent);
                  if (running + length > count) {
                    var offset = reader.textOffsetForCharCount(node, count - running), range = document.createRange();
                    range.setStart(node, offset); range.setEnd(node, offset + 1);
                    var rect = range.getBoundingClientRect(), scroll = reader.getPagePosition(reader.getScrollContext());
                    return Math.floor(((context.vertical ? rect.top + rect.bottom : rect.left + rect.right) / 2 + scroll) / context.pageSize);
                  }
                  running += length;
                }
                return -1;
              }
              function total() {
                var walker = reader.createWalker(), node, count = 0;
                while ((node = walker.nextNode())) count += reader.countChars(node.textContent);
                return count;
              }
              reader.setPagePosition(context, 8 * context.pageSize);
              var opening = Math.round(reader.calculateProgress() * total());
              window.hoshiTextLayout.sentences.forEach(function(sentence, index) {
                var button = document.createElement('span'); button.className = 'hoshi-tl'; button.dataset.tl = 'tl-' + index;
                sentence.end.before(button);
              });
              reader.buildNodeOffsets();
              var refreshed = window.hoshiTextLayout.refresh(reader);
              var again = window.hoshiTextLayout.refresh(reader);
              var shown = Math.round(reader.getPagePosition(reader.getScrollContext()) / context.pageSize);
              var holding = pageOfCharacter(opening);
              reader.setPagePosition(reader.getScrollContext(), 0);
              return JSON.stringify({refreshed: refreshed, again: again, shown: shown, holding: holding});
            })();
        """.trimIndent()
        for (vertical in listOf(false, true)) {
            val result = render(ReaderSettings(verticalWriting = vertical, fontSize = 20), content, lateButtons, buttonCss)
            assertPreserved(result)
            val refresh = result.getJSONObject("beforeInspect")
            assertTrue("late buttons must place the breaks again: $refresh", refresh.getBoolean("refreshed"))
            assertFalse("unchanged buttons must not lay out again", refresh.getBoolean("again"))
            assertEquals("the page shown holds the character that opened the page before: $refresh", refresh.getInt("holding"), refresh.getInt("shown"))
            val sentences = result.getJSONArray("sentences")
            for (index in 0 until sentences.length()) {
                val sentence = sentences.getJSONObject(index)
                assertEquals("a fitting sentence crosses a page after late buttons: $sentence", sentence.getInt("first"), sentence.getInt("last"))
            }
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
    // Publisher CSS goes into the head: the reader counts every text node in the body.
    private fun render(settings: ReaderSettings, content: String, beforeInspect: String? = null, css: String = ""): JSONObject {
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
                    ${ReaderContentStyles.styleTag(settings)}<style>$css</style>
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
                        val prepared = beforeInspect?.let { evaluate(scenario, webView, it) }
                        return evaluate(scenario, webView, inspectionScript).apply {
                            if (prepared != null) put("beforeInspect", prepared)
                        }
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
            return {text:sentence.text, first:first, last:last, oversized:sentence.start.dataset.oversized === 'true', forcedPageBreak:sentence.breakTarget.classList.contains(sentence.breakClass),
              insideInline:sentence.breakTarget === sentence.start && sentence.start.parentElement !== sentence.block};
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
          // A page without glyphs stays in the list as 0, so a blank page is visible.
          for (var page = 0; page < pageGlyphCounts.length; page++) pageGlyphCounts[page] = pageGlyphCounts[page] || 0;
          var blockBreakIndents = Array.from(document.querySelectorAll('.hoshi-sentence-block-break')).map(function(block) {
            var glyph = window.__baseGlyphs(block).filter(function(g) { return g.text.trim(); })[0];
            var rect = block.getBoundingClientRect(), style = getComputedStyle(block);
            var origin = vertical ? rect.top + parseFloat(style.paddingTop) + parseFloat(style.borderTopWidth)
              : rect.left + parseFloat(style.paddingLeft) + parseFloat(style.borderLeftWidth);
            return {expected:parseFloat(style.textIndent) || 0, actual:glyph ? (vertical ? glyph.y : glyph.x) - origin : -1, html:block.outerHTML.slice(0, 120)};
          });
          // A page holding under a third of the usual glyphs must not end at a forced break whose
          // sentence would fit on it.
          var staleBreaks = [];
          if (context) {
            var allSentences = window.hoshiTextLayout.sentences || [];
            var sentencePages = function(sentence) {
              var range = document.createRange(); range.setStart(sentence.start, 0); range.setEnd(sentence.end, 0);
              var first = Infinity, last = -Infinity;
              Array.from(range.getClientRects()).forEach(function(rect) {
                if (!rect.width || !rect.height) return;
                first = Math.min(first, Math.floor(((vertical ? rect.top : rect.left) + .5) / context.pageSize));
                last = Math.max(last, Math.floor(((vertical ? rect.bottom : rect.right) - .5) / context.pageSize));
              });
              return [first, last];
            };
            var fullPages = pageGlyphCounts.slice(0, -1).sort(function(a, b) { return a - b; });
            var usual = fullPages.length ? fullPages[fullPages.length >> 1] : 0;
            pageGlyphCounts.forEach(function(count, page) {
              if (page >= pageGlyphCounts.length - 1 || (count || 0) * 3 >= usual) return;
              var opener = allSentences.find(function(sentence) { return sentencePages(sentence)[0] === page + 1; });
              if (!opener || !opener.breakTarget.classList.contains(opener.breakClass)) return;
              opener.breakTarget.classList.remove(opener.breakClass);
              var joined = sentencePages(opener);
              opener.breakTarget.classList.add(opener.breakClass);
              if (joined[0] === page && joined[1] === page) staleBreaks.push({page: page, glyphs: count, usual: usual, text: opener.text});
            });
          }
          var markerBreaksOpeningBlocks = Array.from(document.querySelectorAll('.hoshi-sentence-page-break')).filter(function(marker) {
            var block = marker.parentElement.closest('p,div,li,dd,dt,blockquote,figcaption,h1,h2,h3,h4,h5,h6,section,article');
            if (!block) return false;
            var range = document.createRange(); range.setStart(block, 0); range.setEndBefore(marker);
            return !range.toString().trim();
          }).length;
          return JSON.stringify({
            errors:window.__layoutErrors, textPreserved:window.hoshiTextLayout.textPreserved,
            lineStartPunctuation:lineStartPunctuation, pageGlyphCounts:pageGlyphCounts,
            blockBreakIndents:blockBreakIndents, markerBreaksOpeningBlocks:markerBreaksOpeningBlocks,
            allWordsInline:Array.from(document.querySelectorAll('.hoshi-word')).every(function(word) { return getComputedStyle(word).display === 'inline'; }),
            rubyCount:document.querySelectorAll('ruby').length,
            rubiesOutsideWords:Array.from(document.querySelectorAll('ruby')).filter(function(ruby) { return !ruby.closest('.hoshi-word'); }).length,
            studyIdCount:document.querySelectorAll('[id^="study-"]').length,
            staleBreaks:staleBreaks, passes:window.hoshiTextLayout.passes || 0,
            keptBoxes:Array.from(document.querySelectorAll('[data-keep-box]')).every(function(span) { return getComputedStyle(span).display === 'inline'; }),
            // Ruby whose reading shows on another page than its base text.
            splitRubies:context ? Array.from(document.querySelectorAll('ruby')).filter(function(ruby) {
              var rt = ruby.querySelector('rt'), base = ruby.firstChild;
              if (!rt || !base || base === rt) return false;
              var range = document.createRange(); range.selectNode(base);
              var scroll = vertical ? context.scrollEl.scrollTop : context.scrollEl.scrollLeft;
              function pageOf(rect) { return Math.floor(((vertical ? rect.top + rect.bottom : rect.left + rect.right) / 2 + scroll) / context.pageSize); }
              var reading = rt.getBoundingClientRect(), text = range.getBoundingClientRect();
              return reading.width && text.width && pageOf(reading) !== pageOf(text);
            }).map(function(ruby) { return ruby.textContent; }) : [],
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
