package moe.antimony.hoshi.features.bookshelf

import moe.antimony.hoshi.epub.BOOKINFO_FILE_NAME
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.MOKURO_SIDECAR_FILE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Opening a manga used to re-parse `mokuro.json` and rewrite both sidecars every time. The
 * decision is now [mokuroSidecarsNeedRewrite]: rewrite only when a sidecar is missing or
 * unusable, or when `mokuro.json` changed after the sidecars were written.
 */
class MokuroSidecarRewriteTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val metadata = BookMetadata(
        id = "manga-id",
        title = "Polar Bear Cafe",
        cover = null,
        folder = "manga",
        lastAccess = 1.0,
    )
    private val bookInfo = BookInfo(characterCount = 120, chapterInfo = emptyMap())

    private fun mangaRoot(mokuroWrittenAt: Long, bookInfoWrittenAt: Long): File {
        val root = temporaryFolder.newFolder()
        val mokuro = root.resolve(MOKURO_SIDECAR_FILE).apply { writeText("{}") }
        val info = root.resolve(BOOKINFO_FILE_NAME).apply { writeText("{}") }
        check(mokuro.setLastModified(mokuroWrittenAt))
        check(info.setLastModified(bookInfoWrittenAt))
        return root
    }

    @Test
    fun freshSidecarsAreLeftAlone() {
        val root = mangaRoot(mokuroWrittenAt = 1_000_000L, bookInfoWrittenAt = 1_000_000L)

        assertFalse(mokuroSidecarsNeedRewrite(root, metadata, bookInfo))
    }

    @Test
    fun sidecarsWrittenAfterTheMokuroFileAreLeftAlone() {
        val root = mangaRoot(mokuroWrittenAt = 1_000_000L, bookInfoWrittenAt = 1_060_000L)

        assertFalse(mokuroSidecarsNeedRewrite(root, metadata, bookInfo))
    }

    @Test
    fun aNewerMokuroFileForcesARewrite() {
        val root = mangaRoot(mokuroWrittenAt = 1_060_000L, bookInfoWrittenAt = 1_000_000L)

        assertTrue(mokuroSidecarsNeedRewrite(root, metadata, bookInfo))
    }

    @Test
    fun missingMetadataForcesARewrite() {
        val root = mangaRoot(mokuroWrittenAt = 1_000_000L, bookInfoWrittenAt = 1_000_000L)

        assertTrue(mokuroSidecarsNeedRewrite(root, metadata = null, bookInfo = bookInfo))
    }

    @Test
    fun blankTitleForcesARewrite() {
        val root = mangaRoot(mokuroWrittenAt = 1_000_000L, bookInfoWrittenAt = 1_000_000L)

        assertTrue(mokuroSidecarsNeedRewrite(root, metadata.copy(title = " "), bookInfo))
    }

    @Test
    fun missingBookInfoForcesARewrite() {
        val root = temporaryFolder.newFolder()
        root.resolve(MOKURO_SIDECAR_FILE).writeText("{}")

        assertTrue(mokuroSidecarsNeedRewrite(root, metadata, bookInfo = null))
    }

    @Test
    fun zeroPageCountForcesARewrite() {
        val root = mangaRoot(mokuroWrittenAt = 1_000_000L, bookInfoWrittenAt = 1_000_000L)

        assertTrue(mokuroSidecarsNeedRewrite(root, metadata, bookInfo.copy(characterCount = 0)))
    }
}
