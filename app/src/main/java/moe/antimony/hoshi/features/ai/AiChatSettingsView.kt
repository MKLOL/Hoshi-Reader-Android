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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState(initial = null)

    SettingsDetailScaffold(title = "ChatGPT", onClose = onClose, modifier = modifier) { innerPadding ->
        val loaded = settings ?: return@SettingsDetailScaffold
        AiChatSettingsContent(
            settings = loaded,
            onUpdate = { transform -> scope.launch { repository.update(transform) } },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

@Composable
private fun AiChatSettingsContent(
    settings: AiChatSettings,
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
            onValueChange = { value ->
                apiKey = value
                onUpdate { it.copy(apiKey = value) }
            },
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
            onValueChange = { value ->
                model = value
                onUpdate { it.copy(model = value) }
            },
            label = { Text("Model") },
            singleLine = true,
            supportingText = { Text("The OpenAI model id, e.g. ${AiChatSettings.DEFAULT_MODEL}.") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = promptText,
            onValueChange = { value ->
                promptText = value
                onUpdate { it.copy(promptText = value) }
            },
            label = { Text("Bubble prompt") },
            supportingText = { Text("Sent before the speech bubble's OCR text.") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = imagePromptText,
            onValueChange = { value ->
                imagePromptText = value
                onUpdate { it.copy(imagePromptText = value) }
            },
            label = { Text("Image prompt") },
            supportingText = { Text("Sent with cropped screenshot translations.") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
