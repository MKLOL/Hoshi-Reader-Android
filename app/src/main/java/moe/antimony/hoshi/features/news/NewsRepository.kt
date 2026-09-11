package moe.antimony.hoshi.features.news

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.features.bookshelf.BookshelfRepository
import moe.antimony.hoshi.ui.UiText

/** Everything the News tab shows. */
data class NewsFeedState(
    val articles: List<NewsArticle> = emptyList(),
    val saved: Map<String, SavedNewsArticle> = emptyMap(),
    val refreshing: Set<String> = emptySet(),
    val saving: Set<String> = emptySet(),
    /** Per-source error from the last refresh, keyed by source id. */
    val sourceErrors: Map<String, UiText> = emptyMap(),
    val refreshedAt: Map<String, Long> = emptyMap(),
    val loaded: Boolean = false,
)

/**
 * Coordinates listings, saved articles and their conversion into books.
 *
 * Saving writes a self-contained EPUB directory through [NewsArticleEpubWriter] and hands it to
 * [BookshelfRepository.importExtractedEpubDirectory]; from then on the article is an ordinary book
 * that the reader, sync and shelves handle without knowing where it came from. The only news-owned
 * state is the `News/` cache in [NewsFeedStore].
 */
internal class NewsRepository(
    private val filesDir: File,
    private val store: NewsFeedStore,
    private val settings: NewsSettingsRepository,
    private val extractor: NewsArticleExtractor,
    private val http: NewsHttp,
    private val bookshelf: BookshelfRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(NewsFeedState())
    val state: StateFlow<NewsFeedState> = _state
    private val refreshMutex = Mutex()
    private val saveLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun load() {
        val articles = store.loadArticles()
        val saved = store.loadSaved().associateBy { it.articleId }
        val refreshedAt = store.refreshedAt()
        _state.update { it.copy(articles = articles, saved = saved, refreshedAt = refreshedAt, loaded = true) }
    }

    /** Refreshes every enabled source, one at a time so the hidden WebView is never contended. */
    suspend fun refreshAll(force: Boolean = false) {
        val current = settings.current()
        val stale = current.enabledSources.filter { source ->
            force || (now() - (_state.value.refreshedAt[source.id] ?: 0L)) > STALE_AFTER_MILLIS
        }
        for (source in stale) refreshSource(source)
        // Drop cached entries of sources the user disabled or removed.
        val enabledIds = current.enabledSources.map { it.id }.toSet()
        _state.value.articles.map { it.sourceId }.toSet()
            .filterNot { it in enabledIds }
            .forEach { store.removeSourceArticles(it) }
        load()
    }

    suspend fun refreshSource(source: NewsSource) {
        refreshMutex.withLock {
            _state.update { it.copy(refreshing = it.refreshing + source.id) }
            try {
                val items = when (val listing = source.listing) {
                    is NewsListing.Rss -> {
                        val body = http.getText(listing.url)
                        withContext(Dispatchers.Default) { RssFeedParser.parse(body) }
                    }
                    is NewsListing.WebPage -> extractor.extractListing(source)
                    NewsListing.None -> return@withLock
                }
                if (items.isEmpty()) throw NewsNoArticlesException("No articles found for ${source.name}")
                val fetchedAt = now()
                val articles = items.distinctBy { it.url }
                    .sortedByDescending { it.publishedAt }
                    .take(MAX_ARTICLES_PER_SOURCE).map { item ->
                    NewsArticle(
                        id = NewsArticle.idFor(item.url),
                        sourceId = source.id,
                        url = item.url,
                        title = item.title,
                        publishedAt = item.publishedAt,
                        summary = item.summary,
                        imageUrl = item.imageUrl,
                        fetchedAt = fetchedAt,
                    )
                }
                store.replaceSourceArticles(source.id, articles, fetchedAt)
                _state.update { it.copy(sourceErrors = it.sourceErrors - source.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message: UiText = when (error) {
                    is NewsNoArticlesException -> UiText.Resource(R.string.news_error_no_articles)
                    else -> UiText.Resource(R.string.news_error_source_failed_format, error.message ?: error.javaClass.simpleName)
                }
                _state.update { it.copy(sourceErrors = it.sourceErrors + (source.id to message)) }
            } finally {
                _state.update { it.copy(refreshing = it.refreshing - source.id) }
            }
        }
        load()
    }

    /**
     * "Want to read": downloads the article, writes it as a book and puts it on the News shelf.
     * Returns the saved record; an article saved earlier is returned as-is.
     */
    suspend fun saveArticle(article: NewsArticle): SavedNewsArticle =
        saveLocks.getOrPut(article.id) { Mutex() }.withLock { saveArticleLocked(article) }

    private suspend fun saveArticleLocked(article: NewsArticle): SavedNewsArticle {
        store.saved(article.id)?.let { existing ->
            if (bookshelfHasBook(existing.bookId)) return existing
            store.removeSaved(article.id)
        }
        val source = settings.current().sources.firstOrNull { it.id == article.sourceId }
            ?: NewsSourceCatalog.builtInById(article.sourceId)
            ?: NewsSourceCatalog.sharedLink.takeIf { it.id == article.sourceId }
            ?: throw NewsExtractionException("Unknown source ${article.sourceId}")
        _state.update { it.copy(saving = it.saving + article.id) }
        try {
            val extracted = extractor.extractArticle(source, article.url)
            val body = withContext(Dispatchers.Default) {
                NewsArticleXhtml.sanitize(extracted.xhtml) ?: NewsArticleXhtml.paragraphsFromText(extracted.text)
            }
            val cover = (extracted.imageUrl ?: article.imageUrl)?.let { url ->
                runCatching { http.getBytes(url, MAX_COVER_BYTES) }.getOrNull()?.takeIf { it.isNotEmpty() }
            }
            val title = extracted.title.trim().ifEmpty { article.title }
            val tempRoot = withContext(ioDispatcher) {
                File(filesDir, "ImportTemp/${UUID.randomUUID()}").canonicalFile.also { root ->
                    NewsArticleEpubWriter.write(
                        root,
                        NewsArticleEpubWriter.Input(
                            title = title,
                            bodyXhtml = body,
                            sourceName = source.name,
                            sourceUrl = article.url,
                            language = source.language,
                            publishedAt = extracted.publishedAt ?: article.publishedAt,
                            cover = cover,
                        ),
                    )
                }
            }
            val bookId = bookshelf.importExtractedEpubDirectory(
                tempRoot,
                folderName = articleFolderName(title, article.url),
            )
            runCatching { ensureOnNewsShelf(bookId) }
            val saved = SavedNewsArticle(
                articleId = article.id,
                bookId = bookId,
                sourceId = source.id,
                url = article.url,
                title = title,
                savedAt = now(),
            )
            store.upsertSaved(saved)
            _state.update { it.copy(saved = it.saved + (article.id to saved)) }
            return saved
        } finally {
            _state.update { it.copy(saving = it.saving - article.id) }
        }
    }

    /**
     * An article the user shared into the app by URL. It is attributed to the built-in source that
     * owns the host (so its extraction hints apply) or to the shared-link pseudo-source, and is
     * saved right away; the listing cache is left alone.
     */
    suspend fun saveSharedUrl(url: String): SavedNewsArticle {
        val source = NewsSourceCatalog.sourceForUrl(url)
        val article = NewsArticle(
            id = NewsArticle.idFor(url),
            sourceId = source.id,
            url = url,
            title = url,
            fetchedAt = now(),
        )
        return saveArticle(article)
    }

    suspend fun recordTranslation(articleId: String, record: NewsTranslationRecord) {
        store.updateSaved(articleId) { it.copy(translation = record) }
        _state.update { state ->
            val saved = state.saved[articleId] ?: return@update state
            state.copy(saved = state.saved + (articleId to saved.copy(translation = record)))
        }
    }

    /** Forgets saved records whose book was deleted from the shelf. */
    suspend fun reconcileSavedBooks() {
        val saved = store.loadSaved()
        if (saved.isEmpty()) return
        val bookIds = bookshelf.loadBooks(BookSortOption.Recent).entries.map { it.metadata.id }.toSet()
        for (record in saved) {
            if (record.bookId !in bookIds) store.removeSaved(record.articleId)
        }
        load()
    }

    private suspend fun bookshelfHasBook(bookId: String): Boolean =
        bookshelf.loadBooks(BookSortOption.Recent).entries.any { it.metadata.id == bookId }

    private suspend fun ensureOnNewsShelf(bookId: String) {
        val shelves = bookshelf.loadBooks(BookSortOption.Recent).shelves
        if (shelves.none { it.name == NEWS_SHELF_NAME }) bookshelf.createShelf(NEWS_SHELF_NAME)
        bookshelf.moveBooks(setOf(bookId), NEWS_SHELF_NAME)
    }

    companion object {
        /** Shelf name is book data shared with other devices, so it is not localized. */
        const val NEWS_SHELF_NAME = "News"
        private const val STALE_AFTER_MILLIS = 30L * 60L * 1000L
        private const val MAX_ARTICLES_PER_SOURCE = 60
        private const val MAX_COVER_BYTES = 3 * 1024 * 1024

        /** `Books/` directory name: readable title plus a URL hash so two articles never collide. */
        fun articleFolderName(title: String, url: String): String {
            val cleaned = title.replace(FOLDER_UNSAFE, "_").trim()
            // Cut on a code point boundary so an emoji in the title cannot leave a lone surrogate.
            val safe = cleaned.substring(0, cleaned.offsetByCodePoints(0, cleaned.codePointCount(0, cleaned.length).coerceAtMost(60)))
                .ifEmpty { "article" }
            return "$safe-${sha256Hex(url).take(8)}"
        }

        private val FOLDER_UNSAFE = Regex("[\\\\/:*?\"<>|\\p{Cntrl} ]")
    }
}
