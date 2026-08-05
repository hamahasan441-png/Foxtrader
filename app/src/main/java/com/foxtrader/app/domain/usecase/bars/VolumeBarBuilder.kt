package com.foxtrader.app.domain.usecase.bars

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Tick
import kotlin.math.max
import kotlin.math.min
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds volume bars from a tick stream.
 *
 * A new bar is printed once the accumulated traded volume (bid + ask) reaches
 * [volumePerBar]. The tick that crosses the threshold closes the current bar
 * (its volume is counted in full). Volume bars normalise for activity: each bar
 * represents the same amount of business, so bar cadence speeds up in active
 * markets and slows in quiet ones.
 *
 * OHLC is built from [Tick.mid]; the bar is timestamped at its first tick
 * (open time). A trailing, not-yet-complete bar is emitted as the final
 * (forming) bar so no ticks are dropped.
 *
 * Pure domain logic — no Android dependencies; deterministic and unit-testable.
 */
@Singleton
class VolumeBarBuilder @Inject constructor() {

    /**
     * @param ticks Raw ticks (sorted chronologically internally).
     * @param volumePerBar Traded volume that closes a bar (must be > 0).
     * @return Volume bars in chronological order; empty when input is empty or
     *         [volumePerBar] is not positive.
     */
    fun build(ticks: List<Tick>, volumePerBar: Double): List<Candle> {
        if (ticks.isEmpty() || volumePerBar <= 0.0) return emptyList()

        val sorted = ticks.sortedBy { it.timestampMs }
        val bars = ArrayList<Candle>()

        var open = 0.0
        var high = 0.0
        var low = 0.0
        var close = 0.0
        var volume = 0.0
        var openTs = 0L
        var started = false

        for (tick in sorted) {
            val mid = tick.mid
            if (!started) {
                open = mid
                high = mid
                low = mid
                openTs = tick.timestampMs
                volume = 0.0
                started = true
            }
            high = max(high, mid)
            low = min(low, mid)
            close = mid
            volume += tick.bidVolume + tick.askVolume

            if (volume >= volumePerBar) {
                bars.add(Candle(openTs, open, high, low, close, volume))
                started = false
            }
        }

        // Trailing partial bar (still forming) — keep so no ticks are lost.
        if (started) {
            bars.add(Candle(openTs, open, high, low, close, volume))
        }

        return bars
    }
}
