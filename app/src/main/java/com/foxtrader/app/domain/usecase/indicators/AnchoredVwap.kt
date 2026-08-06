package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Anchored VWAP output.
 *
 * [vwap] is the volume-weighted average price accumulated from [anchorIndex]
 * onward; [upperBand]/[lowerBand] are symmetric volume-weighted standard
 * deviation bands at [bandMultiplier] sigma. All three arrays are candle-length;
 * entries **before** [anchorIndex] are [Double.NaN] (undefined) so renderers can
 * skip them cleanly.
 */
data class AnchoredVwapResult(
    val anchorIndex: Int,
    val vwap: DoubleArray,
    val upperBand: DoubleArray,
    val lowerBand: DoubleArray,
    val bandMultiplier: Double,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnchoredVwapResult) return false
        return anchorIndex == other.anchorIndex &&
            bandMultiplier == other.bandMultiplier &&
            vwap.contentEquals(other.vwap) &&
            upperBand.contentEquals(other.upperBand) &&
            lowerBand.contentEquals(other.lowerBand)
    }

    override fun hashCode(): Int {
        var h = anchorIndex
        h = 31 * h + bandMultiplier.hashCode()
        h = 31 * h + vwap.contentHashCode()
        h = 31 * h + upperBand.contentHashCode()
        h = 31 * h + lowerBand.contentHashCode()
        return h
    }
}

/**
 * Anchored VWAP — a professional-grade trading tool.
 *
 * Unlike the session VWAP in [TechnicalIndicators] (which resets every UTC day),
 * an Anchored VWAP accumulates from a single chosen bar (a swing high/low, a
 * session open, a news event, …) and never resets, so it tracks the average
 * price paid *since that event*. The standard-deviation bands mark statistically
 * stretched distances from that average.
 *
 * Pure and stateless (mirrors the [TechnicalIndicators] object style): same
 * inputs → same outputs, no look-ahead (index `i` only uses bars `anchor..i`),
 * safe to call from any dispatcher.
 *
 * Band math uses the running volume-weighted variance:
 * `variance_i = (Σ v·tp²)/(Σ v) − mean_i²`, which is numerically the weighted
 * second moment about the current VWAP — the standard anchored-VWAP band model.
 */
object AnchoredVwap {

    const val DEFAULT_BAND_MULTIPLIER = 2.0
    const val DEFAULT_LOOKBACK = 120

    /**
     * Compute the Anchored VWAP and its bands from [anchorIndex].
     *
     * @param anchorIndex clamped into candle bounds; values before it are NaN.
     * @param bandMultiplier sigma multiple for the bands (default 2σ).
     */
    fun calculate(
        candles: List<Candle>,
        anchorIndex: Int,
        bandMultiplier: Double = DEFAULT_BAND_MULTIPLIER,
    ): AnchoredVwapResult {
        val n = candles.size
        val vwap = DoubleArray(n) { Double.NaN }
        val upper = DoubleArray(n) { Double.NaN }
        val lower = DoubleArray(n) { Double.NaN }
        if (n == 0) return AnchoredVwapResult(0, vwap, upper, lower, bandMultiplier)

        val anchor = anchorIndex.coerceIn(0, n - 1)
        var cumV = 0.0
        var cumVtp = 0.0
        var cumVtp2 = 0.0

        for (i in anchor until n) {
            val c = candles[i]
            val typicalPrice = (c.high + c.low + c.close) / 3.0
            val volume = if (c.volume > 0.0) c.volume else 1.0
            cumV += volume
            cumVtp += typicalPrice * volume
            cumVtp2 += typicalPrice * typicalPrice * volume

            val mean = cumVtp / cumV
            val variance = (cumVtp2 / cumV - mean * mean).coerceAtLeast(0.0)
            val sd = sqrt(variance)
            vwap[i] = mean
            upper[i] = mean + bandMultiplier * sd
            lower[i] = mean - bandMultiplier * sd
        }
        return AnchoredVwapResult(anchor, vwap, upper, lower, bandMultiplier)
    }

    /**
     * Pick a sensible automatic anchor: the most significant swing extreme
     * within the last [lookback] bars — i.e. the origin of the current leg.
     *
     * The earliest occurrence of the window's highest high and lowest low are
     * candidates; the one price has travelled *furthest* from (measured against
     * the latest close) is chosen, because that extreme anchors the dominant
     * move currently in play.
     */
    fun autoAnchorIndex(candles: List<Candle>, lookback: Int = DEFAULT_LOOKBACK): Int {
        val n = candles.size
        if (n == 0) return 0
        val start = (n - lookback).coerceAtLeast(0)
        var hiIdx = start
        var loIdx = start
        for (i in start until n) {
            // Strict comparisons keep the EARLIEST extreme (the leg's origin).
            if (candles[i].high > candles[hiIdx].high) hiIdx = i
            if (candles[i].low < candles[loIdx].low) loIdx = i
        }
        val last = candles[n - 1].close
        val highDistance = abs(candles[hiIdx].high - last)
        val lowDistance = abs(candles[loIdx].low - last)
        return if (highDistance >= lowDistance) hiIdx else loIdx
    }
}
