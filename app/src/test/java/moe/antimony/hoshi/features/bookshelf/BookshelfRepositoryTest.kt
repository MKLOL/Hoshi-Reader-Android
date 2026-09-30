package moe.antimony.hoshi.features.bookshelf

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.MOKURO_SIDECAR_FILE
import moe.antimony.hoshi.epub.bookContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BookshelfRepositoryTest {
    @Test
    fun openingEpubDefersParsingAndPreservesCachedInformationAndIdentity() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-epub-open").toFile())
        val root = repository.createBookDirectory("epub")
        val metadata = BookMetadata(
            id = "stable-book-id", title = "Book", cover = null, folder = root.name,
            lastAccess = 1.0, syncId = "stable-sync-id", renamedTitle = "My title",
        )
        repository.saveMetadata(root, metadata)
        val bookInfo = BookInfo(characterCount = 100, chapterInfo = emptyMap())
        repository.saveBookInfo(root, bookInfo)
        val infoFile = root.resolve("bookinfo.json")
        assertTrue(infoFile.setLastModified(1_000L))

        // No EPUB package is provided: navigation must leave parsing/error handling to the
        // reader, which first completes its sync refresh before inspecting the payload.
        val id = openBookshelfBook(repository, BookEntry(root, metadata)) {
            error("An EPUB must never enter the manga parser")
        }

        assertEquals(metadata.id, id)
        val updated = repository.loadMetadata(root)!!
        assertEquals(metadata, updated.copy(lastAccess = metadata.lastAccess))
        assertTrue(updated.lastAccess > metadata.lastAccess)
        assertEquals(bookInfo, repository.loadBookInfo(root))
        assertEquals(1_000L, infoFile.lastModified())
    }

    @Test
    fun openingMangaRepairsOnlyMissingSidecarsAndUsesTheRepairedIdentity() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-manga-open").toFile())
        val root = repository.createBookDirectory("manga")
        root.resolve(MOKURO_SIDECAR_FILE).writeText("{}")
        val metadata = BookMetadata("old-id", "Manga", null, root.name, 1.0)
        var repairs = 0
        val repair: suspend (java.io.File) -> Unit = {
            repairs += 1
            repository.saveMetadata(it, metadata.copy(id = "repaired-id"))
            repository.saveBookInfo(it, BookInfo(12, emptyMap()))
        }

        assertEquals("repaired-id", openBookshelfBook(repository, BookEntry(root, metadata), repair))
        assertEquals("repaired-id", openBookshelfBook(repository, BookEntry(root, metadata), repair))
        assertEquals(1, repairs)
    }

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
