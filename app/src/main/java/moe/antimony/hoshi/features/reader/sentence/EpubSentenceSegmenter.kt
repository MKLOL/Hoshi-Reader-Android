package moe.antimony.hoshi.features.reader.sentence

import moe.antimony.hoshi.features.ai.EpubTranslationStore

/**
 * One addressable sentence of a chapter. [start] and [length] count matchable characters
 * (the code points `EpubTranslationStore.normalize` keeps), so they are independent of markup
 * and whitespace and match the addresses the desktop pre-translation tool stores.
 */
data class ReaderSentence(
    val spine: Int,
    val start: Int,
    val length: Int,
    val text: String,
    val paragraph: Int,
    /** Furigana over runs of [text], for display only; the tool never sees readings. */
    val ruby: List<RubyAnnotation> = emptyList(),
) {
    /** The pre-translation tool's address for this sentence: chapter + normalized start. */
    val id: String
        get() = "c${spine}s$start"
}

/** A `<ruby>` base inside a sentence: [start] and [length] are UTF-16 units into the sentence text. */
data class RubyAnnotation(val start: Int, val length: Int, val reading: String)

/** A segmented chapter plus the first heading it contains, the tool's fallback chapter title. */
data class SentenceChapterText(val sentences: List<ReaderSentence>, val heading: String)

/**
 * One of the tool's text runs. [groups] is the ruby group of each code point (`-1` for none), or
 * `null` when the run carries no furigana at all; group ids index [ChapterExtraction.readings].
 */
internal class TextRun(val text: String, val groups: IntArray?)

/** The tool's text runs, `null` for a block boundary, plus the readings the runs refer to. */
internal data class ChapterExtraction(val parts: List<TextRun?>, val heading: String, val readings: List<String>)

/**
 * Splits chapter HTML into the exact sentences the desktop pre-translation tool addresses
 * (`tools/pretranslate/hoshi_pretranslate/epub.py`, `_ChapterTextExtractor` + `_segment`), so a
 * sentence shown on its own finds its stored translation by the same id and text hash.
 *
 * The extractor mirrors Python's `html.parser` (with `convert_charrefs=True`) because that is
 * what the tool reads through, and the details matter for offsets:
 *
 *  - A text run is delimited by *any* markup, not by element boundaries, and a terminator only
 *    swallows the trailing brackets in its own run, so `<p>「あ！<em>」</em></p>` is two sentences.
 *  - `rt`/`rp` (furigana), `script` and `style` subtrees are not text; `</ruby>` closes an
 *    unclosed `rt`/`rp` the way browsers do. `script`/`style` content is raw text, so a `<`
 *    inside it is not a tag. Only text after `<body>` counts.
 *  - Character references decode exactly like `html.unescape`: every HTML5 name, the legacy
 *    semicolon-less forms, and numeric references with or without `;`.
 *  - A `<` that does not start markup is text; `<![CDATA[…]]>`, comments, declarations and
 *    processing instructions are skipped whole.
 *  - Block elements end a sentence even without punctuation; `。！？!?` end a sentence and
 *    swallow the closing brackets and ellipses that follow. ASCII `.` is deliberately not a
 *    terminator. A run of 400 matchable characters with no terminator is split at its last comma.
 */
object EpubSentenceSegmenter {
    private const val SENTENCE_TERMINATORS = "。！？!?"
    private const val TRAILING_CHARS = "。、！？…‥」』）)】〉》〕｝}］]"
    private const val SOFT_BREAK = "、，,"
    private const val MAX_SENTENCE_CHARS = 400

    private val BLOCK_TAGS = setOf(
        "p", "div", "br", "hr", "li", "dd", "dt", "blockquote", "pre", "figcaption", "figure",
        "h1", "h2", "h3", "h4", "h5", "h6",
        "section", "article", "aside", "nav", "header", "footer", "main",
        "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption",
        "ul", "ol", "dl",
    )
    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val SKIP_TAGS = setOf("rt", "rp", "script", "style")
    private val FURIGANA_TAGS = setOf("rt", "rp")

    /** `html.parser`'s CDATA_CONTENT_ELEMENTS: their content is raw text until the end tag. */
    private val RAW_TEXT_TAGS = setOf("script", "style")

    fun segment(spine: Int, html: String): List<ReaderSentence> = chapter(spine, html).sentences

    fun chapter(spine: Int, html: String): SentenceChapterText {
        val extraction = extractParts(html)
        return SentenceChapterText(segmentParts(spine, extraction.parts, extraction.readings), extraction.heading)
    }

    /**
     * Turns chapter HTML into text runs with `null` marking a block boundary, and collects the
     * first heading. Tolerant of the markup real books ship (unclosed tags, stray `<`), in the
     * same way the tool's stdlib parser is.
     */
    internal fun extractParts(html: String): ChapterExtraction {
        val parts = mutableListOf<TextRun?>()
        val text = StringBuilder()
        val skipStack = ArrayDeque<String>()
        // A fragment with no <body> at all is all body: the tool never sees one, but the reader
        // may hand over a chapter that was already unwrapped.
        var inBody = !html.contains("<body", ignoreCase = true)
        var heading = ""
        val headingChunks = StringBuilder()
        var headingDepth = 0
        // Furigana ride along for display: text inside <ruby> forms a base group until its <rt>
        // supplies the reading. Offsets are untouched, the tool skips <rt> and so do we.
        val readings = mutableListOf<String>()
        var rubyDepth = 0
        var pendingGroup = -1
        val rtText = StringBuilder()

        // The tool's handle_data: text only counts inside <body> and outside skipped subtrees.
        fun emit(data: String) {
            if (skipStack.isNotEmpty() || !inBody) return
            val groups = if (rubyDepth > 0) {
                if (pendingGroup < 0) {
                    pendingGroup = readings.size
                    readings += ""
                }
                IntArray(data.codePointCount(0, data.length)) { pendingGroup }
            } else {
                null
            }
            parts += TextRun(data, groups)
            if (headingDepth > 0) headingChunks.append(data)
        }

        fun flushText() {
            if (text.isNotEmpty()) {
                val decoded = decodeEntities(text.toString())
                if (skipStack.lastOrNull() == "rt") rtText.append(decoded) else emit(decoded)
                text.setLength(0)
            }
        }

        // An <rt> closed, explicitly or by a sibling or </ruby>: its text is the reading of the
        // base group collected before it. A reading with no base is dropped.
        fun finishRt() {
            val reading = collapseWhitespace(rtText.toString())
            rtText.setLength(0)
            if (pendingGroup >= 0) {
                if (readings[pendingGroup].isEmpty()) readings[pendingGroup] = reading
                pendingGroup = -1
            }
        }

        fun boundary() {
            flushText()
            parts += null
        }

        fun skipPast(marker: String, from: Int): Int {
            val end = html.indexOf(marker, from)
            return if (end < 0) html.length else end + marker.length
        }

        var i = 0
        val n = html.length
        while (i < n) {
            if (html[i] != '<') {
                val next = html.indexOf('<', i).let { if (it < 0) n else it }
                text.append(html, i, next)
                i = next
                continue
            }
            // Every piece of markup ends the current text run, exactly like the parser's data
            // callback; what does not parse as markup is text of its own.
            flushText()
            val next = html.getOrNull(i + 1)
            when {
                html.startsWith("<!--", i) -> i = skipPast("-->", i + 4)
                html.startsWith("<![", i) -> i = skipPast("]]>", i + 3)
                next == '!' || next == '?' -> i = skipPast(">", i + 2)
                next != null && next.isAsciiLetter() -> {
                    val end = tagEnd(html, i)
                    if (end < 0) break // an unfinished tag at the end of the file is dropped
                    val raw = html.substring(i + 1, end).trim()
                    i = end + 1
                    val selfClosing = raw.endsWith("/")
                    val name = tagName(raw)
                    if (name == "body") inBody = true
                    if (name in SKIP_TAGS) {
                        if (selfClosing) continue // a self-closing skip tag has no content
                        if (name in FURIGANA_TAGS && skipStack.lastOrNull() in FURIGANA_TAGS) {
                            if (skipStack.removeLast() == "rt") finishRt()
                        }
                        skipStack.addLast(name)
                        if (name in RAW_TEXT_TAGS) i = rawTextEnd(html, i, name)
                        continue
                    }
                    if (skipStack.isNotEmpty()) continue
                    if (name == "ruby" && !selfClosing) {
                        rubyDepth += 1
                        pendingGroup = -1
                    }
                    if (name in BLOCK_TAGS) boundary()
                    // A self-closing heading has no text and must not leave the depth raised.
                    if (!selfClosing && name in HEADING_TAGS && heading.isEmpty()) headingDepth += 1
                }
                next == '/' -> {
                    if (html.startsWith("</>", i)) {
                        i += 3
                        continue
                    }
                    val end = html.indexOf('>', i + 2)
                    if (end < 0) break
                    val raw = html.substring(i + 2, end).trim()
                    i = end + 1
                    if (raw.isEmpty() || !raw[0].isAsciiLetter()) continue // a bogus comment
                    val name = tagName(raw)
                    if (name in SKIP_TAGS) {
                        if (name in skipStack) {
                            while (skipStack.isNotEmpty()) {
                                val popped = skipStack.removeLast()
                                if (popped == "rt") finishRt()
                                if (popped == name) break
                            }
                        }
                        continue
                    }
                    if (name == "ruby") {
                        // </ruby> closes any <rt>/<rp> still open, as every browser does.
                        while (skipStack.lastOrNull() in FURIGANA_TAGS) {
                            if (skipStack.removeLast() == "rt") finishRt()
                        }
                        if (rubyDepth > 0) rubyDepth -= 1
                        pendingGroup = -1
                    }
                    if (skipStack.isNotEmpty()) continue
                    if (name in BLOCK_TAGS) boundary()
                    if (name in HEADING_TAGS && headingDepth > 0) {
                        headingDepth -= 1
                        if (headingDepth == 0 && heading.isEmpty()) {
                            heading = collapseWhitespace(headingChunks.toString())
                            headingChunks.setLength(0)
                        }
                    }
                }
                else -> {
                    // `<` followed by anything else is text, delivered as a run of its own.
                    emit("<")
                    i += 1
                }
            }
        }
        flushText()
        return ChapterExtraction(parts, heading, readings)
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    /** The parser's tag name: up to whitespace, `/` or the end, lowercased. */
    private fun tagName(raw: String): String =
        raw.takeWhile { !it.isWhitespace() && it != '/' }.lowercase()

    /** Index of the `>` closing the tag that starts at [start], honoring quoted attributes. */
    private fun tagEnd(html: String, start: Int): Int {
        var quote: Char? = null
        var i = start + 1
        while (i < html.length) {
            val c = html[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return -1
    }

    /**
     * Where a `<script>`/`<style>` element's raw text ends: at `</script` (any case) followed by
     * whitespace, `/` or `>`, like the parser's CDATA mode. The end tag itself is left for the
     * main loop, which pops the skip stack. No end tag means the rest of the file is script.
     */
    private fun rawTextEnd(html: String, from: Int, name: String): Int {
        val closer = "</$name"
        var i = from
        while (true) {
            val found = html.indexOf(closer, i, ignoreCase = true)
            if (found < 0) return html.length
            val after = html.getOrNull(found + closer.length)
            if (after == null || after == '>' || after == '/' || after.isWhitespace()) return found
            i = found + 1
        }
    }

    internal fun segmentParts(
        spine: Int,
        parts: List<TextRun?>,
        readings: List<String> = emptyList(),
    ): List<ReaderSentence> {
        val sentences = mutableListOf<ReaderSentence>()
        var normalizedCount = 0
        // One entry per code point, so an over-long split lands on a character boundary; the
        // parallel list carries each code point's ruby group.
        var buffer = mutableListOf<String>()
        var bufferGroups = mutableListOf<Int>()
        var bufferStart: Int? = null
        var bufferLength = 0
        var paragraph = 0
        var lastSoftBreak = -1

        fun flush() {
            val start = bufferStart
            if (start != null && bufferLength > 0) {
                val (text, ruby) = collapse(buffer, bufferGroups, readings)
                if (text.isNotEmpty()) {
                    sentences += ReaderSentence(spine, start, bufferLength, text, paragraph, ruby)
                }
            }
            buffer = mutableListOf()
            bufferGroups = mutableListOf()
            bufferStart = null
            bufferLength = 0
            lastSoftBreak = -1
        }

        fun splitOverlong() {
            val headEnd = if (lastSoftBreak in 1 until buffer.size - 1) lastSoftBreak + 1 else buffer.size
            val head = buffer.subList(0, headEnd).toList()
            val headGroups = bufferGroups.subList(0, headEnd).toList()
            val tail = buffer.subList(headEnd, buffer.size).toList()
            val tailGroups = bufferGroups.subList(headEnd, buffer.size).toList()
            val headLength = head.count { it.isMatchable() }
            val (text, ruby) = collapse(head, headGroups, readings)
            val start = bufferStart
            if (text.isNotEmpty() && headLength > 0 && start != null) {
                sentences += ReaderSentence(spine, start, headLength, text, paragraph, ruby)
            }
            val tailMatchable = tail.count { it.isMatchable() }
            buffer = tail.toMutableList()
            bufferGroups = tailGroups.toMutableList()
            bufferLength = tailMatchable
            bufferStart = if (start != null && tailMatchable > 0) start + headLength else null
            lastSoftBreak = -1
        }

        for (part in parts) {
            if (part == null) {
                flush()
                paragraph += 1
                continue
            }
            val chars = part.text.codePointStrings()
            val groups = part.groups
            var position = 0
            while (position < chars.size) {
                val char = chars[position]
                buffer += char
                bufferGroups += groups?.get(position) ?: -1
                position += 1
                if (char.isMatchable()) {
                    if (bufferStart == null) bufferStart = normalizedCount
                    normalizedCount += 1
                    bufferLength += 1
                } else if (char.length == 1 && char[0] in SOFT_BREAK) {
                    lastSoftBreak = buffer.size - 1
                }
                if (char.length == 1 && char[0] in SENTENCE_TERMINATORS) {
                    while (position < chars.size && chars[position].let { it.length == 1 && it[0] in TRAILING_CHARS }) {
                        val trailing = chars[position]
                        buffer += trailing
                        bufferGroups += groups?.get(position) ?: -1
                        position += 1
                        if (trailing.isMatchable()) {
                            normalizedCount += 1
                            bufferLength += 1
                        }
                    }
                    flush()
                } else if (bufferLength >= MAX_SENTENCE_CHARS) {
                    splitOverlong()
                }
            }
        }
        flush()
        return sentences
    }

    /**
     * The sentence text ([collapseWhitespace] of the buffer) plus its furigana, whose offsets are
     * UTF-16 units into that collapsed text. A base whose group never got a reading is dropped.
     */
    private fun collapse(
        chars: List<String>,
        groups: List<Int>,
        readings: List<String>,
    ): Pair<String, List<RubyAnnotation>> {
        val out = StringBuilder()
        val outGroups = ArrayList<Int>(chars.size)
        var pendingSpace = false
        for (i in chars.indices) {
            val char = chars[i]
            if (char.length == 1 && char[0].isPythonSpace()) {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) {
                    out.append(' ')
                    outGroups += -1
                }
                pendingSpace = false
                out.append(char)
                repeat(char.length) { outGroups += groups[i] }
            }
        }
        val ruby = mutableListOf<RubyAnnotation>()
        var i = 0
        while (i < outGroups.size) {
            val group = outGroups[i]
            if (group < 0) {
                i++
                continue
            }
            var j = i
            while (j < outGroups.size && outGroups[j] == group) j++
            val reading = readings.getOrNull(group).orEmpty()
            if (reading.isNotEmpty()) ruby += RubyAnnotation(i, j - i, reading)
            i = j
        }
        return out.toString() to ruby
    }

    /** Matchable characters before [charOffset] in [text]: how far into the sentence a tap landed. */
    fun matchableCount(text: String, charOffset: Int): Int {
        var count = 0
        var i = 0
        val end = charOffset.coerceIn(0, text.length)
        while (i < end) {
            val codePoint = text.codePointAt(i)
            if (EpubTranslationStore.isMatchableCodePoint(codePoint)) count++
            i += Character.charCount(codePoint)
        }
        return count
    }

    private fun String.isMatchable(): Boolean = EpubTranslationStore.isMatchableCodePoint(codePointAt(0))

    private fun String.codePointStrings(): List<String> {
        val out = ArrayList<String>(length)
        var i = 0
        while (i < length) {
            val codePoint = codePointAt(i)
            val width = Character.charCount(codePoint)
            out += substring(i, i + width)
            i += width
        }
        return out
    }

    /**
     * Python `" ".join(text.split())`: every run of whitespace, in `str.isspace`'s sense (which
     * includes NBSP, U+3000 and NEL), becomes one space.
     */
    internal fun collapseWhitespace(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        for (char in text) {
            if (char.isPythonSpace()) {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.append(char)
            }
        }
        return out.toString()
    }

    /** Python `str.isspace` for one UTF-16 unit: Java's whitespace plus NEL. */
    private fun Char.isPythonSpace(): Boolean = isWhitespace() || this == '\u0085'

    /**
     * Python's `html.unescape`, which the tool's parser applies to every text run: named
     * references with the longest-prefix rule for the legacy semicolon-less names, numeric
     * references with or without `;`, and the standard's replacements for invalid code points.
     */
    internal fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        val out = StringBuilder(text.length)
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            if (i + 1 < n && text[i + 1] == '#') {
                val hex = i + 2 < n && (text[i + 2] == 'x' || text[i + 2] == 'X')
                val digitsStart = if (hex) i + 3 else i + 2
                var j = digitsStart
                while (j < n && (if (hex) text[j].isAsciiHexDigit() else text[j] in '0'..'9')) j++
                if (j == digitsStart) {
                    out.append(c)
                    i++
                    continue
                }
                var value = 0
                for (k in digitsStart until j) {
                    value = value * (if (hex) 16 else 10) + Character.digit(text[k], 16)
                    if (value > 0x10FFFF) value = 0x110000 // saturate: anything above is U+FFFD
                }
                if (j < n && text[j] == ';') j++
                out.append(numericReference(value))
                i = j
                continue
            }
            // A name: up to 32 characters that are not `\t \n \f space < & # ;`, then an optional `;`.
            var j = i + 1
            while (j < n && j - (i + 1) < 32 && text[j] !in NAME_STOP) j++
            if (j == i + 1) {
                out.append(c)
                i++
                continue
            }
            val hasSemicolon = j < n && text[j] == ';'
            val name = text.substring(i + 1, if (hasSemicolon) j + 1 else j)
            i = if (hasSemicolon) j + 1 else j
            val exact = HtmlEntities.table[name]
            if (exact != null) {
                out.append(exact)
                continue
            }
            var replaced = false
            for (x in name.length - 1 downTo 2) {
                val prefix = HtmlEntities.table[name.substring(0, x)]
                if (prefix != null) {
                    out.append(prefix).append(name, x, name.length)
                    replaced = true
                    break
                }
            }
            if (!replaced) out.append('&').append(name)
        }
        return out.toString()
    }

    private const val NAME_STOP = "\t\n\u000C <&#;"

    private fun Char.isAsciiHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun numericReference(code: Int): String {
        HtmlEntities.invalidCharrefs[code]?.let { return it }
        if (code in 0xD800..0xDFFF || code > 0x10FFFF) return "�"
        if (HtmlEntities.isInvalidCodepoint(code)) return ""
        return String(Character.toChars(code))
    }
}
