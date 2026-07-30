package moe.antimony.hoshi.features.ai

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
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
        /* Vocabulary/grammar tables from the tutor prompt. A wide table scrolls inside its own
           box rather than forcing the whole page sideways. */
        .response table {
          border-collapse: collapse;
          margin: 8px 0;
          display: block;
          overflow-x: auto;
          max-width: 100%;
        }
        .response th, .response td {
          border: 1px solid rgba(128, 128, 128, 0.45);
          padding: 6px 9px;
          text-align: left;
          vertical-align: top;
        }
        .response th { font-weight: 600; }
        /* commonmark emits node types the old renderer never produced; the global margin reset
           would otherwise leave them indistinguishable from body text. */
        .response h4, .response h5, .response h6 {
          font-size: 14px;
          font-weight: 600;
          margin: 10px 0 4px;
        }
        .response blockquote {
          margin: 6px 0;
          padding-left: 10px;
          border-left: 3px solid rgba(128, 128, 128, 0.45);
        }
        .response hr {
          border: 0;
          border-top: 1px solid rgba(128, 128, 128, 0.45);
          margin: 10px 0;
        }
        .response img { max-width: 100%; height: auto; }
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
 * Renders a ChatGPT reply's Markdown to HTML for the history WebView.
 *
 * commonmark-java does both the parsing and the HTML emission, with the GFM tables and
 * strikethrough extensions enabled so the tutor prompt's vocabulary tables render as real
 * `<table>` markup instead of literal pipe characters. [MarkdownText] parses with the same
 * parser configuration, so the Compose popup and this WebView cannot drift apart.
 */
internal fun renderMarkdownToHtml(markdown: String): String =
    // normalizeTables for the same reason the Compose popup applies it: commonmark will not start
    // a table when a lead-in line sits directly above the header row, which is how a tutor reply
    // is normally written. Both surfaces must normalise identically or they drift apart.
    HTML_RENDERER.render(MARKDOWN_PARSER.parse(normalizeTables(markdown)))

private val MARKDOWN_PARSER: Parser = Parser.builder()
    .extensions(MARKDOWN_EXTENSIONS)
    .build()

private val HTML_RENDERER: HtmlRenderer = HtmlRenderer.builder()
    .extensions(MARKDOWN_EXTENSIONS)
    // Replies are model output rendered in a local WebView that has JavaScript enabled and a
    // @JavascriptInterface bridge attached, so both halves matter:
    //   escapeHtml  - a raw <script> block in a reply stays text.
    //   sanitizeUrls - escaping does NOT cover Markdown-generated links, so without this a
    //                  `[x](javascript:...)` link would execute in-page when tapped, and an
    //                  `![](https://...)` image would make an outbound request on open.
    .escapeHtml(true)
    .sanitizeUrls(true)
    .build()
