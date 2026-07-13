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
        // mokuroFs = 25 → headroom = (30 - 25) = 5; result = 25 + 5×0.5 = 27.5 → 27.
        // See MokuroBookParser.clampMokuroFontSize.
        assertEquals(27, box.fontSize)
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
        // mokuroFs = 130 is far above the big-artwork threshold so the zoom is 1.0
        // and the OCR text reveals at mokuro's reported size. The parser intentionally
        // trusts mokuro here — catastrophic overshoot, when it happens, is handled at
        // reveal time by the wrap-fallback in MangaPageHtml.
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
    fun clampLeavesAtTargetUnchanged() {
        // mokuroFs = 30 sits at the readable-target; headroom = 0; result = 30. A short
        // emphasis bubble reveals at the artwork's size — the user-facing contract.
        assertEquals(
            30,
            clampMokuroFontSize(
                mokuroFontSize = 30.0,
                boxWidth = 100,
                boxHeight = 300,
                vertical = true,
                lines = listOf("はい"),
            ),
        )
    }

    @Test
    fun clampPassesThroughOvershotMokuroAtBigArtworkSize() {
        // Mokuro overshoots the artwork glyph here (reports 137 for chars actually
        // ~14 px tall), but the parser intentionally trusts mokuro's reported size.
        // 137 is past the big-artwork threshold so zoom = 1.0, result = 137. The
        // runtime wrap-fallback in MangaPageHtml then shrinks-to-fit or word-wraps
        // when the text would visibly overflow the box.
        val clamped = clampMokuroFontSize(
            mokuroFontSize = 137.0,
            boxWidth = 85,
            boxHeight = 145,
            vertical = false,
            lines = listOf("なんだそりゃ"),
        )
        assertEquals(137, clamped)
    }

    @Test
    fun clampPassesThroughBigMokuroVerticalUnchanged() {
        // mokuro reported 175 — past the big-artwork threshold, no zoom, result = 175.
        assertEquals(
            175,
            clampMokuroFontSize(
                mokuroFontSize = 175.0,
                boxWidth = 194,
                boxHeight = 552,
                vertical = true,
                lines = listOf("大不評！？"),
            ),
        )
    }

    @Test
    fun clampScalesBigMokuroByNoZoomRegardlessOfLineCount() {
        // mokuroFs = 80 is past the big-artwork threshold so zoom = 1.0 regardless of
        // line count or char count. Result = 80 — the parser intentionally never
        // depends on box geometry or char count.
        assertEquals(
            80,
            clampMokuroFontSize(
                mokuroFontSize = 80.0,
                boxWidth = 200,
                boxHeight = 400,
                vertical = true,
                lines = listOf("沖縄の曲には", "ウクレレも", "合うかもと思って"),
            ),
        )
    }

    @Test
    fun clampReturnsMokuroValueUnchangedAtTarget() {
        // mokuroFs = 42 is past the readable-target so no boost is applied. The
        // result is mokuro's value unchanged, independent of any other input.
        assertEquals(
            42,
            clampMokuroFontSize(42.0, boxWidth = 0, boxHeight = 100, vertical = true, lines = listOf("a")),
        )
        assertEquals(
            42,
            clampMokuroFontSize(42.0, boxWidth = 100, boxHeight = 100, vertical = true, lines = emptyList()),
        )
        assertEquals(
            42,
            clampMokuroFontSize(42.0, boxWidth = 100, boxHeight = 100, vertical = true, lines = listOf("", "")),
        )
        // mokuroFs=0 floors to 1, then the boost lifts that to ~15 px.
        assertEquals(
            15,
            clampMokuroFontSize(0.0, boxWidth = 100, boxHeight = 100, vertical = true, lines = listOf("a")),
        )
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

    @Test(expected = IllegalArgumentException::class)
    fun parseFailsWhenSidecarHasNoPages() {
        val root = Files.createTempDirectory("hoshi-mokuro-empty").toFile()
        root.resolve("mokuro.json").writeText("""{"version":"0.2.2","title":"T","pages":[]}""")
        MokuroBookParser().parse(root)
    }

    @Test
    fun blankVolumeFallsThroughToTitleNotDirectoryName() {
        // A whitespace-only volume (common in scraped metadata) must not become the
        // title, and must not skip a valid `title`.
        val root = Files.createTempDirectory("hoshi-mokuro-blankvol").toFile()
        root.resolve("mokuro.json").writeText(
            """
            {"version":"0.2.2","volume":"   ","title":"Real Title",
             "pages":[{"img_width":100,"img_height":100,"img_path":"p.jpg","blocks":[]}]}
            """.trimIndent(),
        )
        assertEquals("Real Title", MokuroBookParser().parse(root).title)
    }

    @Test
    fun fractionalBoxCoordinatesTruncateToIntegerGeometry() {
        // mokuro emits Double coords; the parser truncates (toInt), it does not round.
        val root = Files.createTempDirectory("hoshi-mokuro-frac").toFile()
        root.resolve("mokuro.json").writeText(
            """
            {"version":"0.2.2","volume":"V",
             "pages":[{"img_width":1000,"img_height":1500,"img_path":"p.jpg",
               "blocks":[{"box":[10.9,20.1,110.9,80.4],"vertical":false,"font_size":20,"lines":["x"]}]}]}
            """.trimIndent(),
        )
        val box = MokuroBookParser().parse(root).pages[0].textBoxes.single()
        assertEquals(10, box.left)
        assertEquals(20, box.top)
        assertEquals(100, box.width) // (110.9 - 10.9).toInt()
        assertEquals(60, box.height) // (80.4 - 20.1).toInt()
    }

    @Test
    fun degenerateBoxWhereMaxIsBeforeMinCoercesToZeroSizeNotNegative() {
        val root = Files.createTempDirectory("hoshi-mokuro-degen").toFile()
        root.resolve("mokuro.json").writeText(
            """
            {"version":"0.2.2","volume":"V",
             "pages":[{"img_width":1000,"img_height":1500,"img_path":"p.jpg",
               "blocks":[{"box":[100,100,50,50],"vertical":false,"font_size":20,"lines":["x"]}]}]}
            """.trimIndent(),
        )
        val box = MokuroBookParser().parse(root).pages[0].textBoxes.single()
        assertEquals(0, box.width)
        assertEquals(0, box.height)
    }
}
