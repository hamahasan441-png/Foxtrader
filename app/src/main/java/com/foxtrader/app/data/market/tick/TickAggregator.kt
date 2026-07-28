package com.foxtrader.app.data.market.tick

import com.foxtrader.app.data.market.model.AggregatedTick
import com.foxtrader.app.data.market.model.Tick

/**
 * Compresses a tick stream by folding ticks into fixed-length time intervals,
 * emitting one [AggregatedTick] per completed interval.
 *
 * This is the first compression stage between a raw feed and the candle engine:
 * a burst of N ticks inside one interval becomes a single OHLCV summary, cutting
 * downstream work from O(ticks) to O(intervals).
 *
 * ## No-repaint contract
 *  - A tick that falls in the *current* interval is folded in (no emission yet).
 *  - A tick that falls in a *later* interval closes the current one (emitted) and
 *    opens a new interval.
 *  - A tick that falls in an *earlier* interval is dropped: a closed interval is
 *    final and is never revisited. [droppedLateTicks] counts these.
 *
 * The accumulator is a set of primitives — folding a tick allocates nothing.
 */
class TickAggregator(private val intervalMs: Long) {

    init {
        require(intervalMs > 0) { "intervalMs must be > 0" }
    }

    private var active = false
    private var intervalStart = 0L
    private var symbol = ""
    private var open = 0.0
    private var high = 0.0
    private var low = 0.0
    private var close = 0.0
    private var volume = 0.0
    private var tickCount = 0

    /** Ticks rejected because they targeted an already-closed interval. */
    var droppedLateTicks: Long = 0L
        private set

    /**
     * Folds [tick] in. Returns the just-closed [AggregatedTick] when this tick
     * advanced into a new interval, otherwise `null`.
     */
    fun add(tick: Tick): AggregatedTick? {
        val start = Math.floorDiv(tick.timestamp, intervalMs) * intervalMs
        if (!active) {
            openInterval(tick, start)
            return null
        }
        return when {
            start == intervalStart -> {
                fold(tick)
                null
            }

            start > intervalStart -> {
                val closed = currentAggregate()
                openInterval(tick, start)
                closed
            }

            else -> {
                droppedLateTicks++
                null
            }
        }
    }

    /** Finalises and returns the in-progress interval at end-of-stream, if any. */
    fun flush(): AggregatedTick? {
        if (!active) return null
        val closed = currentAggregate()
        active = false
        return closed
    }

    private fun openInterval(tick: Tick, start: Long) {
        active = true
        intervalStart = start
        symbol = tick.symbol
        open = tick.price
        high = tick.price
        low = tick.price
        close = tick.price
        volume = tick.quantity
        tickCount = 1
    }

    private fun fold(tick: Tick) {
        if (tick.price > high) high = tick.price
        if (tick.price < low) low = tick.price
        close = tick.price
        volume += tick.quantity
        tickCount++
    }

    private fun currentAggregate(): AggregatedTick =
        AggregatedTick(
            symbol = symbol,
            intervalStart = intervalStart,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            tickCount = tickCount,
        )
}
