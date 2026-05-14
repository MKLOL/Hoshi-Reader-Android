package moe.antimony.hoshi.features.mangareader

import moe.antimony.hoshi.mokuro.MokuroPage
import moe.antimony.hoshi.mokuro.MokuroTextBox
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaPageHtmlTest {
    private fun page(
        textBoxes: List<MokuroTextBox>,
        imageWidth: Int = 1000,
        imageHeight: Int = 1500,
        imagePath: String = "images/page_000.jpg",
    ) = MokuroPage(
        index = 0,
        imagePath = imagePath,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        textBoxes = textBoxes,
    )

    private fun build(page: MokuroPage, eInkMode: Boolean = false) = MangaPageHtml.build(
        page = page,
        backgroundCssColor = "#ffffff",
        selectionScript = "/* selection script */",
        scanNonJapaneseText = true,
        eInkMode = eInkMode,
    )

    @Test
    fun textBoxCoordinatesAreScaledToPercentagesOfImagePixelSize() {
        val box = MokuroTextBox(
            left = 250,
            top = 300,
            width = 500,
            height = 150,
            fontSize = 40,
            vertical = false,
            lines = listOf("テスト"),
        )
        val html = build(page(listOf(box), imageWidth = 1000, imageHeight = 1500))

        // 250 / 1000 = 25%, 300 / 1500 = 20%, 500 / 1000 = 50%, 150 / 1500 = 10%.
        assertTrue("left should be 25%", html.contains("left: 25%"))
        assertTrue("top should be 20%", html.contains("top: 20%"))
        assertTrue("width should be 50%", html.contains("width: 50%"))
        assertTrue("height should be 10%", html.contains("height: 10%"))
    }

    @Test
    fun fontSizeIsScaledRelativeToImageWidth() {
        val box = MokuroTextBox(
            left = 0,
            top = 0,
            width = 100,
            height = 100,
            fontSize = 50,
            vertical = false,
            lines = listOf("あ"),
        )
        val html = build(page(listOf(box), imageWidth = 1000, imageHeight = 1500))

        // 50 / 1000 = 5% of container width -> 5cqw.
        assertTrue("font-size should be 5cqw", html.contains("font-size: 5cqw"))
    }

    @Test
    fun verticalBoxesGetTheVerticalClass() {
        val verticalBox = MokuroTextBox(0, 0, 50, 200, 30, vertical = true, lines = listOf("縦書き"))
        val horizontalBox = MokuroTextBox(0, 0, 200, 50, 30, vertical = false, lines = listOf("横書き"))
        val html = build(page(listOf(verticalBox, horizontalBox)))

        assertTrue(html.contains("class=\"ocr-box vertical\""))
        assertTrue(html.contains("class=\"ocr-box\""))
    }

    @Test
    fun ocrLinesBecomeRealSelectableDomText() {
        val box = MokuroTextBox(10, 10, 100, 100, 20, vertical = false, lines = listOf("一行目", "二行目"))
        val html = build(page(listOf(box)))

        // The OCR text is present as real DOM text inside a <p>, joined by newlines.
        assertTrue(html.contains("<p>一行目\n二行目</p>"))
        // ...and the box is marked user-selectable so a tap can select it.
        assertTrue(html.contains("user-select: text"))
    }

    @Test
    fun htmlSpecialCharactersInOcrTextAreEscaped() {
        val box = MokuroTextBox(0, 0, 100, 100, 20, vertical = false, lines = listOf("a < b & c > d"))
        val html = build(page(listOf(box)))

        assertTrue(html.contains("a &lt; b &amp; c &gt; d"))
        assertFalse(html.contains("<p>a < b"))
    }

    @Test
    fun imageSrcIsBookRootRelativeSoTheResourceBridgeCanInterceptIt() {
        val html = build(page(emptyList(), imagePath = "images/page_007.jpg"))

        assertTrue(html.contains("src=\"images/page_007.jpg\""))
        // .frame is sized in pixels by the page script from the image's intrinsic size and
        // the JS viewport, so the OCR overlay tracks the rendered image exactly.
        assertTrue(html.contains("IMG_W = 1000, IMG_H = 1500"))
        assertTrue(html.contains("Math.min(vw / IMG_W, vh / IMG_H)"))
    }

    @Test
    fun selectionScriptAndScanSettingAreInjectedIntoThePage() {
        val html = build(page(emptyList()))

        assertTrue(html.contains("/* selection script */"))
        assertTrue(html.contains("window.scanNonJapaneseText = true"))
    }

    @Test
    fun matchedWordHighlightAndOcrBoxPopupRectAreWiredIntoThePage() {
        val html = build(page(emptyList()))

        // The dictionary-matched word gets a visible highlight (CSS Custom Highlight API).
        assertTrue(html.contains("::highlight(hoshi-selection)"))
        // HoshiTextSelection is wrapped so the lookup popup is positioned off the OCR box
        // rather than over the tapped character.
        assertTrue(html.contains("window.HoshiTextSelection ="))
        assertTrue(html.contains("closest('.ocr-box')"))
    }

    @Test
    fun backgroundColourIsAppliedToThePageLetterbox() {
        val html = MangaPageHtml.build(
            page = page(emptyList()),
            backgroundCssColor = "#101010",
            selectionScript = "",
            scanNonJapaneseText = false,
            eInkMode = false,
        )
        assertTrue(html.contains("background: #101010"))
        assertTrue(html.contains("window.scanNonJapaneseText = false"))
    }

    @Test
    fun eInkModeUsesAHighContrastMatchedWordHighlight() {
        val colour = build(page(emptyList()), eInkMode = false)
        val eInk = build(page(emptyList()), eInkMode = true)

        // Colour displays get the amber highlight; e-ink gets an inverted black-on-white one
        // since a colour highlight is nearly invisible on greyscale.
        assertTrue(colour.contains("::highlight(hoshi-selection) { background: #ffd400"))
        assertTrue(eInk.contains("::highlight(hoshi-selection) { background: #000; color: #fff;"))
        assertFalse(eInk.contains("#ffd400"))
    }

    @Test
    fun pageWithNoTextBoxesStillRendersTheImageFrame() {
        val html = build(page(emptyList()))
        assertTrue(html.contains("class=\"page-image\""))
        assertTrue(html.contains("class=\"ocr-layer\""))
        assertFalse(html.contains("class=\"ocr-box"))
    }
}
