package moe.antimony.hoshi.features.reader.sentence

import moe.antimony.hoshi.features.ai.EpubSentenceAnchor
import moe.antimony.hoshi.features.ai.EpubSentenceTranslation
import moe.antimony.hoshi.features.ai.EpubTranslationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceTranslationCompatibilityTest {
    private val prefix = "あ".repeat(399)
    private val chapter = prefix + "大丈夫ですよ次"
    private val oldFirst = anchor(0, prefix + "大")
    private val oldSecond = anchor(400, "丈夫ですよ")
    private val oldThird = anchor(405, "次")
    private val anchors = listOf(oldFirst, oldSecond, oldThird)
    private val stored = anchors.associate { it.id to EpubSentenceTranslation(it.text, "translation ${it.start}", "note ${it.start}", "model") }

    @Test
    fun legacyTranslationsCrossingANewRubyBoundaryKeepTheirFullContext() {
        val sentence = ReaderSentence(0, 399, 6, "大丈夫ですよ。", 0)
        val result = compatibleSentenceTranslation(sentence, chapter, anchors) { stored[it.id] }!!
        assertTrue(result.translation.contains("> " + oldFirst.text))
        assertTrue(result.translation.contains("> " + oldSecond.text))
        assertTrue(result.translation.contains("translation 0"))
        assertTrue(result.translation.contains("translation 400"))
        assertTrue(result.explanation.contains("note 0"))
        assertTrue(result.explanation.contains("note 400"))
        assertTrue(!result.translation.contains("translation 405"))
    }

    @Test
    fun theOffScreenPartOfALegacyAnchorMustMatchTheActualBookToo() {
        val sentence = ReaderSentence(0, 399, 6, "大丈夫ですよ。", 0)
        val stale = oldFirst.copy(text = "い".repeat(399) + "大")
        assertNull(compatibleSentenceTranslation(sentence, chapter, listOf(stale)) { stored[oldFirst.id] })
        assertNull(compatibleSentenceTranslation(sentence.copy(text = "大丈夫でした。"), chapter, anchors) { stored[it.id] })
    }

    @Test
    fun legacyAnchorValidationCountsSupplementaryCodePointsRatherThanUtf16() {
        val text = "𠮷野大丈夫"
        val anchor = anchor(0, text)
        val sentence = ReaderSentence(0, 2, 3, "大丈夫。", 0)
        val result = compatibleSentenceTranslation(sentence, text, listOf(anchor)) {
            EpubSentenceTranslation(text, "All right in Yoshino", "", "model")
        }!!
        assertEquals(text, result.sentenceText)
        assertTrue(result.translation.contains("> $text"))
    }

    @Test
    fun chapterValidationRejectsGapsAndTextLengthDisagreements() {
        val sentences = listOf(ReaderSentence(0, 0, 399, prefix, 0), ReaderSentence(0, 399, 7, "大丈夫ですよ次。", 0))
        assertEquals(chapter, sentenceTranslationChapterTexts(sentences)[0])
        assertTrue(sentenceTranslationChapterTexts(sentences.map { it.copy(start = it.start + 1) }).isEmpty())
        assertTrue(sentenceTranslationChapterTexts(sentences.map { it.copy(length = 0) }).isEmpty())
    }

    @Test
    fun overlappingAnchorsCannotProduceAmbiguousDuplicatedTranslations() {
        val sentence = ReaderSentence(0, 399, 6, "大丈夫ですよ。", 0)
        assertNull(compatibleSentenceTranslation(sentence, chapter, anchors + anchor(399, "大丈")) { stored[it.id] })
    }

    private fun anchor(start: Int, text: String) = EpubSentenceAnchor(
        "c0s$start#${EpubTranslationStore.textHash(text)}", start, text.codePointCount(0, text.length), text,
    )
}
