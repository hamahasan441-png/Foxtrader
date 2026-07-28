package com.foxtrader.app.data.market.candle

import com.foxtrader.app.data.market.model.MarketTimeframe
import com.foxtrader.app.data.market.model.Tick
import com.foxtrader.app.domain.model.Candle

/**
 * Builds OHLCV candles for a single [timeframe] from a raw tick stream, with a
 * hard **no-repaint** guarantee.
 *
 * ## Semantics
 * Each tick maps to exactly one bucket via [MarketTimeframe.bucketStart]. The
 * builder keeps a single *forming* bucket:
 *  - a tick in the forming bucket folds in (high/low/close/volume update);
 *  - a tick in a *later* bucket seals the forming bucket, emits it as a confirmed
 *    [Candle], and opens a new forming bucket;
 *  - a tick in an *earlier* bucket is **rejected** — a sealed bucket is final.
 *
 * Because a sealed candle is emitted exactly once and never mutated afterward,
 * downstream indicators and AI narratives built on confirmed bars are stable:
 * history does not rewrite itself when late or out-of-order ticks arrive. This
 * is the single most important correctness property of a market-data engine.
 *
 * ## Gaps
 * If ticks jump forward by several buckets, only the just-finished bucket is
 * emitted; empty intermediate buckets are **not** fabricated. No data ⇒ no candle.
 *
 * Folding a tick is allocation-free; a [Candle] is allocated only when a bucket
 * actually closes.
 */
class CandleBuilder(val timeframe: MarketTimeframe) {

    private var formingStart = 0L
    private var open = 0.0
    private var high = 0.0
    private var low = 0.0
    private var close = 0.0
    private var volume = 0.0
    private var forming = false

    /**
     * The bucket start of the most recently sealed candle. Any tick whose bucket
     * start is `<= lastEmittedStart` targets an already-emitted bucket and is
     * rejected. Starts at [Long.MIN_VALUE] so the very first tick is always
     * accepted.
     */
    private var lastEmittedStart = Long.MIN_VALUE

    /** Ticks rejected because they targeted a sealed bucket. */
    var rejectedLateTicks: Long = 0L
        private set

    /**
     * Processes one tick. Returns the confirmed [Candle] when this tick closed a
     * bucket, otherwise `null` (the tick was folded into the forming bucket or
     * rejected as late).
     */
    fun onTick(tick: Tick): Candle? {
        val start = timeframe.bucketStart(tick.timestamp)

        // No-repaint guard: never touch a bucket we have already emitted.
        if (start <= lastEmittedStart) {
            rejectedLateTicks++
            return null
        }

        if (!forming) {
            openBucket(tick, start)
            return null
        }

        return when {
            start == formingStart -> {
                fold(tick)
                null
            }

            else -> { // start > formingStart: the forming bucket is complete.
                val sealed = seal()
                openBucket(tick, start)
                sealed
            }
        }
    }

    /**
     * Finalises the forming bucket at end-of-stream and returns it, or `null` if
     * nothing is forming. After a flush the builder accepts new buckets again but
     * still rejects anything at or before the flushed bucket (no repaint).
     */
    fun flush(): Candle? {
        if (!forming) return null
        return seal()
    }

    private fun openBucket(tick: Tick, start: Long) {
        forming = true
        formingStart = start
        open = tick.price
        high = tick.price
        low = tick.price
        close = tick.price
        volume = tick.quantity
    }

    private fun fold(tick: Tick) {
        if (tick.price > high) high = tick.price
        if (tick.price < low) low = tick.price
        close = tick.price
        volume += tick.quantity
    }

    private fun seal(): Candle {
        val candle = Candle(
            timestamp = formingStart,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
        )
        lastEmittedStart = formingStart
        forming = false
        return candle
    }
}
