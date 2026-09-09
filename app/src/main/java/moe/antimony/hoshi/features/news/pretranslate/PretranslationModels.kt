package moe.antimony.hoshi.features.news.pretranslate

import moe.antimony.hoshi.features.reader.sentence.ReaderSentence
import moe.antimony.hoshi.ui.UiText

/** Which engine translates and how. */
sealed interface PretranslationEngine {
    /** A cloud model from [moe.antimony.hoshi.features.ai.ChatModelCatalog] or a custom id. */
    data class Cloud(val modelId: String) : PretranslationEngine

    /** The downloaded llama.cpp model selected in the offline translation settings. */
    data class OnDevice(val modelId: String) : PretranslationEngine

    val label: String
        get() = when (this) {
            is Cloud -> modelId
            is OnDevice -> "on-device:$modelId"
        }
}

data class PretranslationConfig(
    val engine: PretranslationEngine,
    /** Ask for a short vocabulary/grammar note per sentence in addition to the translation. */
    val includeExplanations: Boolean = true,
    val sentencesPerRequest: Int = DEFAULT_SENTENCES_PER_REQUEST,
) {
    /** The batch size the job will actually use: Anthropic's output cap forces smaller batches. */
    fun withEffectiveBatchSize(): PretranslationConfig {
        if (sentencesPerRequest != DEFAULT_SENTENCES_PER_REQUEST) return this
        val cloud = engine as? PretranslationEngine.Cloud ?: return this
        val anthropic = moe.antimony.hoshi.features.ai.ChatModelCatalog.providerForModelId(cloud.modelId).wireFormat ==
            moe.antimony.hoshi.features.ai.ChatWireFormat.ANTHROPIC
        return if (anthropic) copy(sentencesPerRequest = ANTHROPIC_SENTENCES_PER_REQUEST) else this
    }

    companion object {
        const val DEFAULT_SENTENCES_PER_REQUEST = 12

        /** Anthropic replies are capped at 2048 output tokens, so its batches stay small. */
        const val ANTHROPIC_SENTENCES_PER_REQUEST = 6
    }
}

/** Token and cost estimate shown before a job starts. */
data class PretranslationEstimate(
    val sentenceCount: Int,
    val characterCount: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    /** Null when the model's price is unknown or the engine runs on-device. */
    val costUsd: Double?,
)

/** The sentences of one book (spine order) plus the estimate for translating them. */
data class PretranslationPlan(
    val bookId: String,
    val syncId: String,
    val title: String,
    val spineCount: Int,
    val sentences: List<ReaderSentence>,
    val estimate: PretranslationEstimate,
)

/** One translated sentence, keyed by the sentence address (`c{spine}s{start}`). */
data class SentenceTranslation(
    val sentenceId: String,
    val translation: String,
    val explanation: String = "",
)

sealed interface PretranslationJobState {
    val bookId: String

    data class Queued(override val bookId: String) : PretranslationJobState

    data class Running(
        override val bookId: String,
        val completed: Int,
        val total: Int,
        val engineLabel: String,
        /** The user asked to stop; the in-flight request finishes before the job ends. */
        val cancelling: Boolean = false,
    ) : PretranslationJobState

    data class Uploading(override val bookId: String) : PretranslationJobState

    data class Finished(
        override val bookId: String,
        val translated: Int,
        val total: Int,
        val uploaded: Boolean,
        val engineLabel: String,
    ) : PretranslationJobState

    data class Failed(
        override val bookId: String,
        val message: UiText,
        /** Sentences that were translated and saved before the job gave up. */
        val savedTranslations: Int = 0,
    ) : PretranslationJobState

    data class Cancelled(override val bookId: String, val savedTranslations: Int = 0) : PretranslationJobState

    val isActive: Boolean
        get() = this is Queued || this is Running || this is Uploading
}
