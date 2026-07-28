package com.foxtrader.app.data.market.candle

import com.foxtrader.app.data.market.model.MarketTimeframe
import com.foxtrader.app.data.market.model.Tick
import com.foxtrader.app.domain.model.Candle

/**
 * Fans a single tick stream out to one [CandleBuilder] per requested timeframe,
 * producing all twelve bars (M1…MN) from the same ticks.
 *
 * The builders are held in a flat array and iterated by index, so a tick that
 * closes no bucket allocates nothing — important because the overwhelming
 * majority of live ticks fall inside the forming bucket of every timeframe.
 *
 * The primary API is callback-based ([onTick] with an `emit` lambda) to keep the
 * hot path allocation-free; [onTickCollect] is a convenience that materialises a
 * map for callers (and tests) that want the closed bars as a return value.
 */
class MultiTimeframeCandleEngine(timeframes: Collection<MarketTimeframe>) {

    // @PublishedApi internal (rather than private) so the public inline [onTick]
    // hot path can read them without boxing a lambda or allocating per tick.
    @PublishedApi
    internal val builders: Array<CandleBuilder>

    @PublishedApi
    internal val frames: Array<MarketTimeframe>

    init {
        require(timeframes.isNotEmpty()) { "At least one timeframe is required" }
        val distinct = timeframes.distinct()
        frames = distinct.toTypedArray()
        builders = Array(distinct.size) { CandleBuilder(distinct[it]) }
    }

    /** The timeframes this engine builds, in construction order. */
    val timeframes: List<MarketTimeframe> get() = frames.asList()

    /**
     * Processes [tick] across every timeframe. For each timeframe whose bucket
     * this tick closed, [emit] is invoked with the confirmed candle.
     */
    inline fun onTick(tick: Tick, emit: (MarketTimeframe, Candle) -> Unit) {
        for (i in builders.indices) {
            val closed = builders[i].onTick(tick)
            if (closed != null) emit(frames[i], closed)
        }
    }

    /** Convenience: returns the candles closed by this tick, keyed by timeframe. */
    fun onTickCollect(tick: Tick): Map<MarketTimeframe, Candle> {
        val out = HashMap<MarketTimeframe, Candle>(2)
        onTick(tick) { tf, candle -> out[tf] = candle }
        return out
    }

    /** Finalises every timeframe at end-of-stream; returns the confirmed bars. */
    fun flush(): Map<MarketTimeframe, Candle> {
        val out = HashMap<MarketTimeframe, Candle>(builders.size)
        for (i in builders.indices) {
            val closed = builders[i].flush()
            if (closed != null) out[frames[i]] = closed
        }
        return out
    }

    /** Total late ticks rejected across all timeframes (no-repaint metric). */
    fun totalRejectedLateTicks(): Long = builders.sumOf { it.rejectedLateTicks }
}
