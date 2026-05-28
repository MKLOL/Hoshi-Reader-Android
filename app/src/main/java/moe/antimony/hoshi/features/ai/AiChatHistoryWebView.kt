package moe.antimony.hoshi.features.ai

import android.graphics.Color as AndroidColor
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import moe.antimony.hoshi.features.reader.ReaderSelectionBridge
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionScripts
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults

/**
 * Renders the ChatGPT history inside a WebView so tapping a Japanese word looks the
 * word up in the dictionary — exactly the same selection mechanism the EPUB and
 * manga readers use (shared [ReaderSelectionScripts] plus the `HoshiTextSelection`
 * JavaScript interface).
 *
 * Each call rebuilds the HTML from [entries], so when a chat is appended elsewhere
 * and the caller passes a new list, the WebView reloads with the new content. The
 * caller is responsible for showing the lookup popup over this view via the existing
 * `LookupPopupAndroidStack`; this component only emits selections via [onTextSelected].
 */
@Composable
internal fun AiChatHistoryWebView(
    entries: List<AiChatEntry>,
    backgroundColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariantColor: Color,
    outlineVariantColor: Color,
    surfaceVariantColor: Color,
    onTextSelected: (ReaderSelectionData, WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnTextSelected = rememberUpdatedState(onTextSelected)
    val selectionScript = remember { ReaderSelectionScripts.source() }
    val html = remember(
        entries,
        backgroundColor,
        onSurfaceColor,
        onSurfaceVariantColor,
        outlineVariantColor,
        surfaceVariantColor,
        selectionScript,
    ) {
        AiChatHistoryHtml.build(
            entries = entries,
            backgroundCssColor = backgroundColor.toCssHex(),
            textCssColor = onSurfaceColor.toCssHex(),
            mutedTextCssColor = onSurfaceVariantColor.toCssHex(),
            dividerCssColor = outlineVariantColor.toCssHex(),
            codeBackgroundCssColor = surfaceVariantColor.toCssHex(),
            selectionScript = selectionScript,
            maxSelectionLength = HISTORY_MAX_SELECTION_LENGTH,
        )
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                applyHoshiWebViewSecurityDefaults()
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(AndroidColor.TRANSPARENT)
                // Words are sized to the document's CSS — the user's system text-zoom
                // shouldn't fight the layout, and CJK glyphs at small sizes need the
                // minimumFontSize floor dropped (same reason the manga reader does it).
                settings.textZoom = 100
                settings.minimumFontSize = 1
                settings.minimumLogicalFontSize = 1
                addJavascriptInterface(
                    // The shared selection bridge fires when the tapped point lands on
                    // a CJK word the scanner can identify; non-word taps are dropped on
                    // the JS side and never reach this callback. The webView reference
                    // goes back to the caller so it can drive the in-page CSS Custom
                    // Highlight (mark the matched characters yellow) — same mechanism
                    // the manga reader uses.
                    ReaderSelectionBridge(this) { selection, _ ->
                        currentOnTextSelected.value(selection, this)
                    },
                    "HoshiTextSelection",
                )
            }
        },
        onRelease = { webView ->
            // Hold a JavaScript interface and the Activity context; destroy here so
            // GC can reclaim it instead of leaking until the next process kill.
            webView.destroy()
        },
        update = { webView ->
            // Re-key the WebView on the HTML content so appending a chat entry from
            // elsewhere (rare while history is open, but possible) reloads naturally.
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(
                    BASE_URL,
                    html,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
    )
}

private const val BASE_URL = "https://hoshi.local/ai-chat-history/"
private const val HISTORY_MAX_SELECTION_LENGTH = 24

internal fun Color.toCssHex(): String {
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    return "#%02x%02x%02x".format(r, g, b)
}
