package moe.antimony.hoshi.features.mangareader

import com.atilika.kuromoji.ipadic.Tokenizer
import java.text.Normalizer
import java.util.Collections
import java.util.concurrent.CancellationException

/** UTF-16 offsets into the unmodified OCR text, matching Kotlin and JavaScript strings. */
internal data class MangaFuriganaSpan(val start: Int, val length: Int, val reading: String)

/** Offline readings for existing manga. Call from a background dispatcher, including first use. */
internal object MangaFurigana {
    private const val CACHE_ENTRIES = 512
    private const val MAX_TEXT_LENGTH = 8_192
    private const val MAX_ALIGNMENT_LENGTH = 128

    // The dictionary is loaded once, on the first background call, never at app startup.
    // An unavailable dictionary leaves the ordinary OCR text usable for this process.
    private val tokenizer: Tokenizer? by lazy {
        try {
            Tokenizer()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private val cache = object : LinkedHashMap<String, List<MangaFuriganaSpan>>(CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MangaFuriganaSpan>>): Boolean =
            size > CACHE_ENTRIES
    }

    fun annotations(text: String): List<MangaFuriganaSpan> {
        if (text.isEmpty() || text.length > MAX_TEXT_LENGTH || !containsKanji(text)) return emptyList()
        synchronized(cache) { cache[text]?.let { return it } }
        val result = try {
            tokenizer?.tokenize(text)?.flatMap { token ->
                val surface = token.surface
                val start = token.position
                val reading = toHiragana(token.reading)
                if (!token.isKnown || !containsKanji(surface) || !isReading(reading) ||
                    start < 0 || start > text.length - surface.length ||
                    !text.regionMatches(start, surface, 0, surface.length)
                ) {
                    emptyList()
                } else {
                    alignReading(surface, reading, start)
                }
            }.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // OCR remains readable if a dictionary/tokenization failure occurs.
            emptyList()
        }
        val immutableResult = Collections.unmodifiableList(result)
        synchronized(cache) { cache[text] = immutableResult }
        return immutableResult
    }

    private data class Part(val start: Int, val text: String, val kana: Boolean)

    private fun alignReading(surface: String, reading: String, start: Int): List<MangaFuriganaSpan> {
        fun wholeToken() = listOf(MangaFuriganaSpan(start, surface.length, reading))
        if (surface.length > MAX_ALIGNMENT_LENGTH || reading.length > MAX_ALIGNMENT_LENGTH) return wholeToken()
        val parts = mutableListOf<Part>()
        var offset = 0
        while (offset < surface.length) {
            val partStart = offset
            val kana = isKana(surface.codePointAt(offset))
            do {
                offset += Character.charCount(surface.codePointAt(offset))
            } while (offset < surface.length && isKana(surface.codePointAt(offset)) == kana)
            val text = surface.substring(partStart, offset)
            // Never divide a token by guessing a reading for a non-Japanese character.
            if (!kana && !text.codePoints().allMatch { isKanji(it) || it == 0x3005 || it == 0x3006 }) {
                return wholeToken()
            }
            parts += Part(partStart, if (kana) toHiragana(text) else text, kana)
        }

        // Count possible alignments, capped at two. Ambiguous kana anchors keep one ruby
        // over the whole dictionary token instead of choosing a speculative kanji split.
        val counts = HashMap<Pair<Int, Int>, Int>()
        fun count(partIndex: Int, readingOffset: Int): Int {
            if (partIndex == parts.size) return if (readingOffset == reading.length) 1 else 0
            val key = partIndex to readingOffset
            counts[key]?.let { return it }
            val part = parts[partIndex]
            val result = if (part.kana) {
                if (reading.startsWith(part.text, readingOffset)) {
                    count(partIndex + 1, readingOffset + part.text.length)
                } else 0
            } else {
                var possibilities = 0
                for (end in readingOffset + 1..reading.length) {
                    possibilities += count(partIndex + 1, end)
                    if (possibilities >= 2) break
                }
                possibilities.coerceAtMost(2)
            }
            counts[key] = result
            return result
        }
        if (count(0, 0) != 1) return wholeToken()

        val result = mutableListOf<MangaFuriganaSpan>()
        var readingOffset = 0
        parts.forEachIndexed { index, part ->
            if (part.kana) {
                readingOffset += part.text.length
            } else {
                val end = (readingOffset + 1..reading.length).first { count(index + 1, it) == 1 }
                result += MangaFuriganaSpan(
                    start = start + part.start,
                    length = part.text.length,
                    reading = reading.substring(readingOffset, end),
                )
                readingOffset = end
            }
        }
        return result
    }

    private fun containsKanji(text: String): Boolean = text.codePoints().anyMatch(::isKanji)

    private fun isKanji(codePoint: Int): Boolean = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN

    private fun isKana(codePoint: Int): Boolean = codePoint in 0x3041..0x3096 ||
        codePoint in 0x3099..0x309E || codePoint in 0x30A1..0x30FA ||
        codePoint in 0x30FC..0x30FE || codePoint in 0xFF66..0xFF9F

    private fun toHiragana(text: String): String = buildString {
        Normalizer.normalize(text, Normalizer.Form.NFKC).forEach { character ->
            append(when (character.code) {
                in 0x30A1..0x30F6, in 0x30FD..0x30FE -> (character.code - 0x60).toChar()
                else -> character
            })
        }
    }

    private fun isReading(reading: String): Boolean = reading.any { it.code in 0x3041..0x3096 } &&
        reading.all { it.code in 0x3041..0x3096 || it.code in 0x3099..0x309E || it == 'ー' }
}
