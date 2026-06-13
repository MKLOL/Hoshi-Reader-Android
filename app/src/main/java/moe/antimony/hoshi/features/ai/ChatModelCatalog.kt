package moe.antimony.hoshi.features.ai

/**
 * The cloud LLM providers + curated models the manga translation feature can use. The user picks a
 * model from a dropdown; the provider (and its base URL, auth scheme, and request format) is
 * DERIVED from the chosen model id via this catalog. That keeps the synced settings unchanged —
 * only the model-id string syncs, exactly as before — while routing to OpenAI, Anthropic, and
 * cheaper OpenAI-compatible providers (DeepSeek, Qwen, Moonshot/Kimi).
 *
 * Mirrors the iOS `ChatModelCatalog`.
 */
enum class ChatWireFormat {
    /** OpenAI Chat Completions (`/chat/completions`, `Authorization: Bearer`). */
    OPEN_AI_COMPATIBLE,

    /** Anthropic Messages API (`/v1/messages`, `x-api-key` + `anthropic-version`). */
    ANTHROPIC,
}

/**
 * A cloud LLM provider: where to send requests, how to authenticate, and which per-provider
 * DataStore preference key holds its API key (per-device, never synced).
 */
data class ChatProvider(
    val id: String,
    val displayName: String,
    /** For OPEN_AI_COMPATIBLE, `/chat/completions` is appended; for ANTHROPIC, `/v1/messages`. */
    val baseUrl: String,
    val wireFormat: ChatWireFormat,
    /** DataStore string-preference name under which this provider's API key is stored. */
    val prefsKey: String,
    /** Where the user creates a key — shown in the settings UI. */
    val keysUrl: String,
)

/** One selectable model, tied to its provider. */
data class ChatModelOption(
    /** The wire model id sent to the provider (also persisted/synced as `AiChatSettings.model`). */
    val id: String,
    val displayName: String,
    val provider: ChatProvider,
    /** Short hint shown in the picker (e.g. "cheapest", "best"). */
    val note: String?,
)

object ChatModelCatalog {
    val openAI = ChatProvider(
        id = "openai",
        displayName = "OpenAI (ChatGPT)",
        baseUrl = "https://api.openai.com/v1",
        wireFormat = ChatWireFormat.OPEN_AI_COMPATIBLE,
        // Reuse the long-standing "apiKey" DataStore slot so existing users keep their key.
        prefsKey = "apiKey",
        keysUrl = "https://platform.openai.com/api-keys",
    )

    val anthropic = ChatProvider(
        id = "anthropic",
        displayName = "Anthropic (Claude)",
        baseUrl = "https://api.anthropic.com",
        wireFormat = ChatWireFormat.ANTHROPIC,
        prefsKey = "apiKey.anthropic",
        keysUrl = "https://console.anthropic.com/settings/keys",
    )

    val google = ChatProvider(
        id = "google",
        displayName = "Google (Gemini)",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        wireFormat = ChatWireFormat.OPEN_AI_COMPATIBLE,
        prefsKey = "apiKey.google",
        keysUrl = "https://aistudio.google.com/apikey",
    )

    val deepSeek = ChatProvider(
        id = "deepseek",
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        wireFormat = ChatWireFormat.OPEN_AI_COMPATIBLE,
        prefsKey = "apiKey.deepseek",
        keysUrl = "https://platform.deepseek.com/api_keys",
    )

    val qwen = ChatProvider(
        id = "qwen",
        displayName = "Qwen (Alibaba)",
        baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
        wireFormat = ChatWireFormat.OPEN_AI_COMPATIBLE,
        prefsKey = "apiKey.qwen",
        keysUrl = "https://bailian.console.alibabacloud.com/",
    )

    val moonshot = ChatProvider(
        id = "moonshot",
        displayName = "Moonshot (Kimi)",
        baseUrl = "https://api.moonshot.ai/v1",
        wireFormat = ChatWireFormat.OPEN_AI_COMPATIBLE,
        prefsKey = "apiKey.moonshot",
        keysUrl = "https://platform.moonshot.ai/console/api-keys",
    )

    val providers: List<ChatProvider> = listOf(openAI, anthropic, google, deepSeek, qwen, moonshot)

    // Curated, current models per provider. Each id is unique across providers, so the provider can
    // be recovered from the id alone. A "Custom" option in settings still accepts any model id.
    val models: List<ChatModelOption> = listOf(
        ChatModelOption("gpt-5.5", "GPT-5.5", openAI, "default"),
        ChatModelOption("gpt-5", "GPT-5", openAI, null),
        ChatModelOption("gpt-4o", "GPT-4o", openAI, null),
        ChatModelOption("gpt-4o-mini", "GPT-4o mini", openAI, "cheap"),

        ChatModelOption("claude-opus-4-8", "Claude Opus 4.8", anthropic, "best"),
        ChatModelOption("claude-sonnet-4-6", "Claude Sonnet 4.6", anthropic, null),
        ChatModelOption("claude-haiku-4-5", "Claude Haiku 4.5", anthropic, "cheap"),

        // Google Gemini (Flash models are fast + cheap, strong at JP→EN).
        ChatModelOption("gemini-2.5-pro", "Gemini 2.5 Pro", google, "best"),
        ChatModelOption("gemini-2.5-flash", "Gemini 2.5 Flash", google, null),
        ChatModelOption("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite", google, "cheap"),
        ChatModelOption("gemini-2.0-flash", "Gemini 2.0 Flash", google, null),

        ChatModelOption("deepseek-chat", "DeepSeek V3", deepSeek, "cheapest"),
        ChatModelOption("deepseek-reasoner", "DeepSeek R1", deepSeek, "reasoning"),

        ChatModelOption("qwen-plus", "Qwen Plus", qwen, null),
        ChatModelOption("qwen-turbo", "Qwen Turbo", qwen, "cheap"),
        ChatModelOption("qwen-max", "Qwen Max", qwen, null),

        ChatModelOption("moonshot-v1-8k", "Kimi (moonshot-v1-8k)", moonshot, null),
        ChatModelOption("moonshot-v1-32k", "Kimi (moonshot-v1-32k)", moonshot, null),
    )

    /** The provider serving [modelId]. Unknown / custom ids fall back to OpenAI (original behaviour). */
    fun providerForModelId(modelId: String): ChatProvider =
        models.firstOrNull { it.id == modelId.trim() }?.provider ?: openAI

    fun optionForModelId(modelId: String): ChatModelOption? =
        models.firstOrNull { it.id == modelId.trim() }

    fun isKnownModel(modelId: String): Boolean = optionForModelId(modelId) != null
}

/** Routes a translation request to the right client for a provider's wire format. */
object CloudChat {
    suspend fun complete(
        provider: ChatProvider,
        apiKey: String,
        model: String,
        prompt: String,
        bubbleText: String,
    ): String = when (provider.wireFormat) {
        ChatWireFormat.ANTHROPIC ->
            AnthropicChatClient.complete(apiKey, model, prompt, bubbleText)
        ChatWireFormat.OPEN_AI_COMPATIBLE ->
            OpenAiChatClient.complete(apiKey, model, prompt, bubbleText, baseUrl = provider.baseUrl)
    }

    suspend fun completeImage(
        provider: ChatProvider,
        apiKey: String,
        model: String,
        prompt: String,
        imageBase64: String,
        imageMimeType: String,
    ): String = when (provider.wireFormat) {
        ChatWireFormat.ANTHROPIC ->
            AnthropicChatClient.completeImage(apiKey, model, prompt, imageBase64, imageMimeType)
        ChatWireFormat.OPEN_AI_COMPATIBLE ->
            OpenAiChatClient.completeImage(
                apiKey, model, prompt, imageBase64, imageMimeType, baseUrl = provider.baseUrl,
            )
    }
}
