package moe.antimony.hoshi.features.ai.offline

/**
 * A downloadable GGUF model hosted on Hugging Face.
 *
 * @property id stable internal id persisted in settings (see [OfflineTranslationSettings]).
 * @property repo Hugging Face `owner/name` repository slug.
 * @property fileName the GGUF file inside that repo; also the on-disk file name.
 * @property approxSizeBytes advertised download size, used for the progress bar and the
 *   truncated-file check in [OfflineLlmManager.isDownloaded].
 * @property contextLength the model's native context window.
 */
data class LlmModel(
    val id: String,
    val displayName: String,
    val description: String,
    val repo: String,
    val fileName: String,
    val approxSizeBytes: Long,
    val contextLength: Int,
) {
    /** Direct Hugging Face download URL for [fileName] (follows redirects to the CDN). */
    val downloadUrl: String
        get() = "https://huggingface.co/$repo/resolve/main/$fileName?download=true"
}

/**
 * The fixed set of models the app can download for on-device translation.
 *
 * Curated rather than open-ended: each entry is a GGUF known to run under the bundled
 * llama.cpp build, ordered from best-quality (and heaviest) to tiniest/test.
 */
object LlmModelCatalog {
    /** Purpose-built Japanese→English translator. Best quality, heaviest. */
    val GEMMA_TRANSLATE_Q8 = LlmModel(
        id = "gemma-2-2b-jpn-it-translate-q8",
        displayName = "Gemma 2 2B JP→EN (best quality)",
        description = "Purpose-built Japanese→English translation model. ~3.3 GB, best " +
            "quality, needs a strong device.",
        repo = "webbigdata/gemma-2-2b-jpn-it-translate-gguf",
        fileName = "gemma-2-2b-jpn-it-translate-Q8_0.gguf",
        approxSizeBytes = 3_354_000_000L,
        contextLength = 4096,
    )

    /** Lightweight multilingual model — good middle ground for low-RAM / e-ink devices. */
    val QWEN_1_5B_Q4 = LlmModel(
        id = "qwen2.5-1.5b-instruct-q4km",
        displayName = "Qwen2.5 1.5B (lightweight)",
        description = "Smaller multilingual model. ~1 GB, faster, good for low-RAM / e-ink " +
            "devices.",
        repo = "bartowski/Qwen2.5-1.5B-Instruct-GGUF",
        fileName = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
        approxSizeBytes = 1_120_000_000L,
        contextLength = 4096,
    )

    /** Tiny model for verifying the download/load/translate pipeline end-to-end. */
    val QWEN_0_5B_Q4 = LlmModel(
        id = "qwen2.5-0.5b-instruct-q4km",
        displayName = "Qwen2.5 0.5B (test/tiny)",
        description = "Tiny model for testing the pipeline. ~400 MB, lowest quality.",
        repo = "bartowski/Qwen2.5-0.5B-Instruct-GGUF",
        fileName = "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
        approxSizeBytes = 400_000_000L,
        contextLength = 4096,
    )

    /** The model chosen by default when the user first enables on-device translation. */
    val DEFAULT = GEMMA_TRANSLATE_Q8

    /** All catalog models, best-quality first. */
    val ALL = listOf(GEMMA_TRANSLATE_Q8, QWEN_1_5B_Q4, QWEN_0_5B_Q4)

    /** Looks up a model by its persisted [LlmModel.id], or `null` if unknown. */
    fun byId(id: String): LlmModel? = ALL.firstOrNull { it.id == id }
}
