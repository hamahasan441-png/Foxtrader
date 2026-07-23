package com.foxtrader.app.domain.usecase.ai.provider

import com.foxtrader.app.domain.model.AiChatMessage
import com.foxtrader.app.domain.model.AiMessageRole
import com.foxtrader.app.domain.model.AiProviderConfig
import com.foxtrader.app.domain.model.AiProviderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the AI provider abstraction layer.
 *
 * Validates:
 * - [AiProviderConfig] isConfigured logic and defaults.
 * - [AiChatMessage] safety limits and isValid checks.
 * - [NoOpAiProviderClient] always returns a graceful non-success response.
 * - [buildTradeAnalysisMessages] length and structure.
 */
class AiProviderTest {

    // ========================================================================
    // AiProviderConfig
    // ========================================================================

    @Test
    fun `OFFLINE config is not configured`() {
        assertFalse(AiProviderConfig.OFFLINE.isConfigured)
    }

    @Test
    fun `NONE provider with blank key is not configured`() {
        val config = AiProviderConfig(providerType = AiProviderType.NONE, apiKey = "")
        assertFalse(config.isConfigured)
    }

    @Test
    fun `OPENAI provider with blank key is not configured`() {
        val config = AiProviderConfig(providerType = AiProviderType.OPENAI, apiKey = "")
        assertFalse(config.isConfigured)
    }

    @Test
    fun `OPENAI provider with non-blank key is configured`() {
        val config = AiProviderConfig(providerType = AiProviderType.OPENAI, apiKey = "sk-abc123")
        assertTrue(config.isConfigured)
    }

    @Test
    fun `ANTHROPIC provider with non-blank key is configured`() {
        val config = AiProviderConfig(providerType = AiProviderType.ANTHROPIC, apiKey = "ant-xyz789")
        assertTrue(config.isConfigured)
    }

    @Test
    fun `effectiveModel uses modelOverride when set`() {
        val config = AiProviderConfig(
            providerType = AiProviderType.OPENAI,
            apiKey = "key",
            modelOverride = "gpt-4o",
        )
        assertEquals("gpt-4o", config.effectiveModel)
    }

    @Test
    fun `effectiveModel falls back to provider default when override is blank`() {
        val config = AiProviderConfig(
            providerType = AiProviderType.OPENAI,
            apiKey = "key",
            modelOverride = "",
        )
        assertEquals(AiProviderType.OPENAI.defaultModel, config.effectiveModel)
    }

    @Test
    fun `NONE provider effectiveModel is empty`() {
        assertEquals("", AiProviderConfig.OFFLINE.effectiveModel)
    }

    // ========================================================================
    // AiChatMessage
    // ========================================================================

    @Test
    fun `message with content within limit is valid`() {
        val msg = AiChatMessage(AiMessageRole.USER, "Hello, analyze this trade.")
        assertTrue(msg.isValid)
    }

    @Test
    fun `message with blank content is invalid`() {
        val msg = AiChatMessage(AiMessageRole.USER, "")
        assertFalse(msg.isValid)
    }

    @Test
    fun `message with content exceeding MAX_CONTENT_LENGTH is invalid`() {
        val oversized = "x".repeat(AiChatMessage.MAX_CONTENT_LENGTH + 1)
        val msg = AiChatMessage(AiMessageRole.USER, oversized)
        assertFalse(msg.isValid)
    }

    @Test
    fun `safe factory truncates content to MAX_CONTENT_LENGTH`() {
        val oversized = "a".repeat(AiChatMessage.MAX_CONTENT_LENGTH + 500)
        val msg = AiChatMessage.safe(AiMessageRole.USER, oversized)
        assertEquals(AiChatMessage.MAX_CONTENT_LENGTH, msg.content.length)
        assertTrue(msg.isValid)
    }

    @Test
    fun `safe factory preserves short content unchanged`() {
        val short = "Short message"
        val msg = AiChatMessage.safe(AiMessageRole.ASSISTANT, short)
        assertEquals(short, msg.content)
    }

    // ========================================================================
    // NoOpAiProviderClient
    // ========================================================================

    @Test
    fun `NoOpAiProviderClient always returns non-success`() = runBlocking {
        val client = NoOpAiProviderClient()
        val response = client.chat(
            config = AiProviderConfig.OFFLINE,
            messages = listOf(AiChatMessage.safe(AiMessageRole.USER, "test")),
        )
        assertFalse("NoOp client should never succeed", response.isSuccess)
        assertTrue("Error message should be non-blank", response.error.isNotBlank())
        assertEquals("Content should be empty on no-op", "", response.content)
    }

    @Test
    fun `NoOpAiProviderClient works with configured provider too`() = runBlocking {
        val client = NoOpAiProviderClient()
        val response = client.chat(
            config = AiProviderConfig(AiProviderType.OPENAI, "sk-key"),
            messages = listOf(AiChatMessage.safe(AiMessageRole.USER, "analyze")),
        )
        // Even with a "real" config, the no-op client never makes network calls.
        assertFalse(response.isSuccess)
    }

    // ========================================================================
    // buildTradeAnalysisMessages
    // ========================================================================

    @Test
    fun `buildTradeAnalysisMessages returns two messages for short inputs`() {
        val messages = buildTradeAnalysisMessages("System instruction", "Trade context here.")
        assertEquals(2, messages.size)
        assertEquals(AiMessageRole.SYSTEM, messages[0].role)
        assertEquals(AiMessageRole.USER, messages[1].role)
    }

    @Test
    fun `buildTradeAnalysisMessages truncates oversized context`() {
        val oversizedContext = "c".repeat(AiChatMessage.MAX_CONTENT_LENGTH + 1000)
        val messages = buildTradeAnalysisMessages("System", oversizedContext)
        assertNotNull(messages.find { it.role == AiMessageRole.USER })
        val userMessage = messages.first { it.role == AiMessageRole.USER }
        assertTrue(userMessage.content.length <= AiChatMessage.MAX_CONTENT_LENGTH)
        assertTrue(userMessage.isValid)
    }

    @Test
    fun `buildTradeAnalysisMessages filters invalid (blank) messages`() {
        val messages = buildTradeAnalysisMessages("", "")
        // Both content strings are blank → both messages should be filtered out.
        assertTrue("No messages should remain when all content is blank", messages.isEmpty())
    }
}
