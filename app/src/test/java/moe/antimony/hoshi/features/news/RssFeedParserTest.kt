package moe.antimony.hoshi.features.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RssFeedParserTest {
    @Test
    fun parsesRss2ItemsWithCdataEntitiesAndRfc1123Dates() {
        val feed = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
            <channel><title>Watanoc</title>
            <item>
              <title>肉玉そばを食べるなら29日&#8230;(n4)</title>
              <link>https://watanoc.com/post-1610-nikutama</link>
              <pubDate>Mon, 08 Sep 2025 09:15:00 +0000</pubDate>
              <description><![CDATA[<p>東京の<b>お店</b> &amp; 29日</p><img src="https://watanoc.com/a.jpg" />]]></description>
            </item>
            <item><title>No link</title></item>
            </channel></rss>
        """.trimIndent()

        val items = RssFeedParser.parse(feed)

        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("肉玉そばを食べるなら29日…(n4)", item.title)
        assertEquals("https://watanoc.com/post-1610-nikutama", item.url)
        assertEquals(1757322900000L, item.publishedAt)
        assertEquals("東京の お店 & 29日", item.summary)
        assertEquals("https://watanoc.com/a.jpg", item.imageUrl)
    }

    @Test
    fun parsesAtomEntriesWithHrefLinksAndIsoDates() {
        val feed = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title type="html">やさしい&lt;ニュース&gt;</title>
                <link rel="alternate" href="https://example.jp/a/1"/>
                <published>2026-09-08T10:00:00+09:00</published>
                <summary>要約です。</summary>
              </entry>
            </feed>
        """.trimIndent()

        val items = RssFeedParser.parse(feed)

        assertEquals(1, items.size)
        assertEquals("やさしい<ニュース>", items[0].title)
        assertEquals("https://example.jp/a/1", items[0].url)
        assertEquals(1788829200000L, items[0].publishedAt)
        assertEquals("要約です。", items[0].summary)
        assertNull(items[0].imageUrl)
    }

    @Test
    fun atomAlternateLinkIsPreferredOverRepliesAndSelfLinks() {
        val feed = """
            <feed xmlns="http://www.w3.org/2005/Atom"><entry>
              <title>t</title>
              <link rel="replies" type="text/html" href="https://example.jp/a/1#comments"/>
              <link rel="self" href="https://example.jp/feeds/1"/>
              <link rel="alternate" type="text/html" href="https://example.jp/a/1"/>
            </entry></feed>
        """.trimIndent()
        assertEquals("https://example.jp/a/1", RssFeedParser.parse(feed).single().url)
    }

    @Test
    fun malformedFeedYieldsNoItemsInsteadOfThrowing() {
        assertTrue(RssFeedParser.parse("<html><body>not a feed</body></html>").isEmpty())
        assertTrue(RssFeedParser.parse("").isEmpty())
        assertTrue(RssFeedParser.parse("<rss><channel><item><title>x").isEmpty())
    }

    @Test
    fun unparseableDatesBecomeNullAndTitlesFallBackToTheLink() {
        val feed = "<rss><item><link>https://example.jp/x</link><pubDate>someday</pubDate></item></rss>"
        val item = RssFeedParser.parse(feed).single()
        assertNull(item.publishedAt)
        assertEquals("https://example.jp/x", item.title)
    }

    @Test
    fun mediaThumbnailIsPreferredOverInlineImages() {
        val feed = """
            <rss xmlns:media="http://search.yahoo.com/mrss/"><item>
              <link>https://example.jp/y</link>
              <media:thumbnail url="https://example.jp/thumb.jpg"/>
              <description>&lt;img src="https://example.jp/inline.jpg"&gt;</description>
            </item></rss>
        """.trimIndent()
        assertEquals("https://example.jp/thumb.jpg", RssFeedParser.parse(feed).single().imageUrl)
    }
}
