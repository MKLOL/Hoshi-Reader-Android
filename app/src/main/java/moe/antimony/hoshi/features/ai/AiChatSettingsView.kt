package moe.antimony.hoshi.features.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import moe.antimony.hoshi.features.ai.offline.OfflineTranslationSection
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold

/**
 * Settings for the manga translation feature: pick a model from a dropdown spanning multiple
 * providers (OpenAI, Anthropic, and cheaper / Chinese OpenAI-compatible providers — DeepSeek, Qwen,
 * Moonshot/Kimi), or enter a custom id. The API key is stored per provider (never synced). Only the
 * model-id string syncs (the provider is derived from it), so the sync wire shape is unchanged from
 * the original free-text model field.
 */
@Composable
fun AiChatSettingsScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember { context.applicationContext.aiChatSettingsRepository() }
    val appScope = LocalHoshiAppContainer.current.appScope
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = null)

    SettingsDetailScaffold(title = "Translation model", onClose = onClose, modifier = modifier) { innerPadding ->
        val loaded = settings ?: return@SettingsDetailScaffold
        AiChatSettingsContent(
            settings = loaded,
            repository = repository,
            writeScope = appScope,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

private const val CUSTOM_MODEL_TAG = "__custom__"

@OptIn(FlowPreview::class)
@Composable
private fun AiChatSettingsContent(
    settings: AiChatSettings,
    repository: AiChatSettingsRepository,
    writeScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    // Picker selection: a known model id, or CUSTOM_MODEL_TAG when entering a custom id.
    var selectedModelId by rememberSaveable {
        mutableStateOf(
            if (ChatModelCatalog.isKnownModel(settings.model)) settings.model else CUSTOM_MODEL_TAG,
        )
    }
    var customModel by rememberSaveable {
        mutableStateOf(if (ChatModelCatalog.isKnownModel(settings.model)) "" else settings.model)
    }
    var apiKey by rememberSaveable { mutableStateOf(settings.apiKey) }
    var promptText by rememberSaveable { mutableStateOf(settings.promptText) }
    var imagePromptText by rememberSaveable { mutableStateOf(settings.imagePromptText) }
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    val effectiveModel = if (selectedModelId == CUSTOM_MODEL_TAG) customModel else selectedModelId
    val provider = ChatModelCatalog.providerForModelId(effectiveModel)

    // Reload the key field whenever the provider changes (model switched to a different provider),
    // so each provider shows its own stored key. Does NOT run on every keystroke — only when the
    // provider id changes — so an in-progress key edit isn't clobbered.
    LaunchedEffect(provider.id) {
        apiKey = repository.apiKey(provider)
    }

    // Debounce the SYNCED fields (model + prompts). API keys are written separately (below) so a
    // per-keystroke key edit never triggers a sync push.
    val syncedSeed = remember {
        EditedSyncedFields(settings.model, settings.promptText, settings.imagePromptText)
    }
    var lastFlushedSynced by remember { mutableStateOf(syncedSeed) }
    LaunchedEffect(Unit) {
        snapshotFlow { EditedSyncedFields(effectiveModel, promptText, imagePromptText) }
            .drop(1)
            .debounce(400)
            .distinctUntilChanged()
            .collect { fields ->
                if (fields.model.isBlank()) return@collect // don't persist an empty custom id
                writeScope.launch {
                    repository.update {
                        it.copy(
                            model = fields.model,
                            promptText = fields.promptText,
                            imagePromptText = fields.imagePromptText,
                        )
                    }
                }
                lastFlushedSynced = fields
            }
    }
    val currentSynced by rememberUpdatedState(EditedSyncedFields(effectiveModel, promptText, imagePromptText))
    val latestFlushedSynced by rememberUpdatedState(lastFlushedSynced)
    DisposableEffect(Unit) {
        onDispose {
            val current = currentSynced
            if (current != latestFlushedSynced && current.model.isNotBlank()) {
                writeScope.launch {
                    repository.update {
                        it.copy(
                            model = current.model,
                            promptText = current.promptText,
                            imagePromptText = current.imagePromptText,
                        )
                    }
                }
            }
        }
    }

    // Debounce the per-provider API key. Writes to the provider in effect at emit time; the
    // reload-on-provider-change above means a model switch never carries the old provider's key
    // into the new provider's slot.
    val providerState by rememberUpdatedState(provider)
    LaunchedEffect(Unit) {
        snapshotFlow { apiKey }
            .drop(1)
            .debounce(400)
            .distinctUntilChanged()
            .collect { key ->
                val p = providerState
                writeScope.launch { repository.setApiKey(p, key) }
            }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Pick a model. Cheaper and Chinese providers (DeepSeek, Qwen, Kimi) and Anthropic " +
                "(Claude) are included alongside OpenAI.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Model picker
        Box {
            OutlinedButton(
                onClick = { modelMenuExpanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = currentModelLabel(selectedModelId, customModel),
                    modifier = Modifier.fillMaxWidth(),
                )
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false },
            ) {
                ChatModelCatalog.providers.forEach { p ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                p.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                    ChatModelCatalog.models.filter { it.provider == p }.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(modelOptionLabel(m)) },
                            onClick = {
                                modelMenuExpanded = false
                                selectedModelId = m.id
                            },
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text("Custom…") },
                    onClick = {
                        modelMenuExpanded = false
                        selectedModelId = CUSTOM_MODEL_TAG
                    },
                )
            }
        }

        if (selectedModelId == CUSTOM_MODEL_TAG) {
            OutlinedTextField(
                value = customModel,
                onValueChange = { customModel = it },
                label = { Text("Custom model id") },
                singleLine = true,
                supportingText = { Text("A custom id is treated as an OpenAI model.") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = apiKey,
            onValueChange = { value -> apiKey = value },
            label = { Text("${provider.displayName} API key") },
            singleLine = true,
            supportingText = {
                Text("Stored only on this device, never synced. Each provider keeps its own key. " +
                    "Get a key at ${provider.keysUrl}")
            },
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
        HorizontalDivider()
        OfflineTranslationSection()
    }
}

private fun currentModelLabel(selectedModelId: String, customModel: String): String =
    if (selectedModelId == CUSTOM_MODEL_TAG) {
        if (customModel.isBlank()) "Custom…" else customModel
    } else {
        ChatModelCatalog.optionForModelId(selectedModelId)?.displayName ?: selectedModelId
    }

private fun modelOptionLabel(option: ChatModelOption): String =
    option.note?.let { "${option.displayName} · $it" } ?: option.displayName

private data class EditedSyncedFields(
    val model: String,
    val promptText: String,
    val imagePromptText: String,
)
