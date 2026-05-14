package moe.antimony.hoshi.features.mangareader

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaBookmarkTest {
    @Test
    fun chapterIndexIsThePageIndexAndCharacterCountIsOneBasedPagesRead() {
        val bookmark = mangaBookmark(pageIndex = 7, lastModifiedSeconds = 123.0)

        // chapterIndex = 0-based resume position; characterCount = 1-based pages-read count
        // so bookshelf progress (characterCount / pageCount) reaches 100% on the last page.
        assertEquals(7, bookmark.chapterIndex)
        assertEquals(8, bookmark.characterCount)
    }

    @Test
    fun progressIsAlwaysZeroForManga() {
        val bookmark = mangaBookmark(pageIndex = 42, lastModifiedSeconds = 0.0)

        assertEquals(0.0, bookmark.progress, 0.0)
    }

    @Test
    fun lastModifiedSecondsIsCarriedThrough() {
        val bookmark = mangaBookmark(pageIndex = 0, lastModifiedSeconds = 987.5)

        assertEquals(987.5, bookmark.lastModified)
    }
}
