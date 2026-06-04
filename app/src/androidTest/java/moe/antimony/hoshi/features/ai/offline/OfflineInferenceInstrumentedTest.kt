package moe.antimony.hoshi.features.ai.offline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.ai.AiChatSettings
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end on-device translation test that exercises the **real production path** a manga
 * bubble tap takes: [OfflineLlmManager.translate] with the app's actual default instruction
 * ([AiChatSettings.DEFAULT_PROMPT], the "reading tutor" prompt — NOT a tidy "translate this")
 * and realistic mokuro bubble text (multi-line, joined with `\n`, manga punctuation, no spaces —
 * see MangaPageHtml `box.lines.joinToString("\n")`).
 *
 * The model is NOT bundled. Stage the real GGUF at the manager's internal model path before
 * running (the default catalog model is the Gemma Q8 translate model):
 *   adb push model.gguf /sdcard/Android/data/moe.antimony.hoshi.debug/files/gemma-q8.gguf
 *   adb shell run-as moe.antimony.hoshi.debug sh -c \
 *     'mkdir -p files/offline-llm && cp /sdcard/Android/data/moe.antimony.hoshi.debug/files/gemma-q8.gguf files/offline-llm/<fileName>'
 * If the model isn't present the test is skipped.
 */
@RunWith(AndroidJUnit4::class)
class OfflineInferenceInstrumentedTest {

    @Test
    fun translatesRealisticBubblesViaProductionPath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = LlmModelCatalog.DEFAULT
        val modelFile = OfflineLlmManager.modelFile(context, model)
        assumeTrue(
            "Stage the real model at ${modelFile.absolutePath} to run this test.",
            modelFile.exists() && modelFile.length() > 1_000_000_000L,
        )

        // Realistic mokuro bubbles: multi-line (\n between OCR lines), casual, full-width
        // punctuation, ellipses — what `aiText.textContent` actually hands to askAi().
        // REAL Yotsuba&! vol.1 OCR bubbles (extracted from the mokuro file) — the exact
        // multi-line, casual manga strings the app feeds OfflineLlmManager.translate. Kept to a
        // few because the x86 emulator runs a 2B Q8 model at ~0.1 tok/s (minutes per bubble).
        val bubbles = listOf(
            "とーちゃん\nここ家がいっぱい\nあるな！",
            "すげぇー！！\n人がいっぱいいる！",
            "そういや明日から\n夏休みだなぁ",
        )

        runBlocking {
            for (bubble in bubbles) {
                val result = OfflineLlmManager.translate(
                    appContext = context,
                    instruction = AiChatSettings.DEFAULT_PROMPT,
                    japaneseText = bubble,
                    // Cap output — the emulated CPU runs a 2B Q8 model at ~0.1 tok/s.
                    maxTokens = 48,
                )
                val flatIn = bubble.replace("\n", "\\n")
                val flatOut = result.text.replace("\n", "\\n")
                Log.i(TAG, "BUBBLE=[$flatIn] -> [$flatOut] | ${result.debugLine()}")
                println("HOSHI_E2E [$flatIn] => [$flatOut]")
                assertTrue("Empty translation for [$flatIn]", result.text.isNotBlank())
                assertTrue("0 tokens for [$flatIn]", result.generatedTokens > 0)
            }
        }
    }

    private companion object {
        const val TAG = "HoshiOfflineTest"
    }
}
