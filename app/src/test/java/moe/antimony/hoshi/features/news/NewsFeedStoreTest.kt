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
    fun refreshingUndatedArticlesDoesNotPutThemAheadOfDatedNews() = runBlocking {
        val store = NewsFeedStore(temp.root)
        store.replaceSourceArticles("news", listOf(
            article("news", "https://news/recent", 900),
            article("news", "https://news/older", 800),
        ), refreshedAt = 1_000)
        store.replaceSourceArticles("travel", listOf(
            article("travel", "https://travel/undated", null).copy(fetchedAt = 5_000),
        ), refreshedAt = 5_000)
        assertEquals(listOf("https://news/recent", "https://news/older", "https://travel/undated"),
            store.loadArticles().map { it.url })

        store.replaceSourceArticles("travel", listOf(
            article("travel", "https://travel/undated", null).copy(fetchedAt = 10_000),
        ), refreshedAt = 10_000)
        assertEquals("https://news/recent", store.loadArticles().first().url)
    }

    @Test
    fun anOlderCacheIsSortedByPublicationBeforeAnyNetworkRefresh() = runBlocking {
        val directory = temp.root.resolve("News").apply { mkdirs() }
        directory.resolve("feed.json").writeText("""
            {"version":1,"articles":[
              {"id":"undated","sourceId":"a","url":"https://a/undated","title":"Old guide","fetchedAt":1000},
              {"id":"older","sourceId":"b","url":"https://b/older","title":"Older news","publishedAt":10,"fetchedAt":20},
              {"id":"newer","sourceId":"b","url":"https://b/newer","title":"Newer news","publishedAt":30,"fetchedAt":40}
            ],"refreshedAt":{"a":1000,"b":40}}
        """.trimIndent())
        val store = NewsFeedStore(temp.root)
        assertEquals(listOf("newer", "older", "undated"), store.loadArticles().map { it.id })
        assertEquals(mapOf("a" to 1000L, "b" to 40L), store.refreshedAt())
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
