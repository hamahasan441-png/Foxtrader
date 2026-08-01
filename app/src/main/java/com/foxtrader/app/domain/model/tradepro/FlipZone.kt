package com.foxtrader.app.domain.model.tradepro

import com.foxtrader.app.domain.model.Bias

/** Which structural point defines the Flip Zone. */
enum class FlipZoneKind {
    /** Last defended higher-low in an up-structure. Above it = long-only bias. */
    LAST_HIGHER_LOW,

    /** Last defended lower-high in a down-structure. Below it = short-only bias. */
    LAST_LOWER_HIGH,

    /** No qualifying structure yet. */
    UNDEFINED,
}

/**
 * The Flip Zone — the single bias-defining line for the session.
 *
 * It is the last defended structural point (last higher-low in a bull structure, last lower-high in a
 * bear structure). Above the Flip Zone the trader takes long setups only; below it, short setups only.
 * Per the framework it moves *only* when new structure forms — never adjusted emotionally. This model
 * is a pure value object; the "never move it emotionally" discipline is enforced by
 * [com.foxtrader.app.domain.usecase.tradepro.FlipZoneEngine] recomputing it solely from confirmed
 * structure.
 */
data class FlipZone(
    val price: Double,
    val bias: Bias,
    val kind: FlipZoneKind,
    val anchorIndex: Int,
    val anchorTimestamp: Long,
) {
    /** True if [price] argument (the current price) permits a long bias under this Flip Zone. */
    fun allowsLong(currentPrice: Double): Boolean = bias == Bias.BULLISH && currentPrice >= price

    /** True if [currentPrice] permits a short bias under this Flip Zone. */
    fun allowsShort(currentPrice: Double): Boolean = bias == Bias.BEARISH && currentPrice <= price
}
