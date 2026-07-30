package moe.antimony.hoshi.features.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MdText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Renders a ChatGPT reply written in Markdown.
 *
 * Parsing is done by commonmark-java (the reference CommonMark implementation) with the GFM
 * tables and strikethrough extensions, matching what iOS gets from Textual. The previous
 * hand-written parser here understood only headings, lists, code and a few inline spans, so the
 * vocabulary/grammar **tables** the tutor prompt asks for fell through as paragraphs and rendered
 * as literal `|` pipes.
 *
 * Only the AST -> Compose rendering below is ours; no Markdown syntax is interpreted by hand.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val document = remember(markdown) { parseMarkdown(markdown) }
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = modifier) {
        RenderBlocks(document, color, codeBackground, borderColor)
    }
}

internal val MARKDOWN_EXTENSIONS = listOf(TablesExtension.create(), StrikethroughExtension.create())

private val PARSER: Parser = Parser.builder()
    .extensions(MARKDOWN_EXTENSIONS)
    .build()

/**
 * A GFM table delimiter row, e.g. `| --- | :--: |` or `---|---`.
 *
 * Used only to decide where a blank line is missing — the table itself is still parsed entirely by
 * commonmark. See [normalizeTables].
 */
private val TABLE_DELIMITER = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")

/**
 * Inserts the blank line GFM requires before a table.
 *
 * commonmark-java only starts a table when the paragraph directly above the delimiter row is
 * exactly one line — the header row. A tutor reply almost always reads
 *
 * ```
 * Here's the vocabulary:
 * | Word | Meaning |
 * | --- | --- |
 * ```
 *
 * and that lead-in line makes the whole thing parse as one paragraph, which is exactly the
 * "renders as literal pipes" bug this is meant to fix. Separating the lead-in with a blank line is
 * a whitespace normalisation, not Markdown parsing — commonmark still owns every token.
 */
internal fun normalizeTables(markdown: String): String {
    val lines = markdown.replace("\r\n", "\n").split("\n")
    val out = ArrayList<String>(lines.size + 4)
    var inFence = false
    for ((index, line) in lines.withIndex()) {
        if (line.trimStart().startsWith("```")) inFence = !inFence
        // A delimiter row needs a header row above it and a non-blank line above THAT for the
        // ambiguity to arise; fenced code is left completely untouched.
        if (!inFence && index >= 2 && TABLE_DELIMITER.matches(line)) {
            val header = lines[index - 1]
            val before = lines[index - 2]
            if (header.contains('|') && before.isNotBlank() && out.size >= 2) {
                out.add(out.size - 1, "")
            }
        }
        out += line
    }
    return out.joinToString("\n")
}

internal fun parseMarkdown(markdown: String): Document =
    PARSER.parse(normalizeTables(markdown)) as Document

/** Walks the children of [parent], emitting one composable per block-level node. */
@Composable
private fun RenderBlocks(parent: Node, color: Color, codeBackground: Color, borderColor: Color) {
    var child = parent.firstChild
    while (child != null) {
        RenderBlock(child, color, codeBackground, borderColor)
        child = child.next
    }
}

@Composable
private fun RenderBlock(node: Node, color: Color, codeBackground: Color, borderColor: Color) {
    when (node) {
        is Heading -> Text(
            text = inlineText(node, codeBackground),
            style = when (node.level) {
                1 -> MaterialTheme.typography.titleMedium
                2 -> MaterialTheme.typography.titleSmall
                else -> MaterialTheme.typography.bodyLarge
            },
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        )

        is Paragraph -> Text(
            text = inlineText(node, codeBackground),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.padding(vertical = 2.dp),
        )

        is BulletList -> ListBlock(node, ordered = false, start = 1, color, codeBackground, borderColor)
        is OrderedList -> ListBlock(node, ordered = true, node.markerStartNumber ?: 1, color, codeBackground, borderColor)

        is FencedCodeBlock -> CodeBlock(node.literal, color, codeBackground)
        is IndentedCodeBlock -> CodeBlock(node.literal, color, codeBackground)

        is BlockQuote -> Row(
            modifier = Modifier
                .padding(vertical = 3.dp)
                // The intrinsic height must be measured on the ROW (against its content); putting
                // it on the empty Box below resolved to 0 and the rule never drew.
                .height(IntrinsicSize.Min),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(borderColor),
            )
            Column(Modifier.padding(start = 8.dp)) {
                RenderBlocks(node, color, codeBackground, borderColor)
            }
        }

        is ThematicBreak -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .height(1.dp)
                .background(borderColor),
        )

        is TableBlock -> TableBlockView(node, color, codeBackground, borderColor)

        // Anything else (HTML blocks, link reference definitions) contributes no visible output.
        else -> Spacer(Modifier.height(0.dp))
    }
}

@Composable
private fun CodeBlock(literal: String, color: Color, codeBackground: Color) {
    Surface(
        color = codeBackground,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = literal.trimEnd('\n'),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
            modifier = Modifier.padding(10.dp),
        )
    }
}

@Composable
private fun ListBlock(
    list: Node,
    ordered: Boolean,
    start: Int,
    color: Color,
    codeBackground: Color,
    borderColor: Color,
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        var item = list.firstChild
        var number = start
        while (item != null) {
            if (item is ListItem) {
                Row(modifier = Modifier.padding(vertical = 1.dp)) {
                    Text(
                        text = if (ordered) "$number." else "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                        modifier = Modifier.widthIn(min = 22.dp),
                    )
                    Column {
                        // A list item holds blocks (usually one paragraph, sometimes a nested
                        // list), so recurse rather than assuming a single line of text.
                        RenderBlocks(item, color, codeBackground, borderColor)
                    }
                }
                number++
            }
            item = item.next
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Tables
// ---------------------------------------------------------------------------------------------

@Composable
private fun TableBlockView(table: TableBlock, color: Color, codeBackground: Color, borderColor: Color) {
    val rows = remember(table, codeBackground) { tableRows(table, codeBackground) }
    if (rows.isEmpty()) return
    val columnCount = rows.maxOf { it.cells.size }

    // A vocab table can be wider than a phone; let it scroll horizontally instead of squeezing
    // every column into an unreadable sliver.
    Column(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        rows.forEachIndexed { index, row ->
            Row(horizontalArrangement = Arrangement.Start) {
                for (columnIndex in 0 until columnCount) {
                    val cell = row.cells.getOrNull(columnIndex)
                    Box(
                        modifier = Modifier
                            .width(160.dp)
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = cell?.text ?: AnnotatedString(""),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (row.isHeader) FontWeight.Bold else FontWeight.Normal,
                            color = color,
                            textAlign = cell?.alignment,
                        )
                    }
                }
            }
            if (index == 0 || row.isHeader) {
                Box(
                    Modifier
                        .width((160.dp * columnCount))
                        .height(1.dp)
                        .background(borderColor),
                )
            }
        }
    }
}

private class RenderedCell(val text: AnnotatedString, val alignment: TextAlign?)

private class RenderedRow(val cells: List<RenderedCell>, val isHeader: Boolean)

/**
 * Flattens a [TableBlock] into rows of pre-rendered cells.
 *
 * Not a @Composable so it can be memoised with `remember`: the inline spans in a cell never change
 * once the reply has arrived.
 */
private fun tableRows(table: TableBlock, codeBackground: Color): List<RenderedRow> {
    val out = mutableListOf<RenderedRow>()
    var section = table.firstChild
    while (section != null) {
        val isHeader = section is TableHead
        if (section is TableHead || section is TableBody) {
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) {
                    val cells = mutableListOf<RenderedCell>()
                    var cell = row.firstChild
                    while (cell != null) {
                        if (cell is TableCell) {
                            cells += RenderedCell(
                                text = buildAnnotatedString { appendInline(cell, codeBackground) },
                                alignment = when (cell.alignment) {
                                    TableCell.Alignment.CENTER -> TextAlign.Center
                                    TableCell.Alignment.RIGHT -> TextAlign.End
                                    TableCell.Alignment.LEFT -> TextAlign.Start
                                    else -> null
                                },
                            )
                        }
                        cell = cell.next
                    }
                    out += RenderedRow(cells, isHeader)
                }
                row = row.next
            }
        }
        section = section.next
    }
    return out
}

// ---------------------------------------------------------------------------------------------
// Inline spans
// ---------------------------------------------------------------------------------------------

@Composable
private fun inlineText(node: Node, codeBackground: Color): AnnotatedString =
    remember(node, codeBackground) { buildAnnotatedString { appendInline(node, codeBackground) } }

/** Appends every inline child of [parent] with its emphasis/code/link styling applied. */
internal fun AnnotatedString.Builder.appendInline(parent: Node, codeBackground: Color) {
    var child = parent.firstChild
    while (child != null) {
        when (val n = child) {
            is MdText -> append(n.literal)
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInline(n, codeBackground)
            }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInline(n, codeBackground)
            }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendInline(n, codeBackground)
            }
            is Code -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground),
            ) {
                append(n.literal)
            }
            is Link -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                appendInline(n, codeBackground)
            }
            is SoftLineBreak -> append(" ")
            is HardLineBreak -> append("\n")
            else -> appendInline(n, codeBackground)
        }
        child = child.next
    }
}
