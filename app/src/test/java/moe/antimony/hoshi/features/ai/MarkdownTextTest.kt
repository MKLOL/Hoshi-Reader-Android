package moe.antimony.hoshi.features.ai

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Markdown renderer behind the ChatGPT reply view: the block-level parser
 * ([parseMarkdownBlocks]) and the inline span builder ([buildInline]).
 */
class MarkdownTextTest {

    @Test
    fun classifiesHeadingsBulletsAndParagraphs() {
        val blocks = parseMarkdownBlocks(
            """
            # Title
            A paragraph.
            - first
            * second
            1. one
            2) two
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                MdBlock.Heading(1, "Title"),
                MdBlock.Paragraph("A paragraph."),
                MdBlock.ListItem(0, "first", ordered = false, number = 0),
                MdBlock.ListItem(0, "second", ordered = false, number = 0),
                MdBlock.ListItem(0, "one", ordered = true, number = 1),
                MdBlock.ListItem(0, "two", ordered = true, number = 2),
            ),
            blocks,
        )
    }

    @Test
    fun collapsesBlankRunsAndTrimsTrailingBlanks() {
        val blocks = parseMarkdownBlocks("a\n\n\n\nb\n\n")

        assertEquals(
            listOf(
                MdBlock.Paragraph("a"),
                MdBlock.Blank,
                MdBlock.Paragraph("b"),
            ),
            blocks,
        )
    }

    @Test
    fun capturesFencedCodeBlocksVerbatim() {
        val blocks = parseMarkdownBlocks("before\n```\n- not a bullet\n  indented\n```\nafter")

        assertEquals(
            listOf(
                MdBlock.Paragraph("before"),
                MdBlock.Code("- not a bullet\n  indented"),
                MdBlock.Paragraph("after"),
            ),
            blocks,
        )
    }

    @Test
    fun indentedListItemsRecordIndentDepth() {
        val blocks = parseMarkdownBlocks("- top\n    - nested")

        assertEquals(
            listOf(
                MdBlock.ListItem(0, "top", ordered = false, number = 0),
                MdBlock.ListItem(4, "nested", ordered = false, number = 0),
            ),
            blocks,
        )
    }

    @Test
    fun inlineEmphasisStripsMarkersAndStylesTightSpans() {
        val rendered = buildInline("a **bold** and *italic* and `code` here", Color.Unspecified)

        assertEquals("a bold and italic and code here", rendered.text)
        // bold, italic, code -> three styled spans.
        assertEquals(3, rendered.spanStyles.size)
    }

    @Test
    fun looseAsterisksUsedAsPunctuationStayLiteral() {
        // A `*` with whitespace on the inner side is prose punctuation, not emphasis, so it
        // must not open a span (this was rendering " 5 stars " italic before the flanking fix).
        val singles = buildInline("rated 4 * 5 stars * each", Color.Unspecified)
        assertEquals("rated 4 * 5 stars * each", singles.text)
        assertTrue(singles.spanStyles.isEmpty())

        val doubles = buildInline("a ** b ** c", Color.Unspecified)
        assertEquals("a ** b ** c", doubles.text)
        assertTrue(doubles.spanStyles.isEmpty())
    }

    @Test
    fun unbalancedInlineMarkersStayLiteral() {
        val rendered = buildInline("two ** and one * left", Color.Unspecified)

        assertEquals("two ** and one * left", rendered.text)
        assertTrue(rendered.spanStyles.isEmpty())
    }
}
