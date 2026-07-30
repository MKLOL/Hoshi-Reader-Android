package moe.antimony.hoshi.features.ai

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.BulletList
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the Markdown handling shared by the Compose popup ([parseMarkdown]) and the history
 * WebView ([renderMarkdownToHtml]).
 *
 * The table cases are the regression: the previous hand-written parser had no table support, so
 * the vocabulary/grammar tables the tutor prompt asks for reached the UI as literal `|` pipes.
 */
class MarkdownTextTest {

    private fun blockTypes(markdown: String): List<Class<out Node>> {
        val out = mutableListOf<Class<out Node>>()
        var child = parseMarkdown(markdown).firstChild
        while (child != null) {
            out += child.javaClass
            child = child.next
        }
        return out
    }

    @Test
    fun tableParsesIntoATableBlock() {
        val types = blockTypes(
            """
            | Term | Meaning |
            | --- | --- |
            | 元気 | energy |
            """.trimIndent(),
        )
        assertEquals(listOf<Class<out Node>>(TableBlock::class.java), types)
    }

    @Test
    fun tableRendersToRealHtmlTableMarkupNotPipes() {
        val html = renderMarkdownToHtml(
            """
            | Term | Meaning |
            | --- | --- |
            | 元気 | energy |
            """.trimIndent(),
        )
        assertTrue("expected a <table> in: $html", html.contains("<table>"))
        assertTrue(html.contains("<th>Term</th>"))
        assertTrue(html.contains("<td>元気</td>"))
        assertTrue("pipes should not survive as text: $html", !html.contains("| Term |"))
    }

    @Test
    fun tableWithALeadInLineStillRendersAsATable() {
        // THE reported bug: a tutor reply reads "Here's the breakdown:" then the table, with no
        // blank line. commonmark refuses to start a table there, so this used to render as
        // literal pipes even after switching to a real Markdown parser.
        val html = renderMarkdownToHtml(
            """
            Here's the breakdown:
            | Word | Meaning |
            | --- | --- |
            | 元気 | energy |
            """.trimIndent(),
        )
        assertTrue("expected a <table> in: $html", html.contains("<table>"))
        assertTrue(html.contains("<td>元気</td>"))
        assertTrue("lead-in text must survive: $html", html.contains("Here's the breakdown:"))
        assertTrue("pipes should not survive as text: $html", !html.contains("| Word |"))
    }

    @Test
    fun boldLeadInBeforeATableAlsoWorks() {
        val html = renderMarkdownToHtml("**Vocabulary:**\n| A | B |\n| --- | --- |\n| x | y |")
        assertTrue("expected a <table> in: $html", html.contains("<table>"))
        assertTrue(html.contains("<strong>Vocabulary:</strong>"))
    }

    @Test
    fun aPipeTableInsideFencedCodeIsLeftAlone() {
        val html = renderMarkdownToHtml("```\nnot a table:\n| a | b |\n| --- | --- |\n```")
        assertTrue("fenced content must stay literal: $html", !html.contains("<table>"))
        assertTrue(html.contains("| a | b |"))
    }

    @Test
    fun normalizeTablesLeavesNonTableTextUntouched() {
        val input = "Just a sentence.\nAnother line.\n\n- a\n- b"
        assertEquals(input, normalizeTables(input))
    }

    @Test
    fun javascriptLinkUrlsAreSanitized() {
        val html = renderMarkdownToHtml("[click me](javascript:alert(1))")
        assertTrue("javascript: URL must not reach href: $html", !html.contains("href=\"javascript:"))
    }

    @Test
    fun orderedListStartNumberIsPreserved() {
        val html = renderMarkdownToHtml("3. three\n4. four")
        assertTrue("expected start=3 in: $html", html.contains("start=\"3\""))
    }

    @Test
    fun strikethroughRenders() {
        val html = renderMarkdownToHtml("~~gone~~")
        assertTrue(html.contains("<del>gone</del>"))
    }

    @Test
    fun headingsParagraphsAndListsStillParse() {
        val types = blockTypes("# Title\n\nA paragraph.\n\n- first\n- second\n\n1. one\n2. two")
        assertEquals(
            listOf<Class<out Node>>(
                Heading::class.java,
                Paragraph::class.java,
                BulletList::class.java,
                OrderedList::class.java,
            ),
            types,
        )
    }

    @Test
    fun fencedCodeIsNotTreatedAsAList() {
        val types = blockTypes("before\n\n```\n- not a bullet\n```\n\nafter")
        assertEquals(
            listOf<Class<out Node>>(
                Paragraph::class.java,
                FencedCodeBlock::class.java,
                Paragraph::class.java,
            ),
            types,
        )
    }

    @Test
    fun inlineEmphasisAndCodeRenderAsHtmlSpans() {
        val html = renderMarkdownToHtml("a **bold** and *italic* and `code` here")
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("<em>italic</em>"))
        assertTrue(html.contains("<code>code</code>"))
    }

    @Test
    fun aLoneAsteriskStaysLiteral() {
        val html = renderMarkdownToHtml("rated 4 * 5 stars")
        assertTrue("expected the asterisk to survive: $html", html.contains("4 * 5"))
        assertTrue(!html.contains("<em>"))
    }

    @Test
    fun rawHtmlInAReplyIsEscapedRatherThanInjected() {
        val html = renderMarkdownToHtml("<script>alert(1)</script>")
        assertTrue("script tag must not pass through: $html", !html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun japaneseTextSurvivesRoundTripping() {
        val html = renderMarkdownToHtml("元気ですか")
        assertTrue(html.contains("元気ですか"))
    }
}
