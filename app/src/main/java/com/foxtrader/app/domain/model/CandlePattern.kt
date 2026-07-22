package com.foxtrader.app.domain.model

/**
 * All recognized candlestick pattern types.
 */
enum class CandlePatternType {
    DOJI,
    HAMMER,
    INVERTED_HAMMER,
    SHOOTING_STAR,
    HANGING_MAN,
    ENGULFING_BULLISH,
    ENGULFING_BEARISH,
    MORNING_STAR,
    EVENING_STAR,
    THREE_WHITE_SOLDIERS,
    THREE_BLACK_CROWS,
    HARAMI_BULLISH,
    HARAMI_BEARISH,
    PIERCING_LINE,
    DARK_CLOUD_COVER,
    TWEEZER_TOP,
    TWEEZER_BOTTOM,
    SPINNING_TOP,
    MARUBOZU_BULLISH,
    MARUBOZU_BEARISH,
    DRAGONFLY_DOJI,
    GRAVESTONE_DOJI,
    THREE_INSIDE_UP,
    THREE_INSIDE_DOWN,
    BULLISH_KICKER,
    BEARISH_KICKER,
    ABANDONED_BABY_BULL,
    ABANDONED_BABY_BEAR,
}

/** Whether the pattern signals reversal or continuation. */
enum class PatternBias { REVERSAL, CONTINUATION }

/**
 * A detected candlestick pattern with associated metadata.
 */
data class DetectedPattern(
    val type: CandlePatternType,
    val direction: Direction,
    val bias: PatternBias,
    val confidence: Int,        // 0-100
    val probability: Int,       // base historical probability %
    val startIndex: Int,
    val endIndex: Int,
    val meaning: String,
    val context: String,
    val timestamp: Long,
)
