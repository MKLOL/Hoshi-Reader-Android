package moe.antimony.hoshi.features.news.pretranslate

import moe.antimony.hoshi.features.ai.ModelPricing
import moe.antimony.hoshi.features.ai.TokenEstimator
import moe.antimony.hoshi.features.news.NewsArticleEpubWriter
import moe.antimony.hoshi.features.reader.sentence.ReaderSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PretranslationPlannerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun sentence(start: Int, text: String) = ReaderSentence(spine = 0, start = start, length = text.length, text = text, paragraph = 0)

    @Test
    fun planSegmentsTheArticleBookAndCarriesSyncIdentity() {
        val root = temp.newFolder("book")
        NewsArticleEpubWriter.write(root, NewsArticleEpubWriter.Input("見出し", "<p>一文目。二文目！</p>", "S", "https://s/1"))

        val plan = PretranslationPlanner.plan("book-id", "sync-id", root, PretranslationConfig(PretranslationEngine.Cloud("gpt-4o-mini")))

        assertEquals("book-id", plan.bookId)
        assertEquals("sync-id", plan.syncId)
        assertEquals("見出し", plan.title)
        assertEquals(1, plan.spineCount)
        assertEquals(listOf("見出し", "S", "一文目。", "二文目！"), plan.sentences.map { it.text })
        assertEquals(4, plan.estimate.sentenceCount)
        assertNotNull(plan.estimate.costUsd)
    }

    @Test
    fun estimateScalesWithCharactersExplanationsAndBatches() {
        val sentences = listOf(sentence(0, "あ".repeat(100)), sentence(100, "い".repeat(50)))
        val cloud = PretranslationConfig(PretranslationEngine.Cloud("gpt-4o-mini"), includeExplanations = false, sentencesPerRequest = 1)
        val withNotes = cloud.copy(includeExplanations = true)

        val plain = PretranslationPlanner.estimate(sentences, cloud)
        val notes = PretranslationPlanner.estimate(sentences, withNotes)

        assertEquals(150, plain.characterCount)
        val expectedInput = TokenEstimator.promptTokens(SentenceBatchPrompt.instructions(false)) * 2 +
            TokenEstimator.inputTokensForSentence(sentences[0].text) + TokenEstimator.inputTokensForSentence(sentences[1].text)
        assertEquals(expectedInput, plain.inputTokens)
        assertEquals(plain.outputTokens + 2 * TokenEstimator.EXPLANATION_TOKENS_PER_SENTENCE, notes.outputTokens)
        assertTrue(notes.costUsd!! > plain.costUsd!!)
        assertEquals(ModelPricing.estimateUsd("gpt-4o-mini", plain.inputTokens, plain.outputTokens), plain.costUsd)
    }

    @Test
    fun onDeviceAndUnknownModelsHaveNoDollarEstimate() {
        val sentences = listOf(sentence(0, "文。"))
        assertNull(PretranslationPlanner.estimate(sentences, PretranslationConfig(PretranslationEngine.OnDevice("gemma"))).costUsd)
        assertNull(PretranslationPlanner.estimate(sentences, PretranslationConfig(PretranslationEngine.Cloud("my-private-model"))).costUsd)
        assertEquals(PretranslationEstimate(0, 0, 0, 0, 0.0), PretranslationPlanner.estimate(emptyList(), PretranslationConfig(PretranslationEngine.Cloud("gpt-4o-mini"))))
    }

    @Test
    fun pricingFormatsSmallAndLargeAmountsReadably() {
        assertEquals("$0.0042", ModelPricing.formatUsd(0.0042))
        assertEquals("$0.123", ModelPricing.formatUsd(0.1234))
        assertEquals("$3.40", ModelPricing.formatUsd(3.4))
        assertNotNull(ModelPricing.priceFor("claude-haiku-4-5"))
        assertNull(ModelPricing.priceFor("nope"))
    }
}
