package moe.antimony.hoshi.features.ai

import java.util.Locale

/** Approximate list price of one cloud model, in US dollars per million tokens. */
data class ModelPrice(
    val inputPerMillionUsd: Double,
    val outputPerMillionUsd: Double,
)

/**
 * Rough per-token list prices for the models in [ChatModelCatalog], used only to show the user an
 * estimate before a bulk job starts. Providers change prices without notice, so every number here
 * is labeled as approximate in the UI and an unknown model simply shows no dollar figure.
 */
object ModelPricing {
    /** Month the table was last checked against the providers' pricing pages. */
    const val PRICES_AS_OF: String = "2026-01"

    private val prices: Map<String, ModelPrice> = mapOf(
        "gpt-5.5" to ModelPrice(1.25, 10.0),
        "gpt-5" to ModelPrice(1.25, 10.0),
        "gpt-5-mini" to ModelPrice(0.25, 2.0),
        "gpt-4o" to ModelPrice(2.5, 10.0),
        "gpt-4o-mini" to ModelPrice(0.15, 0.6),
        "claude-opus-4-8" to ModelPrice(5.0, 25.0),
        "claude-sonnet-4-6" to ModelPrice(3.0, 15.0),
        "claude-haiku-4-5" to ModelPrice(1.0, 5.0),
        "gemini-3.6-flash" to ModelPrice(0.5, 3.0),
        "gemini-3.1-pro-preview" to ModelPrice(2.0, 12.0),
        "gemini-3.1-flash-lite" to ModelPrice(0.1, 0.4),
        "deepseek-chat" to ModelPrice(0.28, 0.42),
        "deepseek-reasoner" to ModelPrice(0.28, 0.42),
        "qwen-plus" to ModelPrice(0.4, 1.2),
        "qwen-turbo" to ModelPrice(0.05, 0.2),
        "qwen-max" to ModelPrice(1.6, 6.4),
        "moonshot-v1-8k" to ModelPrice(0.2, 2.0),
        "moonshot-v1-32k" to ModelPrice(1.0, 3.0),
    )

    fun priceFor(modelId: String): ModelPrice? = prices[ChatModelCatalog.replacementModelId(modelId)] ?: prices[modelId]

    /** Estimated cost in USD, or null when the model has no known price. */
    fun estimateUsd(modelId: String, inputTokens: Long, outputTokens: Long): Double? {
        val price = priceFor(modelId) ?: return null
        return inputTokens / 1_000_000.0 * price.inputPerMillionUsd + outputTokens / 1_000_000.0 * price.outputPerMillionUsd
    }

    /** `$0.0042`, `$0.12`, `$3.40`: enough digits to be meaningful at the low end. */
    fun formatUsd(amount: Double): String = when {
        amount < 0.01 -> String.format(Locale.US, "$%.4f", amount)
        amount < 1.0 -> String.format(Locale.US, "$%.3f", amount)
        else -> String.format(Locale.US, "$%.2f", amount)
    }
}

/**
 * Character-based token estimates for Japanese source text and English output. Modern tokenizers
 * spend roughly one token per Japanese character, and an English translation runs to about one
 * token per source character as well; vocabulary notes add a fixed budget per sentence.
 */
object TokenEstimator {
    const val JAPANESE_TOKENS_PER_CHAR: Double = 1.1
    const val ENGLISH_OUTPUT_TOKENS_PER_SOURCE_CHAR: Double = 0.9
    const val EXPLANATION_TOKENS_PER_SENTENCE: Int = 70
    const val PER_ITEM_OVERHEAD_TOKENS: Int = 14

    fun promptTokens(text: String): Long = (text.length / 4.0).toLong().coerceAtLeast(1)

    fun inputTokensForSentence(sentence: String): Long =
        (sentence.length * JAPANESE_TOKENS_PER_CHAR).toLong() + PER_ITEM_OVERHEAD_TOKENS

    fun outputTokensForSentence(sentence: String, withExplanation: Boolean): Long =
        (sentence.length * ENGLISH_OUTPUT_TOKENS_PER_SOURCE_CHAR).toLong() +
            PER_ITEM_OVERHEAD_TOKENS +
            (if (withExplanation) EXPLANATION_TOKENS_PER_SENTENCE else 0)
}
