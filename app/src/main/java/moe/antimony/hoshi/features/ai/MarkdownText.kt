package moe.antimony.hoshi.features.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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

/** Left/right breathing room inside a table cell. Matches the history WebView's `td` padding. */
private val TABLE_CELL_HORIZONTAL_PADDING = 6.dp

/** Top/bottom padding inside a table cell — tight, so a short row is one compact line. */
private val TABLE_CELL_VERTICAL_PADDING = 3.dp

/** No column is squeezed narrower than this, however greedy its neighbours are. */
private val TABLE_MIN_COLUMN_CONTENT_WIDTH = 52.dp

@Composable
private fun TableBlockView(table: TableBlock, color: Color, codeBackground: Color, borderColor: Color) {
    val rows = remember(table, codeBackground) { tableRows(table, codeBackground) }
    if (rows.isEmpty()) return
    val columnCount = rows.maxOf { it.cells.size }
    val bodyStyle = MaterialTheme.typography.bodyMedium
    val headerStyle = remember(bodyStyle) { bodyStyle.copy(fontWeight = FontWeight.Bold) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // A tutor reply's breakdown is frequently four columns wide, with the meaning/translation
    // last. Fixed-width columns pushed that final column off-screen behind a horizontal scroll
    // — and, because every column was the same width, a long meaning wrapped into a tall stack
    // that left the short columns beside it looking like huge vertical gaps. So: measure what
    // each column actually wants and share the card's width between them, widest-need-first.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        val widths = remember(rows, columnCount, maxWidth, bodyStyle, headerStyle) {
            tableColumnWidths(
                rows = rows,
                columnCount = columnCount,
                available = maxWidth,
                measurer = measurer,
                bodyStyle = bodyStyle,
                headerStyle = headerStyle,
                density = density,
            )
        }
        val tableWidth = widths.fold(0.dp) { total, width -> total + width }
        Column {
            rows.forEachIndexed { index, row ->
                Row {
                    for (columnIndex in 0 until columnCount) {
                        val cell = row.cells.getOrNull(columnIndex)
                        Box(
                            modifier = Modifier
                                .width(widths[columnIndex])
                                .padding(
                                    horizontal = TABLE_CELL_HORIZONTAL_PADDING,
                                    vertical = TABLE_CELL_VERTICAL_PADDING,
                                ),
                        ) {
                            Text(
                                text = cell?.text ?: AnnotatedString(""),
                                style = if (row.isHeader) headerStyle else bodyStyle,
                                color = color,
                                textAlign = cell?.alignment,
                            )
                        }
                    }
                }
                if (index == 0 || row.isHeader) {
                    Box(
                        Modifier
                            .width(tableWidth)
                            .height(1.dp)
                            .background(borderColor),
                    )
                }
            }
        }
    }
}

/**
 * Column widths for one table, in the same order as the cells.
 *
 * Each column's "wanted" width is its widest cell laid out without wrapping (the CSS
 * `max-content` idea). When they all fit, that is what they get. When they don't, every column
 * shrinks in proportion to what it wanted, so a long meaning column keeps most of the space and a
 * one-word reading column gives it up — bounded below by [TABLE_MIN_COLUMN_CONTENT_WIDTH] so
 * nothing collapses to an unreadable sliver.
 *
 * The floor is deliberately a flat minimum rather than each column's longest unbreakable word:
 * reserving room for "punctuation" or a romaji reading takes that room from the meaning column,
 * which is the one the reader is actually here for, and pushes the table off the screen again.
 *
 * Not a @Composable: the result is memoised per (table, available width).
 */
private fun tableColumnWidths(
    rows: List<RenderedRow>,
    columnCount: Int,
    available: Dp,
    measurer: TextMeasurer,
    bodyStyle: TextStyle,
    headerStyle: TextStyle,
    density: Density,
): List<Dp> {
    val cellPadding = TABLE_CELL_HORIZONTAL_PADDING * 2
    val wanted = MutableList(columnCount) { 0.dp }
    for (row in rows) {
        val style = if (row.isHeader) headerStyle else bodyStyle
        for (columnIndex in 0 until columnCount) {
            val cell = row.cells.getOrNull(columnIndex) ?: continue
            if (cell.text.isEmpty()) continue
            val measured = measurer.measure(text = cell.text, style = style, softWrap = false)
            val maxContent = with(density) { measured.size.width.toDp() } + cellPadding
            if (maxContent > wanted[columnIndex]) wanted[columnIndex] = maxContent
        }
    }
    val total = wanted.fold(0.dp) { sum, width -> sum + width }
    if (available <= 0.dp || total <= available) return wanted

    // `available / columnCount` keeps the floors affordable when the card is very narrow.
    val floor = minOf(TABLE_MIN_COLUMN_CONTENT_WIDTH + cellPadding, available / columnCount)
    val floors = MutableList(columnCount) { minOf(wanted[it], floor) }

    val out = MutableList(columnCount) { 0.dp }
    val pinned = BooleanArray(columnCount)
    var remaining = available
    var flexible = total
    // Pin every column the proportional share would starve, then re-share the rest among the
    // others. Each pass pins at least one column, so this runs at most `columnCount` times.
    var pinnedAny = true
    while (pinnedAny && flexible > 0.dp) {
        pinnedAny = false
        val scale = remaining / flexible
        for (columnIndex in 0 until columnCount) {
            if (pinned[columnIndex] || wanted[columnIndex] * scale >= floors[columnIndex]) continue
            pinned[columnIndex] = true
            out[columnIndex] = floors[columnIndex]
            remaining -= floors[columnIndex]
            flexible -= wanted[columnIndex]
            pinnedAny = true
        }
    }
    val scale = if (flexible > 0.dp && remaining > 0.dp) remaining / flexible else 0f
    for (columnIndex in 0 until columnCount) {
        if (!pinned[columnIndex]) {
            out[columnIndex] = (wanted[columnIndex] * scale).coerceAtLeast(floors[columnIndex])
        }
    }
    return out
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
