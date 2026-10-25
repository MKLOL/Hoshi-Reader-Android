package moe.antimony.hoshi.features.reader.sentence

import kotlin.math.ceil
import kotlin.math.min
import java.text.BreakIterator
import java.util.Locale

/** One wrapping atom, with readings still anchored to their own relative base spans. */
internal data class SentenceRubyGroup(
    val start: Int,
    val end: Int,
    val base: String,
    val reading: String,
    val annotations: List<RubyAnnotation> = if (reading.isBlank()) emptyList() else listOf(RubyAnnotation(0, base.length, reading)),
)

/** Invalid or overlapping annotations must never replace or hide sentence characters. */
internal fun sentenceRubyGroups(text: String, annotations: List<RubyAnnotation>): List<SentenceRubyGroup> {
    var previousEnd = 0
    val publisherGroups = annotations.sortedWith(compareBy<RubyAnnotation> { it.start }.thenByDescending { it.length })
        .mapNotNull { annotation ->
            val start = annotation.start
            if (start < previousEnd || start < 0 || annotation.length <= 0 ||
                start > text.length || annotation.length > text.length - start || annotation.reading.isBlank()
            ) return@mapNotNull null
            val end = start + annotation.length
            if ((start > 0 && start < text.length && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) ||
                (end < text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate())
            ) return@mapNotNull null
            val base = text.substring(start, end)
            if ('\n' in base || '\r' in base) return@mapNotNull null
            previousEnd = end
            annotation
        }
    val byStart = publisherGroups.associateBy { it.start }
    fun isCompoundCharacter(offset: Int): Boolean {
        val codePoint = text.codePointAt(offset)
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN || codePoint == 0x3005 || codePoint == 0x303B
    }
    // An explicit multi-character publisher base is a word boundary. Single-kanji readings
    // and unannotated kanji can belong to the same compound without sharing one reading.
    fun isPublisherWord(annotation: RubyAnnotation): Boolean =
        text.codePointCount(annotation.start, annotation.start + annotation.length) > 1 || !isCompoundCharacter(annotation.start)
    val groups = mutableListOf<SentenceRubyGroup>()
    var position = 0
    while (position < text.length) {
        val annotation = byStart[position]
        if (annotation != null && isPublisherWord(annotation)) {
            val end = position + annotation.length
            groups += SentenceRubyGroup(position, end, text.substring(position, end), annotation.reading)
            position = end
            continue
        }
        if (!isCompoundCharacter(position)) {
            position += Character.charCount(text.codePointAt(position))
            continue
        }
        val start = position
        val children = mutableListOf<RubyAnnotation>()
        var characterCount = 0
        while (position < text.length && isCompoundCharacter(position)) {
            val child = byStart[position]
            if (child != null && isPublisherWord(child)) break
            if (child != null) children += child.copy(start = child.start - start)
            position += Character.charCount(text.codePointAt(position))
            characterCount += 1
        }
        if (characterCount >= 2 || children.isNotEmpty()) {
            groups += SentenceRubyGroup(start, position, text.substring(start, position), children.joinToString("") { it.reading }, children)
        }
    }
    return groups
}

/** Physical pixel geometry of one atomic group, including an optional reading above it. */
internal data class SentenceRubyPlacement(
    val width: Float,
    val height: Float,
    val scale: Float,
    val baseLeft: Float,
    val baseTop: Float,
    val readingLeft: Float,
)

/** A word wider than its pane shrinks as a whole; neither its base nor its reading wraps. */
internal fun sentenceRubyPlacement(
    baseWidth: Float,
    baseHeight: Float,
    readingWidth: Float,
    readingHeight: Float,
    availableWidth: Float,
): SentenceRubyPlacement {
    val naturalWidth = maxOf(baseWidth, readingWidth, 1f)
    val paneWidth = availableWidth.coerceAtLeast(1f)
    val scale = min(1f, paneWidth / naturalWidth)
    val width = ceil(naturalWidth * scale).coerceAtMost(paneWidth)
    return SentenceRubyPlacement(
        width = width,
        height = ceil((baseHeight + readingHeight) * scale),
        scale = scale,
        baseLeft = (width - baseWidth * scale) / 2f,
        baseTop = readingHeight * scale,
        readingLeft = (width - readingWidth * scale) / 2f,
    )
}

/** Translate an inline base's local UTF-16 hit back to a complete original character. */
internal fun sentenceRubyHitOffset(group: SentenceRubyGroup, localOffset: Int): Int {
    return group.start + sentenceCharacterStart(group.base, localOffset)
}

internal data class SentenceGlyphBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/** Keep taps on a supplementary or combining character at the beginning of its grapheme. */
internal fun sentenceCharacterStart(text: String, offset: Int): Int {
    if (text.isEmpty()) return 0
    val boundaries = BreakIterator.getCharacterInstance(Locale.JAPANESE).apply { setText(text) }
    val clamped = offset.coerceIn(0, text.lastIndex)
    return if (boundaries.isBoundary(clamped)) clamped else boundaries.preceding(clamped).coerceAtLeast(0)
}

/**
 * Layout hit testing returns the nearest caret, which is the following character when the
 * right half of a glyph is pressed. Resolve the actual grapheme rectangle around that caret.
 * [bounds] must cover the supplied UTF-16 grapheme range, including its combining marks.
 */
internal fun sentenceCharacterHit(
    text: String,
    caretOffset: Int,
    x: Float,
    y: Float,
    bounds: (start: Int, end: Int) -> SentenceGlyphBounds,
): Int {
    if (text.isEmpty()) return 0
    val boundaries = BreakIterator.getCharacterInstance(Locale.JAPANESE).apply { setText(text) }
    val candidate = sentenceCharacterStart(text, caretOffset)
    val previous = boundaries.preceding(candidate)
    val following = boundaries.following(candidate)
    for (start in listOf(candidate, previous, following)) {
        if (start < 0 || start >= text.length) continue
        val end = boundaries.following(start).takeIf { it != BreakIterator.DONE } ?: text.length
        if (bounds(start, end).contains(x, y)) return start
    }
    return candidate
}
