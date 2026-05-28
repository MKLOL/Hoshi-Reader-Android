package moe.antimony.hoshi.features.ai

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Builds the self-contained HTML document rendered inside [AiChatHistoryWebView].
 *
 * The history is one scrolling document instead of a Compose LazyColumn so the shared
 * selection script in [moe.antimony.hoshi.features.reader.ReaderSelectionScripts] can
 * pick up taps on Japanese words anywhere in the response markdown — exactly like the
 * EPUB and manga readers. Each entry renders timestamp, bubble label, optional
 * screenshot, then the ChatGPT response with markdown converted to inline HTML.
 */
internal object AiChatHistoryHtml {
    fun build(
        entries: List<AiChatEntry>,
        backgroundCssColor: String,
        textCssColor: String,
        mutedTextCssColor: String,
        dividerCssColor: String,
        codeBackgroundCssColor: String,
        selectionScript: String,
        maxSelectionLength: Int,
    ): String {
        // Newest entries first — mirrors the previous LazyColumn `entries.asReversed()`
        // ordering so the most recent ChatGPT exchange is at the top of the history.
        val body = entries.asReversed().joinToString("\n") { entry -> entryHtml(entry) }
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5, user-scalable=yes">
            <style>
            ${css(backgroundCssColor, textCssColor, mutedTextCssColor, dividerCssColor, codeBackgroundCssColor)}
            </style>
            </head>
            <body>
            $body
            <script>
            // The shared scanner reads this flag to decide whether tapping a non-CJK
            // span counts as a hit; ChatGPT replies are written in English but laced
            // with the original Japanese — we only want the Japanese spans to look up.
            window.scanNonJapaneseText = false;
            $selectionScript
            (function() {
              // A tap on the body forwards to the shared selection scanner. The scanner
              // looks up the tapped word and posts the result back via HoshiTextSelection;
              // taps that don't land on a Japanese word return null and we leave the
              // previously-shown popup alone (the user must tap empty space to dismiss).
              document.addEventListener('click', function(event) {
                if (!window.hoshiSelection) return;
                window.hoshiSelection.selectText(event.clientX, event.clientY, $maxSelectionLength);
              }, false);
            })();
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun css(
        background: String,
        text: String,
        muted: String,
        divider: String,
        codeBackground: String,
    ): String = """
        * { margin: 0; padding: 0; box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
        html, body { background: $background; color: $text; }
        body {
          font-family: -apple-system, BlinkMacSystemFont, system-ui, sans-serif;
          font-size: 15px;
          line-height: 1.45;
          padding: 16px 16px 80px 16px;
          -webkit-text-size-adjust: 100%;
        }
        .entry {
          background: $background;
          border-radius: 16px;
          padding: 16px;
          margin-bottom: 12px;
          border: 1px solid $divider;
        }
        .timestamp {
          font-size: 12px;
          color: $muted;
          margin-bottom: 6px;
        }
        .bubble-text {
          font-weight: 600;
          margin-bottom: 8px;
          word-break: break-word;
        }
        .screenshot {
          display: block;
          max-width: 100%;
          height: auto;
          border-radius: 8px;
          margin-bottom: 8px;
        }
        .divider {
          height: 1px;
          background: $divider;
          margin: 8px 0;
        }
        .response p { margin: 4px 0; }
        .response h1, .response h2, .response h3 {
          font-weight: 700;
          margin: 6px 0 2px 0;
          line-height: 1.25;
        }
        .response h1 { font-size: 18px; }
        .response h2 { font-size: 16px; }
        .response h3 { font-size: 15px; }
        .response ul, .response ol {
          margin: 4px 0 4px 22px;
        }
        .response li { margin: 2px 0; }
        .response code {
          background: $codeBackground;
          padding: 0 4px;
          border-radius: 4px;
          font-family: ui-monospace, Menlo, Consolas, monospace;
          font-size: 13px;
        }
        .response pre {
          background: $codeBackground;
          padding: 10px;
          border-radius: 8px;
          margin: 6px 0;
          overflow-x: auto;
        }
        .response pre code {
          background: transparent;
          padding: 0;
        }
        ::selection { background: rgba(70, 130, 220, 0.45); }
        ::highlight(hoshi-selection) { background: #ffd400; color: #000; }
    """.trimIndent()

    private fun entryHtml(entry: AiChatEntry): String {
        val timestamp = formatChatTimestamp(entry.timestampSeconds)
        val bubble = escapeHtml(entry.bubbleText)
        val screenshot = entry.screenshotImage?.let { image ->
            """<img class="screenshot" src="data:${image.mimeType};base64,${image.base64Data}" alt="">"""
        }.orEmpty()
        val response = renderMarkdownToHtml(entry.response)
        return """
            <div class="entry">
              <div class="timestamp">${escapeHtml(timestamp)}</div>
              <div class="bubble-text">$bubble</div>
              $screenshot
              <div class="divider"></div>
              <div class="response">$response</div>
            </div>
        """.trimIndent()
    }

    /**
     * Apple-reference-date seconds (the epoch the app's sidecar files use) to a local
     * date-time string. Apple's reference date is 2001-01-01 UTC, 978307200 s after the
     * Unix epoch — same conversion as the previous Compose history row.
     */
    private fun formatChatTimestamp(appleReferenceSeconds: Double): String =
        runCatching {
            Instant.ofEpochSecond((appleReferenceSeconds + 978_307_200.0).toLong())
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
        }.getOrDefault("")
}

private fun escapeHtml(value: String): String = buildString(value.length) {
    for (ch in value) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }
}

/**
 * Renders the same Markdown subset [MarkdownText] supports to inline HTML. ChatGPT
 * replies use ATX headings, bullet/numbered lists, fenced code, and the inline spans
 * `**bold**`, `*italic*`, `` `code` ``; anything else falls through as paragraph text.
 *
 * Splitting the parser per surface (Compose vs WebView) would mean keeping two
 * Markdown implementations in lockstep — instead we reuse [parseMarkdownBlocks] from
 * `MarkdownText.kt` and only emit different output here. The inline transform is its
 * own pass below since [MarkdownText]'s `buildInline` builds an AnnotatedString and
 * can't be reused directly.
 */
internal fun renderMarkdownToHtml(markdown: String): String {
    val blocks = parseMarkdownBlocks(markdown)
    val out = StringBuilder()
    var i = 0
    while (i < blocks.size) {
        val block = blocks[i]
        when (block) {
            MdBlock.Blank -> {
                /* CSS margins on adjacent blocks already provide spacing; explicit blanks
                   would otherwise stack with the margins and visibly double-space. */
            }
            is MdBlock.Heading -> {
                val level = block.level.coerceIn(1, 3)
                out.append("<h").append(level).append(">")
                    .append(renderInlineToHtml(block.text))
                    .append("</h").append(level).append(">\n")
            }
            is MdBlock.Paragraph -> {
                out.append("<p>").append(renderInlineToHtml(block.text)).append("</p>\n")
            }
            is MdBlock.Code -> {
                out.append("<pre><code>").append(escapeHtml(block.text)).append("</code></pre>\n")
            }
            is MdBlock.ListItem -> {
                // Group a run of list items at the same indent + same ordered flag
                // under a single <ul>/<ol> so the WebView renders proper list semantics
                // (bullet/number on the first line, hanging indent on wraps).
                val ordered = block.ordered
                val tag = if (ordered) "ol" else "ul"
                out.append("<").append(tag).append(">\n")
                while (i < blocks.size && blocks[i] is MdBlock.ListItem &&
                    (blocks[i] as MdBlock.ListItem).ordered == ordered
                ) {
                    val item = blocks[i] as MdBlock.ListItem
                    out.append("  <li>").append(renderInlineToHtml(item.text)).append("</li>\n")
                    i++
                }
                out.append("</").append(tag).append(">\n")
                continue
            }
        }
        i++
    }
    return out.toString()
}

private fun renderInlineToHtml(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = if (i + 2 < text.length && !text[i + 2].isWhitespace()) {
                    text.indexOf("**", i + 2)
                } else {
                    -1
                }
                if (end < 0 || text[end - 1].isWhitespace()) {
                    out.append("**"); i += 2
                } else {
                    out.append("<strong>")
                        .append(renderInlineToHtml(text.substring(i + 2, end)))
                        .append("</strong>")
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
                    out.append("*"); i += 1
                } else {
                    out.append("<em>")
                        .append(renderInlineToHtml(text.substring(i + 1, end)))
                        .append("</em>")
                    i = end + 1
                }
            }
            text.startsWith("`", i) -> {
                val end = text.indexOf("`", i + 1)
                if (end < 0) {
                    out.append("`"); i += 1
                } else {
                    out.append("<code>")
                        .append(escapeHtml(text.substring(i + 1, end)))
                        .append("</code>")
                    i = end + 1
                }
            }
            else -> {
                var next = text.length
                for (marker in listOf("**", "*", "`")) {
                    val idx = text.indexOf(marker, i)
                    if (idx in i until next) next = idx
                }
                out.append(escapeHtml(text.substring(i, next)))
                i = next
            }
        }
    }
    return out.toString()
}
