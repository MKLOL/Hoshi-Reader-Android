package moe.antimony.hoshi.features.reader.sentence

import kotlin.math.ceil
import kotlin.math.min
import java.text.BreakIterator
import java.util.Locale

/** A publisher's complete ruby base; its offsets still address the original sentence. */
internal data class SentenceRubyGroup(
    val start: Int,
    val end: Int,
    val base: String,
    val reading: String,
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
            // An inline placeholder cannot span paragraphs or half of a supplementary character.
            if ((start > 0 && start < text.length && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) ||
                (end < text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate())
            ) return@mapNotNull null
            val base = text.substring(start, end)
            if ('\n' in base || '\r' in base) return@mapNotNull null
            previousEnd = end
            SentenceRubyGroup(start, end, base, annotation.reading)
        }
    val groups = mutableListOf<SentenceRubyGroup>()
    var position = 0
    fun appendUnannotated(end: Int) {
        while (position < end) {
            val codePoint = text.codePointAt(position)
            if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) {
                position += Character.charCount(codePoint)
                continue
            }
            val start = position
            var count = 0
            while (position < end) {
                val next = text.codePointAt(position)
                if (Character.UnicodeScript.of(next) != Character.UnicodeScript.HAN && next != 0x3005 && next != 0x303B) break
                position += Character.charCount(next)
                count += 1
            }
            // Even without publisher readings, keep compounds such as 大丈夫 or 人々 together.
            // Kana remains native paragraph text; a whole Japanese sentence is never one unit.
            if (count >= 2) groups += SentenceRubyGroup(start, position, text.substring(start, position), "")
        }
    }
    publisherGroups.forEach { group ->
        appendUnannotated(group.start)
        groups += group
        position = group.end
    }
    appendUnannotated(text.length)
    return coalesceSingleKanjiReadings(groups)
}

/**
 * Books often encode a compound as one ruby per kanji. Such adjacent single-character labels
 * can safely share one base and concatenated reading. Explicit word-sized publisher bases are
 * boundaries: joining those could put one word's reading over a different neighboring word.
 */
private fun coalesceSingleKanjiReadings(groups: List<SentenceRubyGroup>): List<SentenceRubyGroup> {
    val result = mutableListOf<SentenceRubyGroup>()
    var pending: SentenceRubyGroup? = null
    fun flush() {
        pending?.let(result::add)
        pending = null
    }
    for (group in groups) {
        val singleKanjiReading = group.reading.isNotBlank() && group.base.codePointCount(0, group.base.length) == 1 &&
            Character.UnicodeScript.of(group.base.codePointAt(0)) == Character.UnicodeScript.HAN
        if (!singleKanjiReading) {
            flush()
            result += group
            continue
        }
        val previous = pending
        if (previous != null && previous.end == group.start) {
            pending = SentenceRubyGroup(previous.start, group.end, previous.base + group.base, previous.reading + group.reading)
        } else {
            flush()
            pending = group
        }
    }
    flush()
    return result
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
