package moe.antimony.hoshi.features.news

/**
 * Built-in easy-Japanese news sources. Users can disable any of them and add their own RSS feeds
 * through [NewsSettingsRepository]; those custom sources carry `builtIn = false`.
 */
object NewsSourceCatalog {
    const val NHK_EASY_ID = "nhk-easy"
    const val WATANOC_ID = "watanoc"
    const val MATCHA_EASY_ID = "matcha-easy"

    /**
     * NHK NEWS WEB EASY moved to a client-rendered site in 2025 whose news API needs a session
     * token, so both the listing and the articles are read from the rendered page. Outside Japan
     * the site first shows an access notice whose acknowledgment runs through a sign-in redirect;
     * the extractor clicks it, but the listing has not been reachable from abroad in testing, so
     * the source starts disabled and says so in its name.
     */
    val nhkEasy = NewsSource(
        id = NHK_EASY_ID,
        name = "NHK NEWS WEB EASY (Japan only)",
        enabledByDefault = false,
        homepage = "https://news.web.nhk/news/easy/",
        listing = NewsListing.WebPage(
            url = "https://news.web.nhk/news/easy/",
            articleUrlPattern = """https://news\.web\.nhk/news/easy/(ne\d+)/\1\.html""",
        ),
        hints = NewsExtractionHints(
            articleSelectors = listOf("article", "main"),
            titleSelectors = listOf("h1"),
            removeSelectors = listOf("nav", "header", "footer", "aside", "video", "audio", "button"),
            readySelector = "article p, main p",
            acknowledgeSelector = "button[data-erpccontent-start-button-abroad]",
        ),
    )

    val watanoc = NewsSource(
        id = WATANOC_ID,
        name = "Watanoc",
        homepage = "https://watanoc.com/",
        listing = NewsListing.Rss("https://watanoc.com/feed"),
        hints = NewsExtractionHints(
            articleSelectors = listOf(".entry-content", "article"),
            titleSelectors = listOf("h1.entry-title", "h1"),
            removeSelectors = listOf(".sharedaddy", ".jp-relatedposts", ".wp-block-buttons", "nav", "footer", "aside"),
        ),
    )

    val matchaEasy = NewsSource(
        id = MATCHA_EASY_ID,
        name = "MATCHA Easy Japanese",
        homepage = "https://matcha-jp.com/easy",
        listing = NewsListing.WebPage(
            url = "https://matcha-jp.com/easy",
            articleUrlPattern = """https://matcha-jp\.com/easy/\d+""",
        ),
        hints = NewsExtractionHints(
            articleSelectors = listOf(".article-body", ".p-article__body", "article", "main"),
            titleSelectors = listOf("h1"),
            removeSelectors = listOf(
                "nav", "header", "footer", "aside", ".c-ad", ".ad", "[class*=\"share\"]",
                "[class*=\"breadcrumb\"]", "[class*=\"tagList\"]", "[class*=\"tag-list\"]", ".tags", "[class*=\"related\"]",
            ),
        ),
    )

    val builtIn: List<NewsSource> = listOf(nhkEasy, watanoc, matchaEasy)

    const val SHARED_LINK_ID = "shared-link"

    /** Pseudo-source for article URLs shared into the app from a browser; never listed. */
    val sharedLink = NewsSource(
        id = SHARED_LINK_ID,
        name = "Shared link",
        homepage = "",
        listing = NewsListing.None,
        builtIn = true,
        enabledByDefault = true,
    )

    /** The built-in source that owns [url], or [sharedLink] when none matches. */
    fun sourceForUrl(url: String): NewsSource =
        builtIn.firstOrNull { source -> homepageHost(source)?.let { host -> url.contains(host) } == true } ?: sharedLink

    private fun homepageHost(source: NewsSource): String? =
        runCatching { java.net.URI(source.homepage).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }

    fun builtInById(id: String): NewsSource? = builtIn.firstOrNull { it.id == id }

    /** A user-added RSS feed. The id is derived from the URL so re-adding the same feed is idempotent. */
    fun customRss(name: String, url: String): NewsSource = NewsSource(
        id = "rss-" + sha256Hex(url.trim()).take(16),
        name = name.trim().ifEmpty { url.trim() },
        homepage = url.trim(),
        listing = NewsListing.Rss(url.trim()),
        builtIn = false,
    )
}
