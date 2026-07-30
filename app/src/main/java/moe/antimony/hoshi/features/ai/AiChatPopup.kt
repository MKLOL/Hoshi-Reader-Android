package moe.antimony.hoshi.features.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.features.ai.offline.OfflineLlmManager
import moe.antimony.hoshi.features.dictionary.LookupPopupAndroidStack
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupOptions
import moe.antimony.hoshi.features.dictionary.createLookupPopupItem
import moe.antimony.hoshi.features.reader.ReaderSelectionData

/** UI state for the manga ChatGPT popup. Null (in the caller) means no popup is shown. */
sealed interface AiChatUiState {
    /** The speech-bubble OCR text the popup is about. */
    val bubbleText: String

    /** True when the active engine is the on-device LLM rather than the ChatGPT API. */
    val onDevice: Boolean

    data class Loading(
        override val bubbleText: String,
        override val onDevice: Boolean = false,
    ) : AiChatUiState

    data class Loaded(
        val entry: AiChatEntry,
        override val onDevice: Boolean = false,
    ) : AiChatUiState {
        override val bubbleText: String get() = entry.bubbleText
    }

    data class Failed(
        override val bubbleText: String,
        val message: String,
        override val onDevice: Boolean = false,
    ) : AiChatUiState
}

/**
 * The ChatGPT response popup, shown above the manga page. Tapping outside the card or the
 * close button dismisses it; a failed request offers a retry.
 *
 * Caller is expected to place this in a full-size [Box] with a high `zIndex` so it sits over
 * the page and the dictionary lookup popups.
 */
@Composable
fun AiChatPopupView(
    state: AiChatUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    /** Non-null when the reply came from the pre-translation cache. */
    onAskLive: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            // Swallow taps on the card so they do not fall through to the dismiss layer.
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (state.onDevice) "On-device translation" else "ChatGPT",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    text = state.bubbleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
                Spacer(Modifier.size(12.dp))
                when (state) {
                    is AiChatUiState.Loading -> LoadingBody(onDevice = state.onDevice)
                    is AiChatUiState.Loaded -> ResponseBody(
                        response = state.entry.response,
                        debugInfo = state.entry.debugInfo,
                        onAskLive = onAskLive,
                    )
                    is AiChatUiState.Failed -> FailedBody(
                        message = state.message,
                        onRetry = onRetry,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingBody(onDevice: Boolean) {
    val progress by OfflineLlmManager.generationProgress.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(12.dp))
        Column {
            Text(
                text = if (onDevice) "Translating on-device…" else "Asking ChatGPT…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Live tokens/sec while the on-device model generates, so a slow reply shows progress.
            val live = progress
            if (onDevice && live != null && live.tokens > 0) {
                Text(
                    text = "⚡ %.1f tok/s · %d tokens".format(
                        java.util.Locale.US,
                        live.tokensPerSecond,
                        live.tokens,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ResponseBody(response: String, debugInfo: String?, onAskLive: (() -> Unit)? = null) {
    Column {
        SelectionContainer {
            MarkdownText(
                markdown = response,
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        if (onAskLive != null) {
            Spacer(Modifier.size(8.dp))
            // A pre-translated reply is instant and free, so it is what a tap shows. Offer the
            // live model for the times it isn't enough.
            OutlinedButton(onClick = onAskLive) { Text("Ask ChatGPT instead") }
        }
        if (debugInfo != null) {
            Spacer(Modifier.size(10.dp))
            Text(
                text = debugInfo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FailedBody(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    // The "missing API key" failure is shaped "Set your OpenAI API key first in Settings →
    // ChatGPT." — that message already points the user at Settings, so we don't append a
    // second hint. Retrying would fail the same way, so swap the action button to Dismiss.
    val isMissingKey = message.startsWith("Set your OpenAI API key", ignoreCase = true)
    Column {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.size(8.dp))
        TextButton(
            onClick = if (isMissingKey) onDismiss else onRetry,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(if (isMissingKey) "Dismiss" else "Retry")
        }
    }
}

/**
 * The per-manga ChatGPT history, reached from the manga reader's overflow (⋯) menu.
 *
 * Renders inside a WebView so tapping a Japanese word looks the word up in the
 * dictionary — the same selection-script + `HoshiTextSelection` bridge mechanism the
 * EPUB and manga readers use, then the existing [LookupPopupAndroidStack] shows the
 * popup overlay on top. [lookupOptions] carries the colour scheme, dictionary
 * settings, popup geometry, and the book title used as the lookup-popup label, so
 * the caller can re-use whatever options it already builds for the in-reader popup
 * stack.
 */
@Composable
internal fun AiChatHistoryView(
    entries: List<AiChatEntry>,
    lookupOptions: LookupPopupOptions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    moe.antimony.hoshi.features.settings.SettingsDetailScaffold(
        title = "ChatGPT history",
        onClose = onClose,
        modifier = modifier,
    ) { innerPadding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No ChatGPT chats for this manga yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AiChatHistoryWebViewWithLookup(
                entries = entries,
                lookupOptions = lookupOptions,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

@Composable
private fun AiChatHistoryWebViewWithLookup(
    entries: List<AiChatEntry>,
    lookupOptions: LookupPopupOptions,
    modifier: Modifier = Modifier,
) {
    var popups by remember { mutableStateOf<List<LookupPopupItem>>(emptyList()) }
    var historyWebView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    // Looks the tapped word up in the dictionary using the same engine as the manga
    // reader. Returns the popup + highlight-count pair the popup stack expects.
    fun lookupForSelection(selection: ReaderSelectionData): Pair<LookupPopupItem, Int>? =
        createLookupPopupItem(selection = selection, options = lookupOptions)
    Box(modifier = modifier) {
        AiChatHistoryWebView(
            entries = entries,
            backgroundColor = MaterialTheme.colorScheme.background,
            onSurfaceColor = MaterialTheme.colorScheme.onSurface,
            onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant,
            outlineVariantColor = MaterialTheme.colorScheme.outlineVariant,
            surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant,
            onTextSelected = { selection, webView ->
                historyWebView = webView
                val lookup = lookupForSelection(selection) ?: return@AiChatHistoryWebView
                val (popup, highlightCount) = lookup
                popups = listOf(popup)
                // Paint the matched character range yellow via CSS.highlights so the
                // reader can see which span the dictionary popup is showing — same
                // mechanism the manga reader uses, same `::highlight(hoshi-selection)`
                // CSS rule baked into the history HTML.
                webView.evaluateJavascript(
                    moe.antimony.hoshi.features.reader.ReaderSelectionCommand
                        .HighlightSelection(highlightCount).source,
                    null,
                )
            },
            modifier = Modifier.fillMaxSize(),
        )
        LookupPopupAndroidStack(
            popups = popups,
            onPopupsChange = { popups = it },
            lookupChildPopup = ::lookupForSelection,
            onRootPopupDismissed = {
                // The root popup is going away — clear the in-page highlight too so
                // the user isn't left with a yellow word selected after the popup is
                // dismissed. Return false so the stack still removes the popup itself.
                historyWebView?.evaluateJavascript(
                    moe.antimony.hoshi.features.reader.ReaderSelectionCommand
                        .ClearSelection.source,
                    null,
                )
                false
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

