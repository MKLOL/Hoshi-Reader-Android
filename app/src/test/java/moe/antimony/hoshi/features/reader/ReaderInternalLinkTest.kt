package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderInternalLinkTest {
    private val book = EpubBook(
        title = "Internal Links",
        chapters = listOf(
            EpubChapter(
                id = "toc",
                href = "OPS/nav.xhtml",
                mediaType = "application/xhtml+xml",
                html = "<html><body>目次</body></html>",
            ),
            EpubChapter(
                id = "chapter-1",
                href = "OPS/chapter-1.xhtml",
                mediaType = "application/xhtml+xml",
                html = "<html><body>第一章</body></html>",
            ),
        ),
    )

    @Test
    fun resolvesHoshiEpubUrlToChapterAndFragment() {
        val target = book.resolveInternalReaderLink(
            "https://hoshi.local/epub/OPS/chapter-1.xhtml#toc-001",
        )

        assertEquals(ReaderChapterPosition(index = 1, progress = 0.0), target?.position)
        assertEquals("toc-001", target?.fragment)
    }

    @Test
    fun rejectsExternalLinksSoTheyDoNotMutateReaderState() {
        assertNull(book.resolveInternalReaderLink("https://example.com/OPS/chapter-1.xhtml#toc-001"))
    }

    @Test
    fun resolvesEncodedChapterAndFragmentWithoutDecodingLiteralPercentTwice() {
        val encodedBook = EpubBook(
            title = "Encoded paths",
            chapters = listOf(EpubChapter("chapter", "OPS/chapter%2520+1.xhtml", "application/xhtml+xml", "")),
        )

        val target = encodedBook.resolveInternalReaderLink(
            "https://hoshi.local/epub/OPS/chapter%2520+1.xhtml#part%201+2",
        )

        assertEquals(0, target?.position?.index)
        assertEquals("part 1+2", target?.fragment)
        assertEquals(target, encodedBook.resolveReaderChapterHref("OPS/chapter%2520+1.xhtml#part%201+2"))
        assertNull(encodedBook.resolveInternalReaderLink("https://hoshi.local/epub/OPS/chapter%20+1.xhtml"))
    }

    @Test
    fun matchesFullNormalizedChapterPathInsteadOfUnrelatedFilenameSuffix() {
        val duplicateBook = book.copy(chapters = listOf(
            EpubChapter("nested", "extra/OPS/chapter-1.xhtml", "application/xhtml+xml", ""),
            book.chapters.last(),
        ))

        assertEquals(1, duplicateBook.resolveInternalReaderLink(
            "https://hoshi.local/epub/OPS/text/../chapter-1.xhtml",
        )?.position?.index)
        assertNull(duplicateBook.resolveInternalReaderLink("https://hoshi.local/epub/chapter-1.xhtml"))
    }

}
