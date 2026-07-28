package com.foxtrader.app.data.market.model

import com.foxtrader.app.domain.model.Candle

/**
 * A compressed summary of many ticks within one time interval.
 *
 * Tick streams from a live feed can run to dozens of ticks per second; folding
 * them into one [AggregatedTick] per interval is the first stage of compression
 * and is exactly the OHLCV shape a candle needs. [toCandle] converts losslessly.
 */
data class AggregatedTick(
    val symbol: String,
    val intervalStart: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val tickCount: Int,
) {
    init {
        require(tickCount > 0) { "An aggregated tick must summarise at least one tick" }
    }

    fun toCandle(): Candle =
        Candle(
            timestamp = intervalStart,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
        )
}

/**
 * A candle emitted by the engine, tagged with the timeframe it belongs to and
 * whether the bar is confirmed. Mirrors the domain `TickUpdate` idea but is the
 * engine's own output type so it can carry engine-only timeframes (M2/M3/M10).
 */
data class CandleUpdate(
    val timeframe: MarketTimeframe,
    val candle: Candle,
    /** True once the bucket has closed and the bar will never change (no repaint). */
    val isBarClose: Boolean,
)
