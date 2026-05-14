package moe.antimony.hoshi.features.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * A minimal CommonMark-ish renderer for ChatGPT replies.
 *
 * ChatGPT replies arrive as Markdown, which looks wrong rendered as plain text (literal `**`,
 * `- `, `#`). This handles the small subset OpenAI actually emits for the manga tutor prompt:
 * ATX headings, bullet / numbered lists, fenced code blocks, and the inline spans `**bold**`,
 * `*italic*` and `` `code` ``. Anything fancier (tables, links, blockquotes) just falls through
 * as paragraph text — acceptable for this use, and it keeps the feature dependency-free so it
 * stays easy to merge alongside upstream.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier) {
        blocks.forEach { block -> MarkdownBlock(block, color, codeBackground) }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, baseColor: Color, codeBackground: Color) {
    when (block) {
        MdBlock.Blank -> Spacer(Modifier.height(6.dp))
        is MdBlock.Heading -> Text(
            text = buildInline(block.text, codeBackground),
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleMedium
                2 -> MaterialTheme.typography.titleSmall
                else -> MaterialTheme.typography.bodyLarge
            },
            fontWeight = FontWeight.Bold,
            color = baseColor,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        )
        is MdBlock.Paragraph -> Text(
            text = buildInline(block.text, codeBackground),
            style = MaterialTheme.typography.bodyMedium,
            color = baseColor,
        )
        is MdBlock.ListItem -> Row(
            modifier = Modifier.padding(start = (block.indent * 6).dp, top = 1.dp, bottom = 1.dp),
        ) {
            Text(
                text = if (block.ordered) "${block.number}." else "•",
                style = MaterialTheme.typography.bodyMedium,
                color = baseColor,
                modifier = Modifier.widthIn(min = 22.dp),
            )
            Text(
                text = buildInline(block.text, codeBackground),
                style = MaterialTheme.typography.bodyMedium,
                color = baseColor,
            )
        }
        is MdBlock.Code -> Surface(
            color = codeBackground,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        ) {
            Text(
                text = block.text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = baseColor,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListItem(
        val indent: Int,
        val text: String,
        val ordered: Boolean,
        val number: Int,
    ) : MdBlock
    data class Code(val text: String) : MdBlock
    object Blank : MdBlock
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^[-*+]\s+(.*)$""")
private val ORDERED = Regex("""^(\d+)[.)]\s+(.*)$""")

/** Splits Markdown into block-level pieces. Collapses runs of blank lines into a single gap. */
internal fun parseMarkdownBlocks(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    var inCode = false
    val codeBuffer = StringBuilder()
    for (raw in markdown.replace("\r\n", "\n").split("\n")) {
        val trimmed = raw.trimStart()
        if (trimmed.startsWith("```")) {
            if (inCode) {
                blocks += MdBlock.Code(codeBuffer.toString().trimEnd('\n'))
                codeBuffer.clear()
            }
            inCode = !inCode
            continue
        }
        if (inCode) {
            codeBuffer.append(raw).append('\n')
            continue
        }
        if (raw.isBlank()) {
            if (blocks.isNotEmpty() && blocks.last() != MdBlock.Blank) blocks += MdBlock.Blank
            continue
        }
        val heading = HEADING.find(trimmed)
        val bullet = BULLET.find(trimmed)
        val ordered = ORDERED.find(trimmed)
        val indent = (raw.length - trimmed.length).coerceAtMost(8)
        blocks += when {
            heading != null ->
                MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            bullet != null ->
                MdBlock.ListItem(indent, bullet.groupValues[1].trim(), ordered = false, number = 0)
            ordered != null ->
                MdBlock.ListItem(
                    indent = indent,
                    text = ordered.groupValues[2].trim(),
                    ordered = true,
                    number = ordered.groupValues[1].toIntOrNull() ?: 1,
                )
            else -> MdBlock.Paragraph(raw.trim())
        }
    }
    if (inCode && codeBuffer.isNotEmpty()) {
        blocks += MdBlock.Code(codeBuffer.toString().trimEnd('\n'))
    }
    return blocks.dropLastWhile { it == MdBlock.Blank }
}

/** Renders the inline spans `**bold**`, `*italic*` and `` `code` `` into an [AnnotatedString]. */
internal fun buildInline(text: String, codeBackground: Color): AnnotatedString =
    buildAnnotatedString { appendInline(text, codeBackground) }

private fun AnnotatedString.Builder.appendInline(text: String, codeBackground: Color) {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                // Emphasis marker only — not a `*` used as prose punctuation. Require the
                // span to be "tight": non-whitespace right after the opening `**` and right
                // before the closing one, so `a ** b ** c` stays literal.
                val end = if (i + 2 < text.length && !text[i + 2].isWhitespace()) {
                    text.indexOf("**", i + 2)
                } else {
                    -1
                }
                if (end < 0 || text[end - 1].isWhitespace()) {
                    append("**"); i += 2
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInline(text.substring(i + 2, end), codeBackground)
                    }
                    i = end + 2
                }
            }
            text.startsWith("*", i) -> {
                val end = if (i + 1 < text.length && !text[i + 1].isWhitespace()) {
                    text.indexOf("*", i + 1)
                } else {
                    -1
                }
                if (end < 0 || text[end - 1].isWhitespace()) {
                    append("*"); i += 1
                } else {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInline(text.substring(i + 1, end), codeBackground)
                    }
                    i = end + 1
                }
            }
            text.startsWith("`", i) -> {
                val end = text.indexOf("`", i + 1)
                if (end < 0) {
                    append("`"); i += 1
                } else {
                    withStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground),
                    ) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                }
            }
            else -> {
                // Plain run: append up to the next inline marker (always strictly past i,
                // since a marker at i would have matched a branch above).
                var next = text.length
                for (marker in listOf("**", "*", "`")) {
                    val idx = text.indexOf(marker, i)
                    if (idx in i until next) next = idx
                }
                append(text.substring(i, next))
                i = next
            }
        }
    }
}
