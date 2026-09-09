package moe.antimony.hoshi.features.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsArticleXhtmlTest {
    @Test
    fun keepsParagraphsRubyAndImagesAndDropsScriptsStylesAndAttributes() {
        val input = """
            <div xmlns="http://www.w3.org/1999/xhtml" class="article-body" onclick="x()">
              <script>alert(1)</script>
              <style>p{}</style>
              <p style="color:red"><ruby>東京<rt>とうきょう</rt></ruby>で<a href="https://x">雨</a>が<span class="colorL">降りました</span>。</p>
              <nav><a href="/">home</a></nav>
              <figure><img src="http://example.jp/a.jpg" alt="写真" width="100" data-track="1"/><figcaption>写真です</figcaption></figure>
              <p></p>
              <div><div></div></div>
            </div>
        """.trimIndent()

        val output = NewsArticleXhtml.sanitize(input)!!

        assertTrue(output, output.contains("<p><ruby>東京<rt>とうきょう</rt></ruby>で雨が降りました。</p>"))
        // The serializer orders attributes alphabetically, so check them independently.
        val img = Regex("""<img [^>]*/>""").find(output)?.value ?: error("no img in $output")
        // Plain-http sources are upgraded to https so the iOS reader (App Transport Security) can load them.
        assertTrue(img, img.contains("""src="https://example.jp/a.jpg"""") && img.contains("""alt="写真"""") && !img.contains("width") && !img.contains("data-"))
        assertTrue(output, output.contains("<figcaption>写真です</figcaption>"))
        assertFalse(output, output.contains("script"))
        assertFalse(output, output.contains("style"))
        assertFalse(output, output.contains("home"))
        assertFalse(output, output.contains("onclick"))
        assertFalse(output, output.contains("href"))
        assertFalse(output, output.contains("<p></p>"))
        assertFalse(output, output.contains("<div>"))
        assertFalse(output, output.contains("xmlns"))
    }

    @Test
    fun malformedInputReturnsNull() {
        assertNull(NewsArticleXhtml.sanitize("<div><p>unclosed"))
        assertNull(NewsArticleXhtml.sanitize(""))
    }

    @Test
    fun fragmentWithNoTextOrImagesReturnsNull() {
        assertNull(NewsArticleXhtml.sanitize("<div><nav>x</nav><p>  </p></div>"))
    }

    @Test
    fun imagesWithoutSourceAreDropped() {
        val output = NewsArticleXhtml.sanitize("<div><p>本文</p><img alt=\"x\"/></div>")!!
        assertEquals("<p>本文</p>", output)
    }

    @Test
    fun plainTextFallbackEscapesAndSplitsLines() {
        val html = NewsArticleXhtml.paragraphsFromText("一行目 <b>\n\n  二行目 & 三\n")
        assertEquals("<p>一行目 &lt;b&gt;</p>\n<p>二行目 &amp; 三</p>", html)
    }
}
