package com.foxtrader.app.domain.sdk.auto

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SignalGrade

/**
 * Android Auto contract — voice-first, read-only, safety-constrained.
 *
 * RULE: No chart interaction or trading actions while driving.
 * Only high-priority alert readouts and watchlist status.
 */

/** A voice-readable alert summary for Android Auto. */
data class AutoAlertMessage(
    val symbol: String,
    val direction: Direction,
    val grade: SignalGrade,
    val spokenText: String,   // TTS-friendly: "Bullish BTCUSDT, strong signal, 7 of 9 confluences"
    val timestamp: Long,
)

/** Watchlist glanceable item for the Auto dashboard. */
data class AutoWatchlistItem(
    val symbol: String,
    val lastPrice: Double,
    val changePercent: Double,
    val biasLabel: String,    // "BULL" / "BEAR" / "—"
)

/** Voice command intents the Auto surface can trigger. */
enum class AutoVoiceCommand {
    READ_ALERTS,       // "What are my alerts?"
    WATCHLIST_STATUS,  // "How's my watchlist?"
    MARKET_BIAS,       // "What's the bias on EURUSD?"
}
