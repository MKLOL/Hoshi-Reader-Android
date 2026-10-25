package moe.antimony.hoshi.features.reader.sentence

import moe.antimony.hoshi.features.ai.EpubSentenceAnchor
import moe.antimony.hoshi.features.ai.EpubSentenceTranslation
import moe.antimony.hoshi.features.ai.EpubTranslationStore

/** A complete normalized chapter lets us validate even the part of a legacy anchor off-screen. */
internal fun sentenceTranslationChapterTexts(sentences: List<ReaderSentence>): Map<Int, String> =
    sentences.groupBy { it.spine }.mapNotNull { (spine, chapter) ->
        val normalized = StringBuilder()
        var count = 0
        for (sentence in chapter.sortedBy { it.start }) {
            val text = EpubTranslationStore.normalize(sentence.text)
            if (sentence.start != count || text.codePointCount(0, text.length) != sentence.length) return@mapNotNull null
            normalized.append(text)
            count += sentence.length
        }
        spine to normalized.toString()
    }.toMap()

/**
 * Earlier tools cut a ruby base at character 400. Its old translations remain useful after
 * moving that boundary, but cannot be sliced along with the Japanese. Show their full original
 * source context and translation, and validate their entire span against this book first.
 */
internal fun compatibleSentenceTranslation(
    sentence: ReaderSentence,
    chapterText: String,
    anchors: List<EpubSentenceAnchor>,
    lookup: (EpubSentenceAnchor) -> EpubSentenceTranslation?,
): EpubSentenceTranslation? {
    val chapterLength = chapterText.codePointCount(0, chapterText.length)
    fun slice(start: Int, length: Int): String? {
        if (start < 0 || length <= 0 || start > chapterLength || length > chapterLength - start) return null
        val begin = chapterText.offsetByCodePoints(0, start)
        return chapterText.substring(begin, chapterText.offsetByCodePoints(begin, length))
    }
    if (slice(sentence.start, sentence.length) != EpubTranslationStore.normalize(sentence.text)) return null
    val end = sentence.start.toLong() + sentence.length
    val matching = anchors.filter { anchor ->
        anchor.start.toLong() < end && anchor.start.toLong() + anchor.len > sentence.start &&
            slice(anchor.start, anchor.len) == anchor.text
    }.sortedBy { it.start }
    // Ambiguous overlapping entries cannot be assigned to one sentence reliably.
    if (matching.zipWithNext().any { (first, second) -> first.start.toLong() + first.len > second.start }) return null
    val translations = matching.mapNotNull { anchor ->
        lookup(anchor)?.takeIf { EpubTranslationStore.normalize(it.sentenceText) == anchor.text }
    }
    if (translations.isEmpty()) return null
    fun source(text: String): String = text.lineSequence().joinToString("\n") { "> $it" }
    return EpubSentenceTranslation(
        sentenceText = translations.joinToString("\n\n") { it.sentenceText },
        translation = translations.joinToString("\n\n") { "${source(it.sentenceText)}\n\n${it.translation}" },
        explanation = translations.filter { it.explanation.isNotBlank() }
            .joinToString("\n\n") { "${source(it.sentenceText)}\n\n${it.explanation}" },
        model = translations.map { it.model }.distinct().joinToString(", "),
    )
}
