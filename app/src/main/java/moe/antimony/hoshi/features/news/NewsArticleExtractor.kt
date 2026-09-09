package moe.antimony.hoshi.features.news

/**
 * Reads listings and articles out of rendered web pages.
 *
 * The production implementation ([WebViewNewsExtractor]) drives a hidden WebView so that
 * client-rendered sites work and the page's own scripts fetch whatever they need; tests use a
 * fake. Implementations serialize their work: one page renders at a time.
 */
interface NewsArticleExtractor {
    /** Article links found on a source's listing page. */
    suspend fun extractListing(source: NewsSource): List<NewsListingItem>

    /** The readable body of one article page. Throws when the page yields no article text. */
    suspend fun extractArticle(source: NewsSource, url: String): ExtractedNewsArticle
}

open class NewsExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A listing rendered but contained no article links; shown to the user as "No articles found". */
class NewsNoArticlesException(message: String) : NewsExtractionException(message)
