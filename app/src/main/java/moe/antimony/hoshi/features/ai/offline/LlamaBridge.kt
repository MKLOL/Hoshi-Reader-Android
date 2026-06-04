package moe.antimony.hoshi.features.ai.offline

/**
 * JNI surface for the bundled `hoshi_llama_jni` native library (a thin llama.cpp wrapper).
 *
 * This object is the *only* place that touches `System.loadLibrary` and declares `external`
 * methods. The matching C++ implementation is compiled against these exact names and
 * signatures — **do not rename them** or the JNI symbol lookup will fail at runtime.
 *
 * The native side owns all model memory behind an opaque `Long` handle; Kotlin never
 * dereferences it. A handle of `0L` means "load failed" / "already freed".
 */
internal object LlamaBridge {
    /**
     * Guards against repeated `System.loadLibrary` calls. `@Volatile` so the double-checked
     * read in [ensureLibraryLoaded] sees writes from the synchronized block on other threads.
     */
    @Volatile
    private var libraryLoaded = false

    /** Loads `hoshi_llama_jni` exactly once. Safe to call from any thread. */
    @Synchronized
    fun ensureLibraryLoaded() {
        if (!libraryLoaded) {
            System.loadLibrary("hoshi_llama_jni")
            libraryLoaded = true
        }
    }

    /**
     * Loads a GGUF model from disk.
     *
     * @return an opaque model handle, or `0L` if the model could not be loaded.
     */
    external fun nativeLoadModel(modelPath: String, nThreads: Int, nCtx: Int): Long

    /**
     * Runs a single translation pass over a UTF-8 prompt and returns the generated UTF-8 bytes.
     *
     * [metricsOut] must be a `DoubleArray` of length >= 4; the native side fills it with:
     * `[0]=promptTokens, [1]=generatedTokens, [2]=promptEvalMs, [3]=generationMs`.
     */
    external fun nativeTranslate(
        handle: Long,
        promptUtf8: ByteArray,
        maxTokens: Int,
        metricsOut: DoubleArray,
    ): ByteArray

    /**
     * Live progress of the in-flight [nativeTranslate] for [handle], as
     * `[tokensGeneratedSoFar, generationElapsedMs]`. Safe to call from another thread while
     * `nativeTranslate` runs (the native side uses atomics).
     */
    external fun nativeProgress(handle: Long): LongArray

    /** Frees the model and all native resources behind [handle]. */
    external fun nativeFreeModel(handle: Long)
}
