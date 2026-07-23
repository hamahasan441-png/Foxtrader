package com.foxtrader.app.domain.usecase.ai.provider

import com.foxtrader.app.domain.model.AiChatMessage
import com.foxtrader.app.domain.model.AiMessageRole
import com.foxtrader.app.domain.model.AiProviderConfig
import com.foxtrader.app.domain.model.AiProviderResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * No-op AI provider client used when no external provider is configured.
 *
 * All [chat] calls immediately return a graceful-degradation response so
 * feature code can treat it uniformly: check [AiProviderResponse.isSuccess]
 * and fall back to the built-in offline analysis when it is false.
 *
 * This is the default production implementation and the one injected by Hilt
 * when [com.foxtrader.app.domain.model.AiProviderConfig.isConfigured] is false.
 * When a user configures an external provider in Settings, the calling use-case
 * switches to the real network-backed implementation.
 */
@Singleton
class NoOpAiProviderClient @Inject constructor() : AiProviderClient {

    override suspend fun chat(
        config: AiProviderConfig,
        messages: List<AiChatMessage>,
        systemPrompt: String,
    ): AiProviderResponse = AiProviderResponse(
        isSuccess = false,
        content = "",
        error = "No external AI provider configured. Using built-in offline analysis.",
        tokensUsed = 0,
        model = "",
    )
}

/**
 * Builds a safe, length-bounded prompt from the given [trade] context string.
 * Applies content size guard ([AiChatMessage.MAX_CONTENT_LENGTH]) and returns
 * a list ready for [AiProviderClient.chat].
 */
fun buildTradeAnalysisMessages(
    systemInstruction: String,
    tradeContext: String,
): List<AiChatMessage> {
    val safeContext = tradeContext.take(AiChatMessage.MAX_CONTENT_LENGTH)
    return listOf(
        AiChatMessage.safe(AiMessageRole.SYSTEM, systemInstruction),
        AiChatMessage.safe(AiMessageRole.USER, safeContext),
    ).filter { it.isValid }
}
