package com.foxtrader.app.domain.usecase.bars

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Tick
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds tick bars from a tick stream: one bar per fixed count of ticks.
 *
 * Every [ticksPerBar] consecutive ticks form one [Candle] (OHLC from
 * [Tick.mid]; volume = summed bid + ask). Because each bar contains the same
 * number of transactions, tick bars expose the *pace* of trading — bars print
 * faster when activity surges. A trailing group smaller than [ticksPerBar] is
 * emitted as the final (forming) bar so no ticks are dropped.
 *
 * Bars are timestamped at their first tick (open time).
 *
 * Pure domain logic — no Android dependencies; deterministic and unit-testable.
 */
@Singleton
class TickBarBuilder @Inject constructor() {

    /**
     * @param ticks Raw ticks (sorted chronologically internally).
     * @param ticksPerBar Number of ticks per bar (must be > 0).
     * @return Tick bars in chronological order; empty when input is empty or
     *         [ticksPerBar] is not positive.
     */
    fun build(ticks: List<Tick>, ticksPerBar: Int): List<Candle> {
        if (ticks.isEmpty() || ticksPerBar <= 0) return emptyList()

        val sorted = ticks.sortedBy { it.timestampMs }
        val bars = ArrayList<Candle>((sorted.size + ticksPerBar - 1) / ticksPerBar)

        var index = 0
        while (index < sorted.size) {
            val end = minOf(index + ticksPerBar, sorted.size)
            val group = sorted.subList(index, end)

            var high = group.first().mid
            var low = group.first().mid
            var volume = 0.0
            for (t in group) {
                val mid = t.mid
                if (mid > high) high = mid
                if (mid < low) low = mid
                volume += t.bidVolume + t.askVolume
            }
            bars.add(
                Candle(
                    timestamp = group.first().timestampMs,
                    open = group.first().mid,
                    high = high,
                    low = low,
                    close = group.last().mid,
                    volume = volume,
                )
            )
            index += ticksPerBar
        }

        return bars
    }
}
