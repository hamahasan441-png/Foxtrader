package com.foxtrader.app.domain.sdk.voice

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe

/**
 * Voice assistant contract for hands-free market queries.
 *
 * Supports natural-language queries like:
 * - "What's the bias on EURUSD?"
 * - "Any setups on Bitcoin?"
 * - "Read my alerts"
 *
 * The implementation bridges recognized intents to the appropriate
 * domain use cases and produces a spoken response.
 */
interface VoiceAssistant {

    /** Process a voice query and return a spoken response. */
    suspend fun query(input: VoiceQuery): VoiceResponse
}

data class VoiceQuery(
    val text: String,
    val intent: VoiceIntent = VoiceIntent.UNKNOWN,
    val symbol: String? = null,
    val timeframe: Timeframe? = null,
)

enum class VoiceIntent {
    MARKET_BIAS,     // "What's the bias?"
    ACTIVE_ALERTS,   // "Any alerts?"
    WATCHLIST,       // "Watchlist status"
    SETUP_CHECK,     // "Any setups on X?"
    UNKNOWN,
}

data class VoiceResponse(
    val spokenText: String,
    val displayText: String = spokenText,
    val bias: Bias? = null,
    val confidence: Int? = null,
)
