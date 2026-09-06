package moe.antimony.hoshi.features.ai.offline

import androidx.annotation.StringRes
import moe.antimony.hoshi.R

/**
 * A downloadable GGUF model hosted on Hugging Face.
 *
 * @property id stable internal id persisted in settings (see [OfflineTranslationSettings]).
 * @property displayNameRes localized display name resource shown in the UI / notification.
 * @property descriptionRes localized description resource shown in the settings UI.
 * @property repo Hugging Face `owner/name` repository slug.
 * @property fileName the GGUF file inside that repo; also the on-disk file name.
 * @property approxSizeBytes advertised download size, used for the progress bar and the
 *   truncated-file check in [OfflineLlmManager.isDownloaded].
 * @property contextLength the model's native context window.
 */
data class LlmModel(
    val id: String,
    @StringRes val displayNameRes: Int,
    @StringRes val descriptionRes: Int,
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
    /** Purpose-built Japanese→English translator. Best translation, but translation only. */
    val GEMMA_TRANSLATE_Q8 = LlmModel(
        id = "gemma-2-2b-jpn-it-translate-q8",
        displayNameRes = R.string.offline_model_gemma_translate_name,
        descriptionRes = R.string.offline_model_gemma_translate_desc,
        repo = "webbigdata/gemma-2-2b-jpn-it-translate-gguf",
        fileName = "gemma-2-2b-jpn-it-translate-Q8_0.gguf",
        approxSizeBytes = 3_354_000_000L,
        contextLength = 4096,
    )

    /**
     * Best offline Qwen — explains grammar & vocab, not just translates. Heavy and slow on a phone.
     *
     * NOTE: Qwen3.5 is a reasoning ("thinking") model — by default it emits a `<think>…</think>`
     * monologue before its answer. See [moe.antimony.hoshi.features.ai.offline.LlamaInference] for
     * how that is handled; with thinking left on the answer is buried and the token budget is spent
     * on reasoning, so the offline pipeline must strip/disable it.
     */
    val QWEN_9B_Q4 = LlmModel(
        id = "qwen3.5-9b-q4km",
        displayNameRes = R.string.offline_model_qwen_9b_name,
        descriptionRes = R.string.offline_model_qwen_9b_desc,
        repo = "bartowski/Qwen_Qwen3.5-9B-GGUF",
        fileName = "Qwen_Qwen3.5-9B-Q4_K_M.gguf",
        approxSizeBytes = 6_169_000_000L,
        contextLength = 4096,
    )

    /** General model big enough to also EXPLAIN grammar/vocab, the mainstream "strong phone" pick. */
    val QWEN_4B_Q4 = LlmModel(
        id = "qwen3.5-4b-q4km",
        displayNameRes = R.string.offline_model_qwen_4b_name,
        descriptionRes = R.string.offline_model_qwen_4b_desc,
        repo = "bartowski/Qwen_Qwen3.5-4B-GGUF",
        fileName = "Qwen_Qwen3.5-4B-Q4_K_M.gguf",
        approxSizeBytes = 3_013_000_000L,
        contextLength = 4096,
    )

    /** Lightweight model — good middle ground for low-RAM / e-ink devices. */
    val QWEN_2B_Q4 = LlmModel(
        id = "qwen3.5-2b-q4km",
        displayNameRes = R.string.offline_model_qwen_2b_name,
        descriptionRes = R.string.offline_model_qwen_2b_desc,
        repo = "bartowski/Qwen_Qwen3.5-2B-GGUF",
        fileName = "Qwen_Qwen3.5-2B-Q4_K_M.gguf",
        approxSizeBytes = 1_396_000_000L,
        contextLength = 4096,
    )

    /** Tiny model for verifying the download/load/translate pipeline end-to-end. */
    val QWEN_0_8B_Q4 = LlmModel(
        id = "qwen3.5-0.8b-q4km",
        displayNameRes = R.string.offline_model_qwen_0_8b_name,
        descriptionRes = R.string.offline_model_qwen_0_8b_desc,
        repo = "bartowski/Qwen_Qwen3.5-0.8B-GGUF",
        fileName = "Qwen_Qwen3.5-0.8B-Q4_K_M.gguf",
        approxSizeBytes = 580_000_000L,
        contextLength = 4096,
    )

    /** The model chosen by default when the user first enables on-device translation. */
    val DEFAULT = GEMMA_TRANSLATE_Q8

    /** All catalog models. Default translator first, then the explain-capable models, then small. */
    val ALL = listOf(
        GEMMA_TRANSLATE_Q8,
        QWEN_9B_Q4,
        QWEN_4B_Q4,
        QWEN_2B_Q4,
        QWEN_0_8B_Q4,
    )

    /** Looks up a model by its persisted [LlmModel.id], or `null` if unknown. */
    fun byId(id: String): LlmModel? = ALL.firstOrNull { it.id == id }
}
