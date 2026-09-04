package moe.antimony.hoshi.features.reader.sentence

import moe.antimony.hoshi.epub.isReaderMatchableCodePoint

/**
 * One addressable sentence of a chapter. [start] and [length] count matchable characters
 * (the same code points `filteredReaderText` keeps), so they are independent of markup and
 * whitespace and match the addresses the desktop pre-translation tool stores.
 */
data class ReaderSentence(
    val spine: Int,
    val start: Int,
    val length: Int,
    val text: String,
    val paragraph: Int,
) {
    /** The pre-translation tool's address for this sentence: chapter + normalized start. */
    val id: String
        get() = "c${spine}s$start"
}

/**
 * Splits chapter HTML into the exact sentences the desktop pre-translation tool addresses
 * (`tools/pretranslate/hoshi_pretranslate/epub.py`, `_segment`), so a sentence shown on its own
 * finds its stored translation by the same id and text hash. The rules, in order:
 *
 *  - `rt`/`rp` (furigana), `script` and `style` subtrees are not text; `</ruby>` closes an
 *    unclosed `rt`/`rp` the way browsers do. Only text after `<body>` counts.
 *  - Block elements end a sentence even without punctuation, so headings and unpunctuated
 *    lines of dialogue stand on their own.
 *  - `。！？!?` end a sentence and swallow the closing brackets and ellipses that follow, so a
 *    sentence ending inside quotes keeps its closing bracket. ASCII `.` is deliberately not a
 *    terminator: in Japanese prose it is a decimal point or an abbreviation far more often.
 *  - A run of 400 matchable characters with no terminator is split at its last comma.
 *  - A run with no matchable characters at all is dropped.
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
    private val SKIP_TAGS = setOf("rt", "rp", "script", "style")
    private val FURIGANA_TAGS = setOf("rt", "rp")

    fun segment(spine: Int, html: String): List<ReaderSentence> = segmentParts(spine, extractParts(html))

    /**
     * Turns chapter HTML into text runs with `null` marking a block boundary.
     *
     * A run is delimited by *any* tag, not by element boundaries: that is what `html.parser` hands
     * the tool, and it matters because a terminator only swallows the trailing brackets that sit
     * in its own run, so `<p>「あ！<em>」</em></p>` is two sentences on every platform.
     *
     * Tolerant of the markup real books ship (unclosed tags, stray `<`), like the tool's parser.
     */
    internal fun extractParts(html: String): List<String?> {
        val parts = mutableListOf<String?>()
        val text = StringBuilder()
        val skipStack = ArrayDeque<String>()
        var inBody = !html.contains("<body", ignoreCase = true)

        fun flushText() {
            if (text.isNotEmpty()) {
                parts += decodeEntities(text.toString())
                text.setLength(0)
            }
        }

        fun boundary() {
            flushText()
            parts += null
        }

        var i = 0
        val n = html.length
        while (i < n) {
            if (html[i] != '<') {
                val next = html.indexOf('<', i).let { if (it < 0) n else it }
                if (skipStack.isEmpty() && inBody) text.append(html, i, next)
                i = next
                continue
            }
            flushText() // any markup ends the current text run, exactly like html.parser
            if (html.startsWith("<!--", i)) {
                val end = html.indexOf("-->", i + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            if (html.startsWith("<!", i) || html.startsWith("<?", i)) {
                val end = html.indexOf('>', i)
                i = if (end < 0) n else end + 1
                continue
            }
            val end = tagEnd(html, i)
            if (end < 0) break
            val raw = html.substring(i + 1, end).trim()
            i = end + 1
            val closing = raw.startsWith("/")
            val selfClosing = !closing && raw.endsWith("/")
            val name = raw.trim('/').trim().takeWhile { !it.isWhitespace() && it != '/' }.lowercase()
            if (name.isEmpty()) continue
            if (!closing) {
                if (name == "body") inBody = true
                if (name in SKIP_TAGS) {
                    if (selfClosing) continue // a self-closing skip tag has no content
                    if (name in FURIGANA_TAGS && skipStack.lastOrNull() in FURIGANA_TAGS) skipStack.removeLast()
                    skipStack.addLast(name)
                    continue
                }
                if (skipStack.isNotEmpty()) continue
                if (name in BLOCK_TAGS) boundary()
            } else {
                if (name in SKIP_TAGS) {
                    if (name in skipStack) {
                        while (skipStack.isNotEmpty() && skipStack.removeLast() != name) { /* unwind */ }
                    }
                    continue
                }
                if (name == "ruby") {
                    while (skipStack.lastOrNull() in FURIGANA_TAGS) skipStack.removeLast()
                }
                if (skipStack.isNotEmpty()) continue
                if (name in BLOCK_TAGS) boundary()
            }
        }
        flushText()
        return parts
    }

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

    internal fun segmentParts(spine: Int, parts: List<String?>): List<ReaderSentence> {
        val sentences = mutableListOf<ReaderSentence>()
        var normalizedCount = 0
        // One entry per code point, so an over-long split lands on a character boundary.
        var buffer = mutableListOf<String>()
        var bufferStart: Int? = null
        var bufferLength = 0
        var paragraph = 0
        var lastSoftBreak = -1

        fun collapsed(chars: List<String>): String = collapseWhitespace(chars.joinToString(""))

        fun flush() {
            val start = bufferStart
            if (start != null && bufferLength > 0) {
                val text = collapsed(buffer)
                if (text.isNotEmpty()) {
                    sentences += ReaderSentence(spine, start, bufferLength, text, paragraph)
                }
            }
            buffer = mutableListOf()
            bufferStart = null
            bufferLength = 0
            lastSoftBreak = -1
        }

        fun splitOverlong() {
            val (head, tail) = if (lastSoftBreak in 1 until buffer.size - 1) {
                buffer.subList(0, lastSoftBreak + 1).toList() to buffer.subList(lastSoftBreak + 1, buffer.size).toList()
            } else {
                buffer.toList() to emptyList()
            }
            val headLength = head.count { it.isMatchable() }
            val text = collapsed(head)
            val start = bufferStart
            if (text.isNotEmpty() && headLength > 0 && start != null) {
                sentences += ReaderSentence(spine, start, headLength, text, paragraph)
            }
            val tailMatchable = tail.count { it.isMatchable() }
            buffer = tail.toMutableList()
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
            val chars = part.codePointStrings()
            var position = 0
            while (position < chars.size) {
                val char = chars[position]
                position += 1
                buffer += char
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
                        position += 1
                        buffer += trailing
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

    /** Matchable characters before [charOffset] in [text] — how far into the sentence a tap landed. */
    fun matchableCount(text: String, charOffset: Int): Int {
        var count = 0
        var i = 0
        val end = charOffset.coerceIn(0, text.length)
        while (i < end) {
            val codePoint = text.codePointAt(i)
            if (codePoint.isReaderMatchableCodePoint()) count++
            i += Character.charCount(codePoint)
        }
        return count
    }

    private fun String.isMatchable(): Boolean = codePointAt(0).isReaderMatchableCodePoint()

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

    /** Python `" ".join(text.split())`: every whitespace run, including NBSP and U+3000, becomes one space. */
    internal fun collapseWhitespace(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        for (char in text) {
            if (char.isWhitespace() || char == ' ' || char == ' ' || char == ' ') {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.append(char)
            }
        }
        return out.toString()
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "ensp" to " ", "emsp" to " ",
        "thinsp" to " ", "copy" to "©", "reg" to "®", "trade" to "™", "laquo" to "«", "raquo" to "»",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "middot" to "·", "bull" to "•",
        "deg" to "°", "times" to "×",
    )

    internal fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = text.indexOf(';', i + 1)
            if (end < 0 || end - i > 12) {
                out.append(c)
                i++
                continue
            }
            val body = text.substring(i + 1, end)
            val decoded = when {
                body.startsWith("#x", ignoreCase = true) -> body.substring(2).toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
                body.startsWith("#") -> body.substring(1).toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
                else -> NAMED_ENTITIES[body]
            }
            if (decoded == null) {
                out.append(c)
                i++
            } else {
                out.append(decoded)
                i = end + 1
            }
        }
        return out.toString()
    }
}
