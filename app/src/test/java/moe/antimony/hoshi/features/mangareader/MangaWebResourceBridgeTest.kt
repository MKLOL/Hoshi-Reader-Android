package moe.antimony.hoshi.features.mangareader

import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.mokuro.MokuroPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MangaWebResourceBridgeTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun bookWith(vararg imagePaths: String) = MokuroBook(
        title = "Test Volume",
        pages = imagePaths.mapIndexed { index, path ->
            MokuroPage(
                index = index,
                imagePath = path,
                imageWidth = 800,
                imageHeight = 1200,
                textBoxes = emptyList(),
            )
        },
        coverImagePath = imagePaths.firstOrNull(),
    )

    private fun writeImage(root: File, relativePath: String): File {
        val file = root.resolve(relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        return file
    }

    @Test
    fun resolvesADeclaredImageToItsOnDiskFile() {
        val root = tempFolder.newFolder("book")
        val image = writeImage(root, "images/page_000.jpg")
        val bridge = MangaWebResourceBridge(root, bookWith("images/page_000.jpg"))

        val resolved = bridge.resolveImageFile("https://hoshi.local/manga/images/page_000.jpg")

        assertEquals(image.canonicalFile, resolved)
    }

    @Test
    fun resolvesADeclaredImagePathForNativeCropping() {
        val root = tempFolder.newFolder("book")
        val image = writeImage(root, "images/page_000.jpg")
        val bridge = MangaWebResourceBridge(root, bookWith("images/page_000.jpg"))

        val resolved = bridge.resolveDeclaredImageFile("images/page_000.jpg")

        assertEquals(image.canonicalFile, resolved)
    }

    @Test
    fun rejectsRequestsForImagesTheBookDoesNotDeclare() {
        val root = tempFolder.newFolder("book")
        writeImage(root, "images/secret.jpg")
        val bridge = MangaWebResourceBridge(root, bookWith("images/page_000.jpg"))

        assertNull(bridge.resolveImageFile("https://hoshi.local/manga/images/secret.jpg"))
    }

    @Test
    fun rejectsPathTraversalOutsideTheBookRoot() {
        val parent = tempFolder.newFolder("parent")
        val root = parent.resolve("book").apply { mkdirs() }
        // A file outside the book root that a traversal attack might target.
        parent.resolve("escape.jpg").writeBytes(byteArrayOf(9))
        // Even if the malicious path were "declared", canonicalisation must keep it contained.
        val book = MokuroBook(
            title = "Evil",
            pages = listOf(
                MokuroPage(0, "../escape.jpg", 1, 1, emptyList()),
            ),
            coverImagePath = null,
        )
        val bridge = MangaWebResourceBridge(root, book)

        assertNull(bridge.resolveImageFile("https://hoshi.local/manga/../escape.jpg"))
    }

    @Test
    fun rejectsUrlsForOtherHostsAndPaths() {
        val root = tempFolder.newFolder("book")
        writeImage(root, "images/page_000.jpg")
        val bridge = MangaWebResourceBridge(root, bookWith("images/page_000.jpg"))

        assertNull(bridge.resolveImageFile("https://evil.example/manga/images/page_000.jpg"))
        assertNull(bridge.resolveImageFile("https://hoshi.local/epub/images/page_000.jpg"))
        assertNull(bridge.resolveImageFile("not a url"))
    }

    @Test
    fun rejectsDeclaredImagesThatAreMissingOnDisk() {
        val root = tempFolder.newFolder("book")
        // Declared but never written to disk.
        val bridge = MangaWebResourceBridge(root, bookWith("images/page_000.jpg"))

        assertNull(bridge.resolveImageFile("https://hoshi.local/manga/images/page_000.jpg"))
    }

    @Test
    fun mediaTypeIsDerivedFromTheImageExtension() {
        with(MangaWebResourceBridge) {
            assertEquals("image/jpeg", File("a/page.jpg").imageMediaType())
            assertEquals("image/jpeg", File("a/page.JPEG").imageMediaType())
            assertEquals("image/png", File("a/page.png").imageMediaType())
            assertEquals("image/webp", File("a/page.webp").imageMediaType())
            assertEquals("application/octet-stream", File("a/page.txt").imageMediaType())
        }
    }
}
