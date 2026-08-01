package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.tradepro.BarMode
import com.foxtrader.app.domain.model.tradepro.BarSpec
import javax.inject.Inject

/**
 * Builds TRADEPRO-style non-time bars (RANGE / REVERSAL) from a candle series.
 *
 * Fidelity note (same honest-proxy story as the order-flow seam): tick-accurate range/reversal bars
 * require a tick feed, which the app does not have. This builder is **close-based** — it walks the
 * sequence of candle closes and prints a new bar when the accumulated range (RANGE) or the retracement
 * from the running extreme (REVERSAL) crosses the configured tick threshold. It is deterministic and
 * self-consistent; when a real tick feed is added, only this builder changes.
 *
 * Input is always sanitized first, so NaN/gappy/out-of-order data cannot produce broken bars.
 */
class ReversalRangeBarBuilder @Inject constructor(
    private val sanitizer: CandleSanitizer,
) {

    fun build(candles: List<Candle>, spec: BarSpec): List<Candle> {
        val clean = sanitizer.sanitize(candles)
        if (spec.mode == BarMode.TIME || clean.size < 2 || spec.size <= 0.0) return clean
        return when (spec.mode) {
            BarMode.RANGE -> buildRange(clean, spec.size)
            BarMode.REVERSAL -> buildReversal(clean, spec.size)
            BarMode.TIME -> clean
        }
    }

    private fun buildRange(candles: List<Candle>, size: Double): List<Candle> {
        val out = ArrayList<Candle>()
        var open = candles.first().close
        var high = open
        var low = open
        var vol = 0.0
        var ts = candles.first().timestamp
        var touched = false

        for (c in candles) {
            val price = c.close
            if (price > high) high = price
            if (price < low) low = price
            vol += c.volume
            ts = c.timestamp
            touched = true
            if (high - low >= size) {
                out += Candle(ts, open, high, low, price, vol)
                open = price
                high = price
                low = price
                vol = 0.0
                touched = false
            }
        }
        if (touched && high > low) {
            out += Candle(ts, open, high, low, candles.last().close, vol)
        }
        return out
    }

    private fun buildReversal(candles: List<Candle>, size: Double): List<Candle> {
        val out = ArrayList<Candle>()
        var open = candles.first().close
        var high = open
        var low = open
        var vol = 0.0
        var ts = candles.first().timestamp
        var dir = 0 // 0 = undecided, 1 = up leg, -1 = down leg
        var extreme = open
        var touched = false

        for (c in candles) {
            val price = c.close
            if (price > high) high = price
            if (price < low) low = price
            vol += c.volume
            ts = c.timestamp
            touched = true

            when (dir) {
                0 -> when {
                    price >= open + size -> { dir = 1; extreme = price }
                    price <= open - size -> { dir = -1; extreme = price }
                }
                1 -> if (price > extreme) {
                    extreme = price
                } else if (price <= extreme - size) {
                    out += Candle(ts, open, high, low, price, vol)
                    open = price; high = price; low = price; vol = 0.0
                    dir = -1; extreme = price; touched = false
                }
                -1 -> if (price < extreme) {
                    extreme = price
                } else if (price >= extreme + size) {
                    out += Candle(ts, open, high, low, price, vol)
                    open = price; high = price; low = price; vol = 0.0
                    dir = 1; extreme = price; touched = false
                }
            }
        }
        if (touched && high > low) {
            out += Candle(ts, open, high, low, candles.last().close, vol)
        }
        return out
    }
}
