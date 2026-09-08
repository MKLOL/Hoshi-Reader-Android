package moe.antimony.hoshi.features.reader.sentence

internal data class SentenceLookupQuery(
    /** UTF-16 offset for Compose geometry, sentence context, and highlight ranges. */
    val startOffset: Int,
    val text: String,
)

/** Keep complete Unicode characters at both ends of the native lookup query. */
internal fun sentenceLookupQuery(text: String, tapOffset: Int): SentenceLookupQuery? {
    if (text.isEmpty()) return null
    var start = tapOffset.coerceIn(0, text.lastIndex)
    if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) {
        start -= 1
    }
    var end = start
    var characterCount = 0
    while (end < text.length && characterCount < MAX_TAP_QUERY_CHARACTERS) {
        end += Character.charCount(text.codePointAt(end))
        characterCount += 1
    }
    return SentenceLookupQuery(startOffset = start, text = text.substring(start, end))
}

/** Longest query handed to the dictionary from a tap; the scan-length setting trims it further. */
private const val MAX_TAP_QUERY_CHARACTERS = 32
