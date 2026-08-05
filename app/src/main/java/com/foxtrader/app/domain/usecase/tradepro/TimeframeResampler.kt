package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe

/**
 * Pure, stateless aggregation of a base-timeframe candle series into a higher timeframe.
 *
 * Candles are bucketed by **absolute epoch time** (`floor(timestamp / bucketMillis)`), which is the
 * correct behaviour for real market data: an H4 bar always spans a fixed wall-clock window regardless
 * of how many base bars actually printed inside it (gaps, holidays, thin sessions). Aggregation per
 * bucket is the canonical OHLCV roll-up:
 *  - open   = first (earliest) base bar's open
 *  - high   = max of highs
 *  - low    = min of lows
 *  - close  = last (latest) base bar's close
 *  - volume = sum of volumes
 *  - timestamp = the bucket's start (its open time)
 *
 * The input MUST be ascending by timestamp (as it is everywhere in the app); the output preserves that
 * order. Because the roll-up only ever reads bars already present in [base], resampling a series that
 * ends at bar *i* yields an HTF view containing no information from after bar *i* — i.e. it is
 * inherently free of look-ahead bias, which is what makes it safe to drive a walk-forward backtest.
 */
object TimeframeResampler {

    /**
     * Aggregate [base] (assumed to be a lower or equal timeframe) into [target].
     *
     * Returns [base] unchanged when [target] is not strictly higher than the data already implies
     * (there is nothing to merge), and an empty list for empty input.
     */
    fun resample(base: List<Candle>, target: Timeframe): List<Candle> {
        if (base.isEmpty()) return emptyList()
        val bucketMillis = target.minutes.toLong() * MILLIS_PER_MINUTE
        if (bucketMillis <= 0L) return base

        val out = ArrayList<Candle>((base.size / target.minutes.coerceAtLeast(1)) + 1)

        var hasBucket = false
        var bucketIndex = 0L
        var open = 0.0
        var high = 0.0
        var low = 0.0
        var close = 0.0
        var volume = 0.0

        for (candle in base) {
            val index = Math.floorDiv(candle.timestamp, bucketMillis)
            if (!hasBucket || index != bucketIndex) {
                if (hasBucket) {
                    out += Candle(bucketIndex * bucketMillis, open, high, low, close, volume, source = com.foxtrader.app.domain.model.CandleSource.CACHED)
                }
                hasBucket = true
                bucketIndex = index
                open = candle.open
                high = candle.high
                low = candle.low
                close = candle.close
                volume = candle.volume
            } else {
                if (candle.high > high) high = candle.high
                if (candle.low < low) low = candle.low
                close = candle.close
                volume += candle.volume
            }
        }
        if (hasBucket) {
            out += Candle(bucketIndex * bucketMillis, open, high, low, close, volume, source = com.foxtrader.app.domain.model.CandleSource.CACHED)
        }
        return out
    }

    private const val MILLIS_PER_MINUTE = 60_000L
}
