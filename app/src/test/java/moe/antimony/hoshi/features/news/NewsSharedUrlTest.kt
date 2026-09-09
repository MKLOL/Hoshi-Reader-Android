package moe.antimony.hoshi.features.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewsSharedUrlTest {
    @Test
    fun extractsTheFirstLinkFromTypicalShareText() {
        assertEquals("https://matcha-jp.com/easy/10505", NewsSharedUrl.extract("由布院温泉 https://matcha-jp.com/easy/10505 via MATCHA"))
        assertEquals("https://news.web.nhk/news/easy/ne1/ne1.html", NewsSharedUrl.extract("https://news.web.nhk/news/easy/ne1/ne1.html."))
        assertEquals("http://example.jp/a?b=1&c=2", NewsSharedUrl.extract("read this: http://example.jp/a?b=1&c=2"))
    }

    @Test
    fun textWithoutALinkYieldsNull() {
        assertNull(NewsSharedUrl.extract("ただの文章です。"))
        assertNull(NewsSharedUrl.extract(null))
        assertNull(NewsSharedUrl.extract(""))
    }

    @Test
    fun sourceForUrlMatchesBuiltInHostsAndFallsBackToSharedLink() {
        assertEquals(NewsSourceCatalog.MATCHA_EASY_ID, NewsSourceCatalog.sourceForUrl("https://matcha-jp.com/easy/10505").id)
        assertEquals(NewsSourceCatalog.NHK_EASY_ID, NewsSourceCatalog.sourceForUrl("https://news.web.nhk/news/easy/ne1/ne1.html").id)
        assertEquals(NewsSourceCatalog.SHARED_LINK_ID, NewsSourceCatalog.sourceForUrl("https://example.jp/x").id)
    }

    @Test
    fun geoRestrictedBuiltInStartsDisabledUntilSwitchedOn() {
        val defaults = NewsSettings()
        assertEquals(false, defaults.isEnabled(NewsSourceCatalog.nhkEasy))
        assertEquals(true, defaults.isEnabled(NewsSourceCatalog.watanoc))
        assertEquals(true, defaults.copy(enabledSourceIds = setOf(NewsSourceCatalog.NHK_EASY_ID)).isEnabled(NewsSourceCatalog.nhkEasy))
        assertEquals(false, defaults.copy(disabledSourceIds = setOf(NewsSourceCatalog.WATANOC_ID)).isEnabled(NewsSourceCatalog.watanoc))
    }
}
