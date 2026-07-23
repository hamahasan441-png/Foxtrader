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

// ============================================================================
// BREAKER BLOCKS
// ============================================================================

/** Type of breaker block (flipped order block). */
enum class BreakerType { BULLISH, BEARISH }

/**
 * A Breaker Block — a failed order block that has been violated and now
 * acts as resistance (ex-bullish OB → bearish breaker) or support
 * (ex-bearish OB → bullish breaker) from the opposite direction.
 *
 * Detection: an OB is classified as a breaker when price sweeps through
 * the OB zone and then uses that zone as a level in the opposite direction.
 *
 * Port of reference/modules/order-blocks — breaker variant.
 */
data class BreakerBlock(
    /** Bullish breaker: provides support after an ex-bearish OB is violated. */
    val type: BreakerType,
    val highPrice: Double,
    val lowPrice: Double,
    /** Bar index at which the original OB was mitigated / violated. */
    val originIndex: Int,
    /** Bar index at which the breaker became active (price returned). */
    val breakerIndex: Int,
    /** Strength inherited from the original OB. */
    val strength: Double,
)

// ============================================================================
// INVERSION FAIR VALUE GAP (IFVG)
// ============================================================================

/** Type of inversion FVG. */
enum class IfvgType { BULLISH, BEARISH }

/**
 * An Inversion Fair Value Gap (IFVG) — an FVG that has been fully filled
 * and now acts as support/resistance from the opposite direction.
 *
 * - A bullish FVG that gets filled → bearish IFVG (resistance zone).
 * - A bearish FVG that gets filled → bullish IFVG (support zone).
 *
 * Port of reference/modules/fair-value-gaps — inversion variant.
 */
data class InversionFVG(
    val type: IfvgType,
    val highPrice: Double,
    val lowPrice: Double,
    /** Bar index of the original FVG. */
    val originIndex: Int,
    /** Bar index at which the FVG was fully filled (inversion confirmed). */
    val inversionIndex: Int,
)

// ============================================================================
// BALANCED PRICE RANGE (BPR)
// ============================================================================

/**
 * A Balanced Price Range (BPR) — the overlapping zone of a bullish FVG and
 * a bearish FVG. The overlapping area represents high-sensitivity equilibrium
 * where price frequently reacts.
 *
 * Port of reference/modules/fair-value-gaps — BPR variant.
 */
data class BalancedPriceRange(
    /** The overlap zone (intersection of bullish + bearish FVG). */
    val highPrice: Double,
    val lowPrice: Double,
    /** Index of the bullish FVG. */
    val bullishFvgIndex: Int,
    /** Index of the bearish FVG. */
    val bearishFvgIndex: Int,
)

// ============================================================================
// AMD / POWER OF THREE
// ============================================================================

/** Phase of the AMD (Accumulation-Manipulation-Distribution) cycle. */
enum class AmdPhase { ACCUMULATION, MANIPULATION, DISTRIBUTION }

/**
 * AMD (Accumulation → Manipulation → Distribution) pattern, also known as
 * ICT Power of Three.
 *
 * - Accumulation: range-bound session (typically the Asian range).
 * - Manipulation: false spike that sweeps liquidity against the real direction.
 * - Distribution: the sustained move in the true direction following the sweep.
 *
 * Non-repainting: confirmed once the distribution move is established.
 * Port of reference/modules/ict-concepts — AMD variant.
 */
data class AmdPattern(
    /** The detected phase at the time of detection. */
    val phase: AmdPhase,
    /** Intended direction of the distribution move. */
    val direction: Direction,
    /** Price range of the accumulation phase. */
    val accumulationHigh: Double,
    val accumulationLow: Double,
    /** Index range of the accumulation phase. */
    val accumulationStart: Int,
    val accumulationEnd: Int,
    /** Price of the manipulation sweep (the false spike). */
    val manipulationPrice: Double,
    /** Bar index of the manipulation sweep. */
    val manipulationIndex: Int,
    /** Distribution target — structural level or OB on the opposing side. */
    val distributionTarget: Double,
    /** Bar index when the pattern was confirmed. */
    val confirmIndex: Int,
)
