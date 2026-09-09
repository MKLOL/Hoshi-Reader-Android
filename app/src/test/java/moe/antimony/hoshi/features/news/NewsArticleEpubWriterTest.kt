package moe.antimony.hoshi.features.news

import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.features.reader.sentence.EpubSentenceSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NewsArticleEpubWriterTest {
    @get:Rule val temp = TemporaryFolder()

    private val body = """
        <p><ruby>東京<rt>とうきょう</rt></ruby>で<ruby>雨<rt>あめ</rt></ruby>が降りました。</p>
        <p>二つ目の文です。三つ目！</p>
        <figure><img src="https://example.jp/a.jpg" alt="写真"/></figure>
    """.trimIndent()

    @Test
    fun writtenDirectoryParsesAsASingleChapterEpubWithTitleAndCover() {
        val root = temp.newFolder("article")
        NewsArticleEpubWriter.write(
            root,
            NewsArticleEpubWriter.Input(
                title = "雨 & <大雨>",
                bodyXhtml = body,
                sourceName = "NHK NEWS WEB EASY",
                sourceUrl = "https://news.web.nhk/news/easy/ne1/ne1.html",
                publishedAt = 1_757_298_000_000,
                cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 74, 70, 73, 70, 0, 1, 1),
            ),
        )

        val book = EpubBookParser().parse(root)

        assertEquals("雨 & <大雨>", book.title)
        assertEquals(1, book.chapters.size)
        assertEquals(1, book.spineCount)
        assertEquals("OEBPS/article.xhtml", book.chapters.single().href)
        assertEquals("OEBPS/cover.jpg", book.coverHref)
        assertTrue(root.resolve("OEBPS/toc.ncx").isFile)
        assertTrue(root.resolve("OEBPS/content.opf").readText().contains("<dc:date>2025-09-08</dc:date>"))
    }

    @Test
    fun articleBodySegmentsIntoSentencesLikeAnyEpubChapter() {
        val root = temp.newFolder("article")
        NewsArticleEpubWriter.write(root, NewsArticleEpubWriter.Input("見出し", body, "Source", "https://s/1"))

        val html = root.resolve("OEBPS/article.xhtml").readText()
        val sentences = EpubSentenceSegmenter.segment(0, html)

        assertEquals(listOf("見出し", "Source", "東京で雨が降りました。", "二つ目の文です。", "三つ目！"), sentences.map { it.text })
        assertEquals("c0s0", sentences.first().id)
        assertTrue(sentences.all { it.spine == 0 })
    }

    @Test
    fun noCoverProducesNoCoverEntry() {
        val root = temp.newFolder("article")
        NewsArticleEpubWriter.write(root, NewsArticleEpubWriter.Input("t", "<p>本文。</p>", "S", "https://s/2"))
        val book = EpubBookParser().parse(root)
        assertNull(book.coverHref)
        assertTrue(!root.resolve("OEBPS/cover.jpg").exists())
        assertTrue(!root.resolve("OEBPS/content.opf").readText().contains("cover"))
    }

    @Test
    fun chapterIsWellFormedXml() {
        val root = temp.newFolder("article")
        NewsArticleEpubWriter.write(root, NewsArticleEpubWriter.Input("a<b", "<p>x &amp; y</p>", "S & T", "https://s/3?a=1&b=2"))
        val chapter = root.resolve("OEBPS/article.xhtml").readText()
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.newDocumentBuilder().parse(chapter.byteInputStream())
        val opf = root.resolve("OEBPS/content.opf").readText()
        factory.newDocumentBuilder().parse(opf.byteInputStream())
    }
}
