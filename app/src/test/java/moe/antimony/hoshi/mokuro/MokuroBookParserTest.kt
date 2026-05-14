package moe.antimony.hoshi.mokuro

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.bookContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MokuroBookParserTest {
    // Trimmed-down sample of real mokuro 0.2.x output: two pages, mixed horizontal/vertical
    // boxes, plus an unknown field ("lines_coords") that must be ignored.
    private val sampleMokuroJson = """
        {
          "version": "0.2.2",
          "title": "Downloads",
          "volume": "001 [JP] Yotsubato",
          "pages": [
            {
              "img_width": 978,
              "img_height": 1400,
              "img_path": "Yotsubato_v01_000-a.jpg",
              "blocks": [
                {
                  "box": [740, 1315, 952, 1380],
                  "vertical": false,
                  "font_size": 25,
                  "lines_coords": [[[746.0, 1321.0]]],
                  "lines": ["ＹＯＴＳＵＢＡ＆！", "ＫＲＹＯＨＩＫＯＡＺＵＭＡ"]
                }
              ]
            },
            {
              "img_width": 962,
              "img_height": 1400,
              "img_path": "Yotsubato_v01_001.jpg",
              "blocks": [
                {
                  "box": [63, 233, 303, 352],
                  "vertical": true,
                  "font_size": 130,
                  "lines": ["はいはい！！"]
                },
                {
                  "box": [10, 10],
                  "vertical": false,
                  "font_size": 12,
                  "lines": ["truncated box ignored"]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesPagesBlocksAndPrefersVolumeAsTitle() {
        val root = Files.createTempDirectory("hoshi-mokuro-parse").toFile()
        root.resolve("mokuro.json").writeText(sampleMokuroJson)

        val book = MokuroBookParser().parse(root)

        assertEquals("001 [JP] Yotsubato", book.title)
        assertEquals(2, book.pages.size)
        assertEquals("Yotsubato_v01_000-a.jpg", book.coverImagePath)

        val firstPage = book.pages[0]
        assertEquals(0, firstPage.index)
        assertEquals(978, firstPage.imageWidth)
        assertEquals(1400, firstPage.imageHeight)
        assertEquals(1, firstPage.textBoxes.size)

        val box = firstPage.textBoxes.single()
        // box [740, 1315, 952, 1380] -> left/top + (xMax-xMin)/(yMax-yMin)
        assertEquals(740, box.left)
        assertEquals(1315, box.top)
        assertEquals(212, box.width)
        assertEquals(65, box.height)
        assertEquals(25, box.fontSize)
        assertFalse(box.vertical)
        assertEquals(listOf("ＹＯＴＳＵＢＡ＆！", "ＫＲＹＯＨＩＫＯＡＺＵＭＡ"), box.lines)
    }

    @Test
    fun secondPageKeepsVerticalFlagAndDropsTruncatedBoxes() {
        val root = Files.createTempDirectory("hoshi-mokuro-vertical").toFile()
        root.resolve("mokuro.json").writeText(sampleMokuroJson)

        val secondPage = MokuroBookParser().parse(root).pages[1]

        assertEquals(1, secondPage.index)
        // One block has a 2-element box and is dropped; only the valid vertical block survives.
        assertEquals(1, secondPage.textBoxes.size)
        assertTrue(secondPage.textBoxes.single().vertical)
        assertEquals(130, secondPage.textBoxes.single().fontSize)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseFailsWhenSidecarMissing() {
        val root = Files.createTempDirectory("hoshi-mokuro-missing").toFile()
        MokuroBookParser().parse(root)
    }

    @Test
    fun bookContentTypeDetectsMokuroSidecar() {
        val mokuroRoot = Files.createTempDirectory("hoshi-content-mokuro").toFile()
        mokuroRoot.resolve("mokuro.json").writeText(sampleMokuroJson)
        assertEquals(ContentType.Mokuro, bookContentType(mokuroRoot))

        val epubRoot = Files.createTempDirectory("hoshi-content-epub").toFile()
        epubRoot.resolve("metadata.json").writeText("{}")
        assertEquals(ContentType.Epub, bookContentType(epubRoot))
    }

    @Test
    fun coverImagePathIsNullForEmptyVolumeButTitleStillFallsBack() {
        val root = Files.createTempDirectory("hoshi-mokuro-fallback").toFile()
        root.resolve("mokuro.json").writeText(
            """{ "pages": [ { "img_path": "p0.jpg", "img_width": 1, "img_height": 1, "blocks": [] } ] }""",
        )

        val book = MokuroBookParser().parse(root)

        // No "volume"/"title" in JSON -> falls back to the directory name.
        assertEquals(root.nameWithoutExtension, book.title)
        assertEquals("p0.jpg", book.coverImagePath)
        assertTrue(book.pages.single().textBoxes.isEmpty())
        assertNull(book.pages.single().textBoxes.firstOrNull())
    }
}
