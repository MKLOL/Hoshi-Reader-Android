package moe.antimony.hoshi.mokuro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.file.Files

/**
 * Exercises the @Volatile single-entry cache in [MokuroBookParser].
 *
 * The book-open path parses the same `mokuro.json` twice in quick succession (once when the
 * bookshelf repository writes its metadata sidecars, once when the manga reader loader
 * navigates onto it); the cache coalesces those into one parse. The cache key is
 * (path, mtime, size), so any one of those changing must invalidate.
 */
class MokuroBookParserCacheTest {
    private val sampleJson = """
        {
          "title": "Series",
          "volume": "Volume 1",
          "pages": [
            {
              "img_width": 800,
              "img_height": 1200,
              "img_path": "p0.jpg",
              "blocks": []
            }
          ]
        }
    """.trimIndent()

    private val altSampleJson = """
        {
          "title": "Series",
          "volume": "Volume Renamed",
          "pages": [
            {
              "img_width": 800,
              "img_height": 1200,
              "img_path": "p0.jpg",
              "blocks": []
            }
          ]
        }
    """.trimIndent()

    @Test
    fun secondParseReturnsSameInstanceWhenFileUntouched() {
        val root = Files.createTempDirectory("hoshi-mokuro-cache-hit").toFile()
        root.resolve("mokuro.json").writeText(sampleJson)
        val parser = MokuroBookParser()

        val first = parser.parse(root)
        val second = parser.parse(root)

        // Identity equality proves the cache hit: a second decode would build a new object.
        assertSame(first, second)
    }

    @Test
    fun changingMtimeInvalidatesCache() {
        val root = Files.createTempDirectory("hoshi-mokuro-cache-mtime").toFile()
        val sidecar = root.resolve("mokuro.json")
        sidecar.writeText(sampleJson)
        val parser = MokuroBookParser()

        val first = parser.parse(root)
        // Force a distinct mtime — pick a value clearly different from "now" so even a
        // coarse FS timestamp resolution can't collide.
        val tweakedMtime = sidecar.lastModified() - 60_000L
        check(sidecar.setLastModified(tweakedMtime)) { "Cannot set lastModified on test fixture" }

        val second = parser.parse(root)

        assertNotSame("mtime change must invalidate the cache", first, second)
        // Content unchanged, so the parsed value must still be equal.
        assertEquals(first.title, second.title)
        assertEquals(first.pages.size, second.pages.size)
    }

    @Test
    fun changingFileContentInvalidatesCache() {
        val root = Files.createTempDirectory("hoshi-mokuro-cache-content").toFile()
        val sidecar = root.resolve("mokuro.json")
        sidecar.writeText(sampleJson)
        val parser = MokuroBookParser()

        val first = parser.parse(root)
        // Rewrite with different content; this changes size (or at least content) and
        // typically updates mtime — both should cause invalidation.
        sidecar.writeText(altSampleJson)

        val second = parser.parse(root)

        assertNotSame(first, second)
        assertEquals("Volume 1", first.title)
        assertEquals("Volume Renamed", second.title)
    }

    @Test
    fun changingOnlySizeInvalidatesCacheEvenIfMtimeMatches() {
        val root = Files.createTempDirectory("hoshi-mokuro-cache-size").toFile()
        val sidecar = root.resolve("mokuro.json")
        sidecar.writeText(sampleJson)
        val parser = MokuroBookParser()

        val first = parser.parse(root)
        val originalMtime = sidecar.lastModified()
        // Rewrite with a different-length payload, then pin mtime back to the original
        // value: the size component of the cache key must catch this.
        sidecar.writeText(altSampleJson)
        check(sidecar.setLastModified(originalMtime)) { "Cannot pin mtime on test fixture" }

        val second = parser.parse(root)

        assertNotSame("size delta must invalidate the cache", first, second)
        assertEquals("Volume Renamed", second.title)
    }

    @Test
    fun cacheIsPerInstanceSoFreshParserColdStarts() {
        val root = Files.createTempDirectory("hoshi-mokuro-cache-instance").toFile()
        root.resolve("mokuro.json").writeText(sampleJson)

        val firstParser = MokuroBookParser()
        val firstBook = firstParser.parse(root)
        val secondParser = MokuroBookParser()
        val secondBook = secondParser.parse(root)

        assertNotSame("each parser instance has its own cache", firstBook, secondBook)
        assertEquals(firstBook.title, secondBook.title)
    }
}
