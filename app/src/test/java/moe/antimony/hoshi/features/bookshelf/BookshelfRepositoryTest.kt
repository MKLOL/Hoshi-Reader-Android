package moe.antimony.hoshi.features.bookshelf

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.MOKURO_SIDECAR_FILE
import moe.antimony.hoshi.epub.bookContentType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class BookshelfRepositoryTest {
    @Test
    fun loadingBookshelfPreservesEpubAndMokuroEntries() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-dual-format-shelf").toFile())
        val epubRoot = repository.createBookDirectory("epub-book")
        val mokuroRoot = repository.createBookDirectory("mokuro-book")
        mokuroRoot.resolve(MOKURO_SIDECAR_FILE).writeText("{}")
        repository.saveMetadata(
            epubRoot,
            BookMetadata(
                id = "epub-id",
                title = "EPUB Book",
                cover = null,
                folder = epubRoot.name,
                lastAccess = 1.0,
            ),
        )
        repository.saveMetadata(
            mokuroRoot,
            BookMetadata(
                id = "mokuro-id",
                title = "Mokuro Book",
                cover = null,
                folder = mokuroRoot.name,
                lastAccess = 2.0,
            ),
        )

        val result = loadBookshelfResult(
            bookRepository = repository,
            sortOption = BookSortOption.Recent,
            settings = BookshelfSettings(),
        )

        assertEquals(listOf("Mokuro Book", "EPUB Book"), result.entries.map { it.metadata.title })
        assertEquals(
            setOf(ContentType.Epub, ContentType.Mokuro),
            result.entries.map { bookContentType(it.root) }.toSet(),
        )
    }
}
