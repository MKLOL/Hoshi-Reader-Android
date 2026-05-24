package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for [resolveSyncImportedCoverPath]: the helper that fills `BookMetadata.cover`
 * after a sync import so the bookshelf renders the cover without waiting for the user to
 * open the book once. Must never throw — sync would abort an entire reconcile cycle.
 */
class SyncImportCoverTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun mokuroBookReturnsNormalizedCoverPathFromFirstPage() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val repo = BookRepository(filesDir)
        val bookRoot = repo.createBookDirectoryForImportedTitle("Mokuro Cover")
        // Realistic mokuro layout: images live under "images/" alongside the sidecar.
        bookRoot.resolve("images").mkdirs()
        val coverSource = bookRoot.resolve("images/p0.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        bookRoot.resolve("mokuro.json").writeText(
            """
                {
                  "title": "Mokuro Cover",
                  "volume": "Volume",
                  "pages": [
                    { "img_width": 800, "img_height": 1200, "img_path": "images/p0.jpg", "blocks": [] },
                    { "img_width": 800, "img_height": 1200, "img_path": "images/p1.jpg", "blocks": [] }
                  ]
                }
            """.trimIndent(),
        )

        val result = resolveSyncImportedCoverPath(repo, bookRoot)

        // metadataCoverPath copies the cover to <bookRoot>/<basename> and returns the
        // shelf-relative form.
        assertEquals("Books/${bookRoot.name}/p0.jpg", result)
        assertTrue(coverSource.isFile)
        assertTrue(bookRoot.resolve("p0.jpg").isFile)
    }

    @Test
    fun mokuroBookWithoutOnDiskImageReturnsNull() = runBlocking {
        val filesDir = tempFolder.newFolder("files-no-img")
        val repo = BookRepository(filesDir)
        val bookRoot = repo.createBookDirectoryForImportedTitle("Mokuro NoImg")
        // Sidecar references an image that does not exist on disk. The parser still
        // resolves `coverImagePath` to that string, but `metadataCoverPath` returns null
        // because the source file is missing — and so must this helper.
        bookRoot.resolve("mokuro.json").writeText(
            """
                {
                  "pages": [
                    { "img_width": 1, "img_height": 1, "img_path": "missing.jpg", "blocks": [] }
                  ]
                }
            """.trimIndent(),
        )

        val result = resolveSyncImportedCoverPath(repo, bookRoot)

        assertNull(result)
    }

    @Test
    fun mokuroBookWithEmptyPagesReturnsNullViaRunCatching() = runBlocking {
        val filesDir = tempFolder.newFolder("files-empty")
        val repo = BookRepository(filesDir)
        val bookRoot = repo.createBookDirectoryForImportedTitle("Mokuro Empty")
        // `MokuroBookParser.parse` requires `pages.isNotEmpty()` and throws otherwise —
        // the helper must swallow that exception (otherwise sync reconcile aborts).
        bookRoot.resolve("mokuro.json").writeText("""{ "pages": [] }""")

        val result = resolveSyncImportedCoverPath(repo, bookRoot)

        assertNull(result)
    }

    @Test
    fun mokuroBookWithCorruptSidecarReturnsNull() = runBlocking {
        val filesDir = tempFolder.newFolder("files-corrupt")
        val repo = BookRepository(filesDir)
        val bookRoot = repo.createBookDirectoryForImportedTitle("Mokuro Corrupt")
        // Not even JSON. The parser raises a serialization exception; runCatching catches.
        bookRoot.resolve("mokuro.json").writeText("this is not json")

        val result = resolveSyncImportedCoverPath(repo, bookRoot)

        assertNull(result)
    }

    @Test
    fun epubBookDirectoryWithoutValidEpubStructureReturnsNull() = runBlocking {
        val filesDir = tempFolder.newFolder("files-epub")
        val repo = BookRepository(filesDir)
        val bookRoot = repo.createBookDirectoryForImportedTitle("Epub Broken")
        // No mokuro.json -> bookContentType resolves to Epub. The native EPUB parser fails
        // on an empty directory; the helper must swallow it.
        // (Sanity guard: directory exists and is empty.)
        assertTrue(bookRoot.isDirectory)
        assertEquals(0, bookRoot.listFiles()?.size ?: -1)

        val result = resolveSyncImportedCoverPath(repo, bookRoot)

        assertNull(result)
    }

    @Test
    fun nonExistentBookRootReturnsNullWithoutThrowing() = runBlocking {
        val filesDir = tempFolder.newFolder("files-missing")
        val repo = BookRepository(filesDir)
        // Path that doesn't exist on disk at all. The helper should still be safe.
        val ghost = File(filesDir, "Books/ghost-book")
        val result = resolveSyncImportedCoverPath(repo, ghost)
        assertNull(result)
    }
}
