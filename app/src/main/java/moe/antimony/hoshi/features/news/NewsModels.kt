package moe.antimony.hoshi.features.news

import kotlinx.serialization.Serializable

/**
 * Where a source's article list comes from.
 *
 * [Rss] is fetched with a plain HTTP GET and parsed by [RssFeedParser]. [WebPage] is rendered in a
 * hidden WebView and the article links are read from the DOM, which is what client-rendered sites
 * such as NHK NEWS WEB EASY require; [articleUrlPattern] tells the page extractor which anchors
 * are articles.
 */
@Serializable
sealed interface NewsListing {
    @Serializable
    data class Rss(val url: String) : NewsListing

    @Serializable
    data class WebPage(val url: String, val articleUrlPattern: String) : NewsListing

    /** No listing: the source only describes how to extract articles the user shares by URL. */
    @Serializable
    data object None : NewsListing
}

/** Selectors the article extractor tries first for a source before falling back to heuristics. */
@Serializable
data class NewsExtractionHints(
    val articleSelectors: List<String> = emptyList(),
    val titleSelectors: List<String> = emptyList(),
    val removeSelectors: List<String> = emptyList(),
    /** Selector that must exist before a client-rendered article is considered loaded. */
    val readySelector: String? = null,
    /**
     * Selector of a one-time access-notice button the extractor may click once per page load
     * (NHK ONE's "For users abroad — I understand"). Null for every other source.
     */
    val acknowledgeSelector: String? = null,
)

@Serializable
data class NewsSource(
    val id: String,
    val name: String,
    val homepage: String,
    val listing: NewsListing,
    val hints: NewsExtractionHints = NewsExtractionHints(),
    /** ISO 639-1 language of the articles; written into the generated EPUB. */
    val language: String = "ja",
    val builtIn: Boolean = true,
    /** Built-ins that cannot work for most users (geo-restricted) start switched off. */
    val enabledByDefault: Boolean = true,
)

/** One entry of a source's listing before it is saved. */
@Serializable
data class NewsArticle(
    /** Stable id derived from the URL, see [NewsArticle.idFor]. */
    val id: String,
    val sourceId: String,
    val url: String,
    val title: String,
    /** Epoch milliseconds, or null when the listing carried no date. */
    val publishedAt: Long? = null,
    val summary: String? = null,
    val imageUrl: String? = null,
    /** Epoch milliseconds when the entry was last seen in a listing. */
    val fetchedAt: Long,
) {
    companion object {
        fun idFor(url: String): String = sha256Hex(url.trim()).take(24)
    }
}

/** State of an article the user asked to keep. */
@Serializable
data class SavedNewsArticle(
    val articleId: String,
    val bookId: String,
    val sourceId: String,
    val url: String,
    val title: String,
    val savedAt: Long,
    val translation: NewsTranslationRecord? = null,
)

/** Outcome of the last pre-translation run for a saved article. */
@Serializable
data class NewsTranslationRecord(
    val model: String,
    val sentenceCount: Int,
    val translatedCount: Int,
    val completedAt: Long,
    val uploaded: Boolean,
)

/** Everything the article extractor returns for one page. */
data class ExtractedNewsArticle(
    val url: String,
    val title: String,
    /** Well-formed XHTML fragment (the article container serialized by XMLSerializer). */
    val xhtml: String,
    /** Plain text fallback used when [xhtml] cannot be parsed. */
    val text: String,
    val publishedAt: Long? = null,
    val imageUrl: String? = null,
)

/** One entry read from a listing page or feed before it becomes a [NewsArticle]. */
data class NewsListingItem(
    val url: String,
    val title: String,
    val publishedAt: Long? = null,
    val summary: String? = null,
    val imageUrl: String? = null,
)

internal fun sha256Hex(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
