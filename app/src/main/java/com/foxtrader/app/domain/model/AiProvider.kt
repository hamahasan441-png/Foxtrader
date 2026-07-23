package com.foxtrader.app.domain.model

/**
 * Configuration for an external AI language-model provider.
 *
 * FoxTrader's built-in AI layer is fully offline and deterministic —
 * it runs the multi-agent confluence engine locally without any network
 * dependency. This model defines the **extension seam** for connecting an
 * optional external LLM provider (e.g. OpenAI, Claude, Gemini, Mistral, or a
 * self-hosted instance) to power features like the AI Mentor, natural-language
 * trade explanations, and strategy generation.
 *
 * Graceful degradation: if [isConfigured] returns false, all LLM-backed
 * features should fall back to the built-in offline analysis pipeline.
 */
data class AiProviderConfig(
    /** Which external provider to use, or [AiProviderType.NONE] for offline-only. */
    val providerType: AiProviderType = AiProviderType.NONE,
    /**
     * API key for the external provider.
     * NEVER stored in plain text or logs — always read from
     * [com.foxtrader.app.domain.usecase.preferences.AppPreferences] which
     * delegates to EncryptedSharedPreferences backed by Android Keystore.
     */
    val apiKey: String = "",
    /** Optional model override (e.g. "gpt-4o", "claude-3-5-sonnet"). */
    val modelOverride: String = "",
    /** Maximum tokens per request (caps cost/latency). */
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    /** Request timeout in milliseconds. */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    /** True only when a real provider is selected and an API key has been supplied. */
    val isConfigured: Boolean
        get() = providerType != AiProviderType.NONE && apiKey.isNotBlank()

    /** Effective model name (override or the provider default). */
    val effectiveModel: String
        get() = modelOverride.ifBlank { providerType.defaultModel }

    companion object {
        const val DEFAULT_MAX_TOKENS = 1024
        const val DEFAULT_TIMEOUT_MS = 30_000L

        /** An unconfigured instance — safe to use as the default/fallback. */
        val OFFLINE = AiProviderConfig()
    }
}

/** External LLM providers supported by FoxTrader's AI Mentor feature. */
enum class AiProviderType(
    /** Display name shown in the Settings screen. */
    val displayName: String,
    /** Default model identifier for this provider. */
    val defaultModel: String,
    /** Base URL for the provider's chat-completion endpoint. */
    val baseUrl: String,
) {
    NONE(
        displayName = "Offline (Built-in)",
        defaultModel = "",
        baseUrl = "",
    ),
    OPENAI(
        displayName = "OpenAI",
        defaultModel = "gpt-4o-mini",
        baseUrl = "https://api.openai.com/v1/",
    ),
    ANTHROPIC(
        displayName = "Anthropic (Claude)",
        defaultModel = "claude-3-5-haiku-20241022",
        baseUrl = "https://api.anthropic.com/v1/",
    ),
    GEMINI(
        displayName = "Google Gemini",
        defaultModel = "gemini-1.5-flash",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/",
    ),
    CUSTOM(
        displayName = "Custom (OpenAI-compatible)",
        defaultModel = "local-model",
        baseUrl = "",   // User-supplied in Settings
    ),
}

/**
 * A safe, sanitized chat message for sending to an external AI provider.
 *
 * All user-supplied text must be validated against [isValid] before being
 * included in a prompt. This prevents prompt injection and oversized payloads.
 */
data class AiChatMessage(
    val role: AiMessageRole,
    /** Sanitized content — no raw user inputs above [MAX_CONTENT_LENGTH]. */
    val content: String,
) {
    val isValid: Boolean
        get() = content.isNotBlank() && content.length <= MAX_CONTENT_LENGTH

    companion object {
        const val MAX_CONTENT_LENGTH = 8_000

        /** Truncate content to the safe maximum and return a valid message. */
        fun safe(role: AiMessageRole, content: String): AiChatMessage =
            AiChatMessage(role, content.take(MAX_CONTENT_LENGTH))
    }
}

enum class AiMessageRole { SYSTEM, USER, ASSISTANT }

/**
 * The result of an external AI provider call.
 *
 * On success [content] holds the model's response. On failure [error]
 * describes what went wrong. The caller must check [isSuccess] before using
 * [content].
 */
data class AiProviderResponse(
    val isSuccess: Boolean,
    val content: String = "",
    val error: String = "",
    val tokensUsed: Int = 0,
    val model: String = "",
)
