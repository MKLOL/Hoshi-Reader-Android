package moe.antimony.hoshi.features.ai

import de.manhhao.hoshi.LookupResult
import moe.antimony.hoshi.dictionary.LookupEngine
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import java.lang.Character.UnicodeBlock

private const val AiChatDictionaryMaxResults = 3
private const val AiChatGlossariesPerResult = 4

internal fun buildAiChatDictionaryLookup(
    query: String,
    settings: DictionarySettings,
    lookup: (String, Int, Int) -> List<LookupResult> = LookupEngine::lookup,
): AiChatDictionaryLookup? {
    val normalized = settings.normalized()
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return null
    for (candidate in candidateLookupQueries(trimmed)) {
        val results = runCatching {
            lookup(
                candidate,
                normalized.maxResults.coerceAtMost(AiChatDictionaryMaxResults),
                normalized.scanLength,
            )
        }.getOrDefault(emptyList())
        val lookupSnapshot = results.toAiChatDictionaryLookup(candidate)
        if (lookupSnapshot != null) return lookupSnapshot
    }
    return null
}

internal fun List<LookupResult>.toAiChatDictionaryLookup(query: String): AiChatDictionaryLookup? {
    val compactResults = take(AiChatDictionaryMaxResults)
        .map { it.toAiChatDictionaryLookupResult() }
    if (compactResults.isEmpty()) return null
    return AiChatDictionaryLookup(
        query = query,
        results = compactResults,
    )
}

private fun LookupResult.toAiChatDictionaryLookupResult(): AiChatDictionaryLookupResult =
    AiChatDictionaryLookupResult(
        expression = term.expression,
        reading = term.reading,
        matched = matched,
        deinflectionTrace = process
            .reversedArray()
            .map { step ->
                AiChatDeinflectionStep(
                    name = step.name,
                    description = step.description,
                )
            },
        glossaries = term.glossaries
            .take(AiChatGlossariesPerResult)
            .map { glossary ->
                AiChatGlossary(
                    dictionary = glossary.dictName,
                    content = glossary.glossary,
                    definitionTags = glossary.definitionTags,
                    termTags = glossary.termTags,
                )
            },
        frequencies = term.frequencies.map { group ->
            AiChatFrequencyGroup(
                dictionary = group.dictName,
                frequencies = group.frequencies.map { frequency ->
                    AiChatFrequency(
                        value = frequency.value,
                        displayValue = frequency.displayValue,
                    )
                },
            )
        },
        pitches = term.pitches.map { group ->
            AiChatPitchGroup(
                dictionary = group.dictName,
                pitchPositions = group.pitchPositions.toList(),
            )
        },
        rules = term.rules
            .splitToSequence(' ')
            .filter { it.isNotBlank() }
            .toList(),
    )

internal fun candidateLookupQueries(text: String): List<String> {
    val candidates = linkedSetOf<String>()
    val trimmed = text.trim()
    if (trimmed.isNotEmpty()) candidates += trimmed

    var start = -1
    fun flush(end: Int) {
        if (start >= 0 && end > start) {
            val candidate = text.substring(start, end).trimJapaneseLookupEdges()
            if (candidate.isNotEmpty()) candidates += candidate
        }
        start = -1
    }

    text.forEachIndexed { index, char ->
        if (char.isJapaneseLookupChar()) {
            if (start < 0) start = index
        } else {
            flush(index)
        }
    }
    flush(text.length)

    return candidates.toList()
}

private fun String.trimJapaneseLookupEdges(): String =
    trim { !it.isJapaneseLookupChar() }

private fun Char.isJapaneseLookupChar(): Boolean {
    if (this == 'ー' || this == '々' || this == '〆' || this == '〤') return true
    return when (UnicodeBlock.of(this)) {
        UnicodeBlock.HIRAGANA,
        UnicodeBlock.KATAKANA,
        UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS,
        UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
        UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
        -> true
        else -> false
    }
}
