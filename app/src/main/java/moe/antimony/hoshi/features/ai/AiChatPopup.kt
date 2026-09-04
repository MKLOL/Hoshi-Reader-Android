package moe.antimony.hoshi.features.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.ai.offline.OfflineLlmManager
import moe.antimony.hoshi.features.dictionary.LookupPopupAndroidStack
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupOptions
import moe.antimony.hoshi.features.dictionary.createLookupPopupItem
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.rememberStableNavigationBarPadding
import moe.antimony.hoshi.features.reader.rememberStableStatusBarPadding

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
        val pretranslated: Boolean = false,
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
 * Fraction of the screen the card takes, matching iOS `MangaAiPopupView`
 * (`geometry.size.height * 0.78`). A vocabulary breakdown is the whole point of the reply, so
 * the card gets most of the page rather than a 380dp letterbox the user has to scroll.
 */
private const val AI_CHAT_CARD_HEIGHT_FRACTION = 0.78f

/** iOS `targetHeight` floor for the phone idiom: a short screen still gets a usable card. */
private val AI_CHAT_CARD_MIN_HEIGHT = 320.dp

/** iOS `maxCardWidth` for the phone idiom. */
private val AI_CHAT_CARD_MAX_WIDTH = 520.dp

/** iOS `sideMargin` for the phone idiom. */
private val AI_CHAT_CARD_MARGIN = 16.dp

/**
 * The ChatGPT response popup, shown above the manga page. Tapping outside the card or the
 * close button dismisses it; a failed request offers a retry.
 *
 * The title bar is pinned and only the reply scrolls, so the close button is always reachable
 * however long the breakdown is — same split as iOS (`header` + `ScrollView`).
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
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // iOS sizes the card once, from the screen: `min(availableHeight, max(320, height *
        // 0.78))`, where `availableHeight` is the screen minus the safe areas and the card's own
        // margins. One fixed height means the header and the reply stay where they are instead of
        // the card resizing itself around every reply.
        val topInset = rememberStableStatusBarPadding()
        val bottomInset = rememberStableNavigationBarPadding()
        // The stable insets, not the live ones: the reader hides and shows the system bars, and
        // the card must not jump when it does.
        val availableHeight =
            (maxHeight - topInset - bottomInset - AI_CHAT_CARD_MARGIN * 2).coerceAtLeast(1.dp)
        val targetHeight =
            (maxHeight * AI_CHAT_CARD_HEIGHT_FRACTION).coerceAtLeast(AI_CHAT_CARD_MIN_HEIGHT)
        val cardHeight = minOf(availableHeight, targetHeight)
        Surface(
            // Swallow taps on the card so they do not fall through to the dismiss layer.
            modifier = Modifier
                .padding(top = topInset, bottom = bottomInset)
                .padding(AI_CHAT_CARD_MARGIN)
                .widthIn(max = AI_CHAT_CARD_MAX_WIDTH)
                .fillMaxWidth()
                .height(cardHeight)
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
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Text(
                        text = when {
                            state is AiChatUiState.Loaded && state.pretranslated ->
                                stringResource(R.string.ai_chat_backend_pretranslated)
                            state.onDevice -> stringResource(R.string.ai_chat_backend_on_device)
                            else -> stringResource(R.string.ai_chat_backend_chatgpt)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.action_close),
                        )
                    }
                }
                // iOS puts a `Divider()` between the pinned header and the scrolling body, so the
                // reply visibly scrolls under the title rather than past a floating close button.
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier
                        // The card height is fixed, so the reply owns whatever is left below the
                        // header and scrolls inside it.
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                ) {
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
}

@Composable
private fun LoadingBody(onDevice: Boolean) {
    val progress by OfflineLlmManager.generationProgress.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(12.dp))
        Column {
            Text(
                text = stringResource(
                    if (onDevice) {
                        R.string.ai_chat_status_translating_on_device
                    } else {
                        R.string.ai_chat_status_asking_chatgpt
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Live tokens/sec while the on-device model generates, so a slow reply shows progress.
            val live = progress
            if (onDevice && live != null && live.tokens > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.ai_chat_generation_speed_format,
                        live.tokens,
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
            // The card body owns the scrolling; the reply just lays out at full width so a
            // breakdown table can size its columns to the card.
            MarkdownText(markdown = response, modifier = Modifier.fillMaxWidth())
        }
        if (onAskLive != null) {
            Spacer(Modifier.size(8.dp))
            // A pre-translated reply is instant and free, so it is what a tap shows. Offer the
            // live model for the times it isn't enough.
            OutlinedButton(onClick = onAskLive) {
                Text(stringResource(R.string.ai_chat_ask_live_instead))
            }
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
            Text(
                stringResource(
                    if (isMissingKey) R.string.action_dismiss else R.string.action_retry,
                ),
            )
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
        title = stringResource(R.string.ai_chat_history_title),
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
                    text = stringResource(R.string.ai_chat_history_empty),
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
