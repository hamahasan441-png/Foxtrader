package com.foxtrader.app.domain.model

/**
 * A single market tick — the atomic unit of tick-level (sub-candle) data.
 *
 * A tick carries a best bid and best ask quote at a point in time. Candles are
 * later aggregated from a stream of ticks (see [com.foxtrader.app.domain.usecase.tick.TickAggregator]).
 *
 * Immutable value object — belongs to the domain layer (framework-free, pure Kotlin).
 */
data class Tick(
    val timestampMs: Long,       // epoch millis
    val bid: Double,
    val ask: Double,
    val bidVolume: Double = 0.0,
    val askVolume: Double = 0.0,
) {
    /** Mid price — the average of bid and ask. Used as the price basis for aggregation. */
    val mid: Double get() = (bid + ask) / 2.0

    /** Bid/ask spread. */
    val spread: Double get() = ask - bid
}
