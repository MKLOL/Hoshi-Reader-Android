package moe.antimony.hoshi.features.ai.offline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device smoke test for the offline LLM translation pipeline — exercises the real llama.cpp
 * JNI bridge end-to-end: load a GGUF model, run one Japanese→English translation, and assert it
 * produces non-empty output plus the perf metrics the UI shows.
 *
 * The model is intentionally NOT bundled (too large for the repo/APK). Push a small instruct
 * GGUF to the app-under-test's external files dir before running, e.g.:
 *
 *   adb push test-model.gguf \
 *     /sdcard/Android/data/moe.antimony.hoshi.debug/files/test-model.gguf
 *
 * If the file is absent the test is skipped (so a run without a staged model stays green).
 */
@RunWith(AndroidJUnit4::class)
class OfflineInferenceInstrumentedTest {

    @Test
    fun translatesJapaneseToEnglishOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(context.getExternalFilesDir(null), MODEL_FILE_NAME)
        assumeTrue(
            "No model staged — push a GGUF to ${modelFile.absolutePath} to run this test.",
            modelFile.exists() && modelFile.length() > 0L,
        )

        val result = runBlocking {
            val inference = LlamaInference.load(
                modelPath = modelFile.absolutePath,
                modelId = "test-model",
                nThreads = 4,
                nCtx = 1024,
            )
            try {
                inference.translate(
                    promptUtf8Text = "Translate the following Japanese sentence into natural " +
                        "English. Output only the translation.\n\nこんにちは、元気ですか？",
                    maxTokens = 64,
                )
            } finally {
                inference.close()
            }
        }

        // Surfaced in `adb logcat -s HoshiOfflineTest` and the instrumentation stdout.
        Log.i(TAG, "translation='${result.text}'")
        Log.i(TAG, "${result.debugLine()} (prompt=${result.promptTokens}, gen=${result.generatedTokens})")
        println("HOSHI_OFFLINE_TEST translation='${result.text}' | ${result.debugLine()}")

        assertTrue("Expected a non-empty translation", result.text.isNotBlank())
        assertTrue("Expected at least one generated token", result.generatedTokens > 0)
        assertTrue("Expected a positive tokens/sec", result.tokensPerSecond > 0.0)
    }

    private companion object {
        const val TAG = "HoshiOfflineTest"
        const val MODEL_FILE_NAME = "test-model.gguf"
    }
}
