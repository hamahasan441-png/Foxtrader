package com.foxtrader.app.domain.model.tradepro

import com.foxtrader.app.domain.model.Direction

/** A defended order-flow zone built from stacked same-direction imbalances. */
enum class HoldZoneType {
    /** Defended buy zone ("Buy Hold") — enter long on a pullback into it. */
    BUY_HOLD,

    /** Defended sell zone ("Sell Hold") — enter short on a rally into it. */
    SELL_HOLD,
}

/**
 * A "Buy Hold" / "Sell Hold" zone: a box drawn around a cluster of stacked imbalances that show
 * sustained one-sided commitment. The framework says: do not trade single imbalances — group stacked
 * ones into a zone, wait for price to pull back into the zone, and enter in the direction the zone
 * defended. The stop goes just outside the zone.
 *
 * @param stackedCount number of stacked imbalances forming the zone (more = stronger).
 * @param strength 0..100 composite of stack depth and delta dominance.
 * @param defended whether price has re-tested the zone and held (increases conviction).
 */
data class HoldZone(
    val type: HoldZoneType,
    val high: Double,
    val low: Double,
    val startIndex: Int,
    val endIndex: Int,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val stackedCount: Int,
    val strength: Double,
    val defended: Boolean,
) {
    val mid: Double get() = (high + low) / 2.0
    val height: Double get() = high - low

    val direction: Direction
        get() = if (type == HoldZoneType.BUY_HOLD) Direction.BULLISH else Direction.BEARISH

    /** True when [price] is inside the zone (a pullback/rally has reached it). */
    fun contains(price: Double): Boolean = price in low..high
}
