package com.foxtrader.app.domain.usecase.tick

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Tick
import com.foxtrader.app.domain.model.Timeframe
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tick Aggregator — builds OHLCV candles from a raw stream of ticks.
 *
 * Each tick is placed into a fixed-width time bucket determined by the target
 * timeframe: `bucket = floor(timestampMs / durationMs) * durationMs`. The bucket
 * start becomes the candle's [Candle.timestamp] (bar-open time).
 *
 * OHLC is computed from the tick [Tick.mid] price:
 * - open  = mid of the first tick in the bucket (earliest timestamp)
 * - high  = max mid over the bucket
 * - low   = min mid over the bucket
 * - close = mid of the last tick in the bucket (latest timestamp)
 *
 * Volume is the sum of `bidVolume + askVolume` over all ticks in the bucket.
 *
 * Non-repainting: a bucket's candle is derived solely from ticks that fall inside
 * that bucket — no look-ahead into later buckets. Output is sorted ascending by
 * bucket start. Empty input yields an empty list.
 *
 * Pure domain logic — no Android dependencies.
 */
@Singleton
class TickAggregator @Inject constructor() {

    /**
     * Aggregate [ticks] into candles for the given [timeframe].
     *
     * @param ticks Raw ticks in any order (they are grouped by time bucket).
     * @param timeframe Target candle timeframe.
     * @return Candles sorted ascending by open time; empty when [ticks] is empty.
     */
    fun aggregate(ticks: List<Tick>, timeframe: Timeframe): List<Candle> {
        if (ticks.isEmpty()) return emptyList()

        val durationMs = durationMsOf(timeframe)

        // Group ticks by bucket start.
        val buckets = LinkedHashMap<Long, MutableList<Tick>>()
        for (tick in ticks) {
            val bucketStart = Math.floorDiv(tick.timestampMs, durationMs) * durationMs
            buckets.getOrPut(bucketStart) { mutableListOf() }.add(tick)
        }

        val candles = ArrayList<Candle>(buckets.size)
        for ((bucketStart, bucketTicks) in buckets) {
            // Order within the bucket by timestamp so open/close are correct.
            val ordered = bucketTicks.sortedBy { it.timestampMs }
            val open = ordered.first().mid
            val close = ordered.last().mid
            var high = Double.NEGATIVE_INFINITY
            var low = Double.POSITIVE_INFINITY
            var volume = 0.0
            for (t in ordered) {
                val mid = t.mid
                if (mid > high) high = mid
                if (mid < low) low = mid
                volume += t.bidVolume + t.askVolume
            }
            candles.add(
                Candle(
                    timestamp = bucketStart,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = volume,
                )
            )
        }

        return candles.sortedBy { it.timestamp }
    }

    /** Bucket width in milliseconds for each supported timeframe. */
    private fun durationMsOf(timeframe: Timeframe): Long = when (timeframe) {
        Timeframe.M1 -> 60_000L
        Timeframe.M5 -> 300_000L
        Timeframe.M15 -> 900_000L
        Timeframe.M30 -> 1_800_000L
        Timeframe.H1 -> 3_600_000L
        Timeframe.H4 -> 14_400_000L
        Timeframe.D1 -> 86_400_000L
        Timeframe.W1 -> 604_800_000L
        Timeframe.MN -> 2_592_000_000L
    }
}
