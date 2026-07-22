package com.foxtrader.app.domain.model

/**
 * Trade Journal domain models.
 * Records trade entries for review, analysis, and performance tracking.
 */

/**
 * A single trade journal entry — the record of a trade taken.
 */
data class JournalEntry(
    val id: String,
    val symbol: String,
    val direction: Direction,
    val timeframe: Timeframe,
    val entryPrice: Double,
    val exitPrice: Double?,         // null if trade is still open
    val stopLoss: Double,
    val takeProfit: Double,
    val volume: Double,
    val entryTime: Long,
    val exitTime: Long?,
    val pnl: Double?,               // null if still open
    val rMultiple: Double?,         // null if still open
    val setupType: String,          // e.g. "BOS Long", "FVG Rejection", "Liquidity Sweep"
    val notes: String = "",
    val rating: Int = 0,            // 1-5 self-rating of trade quality
    val emotionTag: EmotionTag = EmotionTag.NEUTRAL,
    val screenshot: String? = null, // Path to chart screenshot
    val tags: List<String> = emptyList(),
    val isOpen: Boolean = exitPrice == null,
) {
    val isWin: Boolean get() = (pnl ?: 0.0) > 0.0
    val holdingTimeMs: Long? get() = if (exitTime != null) exitTime - entryTime else null
}

/**
 * Emotion state when entering the trade.
 * Helps identify patterns of emotional trading.
 */
enum class EmotionTag {
    CONFIDENT,
    NEUTRAL,
    FOMO,
    REVENGE,
    FEARFUL,
    GREEDY,
    PATIENT,
}

/**
 * Aggregated journal statistics.
 */
data class JournalStats(
    val totalTrades: Int = 0,
    val winRate: Double = 0.0,
    val averageRMultiple: Double = 0.0,
    val totalPnl: Double = 0.0,
    val bestTrade: Double = 0.0,
    val worstTrade: Double = 0.0,
    val averageHoldingTimeMs: Long = 0L,
    val profitFactor: Double = 0.0,
    val consecutiveWins: Int = 0,
    val consecutiveLosses: Int = 0,
    val averageRating: Double = 0.0,
    val tradesBySetup: Map<String, Int> = emptyMap(),
    val tradesByEmotion: Map<EmotionTag, Int> = emptyMap(),
)
