package moe.antimony.hoshi.features.news.pretranslate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import moe.antimony.hoshi.features.reader.sentence.ReaderSentence

/**
 * Translates a plan's sentences with a [SentenceTranslator]: batches first (a batch that yields
 * nothing is split in half and retried, which recovers from replies cut off by an output cap),
 * then single retries for whatever is still missing, tolerating a bounded number of failed
 * requests. Results accumulate in the caller's [sink] so a job that is cancelled or gives up can
 * still persist what was already paid for. Pure logic, unit-tested with fake translators.
 */
class PretranslationRunner(
    private val translator: SentenceTranslator,
    private val config: PretranslationConfig,
    private val maxFailedRequests: Int = DEFAULT_MAX_FAILED_REQUESTS,
) {
    class TooManyFailuresException(message: String, cause: Throwable?) : Exception(message, cause)

    /**
     * Fills [sink] with every translation gathered, including the entries of [existing] that
     * belong to [sentences]. [onProgress] receives the number of sentences translated so far.
     */
    suspend fun run(
        sentences: List<ReaderSentence>,
        existing: Map<String, SentenceTranslation> = emptyMap(),
        sink: MutableMap<String, SentenceTranslation> = LinkedHashMap(),
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<String, SentenceTranslation> {
        val ids = sentences.mapTo(HashSet()) { it.id }
        sink += existing.filterKeys { it in ids }
        val pending = sentences.filterNot { it.id in sink }
        var failures = 0
        var lastError: Throwable? = null
        onProgress(sink.size, sentences.size)

        suspend fun attempt(block: suspend () -> Unit) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures++
                lastError = error
                if (failures > maxFailedRequests) {
                    throw TooManyFailuresException("$failures requests failed; last error: ${error.message}", error)
                }
            }
        }

        suspend fun translateBatch(batch: List<ReaderSentence>) {
            coroutineContext.ensureActive()
            var translated: Map<String, SentenceTranslation> = emptyMap()
            var replied = false
            attempt {
                translated = translator.translateBatch(batch, config.includeExplanations).filterKeys { id -> batch.any { it.id == id } }
                replied = true
            }
            sink += translated
            onProgress(sink.size, sentences.size)
            // The model answered but nothing usable came back (truncated or malformed reply):
            // halve the batch and retry instead of paying for one request per sentence right away.
            // A failed request is not split; it already counted against the failure budget. Pairs
            // are not split either: the single pass that follows covers them.
            if (replied && translated.isEmpty() && batch.size > 2) {
                val half = batch.size / 2
                translateBatch(batch.subList(0, half))
                translateBatch(batch.subList(half, batch.size))
            }
        }

        if (translator.supportsBatches) {
            for (batch in pending.chunked(config.sentencesPerRequest.coerceAtLeast(1))) translateBatch(batch)
        }
        for (sentence in pending) {
            if (sentence.id in sink) continue
            coroutineContext.ensureActive()
            attempt {
                translator.translateOne(sentence, config.includeExplanations)?.let { sink[sentence.id] = it }
            }
            onProgress(sink.size, sentences.size)
        }
        if (sink.isEmpty() && sentences.isNotEmpty()) {
            throw TooManyFailuresException("No sentence could be translated", lastError)
        }
        return sink
    }

    companion object {
        const val DEFAULT_MAX_FAILED_REQUESTS = 6
    }
}
