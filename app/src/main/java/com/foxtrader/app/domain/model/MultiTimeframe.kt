package com.foxtrader.app.domain.model

/**
 * Per-timeframe analysis result within the MTF engine.
 */
data class TimeframeBias(
    val timeframe: Timeframe,
    val bias: Bias,
    val confidence: Int,          // 0-95
    val lastBreakType: StructureBreakType? = null,
    val lastBreakDirection: Direction? = null,
    val emaAlignment: EmaAlignment = EmaAlignment.MIXED,
    val adxStrength: Double = 0.0,
)

enum class EmaAlignment { BULLISH, BEARISH, MIXED }

/**
 * Aggregate Multi-Timeframe Analysis result.
 */
data class MTFResult(
    val symbol: String,
    val biases: Map<Timeframe, TimeframeBias>,
    val htfBias: Bias,
    val htfConfidence: Int,         // 0-95
    val alignedTimeframes: List<Timeframe>,
    val conflictingTimeframes: List<Timeframe>,
    val narrative: String,
    val timestamp: Long,
)
