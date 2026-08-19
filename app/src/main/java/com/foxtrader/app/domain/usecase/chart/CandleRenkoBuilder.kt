package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds Renko bricks from a time-candle series (close-based).
 *
 * Renko discards time and small noise: a new fixed-height brick is printed only
 * when a candle's close travels at least [brickSize] beyond the previous brick's
 * close. A single large move can print several bricks at once. Each brick is a
 * clean [Candle]:
 * - Up brick:   open = prevClose, close = prevClose + brickSize, high = close, low = open
 * - Down brick: open = prevClose, close = prevClose − brickSize, high = open,  low = close
 *
 * This is the candle-sourced counterpart to the tick-driven Renko builder: it
 * needs no tick feed, so it works on any provider's candle data. The anchor is
 * the first candle's close; volume accumulated between bricks is attributed to
 * the first brick of a batch.
 *
 * Pure domain logic — no Android dependencies; deterministic and unit-testable.
 */
@Singleton
class CandleRenkoBuilder @Inject constructor() {

    /**
     * @param candles Source time candles (assumed ascending by timestamp).
     * @param brickSize Absolute price height of one brick (must be > 0).
     * @return Renko bricks in order; empty when input is empty or [brickSize] <= 0.
     */
    fun build(candles: List<Candle>, brickSize: Double): List<Candle> {
        if (candles.isEmpty() || brickSize <= 0.0 || !brickSize.isFinite()) return emptyList()

        val bricks = ArrayList<Candle>()
        var lastClose = candles.first().close
        var volumeAccumulator = 0.0

        for (candle in candles) {
            val price = candle.close
            volumeAccumulator += candle.volume

            // `SAFETY` A brick size that is tiny relative to the price range
            // (e.g. the 10.0 default applied to a 100k BTC chart after the user
            // types 0.0001) can demand millions of bricks from a single candle
            // — an OOM/ANR, not a chart. Cap the output; the freshest bricks
            // are the ones that matter, so stop building rather than degrade.
            if (bricks.size >= MAX_BRICKS) break

            while (price - lastClose >= brickSize && bricks.size < MAX_BRICKS) {
                val open = lastClose
                val close = lastClose + brickSize
                bricks.add(
                    Candle(
                        timestamp = candle.timestamp,
                        open = open,
                        high = close,
                        low = open,
                        close = close,
                        volume = volumeAccumulator,
                    )
                )
                lastClose = close
                volumeAccumulator = 0.0
            }

            while (lastClose - price >= brickSize && bricks.size < MAX_BRICKS) {
                val open = lastClose
                val close = lastClose - brickSize
                bricks.add(
                    Candle(
                        timestamp = candle.timestamp,
                        open = open,
                        high = open,
                        low = close,
                        close = close,
                        volume = volumeAccumulator,
                    )
                )
                lastClose = close
                volumeAccumulator = 0.0
            }
        }

        return bricks
    }

    private companion object {
        /**
         * Hard output cap. 50k bricks is far beyond any usable chart depth
         * (MAX_VISIBLE_BARS is 100k px-wide worst case) while still bounding a
         * pathological brickSize to a few MB instead of an OOM.
         */
        const val MAX_BRICKS = 50_000
    }
}
