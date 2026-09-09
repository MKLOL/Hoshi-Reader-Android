package moe.antimony.hoshi.features.news.pretranslate

import java.io.IOException
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.reader.sentence.ReaderSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PretranslationRunnerTest {
    private fun sentence(start: Int, text: String) = ReaderSentence(spine = 0, start = start, length = text.length, text = text, paragraph = 0)
    private val sentences = (0 until 5).map { sentence(it * 10, "文$it。") }

    private class FakeTranslator(
        override val supportsBatches: Boolean = true,
        val batchBehavior: (List<ReaderSentence>) -> Map<String, SentenceTranslation> = { batch -> batch.associate { it.id to SentenceTranslation(it.id, "T:${it.text}") } },
        val singleBehavior: (ReaderSentence) -> SentenceTranslation? = { SentenceTranslation(it.id, "S:${it.text}") },
    ) : SentenceTranslator {
        val batchCalls = mutableListOf<List<String>>()
        val singleCalls = mutableListOf<String>()
        override suspend fun translateBatch(sentences: List<ReaderSentence>, includeExplanations: Boolean): Map<String, SentenceTranslation> {
            batchCalls += sentences.map { it.id }
            return batchBehavior(sentences)
        }
        override suspend fun translateOne(sentence: ReaderSentence, includeExplanations: Boolean): SentenceTranslation? {
            singleCalls += sentence.id
            return singleBehavior(sentence)
        }
    }

    private fun config(engine: PretranslationEngine = PretranslationEngine.Cloud("gpt-4o-mini"), perRequest: Int = 2) =
        PretranslationConfig(engine = engine, sentencesPerRequest = perRequest)

    @Test
    fun batchesAreChunkedAndMissingItemsAreRetriedSingly() = runBlocking {
        val translator = FakeTranslator(batchBehavior = { batch -> batch.drop(1).associate { it.id to SentenceTranslation(it.id, "T") } })
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = PretranslationRunner(translator, config()).run(sentences) { done, total -> progress += done to total }

        assertEquals(listOf(listOf("c0s0", "c0s10"), listOf("c0s20", "c0s30"), listOf("c0s40")), translator.batchCalls)
        assertEquals(listOf("c0s0", "c0s20", "c0s40"), translator.singleCalls)
        assertEquals(sentences.map { it.id }.toSet(), result.keys)
        assertEquals(5 to 5, progress.last())
        assertTrue(progress.first() == 0 to 5)
    }

    @Test
    fun existingTranslationsAreKeptAndNotRequestedAgain() = runBlocking {
        val translator = FakeTranslator()
        val existing = mapOf("c0s10" to SentenceTranslation("c0s10", "already"), "stale" to SentenceTranslation("stale", "x"))

        val result = PretranslationRunner(translator, config(perRequest = 10)).run(sentences, existing)

        assertEquals("already", result["c0s10"]?.translation)
        assertTrue("stale" !in result)
        assertEquals(listOf(listOf("c0s0", "c0s20", "c0s30", "c0s40")), translator.batchCalls)
    }

    @Test
    fun onDeviceTranslatorSkipsBatchesAndGoesSentenceBySentence() = runBlocking {
        val translator = FakeTranslator(supportsBatches = false)
        val result = PretranslationRunner(translator, config(PretranslationEngine.OnDevice("gemma"))).run(sentences)
        assertTrue(translator.batchCalls.isEmpty())
        assertEquals(sentences.map { it.id }, translator.singleCalls)
        assertEquals(5, result.size)
    }

    @Test
    fun aFewFailedRequestsAreToleratedButTooManyAbortTheJob() = runBlocking {
        var calls = 0
        val flaky = FakeTranslator(
            batchBehavior = { throw IOException("boom") },
            singleBehavior = { if (calls++ % 2 == 0) throw IOException("flaky") else SentenceTranslation(it.id, "ok") },
        )
        val result = PretranslationRunner(flaky, config(perRequest = 5), maxFailedRequests = 10).run(sentences)
        assertTrue(result.isNotEmpty())

        val alwaysFailing = FakeTranslator(batchBehavior = { throw IOException("down") }, singleBehavior = { throw IOException("down") })
        val error = assertThrows(PretranslationRunner.TooManyFailuresException::class.java) {
            runBlocking { PretranslationRunner(alwaysFailing, config(perRequest = 1), maxFailedRequests = 2).run(sentences) }
        }
        assertTrue(error.message!!.contains("down"))
    }

    @Test
    fun anEmptyBatchReplyIsRetriedInHalvesBeforeFallingBackToSingles() = runBlocking {
        // A 4-sentence batch yields nothing (as a truncated reply would); each half succeeds.
        val translator = FakeTranslator(batchBehavior = { batch ->
            if (batch.size > 2) emptyMap() else batch.associate { it.id to SentenceTranslation(it.id, "T") }
        })
        val result = PretranslationRunner(translator, config(perRequest = 4)).run(sentences.take(4))
        assertEquals(listOf(4, 2, 2), translator.batchCalls.map { it.size })
        assertTrue(translator.singleCalls.isEmpty())
        assertEquals(4, result.size)
    }

    @Test
    fun partialResultsStayInTheCallerSinkWhenTheJobGivesUp() {
        var calls = 0
        val translator = FakeTranslator(
            supportsBatches = false,
            singleBehavior = { if (calls++ < 2) SentenceTranslation(it.id, "ok") else throw IOException("down") },
        )
        val sink = LinkedHashMap<String, SentenceTranslation>()
        assertThrows(PretranslationRunner.TooManyFailuresException::class.java) {
            runBlocking { PretranslationRunner(translator, config(), maxFailedRequests = 1).run(sentences, sink = sink) }
        }
        assertEquals(listOf("c0s0", "c0s10"), sink.keys.toList())
    }

    @Test
    fun rerunWhereEveryNewRequestFailsWithinBudgetIsNotReportedAsSuccess() {
        val dead = FakeTranslator(supportsBatches = false, singleBehavior = { throw IOException("down") })
        val existing = mapOf("c0s0" to SentenceTranslation("c0s0", "old"))
        val error = assertThrows(PretranslationRunner.TooManyFailuresException::class.java) {
            runBlocking { PretranslationRunner(dead, config(), maxFailedRequests = 6).run(sentences.take(3), existing) }
        }
        assertTrue(error.message!!.contains("No sentence could be translated"))
    }

    @Test
    fun nothingTranslatedIsAnError() {
        val silent = FakeTranslator(batchBehavior = { emptyMap() }, singleBehavior = { null })
        assertThrows(PretranslationRunner.TooManyFailuresException::class.java) {
            runBlocking { PretranslationRunner(silent, config()).run(sentences) }
        }
    }
}
