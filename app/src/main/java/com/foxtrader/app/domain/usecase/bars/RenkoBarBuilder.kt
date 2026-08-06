package com.foxtrader.app.domain.usecase.bars

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Tick
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds Renko bricks from a tick stream.
 *
 * Renko is a price-driven chart: time and small noise are discarded, and a new
 * fixed-height brick is printed only when price travels at least [brickSize]
 * beyond the previous brick's close. A single large move can print several
 * bricks at once. Each brick is a clean [Candle]:
 * - Up brick:   open = prevClose, close = prevClose + brickSize, high = close, low = open
 * - Down brick: open = prevClose, close = prevClose − brickSize, high = open,  low = close
 *
 * The anchor for the first brick is the first tick's [Tick.mid]. Traded volume
 * (bid + ask) accumulated between bricks is attributed to the first brick of a
 * batch (subsequent same-tick bricks carry zero), so total volume is preserved.
 *
 * Pure domain logic — no Android dependencies; deterministic and unit-testable.
 */
@Singleton
class RenkoBarBuilder @Inject constructor() {

    /**
     * @param ticks Raw ticks (grouped/sorted chronologically internally).
     * @param brickSize Absolute price height of one brick (must be > 0).
     * @return Renko bricks in chronological order; empty when input is empty
     *         or [brickSize] is not positive.
     */
    fun build(ticks: List<Tick>, brickSize: Double): List<Candle> {
        if (ticks.isEmpty() || brickSize <= 0.0) return emptyList()

        val sorted = ticks.sortedBy { it.timestampMs }
        val bricks = ArrayList<Candle>()
        var lastClose = sorted.first().mid
        var volumeAccumulator = 0.0

        for (tick in sorted) {
            val price = tick.mid
            volumeAccumulator += tick.bidVolume + tick.askVolume

            // Up bricks: price has risen at least one brick above the last close.
            while (price - lastClose >= brickSize) {
                val open = lastClose
                val close = lastClose + brickSize
                bricks.add(
                    Candle(
                        timestamp = tick.timestampMs,
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

            // Down bricks: price has fallen at least one brick below the last close.
            while (lastClose - price >= brickSize) {
                val open = lastClose
                val close = lastClose - brickSize
                bricks.add(
                    Candle(
                        timestamp = tick.timestampMs,
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
}
