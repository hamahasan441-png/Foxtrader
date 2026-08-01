package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import javax.inject.Inject

/**
 * Defensive input hygiene for the whole analysis/chart stack.
 *
 * Real feeds occasionally deliver garbage: NaN/Infinity prices, zero/negative prices, out-of-order or
 * duplicate timestamps, or OHLC where high < low. Any of these can crash a renderer or an indicator
 * (divide-by-zero, NaN propagation, IndexOutOfBounds). [sanitize] returns a clean, strictly
 * time-ordered series with self-consistent OHLC so downstream code can assume well-formed data.
 *
 * It also exposes cheap gap detection — weekend/session/daily gaps are normal for many instruments and
 * should be recognised rather than treated as errors.
 */
class CandleSanitizer @Inject constructor() {

    /** Drop malformed bars, enforce strictly increasing timestamps, and repair OHLC bounds. */
    fun sanitize(candles: List<Candle>): List<Candle> {
        if (candles.isEmpty()) return emptyList()
        val out = ArrayList<Candle>(candles.size)
        var lastTs = Long.MIN_VALUE
        for (c in candles) {
            if (!c.open.isFinite() || !c.high.isFinite() || !c.low.isFinite() || !c.close.isFinite()) continue
            if (c.open <= 0.0 || c.high <= 0.0 || c.low <= 0.0 || c.close <= 0.0) continue
            if (c.timestamp <= lastTs) continue // drop duplicate / out-of-order bars
            val hi = maxOf(c.high, c.open, c.close)
            val lo = minOf(c.low, c.open, c.close)
            if (hi < lo) continue
            val vol = if (c.volume.isFinite() && c.volume >= 0.0) c.volume else 0.0
            out += if (hi != c.high || lo != c.low || vol != c.volume) {
                c.copy(high = hi, low = lo, volume = vol)
            } else {
                c
            }
            lastTs = c.timestamp
        }
        return out
    }

    /**
     * Count "gaps" — spacings between consecutive bars larger than [factor] x the timeframe interval
     * (e.g. weekend/overnight gaps). Assumes the input is already time-ordered.
     */
    fun countGaps(candles: List<Candle>, timeframe: Timeframe, factor: Double = 2.0): Int {
        if (candles.size < 2) return 0
        val expected = timeframe.minutes.toLong() * 60_000L
        if (expected <= 0L) return 0
        val threshold = (expected * factor).toLong()
        var gaps = 0
        for (i in 1 until candles.size) {
            if (candles[i].timestamp - candles[i - 1].timestamp > threshold) gaps++
        }
        return gaps
    }
}
