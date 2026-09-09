package moe.antimony.hoshi.features.news.pretranslate

import java.io.File
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.features.ai.ModelPricing
import moe.antimony.hoshi.features.ai.TokenEstimator
import moe.antimony.hoshi.features.reader.sentence.EpubSentenceSegmenter
import moe.antimony.hoshi.features.reader.sentence.ReaderSentence

/**
 * Segments a book's chapters exactly the way sentence mode and the desktop pipeline do, so the
 * translations written later match the reader's anchors, and prices the job.
 */
object PretranslationPlanner {
    fun plan(
        bookId: String,
        syncId: String,
        bookRoot: File,
        config: PretranslationConfig,
        parser: EpubBookParser = EpubBookParser(),
    ): PretranslationPlan {
        val book = parser.parse(bookRoot)
        return plan(bookId, syncId, book, config)
    }

    fun plan(bookId: String, syncId: String, book: EpubBook, config: PretranslationConfig): PretranslationPlan {
        val sentences = book.chapters.flatMap { chapter ->
            val spine = chapter.spineIndex ?: book.chapters.indexOf(chapter)
            val html = book.readResource(chapter.href)?.toString(Charsets.UTF_8) ?: return@flatMap emptyList()
            EpubSentenceSegmenter.segment(spine, html)
        }.filter { it.text.isNotBlank() }
        return PretranslationPlan(
            bookId = bookId,
            syncId = syncId,
            title = book.title,
            spineCount = book.spineCount,
            sentences = sentences,
            estimate = estimate(sentences, config),
        )
    }

    fun estimate(sentences: List<ReaderSentence>, config: PretranslationConfig): PretranslationEstimate {
        val characters = sentences.sumOf { it.text.length }
        val requests = if (sentences.isEmpty()) 0 else (sentences.size + config.sentencesPerRequest - 1) / config.sentencesPerRequest
        val promptTokens = TokenEstimator.promptTokens(SentenceBatchPrompt.instructions(config.includeExplanations)) * requests
        val inputTokens = promptTokens + sentences.sumOf { TokenEstimator.inputTokensForSentence(it.text) }
        val outputTokens = sentences.sumOf { TokenEstimator.outputTokensForSentence(it.text, config.includeExplanations) }
        val cost = when (val engine = config.engine) {
            is PretranslationEngine.Cloud -> ModelPricing.estimateUsd(engine.modelId, inputTokens, outputTokens)
            is PretranslationEngine.OnDevice -> null
        }
        return PretranslationEstimate(
            sentenceCount = sentences.size,
            characterCount = characters,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            costUsd = cost,
        )
    }
}
