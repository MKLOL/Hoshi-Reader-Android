package moe.antimony.hoshi.features.ai.offline

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Formatting and divide-by-zero coverage for [OfflineTranslationResult.debugLine].
 *
 * `debugLine()` is shown verbatim under a translation, so the rendering (one decimal for tok/s
 * and seconds) is part of the contract. The zero-throughput case must not crash.
 */
class OfflineTranslationResultTest {
    @Test
    fun debugLineFormatsMetricsWithOneDecimal() {
        val line = OfflineTranslationResult(
            text = "Hello",
            modelId = "qwen2.5-0.5b-instruct-q4km",
            promptTokens = 20,
            generatedTokens = 47,
            tokensPerSecond = 12.34,
            totalMs = 3800,
        ).debugLine()

        assertTrue("expected tok/s in '$line'", line.contains("12.3 tok/s"))
        assertTrue("expected token count in '$line'", line.contains("47 tokens"))
        assertTrue("expected seconds in '$line'", line.contains("3.8 s"))
        assertTrue("expected on-device tag in '$line'", line.contains("on-device"))
    }

    @Test
    fun debugLineIsDivideByZeroSafe() {
        // A zero-throughput result (e.g. generationMs was 0) must render 0.0 tok/s, not crash.
        val line = OfflineTranslationResult(
            text = "",
            modelId = "qwen2.5-0.5b-instruct-q4km",
            promptTokens = 0,
            generatedTokens = 0,
            tokensPerSecond = 0.0,
            totalMs = 0,
        ).debugLine()

        assertTrue("expected 0.0 tok/s in '$line'", line.contains("0.0 tok/s"))
        assertTrue("expected 0.0 s in '$line'", line.contains("0.0 s"))
    }
}
