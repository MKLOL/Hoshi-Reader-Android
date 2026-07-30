package moe.antimony.hoshi.features.ai.offline

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/** Thrown when an on-device model fails to load or run; [message] is safe to show the user. */
class LlamaModelException(message: String) : Exception(message)

/**
 * Result of a single on-device translation, plus the perf metrics the native side reported.
 *
 * @property tokensPerSecond generation throughput (generated tokens ÷ generation seconds).
 * @property totalMs wall-clock time of the whole native call, measured on the Kotlin side.
 */
data class OfflineTranslationResult(
    val text: String,
    val modelId: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val tokensPerSecond: Double,
    val totalMs: Long,
) {
    /**
     * A compact one-line perf footer, e.g. `"⚡ 12.3 tok/s · 47 tokens · 3.8 s · on-device"`.
     * `tok/s` and seconds are rendered with one decimal; division is guarded so a zero-duration
     * result never crashes (it simply shows `0.0 s`).
     */
    fun debugLine(): String {
        val seconds = totalMs / 1000.0
        // Locale.US so the decimal separator is always '.' (matches the rest of the app's
        // numeric formatting; a comma-locale would otherwise render "12,3 tok/s").
        return "⚡ %.1f tok/s · %d tokens · %.1f s · on-device".format(
            Locale.US,
            tokensPerSecond,
            generatedTokens,
            seconds,
        )
    }
}

/**
 * A single loaded GGUF model, owning an opaque native handle from [LlamaBridge].
 *
 * Construction is private; use [load]. The instance is **not** safe to call from multiple
 * coroutines concurrently — the native model state is single-threaded — so [translate] and
 * [close] both serialize on an internal [Mutex].
 */
class LlamaInference private constructor(
    private var handle: Long,
    private val modelId: String,
) {
    /** Serializes native access; the underlying llama.cpp context is not re-entrant. */
    private val mutex = Mutex()

    /**
     * Runs one translation pass. The heavy native call is moved off the caller's thread onto
     * [dispatcher]. Returns the decoded, trimmed text plus perf metrics.
     *
     * @throws LlamaModelException if this instance was already [close]d.
     */
    suspend fun translate(
        promptUtf8Text: String,
        maxTokens: Int = 256,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        onProgress: (tokens: Int, tokensPerSecond: Double) -> Unit = { _, _ -> },
    ): OfflineTranslationResult = withContext(dispatcher) {
        mutex.withLock {
            val h = handle
            if (h == 0L) {
                throw LlamaModelException("On-device model is already closed.")
            }
            // Filled by the native side: [0]=promptTokens, [1]=generatedTokens,
            // [2]=promptEvalMs, [3]=generationMs.
            val metrics = DoubleArray(4)
            val startNanos = System.nanoTime()
            val resultBytes = coroutineScope {
                // Poll native progress on a sibling coroutine so the UI gets a live tok/s counter
                // while the blocking nativeTranslate occupies another thread.
                val poller = launch {
                    while (isActive) {
                        delay(250)
                        val p = runCatching { LlamaBridge.nativeProgress(h) }.getOrNull()
                        if (p != null && p.size >= 2) {
                            val toks = p[0].toInt()
                            val ms = p[1]
                            onProgress(toks, if (ms > 0L) toks / (ms / 1000.0) else 0.0)
                        }
                    }
                }
                val bytes = LlamaBridge.nativeTranslate(
                    h,
                    promptUtf8Text.toByteArray(Charsets.UTF_8),
                    maxTokens,
                    metrics,
                )
                poller.cancel()
                bytes
            }
            val totalMs = (System.nanoTime() - startNanos) / 1_000_000L

            val generatedTokens = metrics[1].toInt()
            // Prefer llama.cpp's own generation timer (metrics[3] = t_eval_ms). On some builds
            // it comes back as 0 (observed on the x86_64 emulator), which would render a
            // misleading "0.0 tok/s" for a real generation — so fall back to the wall-clock span
            // we measured around the native call.
            val generationMs = if (metrics[3] > 0.0) metrics[3] else totalMs.toDouble()
            val tokensPerSecond = if (generatedTokens > 0 && generationMs > 0.0) {
                generatedTokens / (generationMs / 1000.0)
            } else {
                0.0
            }

            OfflineTranslationResult(
                text = stripReasoning(resultBytes.toString(Charsets.UTF_8)),
                modelId = modelId,
                promptTokens = metrics[0].toInt(),
                generatedTokens = generatedTokens,
                tokensPerSecond = tokensPerSecond,
                totalMs = totalMs,
            )
        }
    }

    /** Frees the native model. Idempotent — a second call is a no-op. */
    suspend fun close() {
        mutex.withLock {
            if (handle != 0L) {
                LlamaBridge.nativeFreeModel(handle)
                handle = 0L
            }
        }
    }

    companion object {
        /**
         * Loads a GGUF model off the caller's thread.
         *
         * @param nCtx context window size; defaults to 2048 (callers usually clamp to the
         *   model's own context length).
         * @throws LlamaModelException if the native loader returns a `0L` handle.
         */
        suspend fun load(
            modelPath: String,
            modelId: String,
            nThreads: Int,
            nCtx: Int = 2048,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): LlamaInference = withContext(dispatcher) {
            LlamaBridge.ensureLibraryLoaded()
            val handle = LlamaBridge.nativeLoadModel(modelPath, nThreads, nCtx)
            if (handle == 0L) {
                throw LlamaModelException("Failed to load on-device model from $modelPath.")
            }
            LlamaInference(handle, modelId)
        }
    }
}

/**
 * Drops a reasoning model's chain-of-thought, returning just the user-facing answer.
 *
 * Reasoning models (Qwen3.5, etc.) emit a `<think>…</think>` monologue before their answer — and
 * some chat templates pre-open the `<think>` tag in the assistant prefix, so the generated text can
 * start *inside* the reasoning with only the closing `</think>` present. We therefore key off the
 * LAST `</think>`: everything after it is the real answer. Models that don't reason (Gemma, the
 * purpose-built translator) carry no tags, so their output is just trimmed and passed through. If a
 * closing tag is somehow missing (e.g. the token budget was exhausted mid-reasoning) we return the
 * raw text rather than nothing, so a truncated reply still shows *something*.
 */
internal fun stripReasoning(raw: String): String {
    val close = "</think>"
    val end = raw.lastIndexOf(close)
    return if (end >= 0) raw.substring(end + close.length).trim() else raw.trim()
}
