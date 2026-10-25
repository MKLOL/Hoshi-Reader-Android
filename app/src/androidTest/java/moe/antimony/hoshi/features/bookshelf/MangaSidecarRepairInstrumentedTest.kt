package moe.antimony.hoshi.features.bookshelf

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.HoshiApplication
import moe.antimony.hoshi.epub.BOOKINFO_FILE_NAME
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.MOKURO_SIDECAR_FILE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaSidecarRepairInstrumentedTest {
    @Test
    fun openingMangaWithMissingBookInfoPreservesCustomTitleAndIdentityAcrossRepeatedRepair() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<HoshiApplication>()
        val container = application.appContainer
        val repository = container.bookRepository
        val bookshelf = container.bookshelfRepository(application)
        // Exercise the real app repositories without creating or modifying a library entry.
        val root = File(application.cacheDir, "manga-sidecar-repair-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        try {
            val original = BookMetadata(
                id = UUID.randomUUID().toString(),
                title = "Original manga title",
                cover = null,
                folder = root.name,
                lastAccess = 1.0,
                syncId = "stable-sync-${UUID.randomUUID()}",
                importedAt = "2026-01-02T03:04:05Z",
                renamedTitle = "My custom manga title",
            )
            writeMokuro(root, "Original manga title", pageCount = 2)
            repository.saveMetadata(root, original)
            assertFalse(root.resolve(BOOKINFO_FILE_NAME).exists())

            assertEquals(original.id, bookshelf.openBook(BookEntry(root, original)))
            val repaired = requireNotNull(repository.loadMetadata(root))
            assertPreservedIdentityAndTitle(original, repaired)
            assertEquals("Original manga title", repaired.title)
            assertEquals(2, repository.loadBookInfo(root)?.characterCount)
            assertTrue(repaired.lastAccess > original.lastAccess)
            assertTrue(root.resolve(BOOKINFO_FILE_NAME).delete())

            // A changed source title forces another parse; the reader-facing rename is stable.
            writeMokuro(root, "Changed publisher title with another edition", pageCount = 3)
            assertEquals(original.id, bookshelf.openBook(BookEntry(root, repaired)))
            val repairedAgain = requireNotNull(repository.loadMetadata(root))
            assertPreservedIdentityAndTitle(original, repairedAgain)
            assertEquals("Changed publisher title with another edition", repairedAgain.title)
            val rebuiltInfo = repository.loadBookInfo(root)
            assertNotNull(rebuiltInfo)
            assertEquals(3, rebuiltInfo?.characterCount)
            assertTrue(rebuiltInfo?.chapterInfo?.isEmpty() == true)
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }

    private fun assertPreservedIdentityAndTitle(original: BookMetadata, actual: BookMetadata) {
        assertEquals(original.id, actual.id)
        assertEquals(original.syncId, actual.syncId)
        assertEquals(original.importedAt, actual.importedAt)
        assertEquals(original.renamedTitle, actual.renamedTitle)
        assertEquals(original.renamedTitle, actual.displayTitle)
    }

    private fun writeMokuro(root: File, title: String, pageCount: Int) {
        val pages = (1..pageCount).joinToString(",") { index ->
            """{"img_path":"page-$index.png","img_width":1,"img_height":1,"blocks":[]}"""
        }
        root.resolve(MOKURO_SIDECAR_FILE).writeText("""{"title":"$title","pages":[$pages]}""")
    }
}
