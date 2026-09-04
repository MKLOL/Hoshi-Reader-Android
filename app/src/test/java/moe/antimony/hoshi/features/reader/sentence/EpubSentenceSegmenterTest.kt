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
