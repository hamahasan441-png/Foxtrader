package com.foxtrader.app.domain.usecase.ai.provider

import com.foxtrader.app.domain.model.AiChatMessage
import com.foxtrader.app.domain.model.AiProviderConfig
import com.foxtrader.app.domain.model.AiProviderResponse

/**
 * Future extension point for optional AI narration.
 *
 * The FoxTrader decision engine is entirely deterministic and rules-based;
 * no LLM is required for trade analysis, confluence scoring, or risk gating.
 * This interface exists solely for an optional narration layer that may, in a
 * future release, provide natural-language trade explanations by delegating to
 * an external chat-completion API. The only production implementation today is
 * [com.foxtrader.app.domain.usecase.ai.provider.NoOpAiProviderClient], which
 * returns an empty response and incurs no cost or network traffic.
 *
 * Implementations are responsible for:
 * - Building the HTTP request for a specific provider's chat-completion API.
 * - Mapping the provider's JSON response to [AiProviderResponse].
 * - Enforcing timeouts and safe input limits.
 *
 * DESIGN CONTRACT:
 * - Implementations must be stateless (depend only on the [AiProviderConfig]
 *   passed at call-time, not stored at construction time).
 * - Implementations must never log API keys or response bodies at RELEASE
 *   log levels.
 * - [chat] is a suspend function and must not block the calling thread.
 * - If the provider is unavailable or misconfigured, return a failure
 *   [AiProviderResponse] with a descriptive [AiProviderResponse.error] rather
 *   than throwing, so callers can gracefully degrade.
 */
interface AiProviderClient {

    /**
     * Send a chat-completion request to the external provider.
     *
     * @param config Provider configuration (type, API key, model, limits).
     * @param messages Ordered conversation history. Only [AiChatMessage.isValid]
     *   messages should be included; the caller is responsible for filtering.
     * @param systemPrompt Optional system-level instruction prepended before
     *   [messages]. If non-blank it overrides any system message already present
     *   in [messages].
     * @return [AiProviderResponse] — always returns a value, never throws.
     */
    suspend fun chat(
        config: AiProviderConfig,
        messages: List<AiChatMessage>,
        systemPrompt: String = "",
    ): AiProviderResponse
}
