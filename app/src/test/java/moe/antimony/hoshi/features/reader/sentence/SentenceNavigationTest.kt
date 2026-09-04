package moe.antimony.hoshi.features.reader.sentence

import moe.antimony.hoshi.epub.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceNavigationTest {
    private fun sentences(spine: Int, count: Int) = List(count) { ReaderSentence(spine, it * 3, 3, "文$it。", it) }

    private val book = SentenceBook(
        listOf(
            SentenceChapter(0, "Cover", emptyList()),
            SentenceChapter(1, "One", sentences(1, 2)),
            SentenceChapter(2, null, emptyList()),
            SentenceChapter(3, "Three", sentences(3, 3)),
        ),
    )

    @Test
    fun nextWalksAcrossChaptersAndSkipsEmptyOnes() {
        assertEquals(SentencePosition(1, 1), SentenceNavigation.next(book, SentencePosition(1, 0)))
        assertEquals(SentencePosition(3, 0), SentenceNavigation.next(book, SentencePosition(1, 1)))
        assertNull(SentenceNavigation.next(book, SentencePosition(3, 2)))
    }

    @Test
    fun previousWalksBackAcrossChaptersAndSkipsEmptyOnes() {
        assertEquals(SentencePosition(1, 1), SentenceNavigation.previous(book, SentencePosition(3, 0)))
        assertEquals(SentencePosition(3, 1), SentenceNavigation.previous(book, SentencePosition(3, 2)))
        assertNull(SentenceNavigation.previous(book, SentencePosition(1, 0)))
    }

    @Test
    fun ordinalsCountAcrossTheWholeBookAndReadableChapters() {
        assertEquals(5, book.totalSentences)
        assertEquals(2, book.readableChapterCount)
        assertEquals(1, book.ordinal(SentencePosition(1, 0)))
        assertEquals(4, book.ordinal(SentencePosition(3, 1)))
        assertEquals(2, book.readableChapterOrdinal(SentencePosition(3, 1)))
    }

    @Test
    fun clampRepairsPositionsThatNoLongerExist() {
        assertEquals(SentencePosition(3, 2), SentenceNavigation.clamp(book, SentencePosition(3, 99)))
        assertEquals("an empty chapter moves to the next readable one", SentencePosition(3, 0), SentenceNavigation.clamp(book, SentencePosition(2, 0)))
        assertEquals("past the end lands on the last sentence", SentencePosition(3, 2), SentenceNavigation.clamp(book, SentencePosition(9, 0)))
        assertNull(SentenceNavigation.clamp(SentenceBook(emptyList()), SentencePosition(0, 0)))
    }

    @Test
    fun initialPositionPrefersSavedThenBookmarkThenFirstSentence() {
        val bookmark = Bookmark(chapterIndex = 3, progress = 0.5, characterCount = 0)
        assertEquals(SentencePosition(1, 1), SentenceNavigation.initialPosition(book, SentencePosition(1, 1), bookmark))
        assertEquals("bookmark progress maps onto a sentence", SentencePosition(3, 1), SentenceNavigation.initialPosition(book, null, bookmark))
        assertEquals("a bookmark in an empty chapter moves to the next readable one", SentencePosition(3, 0), SentenceNavigation.initialPosition(book, null, Bookmark(2, 0.0, 0)))
        assertEquals(SentencePosition(1, 0), SentenceNavigation.initialPosition(book, null, null))
        assertNull(SentenceNavigation.initialPosition(SentenceBook(emptyList()), null, bookmark))
    }
}
