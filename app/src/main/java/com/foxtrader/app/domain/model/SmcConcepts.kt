package com.foxtrader.app.domain.model

/**
 * Smart Money Concepts (SMC) domain models.
 * These represent the institutional trading concepts that are detected
 * and visualized on the chart.
 */

// ============================================================================
// ORDER BLOCKS
// ============================================================================

/** Type of order block. */
enum class OrderBlockType { BULLISH, BEARISH }

/**
 * An Order Block — the last opposing candle before an impulsive move.
 * Institutional supply/demand zones.
 */
data class OrderBlock(
    val type: OrderBlockType,
    val highPrice: Double,
    val lowPrice: Double,
    val startIndex: Int,
    val endIndex: Int,        // How far the zone extends (for rendering width)
    val mitigated: Boolean,   // Whether price has returned and filled the OB
    val strength: Double,     // 0.0 to 1.0 based on the impulsive move that followed
)

// ============================================================================
// FAIR VALUE GAPS (FVG / IMBALANCE)
// ============================================================================

/** Type of fair value gap. */
enum class FvgType { BULLISH, BEARISH }

/**
 * A Fair Value Gap — a three-candle pattern where the middle candle's body
 * creates a gap between candle 1's high/low and candle 3's low/high.
 * Represents inefficiency that price tends to revisit.
 */
data class FairValueGap(
    val type: FvgType,
    val highPrice: Double,    // Top of the gap
    val lowPrice: Double,     // Bottom of the gap
    val index: Int,           // Middle candle index
    val filled: Boolean,      // Whether price has returned to fill the gap
    val fillPercent: Double,  // 0.0 to 1.0 how much of the gap has been filled
)

// ============================================================================
// LIQUIDITY
// ============================================================================

/** Type of liquidity pool. */
enum class LiquidityType {
    BUY_SIDE,    // Equal highs / stop losses above (targets for bearish sweeps)
    SELL_SIDE,   // Equal lows / stop losses below (targets for bullish sweeps)
}

/**
 * A liquidity pool — clustered stop losses / equal highs/lows
 * that smart money targets for sweeps.
 */
data class LiquidityPool(
    val type: LiquidityType,
    val price: Double,
    val startIndex: Int,
    val endIndex: Int,
    val swept: Boolean,       // Whether the liquidity has been taken
    val sweepIndex: Int?,     // Bar where the sweep occurred
)

// ============================================================================
// TRADING SESSIONS
// ============================================================================

/** Major trading sessions. */
enum class TradingSession(
    val label: String,
    val openHourUtc: Int,
    val closeHourUtc: Int,
    val color: Long,          // ARGB for overlay
) {
    SYDNEY("Sydney", 22, 7, 0x1A42A5F5),
    TOKYO("Tokyo", 0, 9, 0x1AEF5350),
    LONDON("London", 7, 16, 0x1A4CAF50),
    NEW_YORK("New York", 13, 22, 0x1AD4A84E),
}

/**
 * A session time range for a specific day, expressed in bar indices.
 */
data class SessionRange(
    val session: TradingSession,
    val startIndex: Int,
    val endIndex: Int,
    val highPrice: Double,
    val lowPrice: Double,
)

// ============================================================================
// VOLUME PROFILE
// ============================================================================

/**
 * A single level in the volume profile (price bucket + volume).
 */
data class VolumeProfileLevel(
    val priceLevel: Double,
    val volume: Double,
    val buyVolume: Double,
    val sellVolume: Double,
) {
    val totalVolume: Double get() = buyVolume + sellVolume
    val delta: Double get() = buyVolume - sellVolume
}

/**
 * Complete volume profile for a visible range.
 */
data class VolumeProfile(
    val levels: List<VolumeProfileLevel>,
    val pocPrice: Double,     // Point of Control — highest volume level
    val vahPrice: Double,     // Value Area High (70% of volume above)
    val valPrice: Double,     // Value Area Low (70% of volume below)
    val totalVolume: Double,
)
