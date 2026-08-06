package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import kotlin.math.max
import kotlin.math.min
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transforms a time-candle series into Heikin-Ashi ("average bar") candles.
 *
 * Heikin-Ashi smooths price to make trends and reversals easier to read while
 * keeping one output candle per input candle — same timestamp, same index, same
 * volume — so every index-based overlay (order blocks, FVG, structure, sessions)
 * remains perfectly aligned.
 *
 * Formulas (standard):
 * - haClose = (open + high + low + close) / 4
 * - haOpen  = first bar: (open + close) / 2; thereafter: (prevHaOpen + prevHaClose) / 2
 * - haHigh  = max(high, haOpen, haClose)
 * - haLow   = min(low,  haOpen, haClose)
 *
 * Pure domain logic — no Android dependencies; deterministic and unit-testable.
 */
@Singleton
class HeikinAshiTransformer @Inject constructor() {

    /** @return Heikin-Ashi candles (same size/timestamps as [candles]); empty in, empty out. */
    fun transform(candles: List<Candle>): List<Candle> {
        if (candles.isEmpty()) return emptyList()

        val out = ArrayList<Candle>(candles.size)
        var prevHaOpen = 0.0
        var prevHaClose = 0.0

        for (i in candles.indices) {
            val c = candles[i]
            val haClose = (c.open + c.high + c.low + c.close) / 4.0
            val haOpen = if (i == 0) (c.open + c.close) / 2.0 else (prevHaOpen + prevHaClose) / 2.0
            val haHigh = max(c.high, max(haOpen, haClose))
            val haLow = min(c.low, min(haOpen, haClose))

            out.add(
                Candle(
                    timestamp = c.timestamp,
                    open = haOpen,
                    high = haHigh,
                    low = haLow,
                    close = haClose,
                    volume = c.volume,
                )
            )
            prevHaOpen = haOpen
            prevHaClose = haClose
        }

        return out
    }
}
