package moe.antimony.hoshi.features.news.pretranslate

import android.content.Context
import moe.antimony.hoshi.features.ai.ChatProvider
import moe.antimony.hoshi.features.ai.CloudChat
import moe.antimony.hoshi.features.ai.offline.OfflineLlmManager
import moe.antimony.hoshi.features.reader.sentence.ReaderSentence

/** The model behind a pre-translation job. Implementations are stateless and may be called serially. */
interface SentenceTranslator {
    /** Whether [translateBatch] is worth calling; small on-device models answer one sentence at a time. */
    val supportsBatches: Boolean

    /** Translates several sentences in one request. May return a subset; missing ids are retried singly. */
    suspend fun translateBatch(sentences: List<ReaderSentence>, includeExplanations: Boolean): Map<String, SentenceTranslation>

    /** Translates one sentence, or returns null when the reply was unusable. */
    suspend fun translateOne(sentence: ReaderSentence, includeExplanations: Boolean): SentenceTranslation?
}

/** Sends batches through the same clients the reader's live translation uses. */
class CloudSentenceTranslator(
    private val provider: ChatProvider,
    private val apiKey: String,
    private val modelId: String,
) : SentenceTranslator {
    override val supportsBatches: Boolean = true

    override suspend fun translateBatch(sentences: List<ReaderSentence>, includeExplanations: Boolean): Map<String, SentenceTranslation> {
        val reply = CloudChat.complete(
            provider = provider,
            apiKey = apiKey,
            model = modelId,
            prompt = SentenceBatchPrompt.instructions(includeExplanations),
            bubbleText = SentenceBatchPrompt.itemsJson(sentences),
        )
        return SentenceBatchPrompt.parseBatch(reply, sentences.mapTo(HashSet()) { it.id })
    }

    override suspend fun translateOne(sentence: ReaderSentence, includeExplanations: Boolean): SentenceTranslation? {
        val reply = CloudChat.complete(
            provider = provider,
            apiKey = apiKey,
            model = modelId,
            prompt = SentenceBatchPrompt.singleInstructions(includeExplanations),
            bubbleText = sentence.text,
        )
        return SentenceBatchPrompt.parseSingle(reply, sentence.id)
    }
}

/** Runs the downloaded llama.cpp model one sentence at a time; explanations are not requested. */
class OnDeviceSentenceTranslator(context: Context) : SentenceTranslator {
    private val appContext = context.applicationContext
    override val supportsBatches: Boolean = false

    override suspend fun translateBatch(sentences: List<ReaderSentence>, includeExplanations: Boolean): Map<String, SentenceTranslation> =
        emptyMap()

    override suspend fun translateOne(sentence: ReaderSentence, includeExplanations: Boolean): SentenceTranslation? {
        val result = OfflineLlmManager.translate(
            appContext = appContext,
            instruction = ON_DEVICE_INSTRUCTION,
            japaneseText = sentence.text,
            maxTokens = 512,
        )
        val text = result.text.trim().takeIf { it.isNotEmpty() } ?: return null
        return SentenceTranslation(sentence.id, text)
    }

    private companion object {
        const val ON_DEVICE_INSTRUCTION =
            "Translate the following Japanese sentence into natural English. Reply with the translation only."
    }
}
