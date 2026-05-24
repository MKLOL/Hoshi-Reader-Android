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
        // 13-char horizontal line in a 212×65 box: fit = min(212/13, 65/(1*1.1)) = 16.3 px;
        // mokuro's 25 px is above the fit ceiling at the small-font safety multiplier
        // (16.3*1.5 = 24.5), so it is clamped down to 24 — see clampMokuroFontSize.
        assertEquals(24, box.fontSize)
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
        // mokuro reported font_size=130 px for "はいはい！！" (6 chars vertical) in a
        // 240×119 box — overshooting the box height by ~6x. The adaptive clamp at a
        // big-font safety multiplier of 1.0 caps it to the fit (18 px) so the OCR plate
        // doesn't grow many times past the artwork bubble.
        assertEquals(18, secondPage.textBoxes.single().fontSize)
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
    fun clampLeavesSmallFontUntouchedWhenBoxComfortablyFits() {
        // Small font (under the small-font threshold) in a box with plenty of room: the
        // adaptive cap is permissive (1.5x of fit), so mokuro's value passes through. This
        // preserves the "small bubble looks zoomed a bit, I like that" feel.
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
    fun clampShrinksOversizedHorizontalTextToFitBox() {
        // Yotsubato Vol 2 page 193 case: tall narrow box wrongly tagged as horizontal with
        // a huge font_size. 6 chars at 137 px horizontally need ~822 px of width but the
        // box is only 85 px wide. The clamp must drop font_size aggressively so the OCR
        // plate doesn't grow ~10x past the artwork. The runtime wrap-fallback in
        // MangaPageHtml then promotes this to a multi-row wrap so the text stays readable.
        val clamped = clampMokuroFontSize(
            mokuroFontSize = 137.0,
            boxWidth = 85,
            boxHeight = 145,
            vertical = false,
            lines = listOf("なんだそりゃ"),
        )
        assertEquals(14, clamped)
    }

    @Test
    fun clampShrinksOversizedVerticalTextWithLineHeightApplied() {
        // Yotsubato Vol 2 page 191 "大不評！？": vertical, 5 chars, mokuro font_size=175 in
        // a 194×552 box. 5 chars * 175 * 1.1 = 962 px tall but the box is 552 — text
        // overshoots by 1.74x. The cap (safety 1.0 at fs=175) brings it back to ~100 px so
        // the plate matches the artwork bubble.
        assertEquals(
            100,
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
    fun clampUsesMaxCharsAcrossMultiLineVerticalBlock() {
        // Multi-line vertical: each line is one column running top-to-bottom; the height
        // axis is constrained by the *longest* line's char count (here 8 chars).
        assertEquals(
            53,
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
    fun clampFallsBackToMokuroValueOnDegenerateInput() {
        // Empty lines or zero box dims would otherwise divide by zero. Fall back to the
        // raw mokuro value (still floor-clamped to 1) so a malformed block still renders.
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
        assertEquals(
            1,
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
}
