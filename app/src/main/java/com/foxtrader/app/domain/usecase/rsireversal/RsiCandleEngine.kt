package com.foxtrader.app.domain.usecase.rsireversal

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import com.foxtrader.app.domain.usecase.rsireversal.model.RsiCandle

/**
 * RSI Orderflow Candle engine (§3).
 *
 * Runs four independent Wilder RSI series — one over the bar opens, highs, lows
 * and closes — and assembles them into a candle per bar:
 *
 * ```
 * RSI_Open  = RSI(open)
 * RSI_High  = max(all four)
 * RSI_Low   = min(all four)
 * RSI_Close = RSI(close)
 * ```
 *
 * Taking the extremes across all four series rather than from RSI(high)/RSI(low)
 * alone is what the specification asks for and is also what keeps the candle
 * well-formed: RSI is not monotonic in its input, so RSI(high) is not reliably
 * the largest of the four and a naive assignment would produce candles whose
 * body escapes its own wicks.
 *
 * This is the calculation layer. Smoothing and Heikin-Ashi presentation modes
 * belong to the renderer and must never reach structure or entry logic (§3.3).
 */
object RsiCandleEngine {

    /** Build the RSI candle series for [candles]. Empty input yields empty output. */
    fun calculate(candles: List<Candle>, rsiLength: Int): List<RsiCandle> {
        if (candles.isEmpty()) return emptyList()

        val size = candles.size
        val opens = DoubleArray(size) { candles[it].open }
        val highs = DoubleArray(size) { candles[it].high }
        val lows = DoubleArray(size) { candles[it].low }
        val closes = DoubleArray(size) { candles[it].close }

        val rsiOpen = TechnicalIndicators.calculateRsiSeries(opens, rsiLength)
        val rsiHigh = TechnicalIndicators.calculateRsiSeries(highs, rsiLength)
        val rsiLow = TechnicalIndicators.calculateRsiSeries(lows, rsiLength)
        val rsiClose = TechnicalIndicators.calculateRsiSeries(closes, rsiLength)

        return ArrayList<RsiCandle>(size).apply {
            for (i in 0 until size) {
                val o = sanitize(rsiOpen[i])
                val h = sanitize(rsiHigh[i])
                val l = sanitize(rsiLow[i])
                val c = sanitize(rsiClose[i])
                add(
                    RsiCandle(
                        index = i,
                        timestamp = candles[i].timestamp,
                        open = o,
                        high = maxOf(o, h, l, c),
                        low = minOf(o, h, l, c),
                        close = c,
                    )
                )
            }
        }
    }

    /**
     * Keep the study defined on degenerate input.
     *
     * A non-finite RSI value can only arise from a malformed bar upstream. It
     * resolves to the neutral midline rather than propagating NaN through pivot
     * comparisons, where it would silently poison every later comparison.
     */
    private fun sanitize(value: Double): Double =
        if (value.isFinite()) value.coerceIn(0.0, 100.0) else 50.0
}
