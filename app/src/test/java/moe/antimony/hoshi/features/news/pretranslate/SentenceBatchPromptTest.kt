package moe.antimony.hoshi.features.news.pretranslate

import moe.antimony.hoshi.features.reader.sentence.ReaderSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceBatchPromptTest {
    private fun sentence(start: Int, text: String) = ReaderSentence(spine = 0, start = start, length = text.length, text = text, paragraph = 0)

    @Test
    fun itemsJsonCarriesIdsAndTextInOrder() {
        val json = SentenceBatchPrompt.itemsJson(listOf(sentence(0, "雨が降った。"), sentence(6, "晴れた。")))
        assertEquals("""[{"id":"c0s0","text":"雨が降った。"},{"id":"c0s6","text":"晴れた。"}]""", json)
    }

    @Test
    fun parseBatchAcceptsFencedJsonAndIgnoresUnknownOrDuplicateIds() {
        val reply = """
            Sure! Here is the JSON:
            ```json
            [
              {"id": "c0s0", "translation": "It rained.", "explanation": "雨 = rain"},
              {"id": "c0s0", "translation": "duplicate"},
              {"id": "c0s99", "translation": "not requested"},
              {"id": "c0s6", "translation": "   "},
              {"translation": "no id"}
            ]
            ```
        """.trimIndent()

        val parsed = SentenceBatchPrompt.parseBatch(reply, setOf("c0s0", "c0s6"))

        assertEquals(1, parsed.size)
        assertEquals(SentenceTranslation("c0s0", "It rained.", "雨 = rain"), parsed["c0s0"])
    }

    @Test
    fun truncatedArraySalvagesTheCompletedItems() {
        val reply = """[{"id":"c0s0","translation":"One.","explanation":""},{"id":"c0s6","translation":"Two.","explanation":"x"},{"id":"c0s12","translation":"Thr"""
        val parsed = SentenceBatchPrompt.parseBatch(reply, setOf("c0s0", "c0s6", "c0s12"))
        assertEquals(listOf("c0s0", "c0s6"), parsed.keys.toList())
        assertEquals("Two.", parsed["c0s6"]?.translation)
    }

    @Test
    fun reasoningBlocksWrappedArraysAndPaddedIdsAreTolerated() {
        val reply = """<think>Let me [consider] this...</think>{"items":[{"id":" c0s0 ","translation":"One."}]}"""
        val parsed = SentenceBatchPrompt.parseBatch(reply, setOf("c0s0"))
        assertEquals("One.", parsed["c0s0"]?.translation)
    }

    @Test
    fun brokenSingleJsonIsRejectedInsteadOfStoredAsAFragment() {
        assertNull(SentenceBatchPrompt.parseSingle("""{"translation": "It rai""", "c0s0"))
        assertNull(SentenceBatchPrompt.parseSingle("""{"explanation": "no translation"}""", "c0s0"))
        assertEquals(SentenceTranslation("c0s0", "It rained."), SentenceBatchPrompt.parseSingle("<think>hmm</think>It rained.", "c0s0"))
    }

    @Test
    fun parseBatchReturnsEmptyForNonJsonReplies() {
        assertTrue(SentenceBatchPrompt.parseBatch("I cannot help with that.", setOf("c0s0")).isEmpty())
        assertTrue(SentenceBatchPrompt.parseBatch("[not json", setOf("c0s0")).isEmpty())
    }

    @Test
    fun parseSinglePrefersJsonAndFallsBackToPlainText() {
        assertEquals(
            SentenceTranslation("c0s0", "It rained.", "note"),
            SentenceBatchPrompt.parseSingle("""{"translation":"It rained.","explanation":"note"}""", "c0s0"),
        )
        assertEquals(SentenceTranslation("c0s0", "It rained."), SentenceBatchPrompt.parseSingle("```\nIt rained.\n```", "c0s0"))
        assertNull(SentenceBatchPrompt.parseSingle("   ", "c0s0"))
    }

    @Test
    fun promptIdIsStableAndDependsOnTheExplanationSwitch() {
        assertEquals(SentenceBatchPrompt.promptId(true), SentenceBatchPrompt.promptId(true))
        assertTrue(SentenceBatchPrompt.promptId(true) != SentenceBatchPrompt.promptId(false))
        assertTrue(SentenceBatchPrompt.promptId(true).startsWith("sha256:"))
        assertEquals("sha256:".length + 32, SentenceBatchPrompt.promptId(true).length)
    }
}
