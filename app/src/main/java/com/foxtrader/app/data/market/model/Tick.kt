package com.foxtrader.app.data.market.model

/**
 * A single raw market trade tick — the atomic input to the real-time engine.
 *
 * Immutable value object. The hot ingestion path works on the pooled
 * [com.foxtrader.app.data.market.tick.MutableTick] to stay allocation-free; a
 * [Tick] is materialised only when a snapshot is taken or a tick is handed to a
 * consumer that needs a stable, immutable record.
 *
 * @param symbol    instrument identifier, e.g. `EURUSD` or `BTCUSDT`.
 * @param price     trade price.
 * @param quantity  trade size (base units); used as candle volume.
 * @param timestamp trade time, epoch millis.
 * @param side      aggressor side when the feed reports it; [TickSide.UNKNOWN] otherwise.
 */
data class Tick(
    val symbol: String,
    val price: Double,
    val quantity: Double,
    val timestamp: Long,
    val side: TickSide = TickSide.UNKNOWN,
)

/** Aggressor side of a tick, when the provider reports it. */
enum class TickSide { BUY, SELL, UNKNOWN }
