package moe.antimony.hoshi.features.ai.offline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract for [stripReasoning], which removes a reasoning model's `<think>` monologue so only the
 * user-facing answer is shown (Qwen3.5 et al. reason before answering; Gemma does not).
 */
class StripReasoningTest {
    @Test
    fun stripsFullThinkBlock() {
        val raw = "<think>\nThe cat. が is subject.\n</think>\n\nI really love cats."
        assertEquals("I really love cats.", stripReasoning(raw))
    }

    @Test
    fun stripsWhenTemplatePreOpenedThinkSoOnlyClosingTagIsGenerated() {
        // Some Qwen chat templates emit `<think>` in the assistant prefix, so the generated text
        // starts INSIDE the reasoning and only the closing tag appears.
        val raw = "We break it down: 猫 = cat...\n</think>\nI love cats."
        assertEquals("I love cats.", stripReasoning(raw))
    }

    @Test
    fun keysOffLastClosingTagWhenMultiplePresent() {
        val raw = "<think>a</think> stray <think>b</think> final answer"
        assertEquals("final answer", stripReasoning(raw))
    }

    @Test
    fun passesThroughNonReasoningOutputUnchanged() {
        // Gemma / translate-only models carry no tags — just trimmed, never mangled.
        assertEquals("I love cats.", stripReasoning("  I love cats.\n"))
    }

    @Test
    fun returnsRawWhenReasoningWasTruncatedWithNoClosingTag() {
        // Budget exhausted mid-<think>: show the partial text rather than an empty reply.
        val raw = "<think>\nStill reasoning and never finished"
        assertEquals("<think>\nStill reasoning and never finished", stripReasoning(raw))
    }
}
