package moe.antimony.hoshi.mokuro

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.bookContentType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Exercises the importer's layout-production core ([MokuroImporter.assembleMokuroBook]) and
 * the `.mokuro` discovery helper. The SAF / zip plumbing is thin glue over this core, so the
 * behaviour worth testing — path rewriting, image flattening, validation — lives here and is
 * verified on plain files without an Android framework.
 */
class MokuroImporterTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun importer(filesDir: File) = MokuroImporter(filesDir)

    private fun newDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun mokuroJson(vararg imgPaths: String): String {
        val pages = imgPaths.joinToString(",\n") { path ->
            """
            {
              "img_width": 800,
              "img_height": 1200,
              "img_path": "$path",
              "blocks": [
                { "box": [10, 20, 110, 80], "vertical": true, "font_size": 24, "lines": ["テスト"] }
              ]
            }
            """.trimIndent()
        }
        return """
            { "version": "0.2.2", "title": "Series", "volume": "Volume One", "pages": [ $pages ] }
        """.trimIndent()
    }

    @Test
    fun assemblesFlatBundleIntoCanonicalLayout() = runBlocking {
        val filesDir = newDir("hoshi-mokuro-files")
        val staging = newDir("hoshi-mokuro-staging")
        staging.resolve("volume.mokuro").writeText(mokuroJson("page_000.jpg", "page_001.jpg"))
        staging.resolve("page_000.jpg").writeBytes(byteArrayOf(1, 2, 3))
        staging.resolve("page_001.jpg").writeBytes(byteArrayOf(4, 5, 6))

        val target = filesDir.resolve("Books/book").apply { mkdirs() }
        val result = importer(filesDir).assembleMokuroBook(staging) { target }

        assertEquals(target, result.bookRoot)
        assertEquals("Volume One", result.title)
        assertEquals(2, result.pageCount)
        assertEquals("images/page_000.jpg", result.coverImagePath)

        // The sidecar marks the directory as manga and every img_path resolves on disk.
        assertEquals(ContentType.Mokuro, bookContentType(target))
        val sidecar = json.parseToJsonElement(target.resolve("mokuro.json").readText()).jsonObject
        val rewrittenPaths = sidecar["pages"]!!.jsonArray.map { it.jsonObject["img_path"]!!.jsonPrimitive.content }
        assertEquals(listOf("images/page_000.jpg", "images/page_001.jpg"), rewrittenPaths)
        rewrittenPaths.forEach { assertTrue(target.resolve(it).isFile) }
        assertArrayEquals(byteArrayOf(1, 2, 3), target.resolve("images/page_000.jpg").readBytes())
        // Unknown fields (version/title) survive the rewrite verbatim.
        assertEquals("0.2.2", sidecar["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun resolvesImagesRelativeToTheMokuroFileLocation() = runBlocking {
        // Bundle where the .mokuro file and images both sit inside a subfolder, and img_path
        // points into a sibling "images" directory relative to the .mokuro file.
        val filesDir = newDir("hoshi-mokuro-nested-files")
        val staging = newDir("hoshi-mokuro-nested-staging")
        val volumeDir = staging.resolve("Yotsuba v01").apply { mkdirs() }
        volumeDir.resolve("vol.mokuro").writeText(mokuroJson("images/p0.jpg", "images/p1.jpg"))
        volumeDir.resolve("images").mkdirs()
        volumeDir.resolve("images/p0.jpg").writeBytes(byteArrayOf(7))
        volumeDir.resolve("images/p1.jpg").writeBytes(byteArrayOf(8))

        val target = filesDir.resolve("Books/nested").apply { mkdirs() }
        val result = importer(filesDir).assembleMokuroBook(staging) { target }

        assertEquals(2, result.pageCount)
        assertTrue(target.resolve("images/p0.jpg").isFile)
        assertTrue(target.resolve("images/p1.jpg").isFile)
        assertArrayEquals(byteArrayOf(7), target.resolve("images/p0.jpg").readBytes())
    }

    @Test
    fun flattensCollidingBasenamesFromDifferentSubfolders() = runBlocking {
        val filesDir = newDir("hoshi-mokuro-collide-files")
        val staging = newDir("hoshi-mokuro-collide-staging")
        staging.resolve("a").mkdirs()
        staging.resolve("b").mkdirs()
        staging.resolve("vol.mokuro").writeText(mokuroJson("a/page.jpg", "b/page.jpg"))
        staging.resolve("a/page.jpg").writeBytes(byteArrayOf(1))
        staging.resolve("b/page.jpg").writeBytes(byteArrayOf(2))

        val target = filesDir.resolve("Books/collide").apply { mkdirs() }
        val result = importer(filesDir).assembleMokuroBook(staging) { target }

        val sidecar = json.parseToJsonElement(target.resolve("mokuro.json").readText()).jsonObject
        val paths = sidecar["pages"]!!.jsonArray.map { it.jsonObject["img_path"]!!.jsonPrimitive.content }
        // Both pages survive with distinct, resolvable destinations.
        assertEquals(2, paths.distinct().size)
        assertEquals(2, result.pageCount)
        paths.forEach { assertTrue(target.resolve(it).isFile) }
    }

    @Test
    fun failsAndLeavesNoBookDirectoryWhenAnImageIsMissing() = runBlocking {
        val filesDir = newDir("hoshi-mokuro-missing-img-files")
        val staging = newDir("hoshi-mokuro-missing-img-staging")
        staging.resolve("vol.mokuro").writeText(mokuroJson("page_000.jpg", "page_001.jpg"))
        staging.resolve("page_000.jpg").writeBytes(byteArrayOf(1))
        // page_001.jpg deliberately absent.

        val target = filesDir.resolve("Books/missing").apply { mkdirs() }
        val error = runCatching { importer(filesDir).assembleMokuroBook(staging) { target } }.exceptionOrNull()

        assertTrue(error is MokuroImportException)
        // Validation happens before the target directory is populated.
        assertFalse(target.resolve("mokuro.json").exists())
        assertFalse(target.resolve("images").exists())
    }

    @Test
    fun failsWhenNoMokuroFileIsPresent() = runBlocking {
        val filesDir = newDir("hoshi-mokuro-none-files")
        val staging = newDir("hoshi-mokuro-none-staging")
        staging.resolve("cover.jpg").writeBytes(byteArrayOf(1))
        staging.resolve("series.html").writeText("<html></html>")

        val target = filesDir.resolve("Books/none").apply { mkdirs() }
        val error = runCatching { importer(filesDir).assembleMokuroBook(staging) { target } }.exceptionOrNull()

        assertTrue(error is MokuroImportException)
    }

    @Test
    fun fallsBackToMokuroFileNameWhenVolumeAndTitleAreAbsent() = runBlocking {
        val filesDir = newDir("hoshi-mokuro-title-files")
        val staging = newDir("hoshi-mokuro-title-staging")
        staging.resolve("My Manga.mokuro").writeText(
            """{ "pages": [ { "img_width": 1, "img_height": 1, "img_path": "p0.jpg", "blocks": [] } ] }""",
        )
        staging.resolve("p0.jpg").writeBytes(byteArrayOf(1))

        val target = filesDir.resolve("Books/title").apply { mkdirs() }
        val result = importer(filesDir).assembleMokuroBook(staging) { target }

        assertEquals("My Manga", result.title)
    }

    @Test
    fun rejectsImagePathThatEscapesTheSourceFolder() = runBlocking {
        val filesDir = newDir("hoshi-mokuro-escape-files")
        val staging = newDir("hoshi-mokuro-escape-staging")
        staging.resolve("vol.mokuro").writeText(mokuroJson("../escaped.jpg"))
        staging.parentFile!!.resolve("escaped.jpg").writeBytes(byteArrayOf(9))

        val target = filesDir.resolve("Books/escape").apply { mkdirs() }
        val error = runCatching { importer(filesDir).assembleMokuroBook(staging) { target } }.exceptionOrNull()

        assertTrue(error is MokuroImportException)
    }

    @Test
    fun findMokuroFilePrefersShallowestThenAlphabeticalMatch() {
        val root = newDir("hoshi-mokuro-find")
        root.resolve("nested").mkdirs()
        root.resolve("nested/deep.mokuro").writeText("{}")
        root.resolve("b.mokuro").writeText("{}")
        root.resolve("a.mokuro").writeText("{}")

        assertEquals("a.mokuro", root.findMokuroFile()?.name)

        val empty = newDir("hoshi-mokuro-find-empty")
        assertNull(empty.findMokuroFile())
    }
}
