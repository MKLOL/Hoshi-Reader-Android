package moe.antimony.hoshi.features.news

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NewsFeedStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun article(source: String, url: String, published: Long?) = NewsArticle(
        id = NewsArticle.idFor(url), sourceId = source, url = url, title = "t $url", publishedAt = published, fetchedAt = 1_000,
    )

    @Test
    fun replacingOneSourceKeepsOtherSourcesAndSortsNewestFirst() = runBlocking {
        val store = NewsFeedStore(temp.root)
        store.replaceSourceArticles("a", listOf(article("a", "https://a/1", 10), article("a", "https://a/2", 30)), refreshedAt = 5)
        store.replaceSourceArticles("b", listOf(article("b", "https://b/1", 20)), refreshedAt = 6)

        assertEquals(listOf("https://a/2", "https://b/1", "https://a/1"), store.loadArticles().map { it.url })

        store.replaceSourceArticles("a", listOf(article("a", "https://a/3", 40)), refreshedAt = 7)
        assertEquals(listOf("https://a/3", "https://b/1"), store.loadArticles().map { it.url })
        assertEquals(mapOf("a" to 7L, "b" to 6L), store.refreshedAt())

        store.removeSourceArticles("b")
        assertEquals(listOf("https://a/3"), store.loadArticles().map { it.url })
        assertEquals(mapOf("a" to 7L), store.refreshedAt())
    }

    @Test
    fun articlesFromAnotherSourceAreIgnoredWhenReplacingASource() = runBlocking {
        val store = NewsFeedStore(temp.root)
        store.replaceSourceArticles("a", listOf(article("b", "https://b/9", 1)), refreshedAt = 1)
        assertTrue(store.loadArticles().isEmpty())
    }

    @Test
    fun savedArticlesRoundTripAndUpdate() = runBlocking {
        val store = NewsFeedStore(temp.root)
        val saved = SavedNewsArticle(articleId = "id1", bookId = "book", sourceId = "a", url = "https://a/1", title = "T", savedAt = 9)
        store.upsertSaved(saved)
        assertEquals(saved, store.saved("id1"))

        store.updateSaved("id1") { it.copy(translation = NewsTranslationRecord("m", 3, 3, 10, uploaded = true)) }
        assertEquals("m", store.saved("id1")?.translation?.model)

        store.upsertSaved(saved.copy(bookId = "book2"))
        assertEquals(1, store.loadSaved().size)
        assertEquals("book2", store.saved("id1")?.bookId)

        store.removeSaved("id1")
        assertNull(store.saved("id1"))
    }

    @Test
    fun corruptFilesAreTreatedAsEmpty() = runBlocking {
        temp.root.resolve("News").mkdirs()
        temp.root.resolve("News/feed.json").writeText("{ not json")
        temp.root.resolve("News/saved.json").writeText("[]")
        val store = NewsFeedStore(temp.root)
        assertTrue(store.loadArticles().isEmpty())
        assertTrue(store.loadSaved().isEmpty())
    }

    @Test
    fun articleIdsAreStableForTheSameUrl() {
        assertEquals(NewsArticle.idFor("https://x/1 "), NewsArticle.idFor("https://x/1"))
        assertEquals(24, NewsArticle.idFor("https://x/1").length)
    }
}
