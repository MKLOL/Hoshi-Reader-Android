package moe.antimony.hoshi.features.news

import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Tolerant RSS 2.0 / Atom parser for news listings.
 *
 * Feeds in the wild are frequently not well-formed XML (unescaped ampersands, stray CDATA,
 * HTML in descriptions), so this deliberately scans for `<item>` / `<entry>` blocks with regular
 * expressions instead of a strict XML parser and never throws on malformed input: a feed that
 * cannot be understood simply yields no items.
 */
object RssFeedParser {
    fun parse(body: String): List<NewsListingItem> {
        val items = ITEM_BLOCK.findAll(body).map { it.groupValues[1] }.toList()
        return items.mapNotNull(::parseItem)
    }

    private fun parseItem(block: String): NewsListingItem? {
        val link = element(block, "link")?.let(::decode)?.trim()?.takeIf { it.isNotEmpty() }
            ?: ALTERNATE_LINK.find(block)?.groupValues?.get(1)?.let(::decode)?.trim()?.takeIf { it.isNotEmpty() }
            ?: attribute(block, "link", "href")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val title = element(block, "title")?.let(::cleanText)?.trim().orEmpty()
        val published = (element(block, "pubDate") ?: element(block, "published") ?: element(block, "updated") ?: element(block, "dc:date"))
            ?.trim()
            ?.let(::parseDate)
        val summary = (element(block, "description")?.let(::cleanSummary)
            ?: (element(block, "summary") ?: element(block, "content"))?.let(::cleanText))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(400)
        val image = attribute(block, "media:content", "url")
            ?: attribute(block, "media:thumbnail", "url")
            ?: attribute(block, "enclosure", "url")?.takeIf { attribute(block, "enclosure", "type")?.startsWith("image/") != false }
            ?: IMG_SRC.find(decode(element(block, "description") ?: element(block, "content") ?: ""))?.groupValues?.get(1)
        return NewsListingItem(
            url = link,
            title = title.ifEmpty { link },
            publishedAt = published,
            summary = summary,
            imageUrl = image,
        )
    }

    private fun element(block: String, name: String): String? {
        val pattern = Regex("""<$name(?:\s[^>]*)?>(.*?)</$name>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return pattern.find(block)?.groupValues?.get(1)
    }

    private fun attribute(block: String, element: String, attribute: String): String? {
        val pattern = Regex("""<$element\b[^>]*\s$attribute\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        return pattern.find(block)?.groupValues?.get(1)?.let(::decode)
    }

    /**
     * Markup inside CDATA is real markup and is removed; entity-encoded angle brackets are literal
     * text and survive, which is why tags are stripped before entities are decoded.
     */
    private fun cleanText(raw: String): String = decodeEntities(stripTags(unwrapCdata(raw)))

    /** RSS descriptions contain HTML, either XML-escaped or wrapped in CDATA. */
    private fun cleanSummary(raw: String): String {
        val html = CDATA.matchEntire(raw.trim())?.groupValues?.get(1) ?: decodeEntities(raw)
        // Furigana is useful in the reader, but duplicates words in a plain-text preview.
        return decodeEntities(stripTags(RUBY_ANNOTATION.replace(html, "")))
    }

    /** Unwraps CDATA, then decodes the handful of entities feeds actually use. */
    internal fun decode(raw: String): String = decodeEntities(unwrapCdata(raw))

    private fun unwrapCdata(raw: String): String {
        val text = raw.trim()
        return CDATA.find(text)?.groupValues?.get(1) ?: text
    }

    private fun decodeEntities(text: String): String {
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace(NUMERIC_ENTITY) { match ->
                val value = match.groupValues[1]
                val codePoint = if (value.startsWith("x", ignoreCase = true)) value.drop(1).toIntOrNull(16) else value.toIntOrNull()
                codePoint?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: match.value
            }
            .replace("&amp;", "&")
    }

    private fun stripTags(text: String): String = TAG.replace(text, " ").replace(WHITESPACE, " ")

    internal fun parseDate(raw: String): Long? {
        runCatching { return OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        for (pattern in FALLBACK_DATE_PATTERNS) {
            runCatching {
                return ZonedDateTime.parse(raw, DateTimeFormatter.ofPattern(pattern, Locale.US)).toInstant().toEpochMilli()
            }
        }
        return null
    }

    private val ITEM_BLOCK = Regex("""<(?:item|entry)\b[^>]*>(.*?)</(?:item|entry)>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    /** Atom entries may list `rel="replies"`/`"self"` links before the article link. */
    private val ALTERNATE_LINK = Regex("""<link\b(?=[^>]*\srel\s*=\s*["']alternate["'])[^>]*\shref\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val CDATA = Regex("""^<!\[CDATA\[(.*?)]]>$""", RegexOption.DOT_MATCHES_ALL)
    private val NUMERIC_ENTITY = Regex("""&#(x?[0-9a-fA-F]+);""")
    private val TAG = Regex("""<[^>]+>""")
    private val RUBY_ANNOTATION = Regex("""<(rt|rp)\b[^>]*>.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val WHITESPACE = Regex("""\s+""")
    private val IMG_SRC = Regex("""<img\b[^>]*\ssrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val FALLBACK_DATE_PATTERNS = listOf(
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm Z",
        "d MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd HH:mm:ss Z",
    )
}
