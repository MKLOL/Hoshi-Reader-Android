package moe.antimony.hoshi.features.news

import org.junit.Assert.assertEquals
import org.junit.Test

class NewsUiStateTest {
    @Test
    fun overlappingCustomAndBuiltInFeedsShowEachArticleOnceAndKeepBothSourceFilters() {
        val url = "https://nhkeasier.com/story/9948/"
        val builtIn = NewsArticle(
            id = NewsArticle.idFor(url),
            sourceId = NewsSourceCatalog.NHK_EASIER_ID,
            url = url,
            title = "News",
            publishedAt = 100,
            fetchedAt = 200,
        )
        val custom = builtIn.copy(sourceId = NewsSourceCatalog.customRss("My news", "https://nhkeasier.com/feed/").id)
        val otherUrl = "https://slow-communication.jp/news/7477/"
        val other = builtIn.copy(id = NewsArticle.idFor(otherUrl), sourceId = NewsSourceCatalog.SLOW_COMMUNICATION_ID, url = otherUrl)
        val state = NewsUiState(feed = NewsFeedState(articles = listOf(builtIn, custom, other)))

        assertEquals(listOf(builtIn, other), state.visibleArticles)
        assertEquals(listOf(builtIn), state.copy(selectedSourceId = builtIn.sourceId).visibleArticles)
        assertEquals(listOf(custom), state.copy(selectedSourceId = custom.sourceId).visibleArticles)
    }
}
