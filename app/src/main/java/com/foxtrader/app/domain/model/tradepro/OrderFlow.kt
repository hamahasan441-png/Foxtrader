package com.foxtrader.app.domain.model.tradepro

import com.foxtrader.app.domain.model.Direction
import kotlin.math.abs

/**
 * Provenance of the order-flow data carried by an [OrderFlowBar].
 *
 * TRADEPRO is an auction / order-flow framework. Its highest-fidelity signals (footprint imbalance,
 * absorption, DOM magnets) require aggressor-tagged trade data (tape) or a Level-2 order book. Most
 * retail crypto/forex feeds only expose aggregate OHLCV candles, so this app derives buy/sell volume
 * from candle shape as a *proxy*. [OrderFlowSource] marks which is which so downstream logic (and the
 * UI) can be honest about signal fidelity, and so a real tape feed can be plugged in without any change
 * to the TRADEPRO engines.
 */
enum class OrderFlowSource {
    /** Aggressor-tagged trades (real footprint / time-and-sales). Highest fidelity. */
    TAPE,

    /** Buy/sell split estimated from candle geometry + volume. A proxy, not real order flow. */
    CANDLE_DERIVED,
}

/**
 * A single bar of order flow: the OHLC of a bar plus the aggressive buy vs sell volume traded within it.
 *
 * When [source] is [OrderFlowSource.TAPE], [buyVolume]/[sellVolume] are real ask-lifted / bid-hit volume.
 * When [source] is [OrderFlowSource.CANDLE_DERIVED], they are estimated (see CandleDerivedOrderFlowProvider).
 */
data class OrderFlowBar(
    val index: Int,
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val buyVolume: Double,
    val sellVolume: Double,
    val source: OrderFlowSource,
) {
    val totalVolume: Double get() = buyVolume + sellVolume

    /** Buy pressure minus sell pressure. Positive = net aggressive buying. */
    val delta: Double get() = buyVolume - sellVolume

    /** |delta| as a fraction of total volume in [0,1]. 0 = balanced, 1 = one-sided. */
    val dominance: Double get() = if (totalVolume <= 0.0) 0.0 else abs(delta) / totalVolume

    val range: Double get() = high - low

    /** Net price progress across the bar (close - open). */
    val priceProgress: Double get() = close - open
}

/**
 * A bar-level order-flow imbalance: one side's aggressive volume overwhelmingly exceeds the other.
 *
 * In a true footprint this is a per-price diagonal comparison; at bar granularity we compare the bar's
 * aggregate buy vs sell volume. A [ratio] >= the configured threshold marks a defended commitment on
 * the [direction] side.
 */
data class Imbalance(
    val index: Int,
    val timestamp: Long,
    val direction: Direction,
    val ratio: Double,
    val price: Double,
    val volume: Double,
)

/**
 * Absorption: strong one-sided aggression that fails to move price — a large passive player is soaking
 * it up. A warning of potential reversal; traders who kept pushing into it become trapped and their
 * forced exits can fuel the opposite move. [absorbedSide] is the side being absorbed; the anticipated
 * reversal is in the opposite direction.
 */
data class AbsorptionEvent(
    val index: Int,
    val timestamp: Long,
    val price: Double,
    val absorbedSide: Direction,
    val aggressionVolume: Double,
    val priceProgress: Double,
    val strength: Double,
    val detail: String,
)
