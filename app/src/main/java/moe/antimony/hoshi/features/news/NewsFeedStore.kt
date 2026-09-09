package moe.antimony.hoshi.features.news

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-device cache of the news listings and the articles the user kept.
 *
 * Lives in its own `News/` directory next to `Books/`, so nothing here is visible to the EPUB
 * or manga code paths, to sync, or to backups of book data. Saved articles reference their book
 * by id; the book itself is an ordinary EPUB directory owned by [moe.antimony.hoshi.epub.BookRepository].
 */
class NewsFeedStore(
    private val filesDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val directory get() = File(filesDir, "News")
    // Several instances exist per process (one per Activity container, one in the translation
    // service), so the read-modify-write lock is keyed by directory rather than by instance.
    private val mutex: Mutex = synchronized(locks) { locks.getOrPut(directory.absolutePath) { Mutex() } }

    @Serializable
    private data class FeedFile(val version: Int = 1, val articles: List<NewsArticle> = emptyList(), val refreshedAt: Map<String, Long> = emptyMap())

    @Serializable
    private data class SavedFile(val version: Int = 1, val saved: List<SavedNewsArticle> = emptyList())

    suspend fun loadArticles(): List<NewsArticle> = withContext(ioDispatcher) { readFeed().articles }

    suspend fun refreshedAt(): Map<String, Long> = withContext(ioDispatcher) { readFeed().refreshedAt }

    /** Replaces the cached listing of one source, keeping other sources' entries untouched. */
    suspend fun replaceSourceArticles(sourceId: String, articles: List<NewsArticle>, refreshedAt: Long) =
        withContext(ioDispatcher) {
            mutex.withLock {
                val current = readFeed()
                val kept = current.articles.filterNot { it.sourceId == sourceId }
                val merged = (kept + articles.filter { it.sourceId == sourceId })
                    .sortedWith(compareByDescending<NewsArticle> { it.publishedAt ?: it.fetchedAt }.thenBy { it.id })
                    .take(MAX_CACHED_ARTICLES)
                writeFeed(current.copy(articles = merged, refreshedAt = current.refreshedAt + (sourceId to refreshedAt)))
            }
        }

    suspend fun removeSourceArticles(sourceId: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val current = readFeed()
            writeFeed(current.copy(articles = current.articles.filterNot { it.sourceId == sourceId }, refreshedAt = current.refreshedAt - sourceId))
        }
    }

    suspend fun loadSaved(): List<SavedNewsArticle> = withContext(ioDispatcher) { readSaved().saved }

    suspend fun saved(articleId: String): SavedNewsArticle? = loadSaved().firstOrNull { it.articleId == articleId }

    suspend fun upsertSaved(saved: SavedNewsArticle) = withContext(ioDispatcher) {
        mutex.withLock {
            val current = readSaved()
            writeSaved(current.copy(saved = current.saved.filterNot { it.articleId == saved.articleId } + saved))
        }
    }

    suspend fun updateSaved(articleId: String, transform: (SavedNewsArticle) -> SavedNewsArticle) = withContext(ioDispatcher) {
        mutex.withLock {
            val current = readSaved()
            writeSaved(current.copy(saved = current.saved.map { if (it.articleId == articleId) transform(it) else it }))
        }
    }

    suspend fun removeSaved(articleId: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val current = readSaved()
            writeSaved(current.copy(saved = current.saved.filterNot { it.articleId == articleId }))
        }
    }

    private fun readFeed(): FeedFile = read(File(directory, FEED_FILE), FeedFile.serializer()) ?: FeedFile()
    private fun readSaved(): SavedFile = read(File(directory, SAVED_FILE), SavedFile.serializer()) ?: SavedFile()
    private fun writeFeed(file: FeedFile) = write(File(directory, FEED_FILE), json.encodeToString(FeedFile.serializer(), file))
    private fun writeSaved(file: SavedFile) = write(File(directory, SAVED_FILE), json.encodeToString(SavedFile.serializer(), file))

    private fun <T> read(file: File, serializer: kotlinx.serialization.KSerializer<T>): T? {
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    private fun write(file: File, body: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(body)
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Unable to write ${file.name}" }
        }
    }

    private companion object {
        const val FEED_FILE = "feed.json"
        const val SAVED_FILE = "saved.json"
        const val MAX_CACHED_ARTICLES = 600
        val locks = HashMap<String, Mutex>()
    }
}
