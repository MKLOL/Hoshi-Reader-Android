package moe.antimony.hoshi.features.reader.sentence

import moe.antimony.hoshi.epub.filteredReaderText
import moe.antimony.hoshi.features.ai.EpubTranslationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The segmenter must produce exactly the sentences the desktop pre-translation tool addresses,
 * or sentence mode could never find a stored translation. These vectors follow the tool's own
 * rules (`tools/pretranslate/hoshi_pretranslate/epub.py`).
 */
class EpubSentenceSegmenterTest {
    private fun chapter(body: String) = """<html><head><title>ignored 無視</title></head><body>$body</body></html>"""

    @Test
    fun terminatorsEndSentencesAndSwallowClosingBrackets() {
        val sentences = EpubSentenceSegmenter.segment(3, chapter("<p>「行こう！」と彼は言った。本当に？</p>"))

        assertEquals(listOf("「行こう！」", "と彼は言った。", "本当に？"), sentences.map { it.text })
        assertEquals(listOf("c3s0", "c3s3", "c3s9"), sentences.map { it.id })
        assertEquals(listOf(3, 6, 3), sentences.map { it.length })
    }

    @Test
    fun aTerminatorOnlySwallowsBracketsInItsOwnTextRun() {
        // `html.parser` hands the tool one run per stretch of text between tags, so a closing
        // bracket wrapped in its own inline element starts the next sentence. Ids and hashes are
        // unaffected (brackets are not matchable), but the shown text must match iOS and the tool.
        val sentences = EpubSentenceSegmenter.segment(0, chapter("<p>「あ！<em>」</em>と<span>言った</span>。</p>"))

        assertEquals(listOf("「あ！", "」と言った。"), sentences.map { it.text })
        assertEquals(listOf("c0s0", "c0s1"), sentences.map { it.id })
        assertEquals(listOf(1, 4), sentences.map { it.length })
    }

    @Test
    fun asciiPeriodIsNotATerminator() {
        val sentences = EpubSentenceSegmenter.segment(0, chapter("<p>Ver. 2.5 が出た。</p>"))

        assertEquals(listOf("Ver. 2.5 が出た。"), sentences.map { it.text })
    }

    @Test
    fun blockBoundariesEndSentencesWithoutPunctuation() {
        val sentences = EpubSentenceSegmenter.segment(0, chapter("<h1>第一章</h1><p>朝だ<br/>起きる</p><div>終わり。</div>"))

        assertEquals(listOf("第一章", "朝だ", "起きる", "終わり。"), sentences.map { it.text })
        assertEquals("paragraph counter follows block boundaries", listOf(1, 3, 4, 6), sentences.map { it.paragraph })
    }

    @Test
    fun furiganaScriptsAndHeadAreNotText() {
        val html = chapter(
            "<p><ruby>漢<rt>かん</rt>字<rt>じ</rt></ruby>を読む。</p><script>var x = '。';</script><style>p{}</style>",
        )
        val sentences = EpubSentenceSegmenter.segment(0, html)

        assertEquals(listOf("漢字を読む。"), sentences.map { it.text })
        assertEquals(5, sentences.single().length)
    }

    @Test
    fun unclosedRtIsClosedByTheRubyEndTagLikeABrowser() {
        val sentences = EpubSentenceSegmenter.segment(0, chapter("<p><ruby>今<rt>いま</ruby>から行く。</p>"))

        assertEquals(listOf("今から行く。"), sentences.map { it.text })
    }

    @Test
    fun offsetsCountMatchableCharactersLikeTheReadersFilter() {
        val html = chapter("<p>今日は　いい天気です。</p><p>　明日も、晴れ？</p>")
        val sentences = EpubSentenceSegmenter.segment(0, html)
        val normalized = html.filteredReaderText()

        for (sentence in sentences) {
            val slice = normalized.codePointSlice(sentence.start, sentence.length)
            assertEquals("sentence '${sentence.text}' addresses its own characters", EpubTranslationStore.normalize(sentence.text), slice)
        }
        assertEquals(normalized.codePointCount(0, normalized.length), sentences.sumOf { it.length })
    }

    @Test
    fun whitespaceInsideASentenceCollapsesToSingleSpaces() {
        val sentences = EpubSentenceSegmenter.segment(0, chapter("<p>one\n   two&nbsp;&nbsp;three。</p>"))

        assertEquals(listOf("one two three。"), sentences.map { it.text })
    }

    @Test
    fun overlongRunsSplitAtTheLastComma() {
        val clause = "あ".repeat(250)
        val sentences = EpubSentenceSegmenter.segment(0, chapter("<p>$clause、$clause、$clause。</p>"))

        assertTrue("every piece stays under the ceiling", sentences.all { it.length <= 400 })
        assertEquals(3, sentences.size)
        assertEquals(listOf(0, 250, 500), sentences.map { it.start })
        assertEquals(750, sentences.sumOf { it.length })
    }

    @Test
    fun entitiesDecodeAndUnknownOnesSurvive() {
        assertEquals("a<b>&\"'…x", EpubSentenceSegmenter.decodeEntities("a&lt;b&gt;&amp;&quot;&apos;&hellip;&#x78;"))
        assertEquals("&bogus; &", EpubSentenceSegmenter.decodeEntities("&bogus; &"))
    }

    @Test
    fun entitiesDecodeExactlyLikeHtmlUnescape() {
        // Legacy semicolon-less names, the longest-prefix rule, numeric references without `;`,
        // and the standard's replacements for invalid references, all as Python's html.unescape.
        assertEquals("a&b", EpubSentenceSegmenter.decodeEntities("a&ampb"))
        assertEquals("¬it;", EpubSentenceSegmenter.decodeEntities("&notit;"))
        assertEquals("€\ufffd", EpubSentenceSegmenter.decodeEntities("&#128;&#0;&#x1;"))
        assertEquals("&\r", EpubSentenceSegmenter.decodeEntities("&amp\r"))
        assertEquals("&" + "a".repeat(40) + ";", EpubSentenceSegmenter.decodeEntities("&" + "a".repeat(40) + ";"))
        assertEquals("\ufffd\ufffd\ufffd", EpubSentenceSegmenter.decodeEntities("&#x110000;&#xD800;&#99999999999999999999;"))
        assertEquals("\t\nA", EpubSentenceSegmenter.decodeEntities("&Tab;&NewLine;&#65;"))
        assertEquals("café ¥", EpubSentenceSegmenter.decodeEntities("caf&eacute; &yen;"))
    }

    @Test
    fun compatibilityIdeographsAreMatchableLikeTheTool() {
        // U+FA11 is in the tool's \p{Unified_Ideograph}; the reader's display filter drops it, and
        // using that filter shifted every address after it by one.
        val sentences = EpubSentenceSegmenter.segment(0, chapter("<p>山﨑さん。次。</p>"))

        assertEquals(listOf("c0s0", "c0s4"), sentences.map { it.id })
        assertEquals(listOf(4, 1), sentences.map { it.length })
        assertEquals(2, EpubSentenceSegmenter.matchableCount("山﨑さん", 2))
    }

    @Test
    fun html5NamedEntitiesDecodeInsideSentences() {
        val sentences = EpubSentenceSegmenter.segment(0, chapter("<p>caf&eacute; は良い。&yen;100だ。次の文。</p>"))

        assertEquals(listOf("café は良い。", "¥100だ。", "次の文。"), sentences.map { it.text })
        assertEquals(listOf("c0s0", "c0s6", "c0s10"), sentences.map { it.id })
    }

    @Test
    fun legacyAndNumericEntitiesFollowHtmlUnescapeInsideSentences() {
        val sentences = EpubSentenceSegmenter.segment(0, chapter("<p>a&ampb。&#12354&#x3042;&notit;。&#128;&#0;&#x1;x。</p>"))

        assertEquals(listOf("a&b。", "ああ¬it;。", "€\ufffdx。"), sentences.map { it.text })
        assertEquals(listOf("c0s0", "c0s2", "c0s6"), sentences.map { it.id })
        assertEquals(listOf(2, 4, 1), sentences.map { it.length })
    }

    @Test
    fun scriptAndStyleContentIsRawText() {
        // A `<` inside a script used to start a tag that swallowed `</script>`, and with the skip
        // never closed the whole chapter vanished.
        val html = "<html><head><script>for(i=0;i<n;i++){}</script></head><body><p>本文。</p>" +
            "<script>if (a < b) {}</script><p>次。</p><style>a>b{}</style><p>三。</p></body></html>"
        val sentences = EpubSentenceSegmenter.segment(0, html)

        assertEquals(listOf("本文。", "次。", "三。"), sentences.map { it.text })
        assertEquals(listOf("c0s0", "c0s2", "c0s3"), sentences.map { it.id })
        assertEquals(listOf(1, 3, 5), sentences.map { it.paragraph })
    }

    @Test
    fun strayLessThanIsTextAndCdataAndCommentsAreSkipped() {
        val html = chapter("<p>if a < b then。次。</p><p>第一<![CDATA[x > y]]>章。<!-- 。 -->終。</p>")
        val sentences = EpubSentenceSegmenter.segment(0, html)

        assertEquals(listOf("if a < b then。", "次。", "第一章。", "終。"), sentences.map { it.text })
        assertEquals(listOf("c0s0", "c0s8", "c0s9", "c0s12"), sentences.map { it.id })
    }

    @Test
    fun furiganaRideAlongAsRubyAnnotationsWithoutTouchingOffsets() {
        val html = chapter(
            "<p>「<ruby>今日<rt>きょう</rt></ruby>も<ruby>学校<rt>がっこう</rt></ruby>か……」と、" +
                "<ruby>私<rt>わたし</rt></ruby>は<ruby>小<rt>ちい</rt></ruby>さくつぶやいた。</p>",
        )
        val sentence = EpubSentenceSegmenter.segment(0, html).single()

        assertEquals("「今日も学校か……」と、私は小さくつぶやいた。", sentence.text)
        assertEquals(listOf("c0s0"), listOf(sentence.id))
        assertEquals(
            listOf(RubyAnnotation(1, 2, "きょう"), RubyAnnotation(4, 2, "がっこう"), RubyAnnotation(12, 1, "わたし"), RubyAnnotation(14, 1, "ちい")),
            sentence.ruby,
        )
        for (ruby in sentence.ruby) {
            assertTrue("base sits on the reading", sentence.text.substring(ruby.start, ruby.start + ruby.length) in setOf("今日", "学校", "私", "小"))
        }
    }

    @Test
    fun rubyPairsRpAndUnclosedRtFollowTheBrowser() {
        // Sibling <rt>s pair with the base before each; <rp> is not a reading; an <rt> left open
        // is closed by </ruby>; a base with no <rt> gets no annotation; whitespace before the
        // base is collapsed away so the offsets point at the shown text.
        val html = chapter(
            "<p>　<ruby>漢<rt>かん</rt>字<rt>じ</rt></ruby>を<ruby>読<rp>(</rp><rt>よ</rt><rp>)</rp></ruby>む。" +
                "<ruby>今<rt>いま</ruby>から<ruby>行</ruby>く。</p>",
        )
        val sentences = EpubSentenceSegmenter.segment(0, html)

        assertEquals(listOf("漢字を読む。", "今から行く。"), sentences.map { it.text })
        assertEquals(
            listOf(RubyAnnotation(0, 1, "かん"), RubyAnnotation(1, 1, "じ"), RubyAnnotation(3, 1, "よ")),
            sentences[0].ruby,
        )
        assertEquals(listOf(RubyAnnotation(0, 1, "いま")), sentences[1].ruby)
    }

    @Test
    fun rubyAnnotationsSurviveTheOverlongSplit() {
        val clause = "あ".repeat(250)
        val html = chapter("<p>$clause、<ruby>$clause<rt>よみ</rt></ruby>、$clause。</p>")
        val sentences = EpubSentenceSegmenter.segment(0, html)

        assertEquals(3, sentences.size)
        assertEquals(listOf(emptyList(), listOf(RubyAnnotation(0, 250, "よみ")), emptyList()), sentences.map { it.ruby })
    }

    @Test
    fun theFirstHeadingIsTheChapterTitleFallback() {
        val chapter = EpubSentenceSegmenter.chapter(0, chapter("<h1>第一章　　朝</h1><p>本文。</p><h2>二</h2>"))

        assertEquals("第一章 朝", chapter.heading)
        assertEquals(listOf("第一章 朝", "本文。", "二"), chapter.sentences.map { it.text })
        assertEquals("", EpubSentenceSegmenter.chapter(0, chapter("<p>見出しなし。</p>")).heading)
    }

    @Test
    fun textBeforeBodyIsIgnoredButABodylessFragmentIsNot() {
        assertEquals(listOf("本文。"), EpubSentenceSegmenter.segment(0, chapter("<p>本文。</p>")).map { it.text })
        assertEquals(listOf("断片。"), EpubSentenceSegmenter.segment(0, "<p>断片。</p>").map { it.text })
    }

    @Test
    fun matchableCountMapsATapOffsetOntoTheNormalizedText() {
        assertEquals(0, EpubSentenceSegmenter.matchableCount("「今日は」", 1))
        assertEquals(2, EpubSentenceSegmenter.matchableCount("「今日は」", 3))
        assertEquals(3, EpubSentenceSegmenter.matchableCount("「今日は」", 99))
    }

    private fun String.codePointSlice(start: Int, length: Int): String {
        val begin = offsetByCodePoints(0, start)
        return substring(begin, offsetByCodePoints(begin, length))
    }
}
