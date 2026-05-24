package moe.antimony.hoshi.features.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold

/**
 * Settings for the manga ChatGPT features: OpenAI API key, model, speech-bubble prompt, and
 * screenshot prompt.
 *
 * This is a fork addition. It is reachable from the main Settings tab (the ChatGPT row, see
 * [moe.antimony.hoshi.navigation.SettingsDetailSection.ChatGpt]) so the key and prompts can be
 * edited without opening a manga. The values persist app-wide through [AiChatSettingsRepository].
 */
@Composable
fun AiChatSettingsScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember { context.applicationContext.aiChatSettingsRepository() }
    // App-wide scope (not rememberCoroutineScope) so the debounced write + dispose-time
    // flush survive the screen leaving composition. A rapid Back press inside the debounce
    // window otherwise drops the user's last edit, e.g. silently losing an API key.
    val appScope = LocalHoshiAppContainer.current.appScope
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = null)

    SettingsDetailScaffold(title = "ChatGPT", onClose = onClose, modifier = modifier) { innerPadding ->
        val loaded = settings ?: return@SettingsDetailScaffold
        AiChatSettingsContent(
            settings = loaded,
            writeScope = appScope,
            onUpdate = { transform -> appScope.launch { repository.update(transform) } },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun AiChatSettingsContent(
    settings: AiChatSettings,
    writeScope: CoroutineScope,
    onUpdate: ((AiChatSettings) -> AiChatSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Seeded once from the first loaded settings; this screen is the only editor of these
    // values, so the local state never needs to re-sync after a save.
    var apiKey by rememberSaveable { mutableStateOf(settings.apiKey) }
    var model by rememberSaveable { mutableStateOf(settings.model) }
    var promptText by rememberSaveable { mutableStateOf(settings.promptText) }
    var imagePromptText by rememberSaveable { mutableStateOf(settings.imagePromptText) }
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }

    // Track what's actually been written so the dispose-flush can detect pending edits.
    val seed = remember {
        EditedAiChatFields(settings.apiKey, settings.model, settings.promptText, settings.imagePromptText)
    }
    var lastFlushed by remember { mutableStateOf(seed) }

    // Debounce DataStore writes: writing on every keystroke fires one disk write and one
    // HTTP-sync push (via lastEditedAt) per character. Collect the four edited fields, wait
    // ~400 ms of quiet, then commit once. drop(1) skips the seed emission so the just-loaded
    // value isn't re-written; distinctUntilChanged elides no-ops if the user types-then-undoes.
    LaunchedEffect(Unit) {
        snapshotFlow { EditedAiChatFields(apiKey, model, promptText, imagePromptText) }
            .drop(1)
            .debounce(400)
            .distinctUntilChanged()
            .collect { fields ->
                onUpdate {
                    it.copy(
                        apiKey = fields.apiKey,
                        model = fields.model,
                        promptText = fields.promptText,
                        imagePromptText = fields.imagePromptText,
                    )
                }
                lastFlushed = fields
            }
    }

    // Flush on dispose so a rapid Back press inside the 400 ms debounce window doesn't drop
    // the last edit. rememberUpdatedState captures the latest values at dispose time; the
    // write is launched on the app-wide [writeScope] so it survives the screen's coroutine
    // scope being cancelled.
    val currentFields by rememberUpdatedState(EditedAiChatFields(apiKey, model, promptText, imagePromptText))
    val latestFlushed by rememberUpdatedState(lastFlushed)
    DisposableEffect(Unit) {
        onDispose {
            val current = currentFields
            if (current != latestFlushed) {
                writeScope.launch {
                    onUpdate {
                        it.copy(
                            apiKey = current.apiKey,
                            model = current.model,
                            promptText = current.promptText,
                            imagePromptText = current.imagePromptText,
                        )
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Powers manga ChatGPT actions. Speech bubbles send OCR text; screenshot " +
                "translation sends the cropped image.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { value -> apiKey = value },
            label = { Text("OpenAI API key") },
            singleLine = true,
            visualTransformation = if (apiKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        imageVector = if (apiKeyVisible) {
                            Icons.Rounded.VisibilityOff
                        } else {
                            Icons.Rounded.Visibility
                        },
                        contentDescription = if (apiKeyVisible) "Hide API key" else "Show API key",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { value -> model = value },
            label = { Text("Model") },
            singleLine = true,
            supportingText = { Text("The OpenAI model id, e.g. ${AiChatSettings.DEFAULT_MODEL}.") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = promptText,
            onValueChange = { value -> promptText = value },
            label = { Text("Bubble prompt") },
            supportingText = { Text("Sent before the speech bubble's OCR text.") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = imagePromptText,
            onValueChange = { value -> imagePromptText = value },
            label = { Text("Image prompt") },
            supportingText = { Text("Sent with cropped screenshot translations.") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private data class EditedAiChatFields(
    val apiKey: String,
    val model: String,
    val promptText: String,
    val imagePromptText: String,
)
