package moe.antimony.hoshi.features.ai

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** UI state for the manga ChatGPT popup. Null (in the caller) means no popup is shown. */
sealed interface AiChatUiState {
    /** The speech-bubble OCR text the popup is about. */
    val bubbleText: String

    data class Loading(override val bubbleText: String) : AiChatUiState

    data class Loaded(val entry: AiChatEntry) : AiChatUiState {
        override val bubbleText: String get() = entry.bubbleText
    }

    data class Failed(
        override val bubbleText: String,
        val message: String,
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
                        text = "ChatGPT",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
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
                    is AiChatUiState.Loading -> LoadingBody()
                    is AiChatUiState.Loaded -> ResponseBody(state.entry.response)
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
private fun LoadingBody() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(12.dp))
        Text(
            text = "Asking ChatGPT…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResponseBody(response: String) {
    SelectionContainer {
        MarkdownText(
            markdown = response,
            modifier = Modifier
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun FailedBody(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    // The "missing API key" failure (raised in OpenAiChatClient.complete) is shaped as
    // "Set your OpenAI API key in Settings → AI." — retrying will fail the same way,
    // so swap the action to a dismiss that guides the user to Settings instead of
    // banging the same failed request again.
    val isMissingKey = message.startsWith("Set your OpenAI API key", ignoreCase = true)
    Column {
        Text(
            text = if (isMissingKey) {
                "$message\n\nOpen Settings → ChatGPT to add your API key."
            } else {
                message
            },
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
 * The per-manga ChatGPT history list, reached from the manga reader's overflow (⋯) menu.
 * Newest exchange first; each row shows the bubble text or screenshot, plus the model's reply.
 */
@Composable
fun AiChatHistoryView(
    entries: List<AiChatEntry>,
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(entries.asReversed()) { entry ->
                    AiChatHistoryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun AiChatHistoryRow(entry: AiChatEntry) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = formatChatTimestamp(entry.timestampSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = entry.bubbleText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            entry.screenshotImage?.let { image ->
                Spacer(Modifier.size(8.dp))
                AiChatHistoryScreenshot(image)
            }
            Spacer(Modifier.size(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.size(8.dp))
            SelectionContainer {
                MarkdownText(markdown = entry.response)
            }
        }
    }
}

@Composable
private fun AiChatHistoryScreenshot(image: AiChatImage) {
    val bitmap = remember(image.base64Data) {
        decodeAiChatImage(image.base64Data)
    } ?: return
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Screenshot sent to ChatGPT",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp),
        )
    }
}

private fun decodeAiChatImage(base64Data: String) = runCatching {
    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

/**
 * Apple-reference-date seconds (the epoch the app's sidecar files use) to a local date-time
 * string. Apple's reference date is 2001-01-01 UTC, 978307200 s after the Unix epoch.
 */
private fun formatChatTimestamp(appleReferenceSeconds: Double): String =
    runCatching {
        Instant.ofEpochSecond((appleReferenceSeconds + 978_307_200.0).toLong())
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }.getOrDefault("")
