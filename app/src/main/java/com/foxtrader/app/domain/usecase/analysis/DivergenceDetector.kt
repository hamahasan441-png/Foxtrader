package com.foxtrader.app.domain.usecase.analysis

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import javax.inject.Inject
import kotlin.math.max

/**
 * Divergence Detector — finds divergences between price and an oscillator (RSI/MACD).
 *
 * Regular divergence (reversal):
 * - Bullish: price makes lower low, oscillator makes higher low
 * - Bearish: price makes higher high, oscillator makes lower high
 *
 * Hidden divergence (continuation):
 * - Bullish: price makes higher low, oscillator makes lower low
 * - Bearish: price makes lower high, oscillator makes higher high
 *
 * Non-repainting: only compares confirmed swing points.
 */
class DivergenceDetector @Inject constructor() {

    data class Divergence(
        val type: DivergenceType,
        val startIndex: Int,
        val endIndex: Int,
        val startPrice: Double,
        val endPrice: Double,
        val oscillatorName: String,
    )

    enum class DivergenceType { REGULAR_BULLISH, REGULAR_BEARISH, HIDDEN_BULLISH, HIDDEN_BEARISH }

    /**
     * Detect divergences using RSI as the oscillator.
     */
    fun detectRsiDivergences(
        candles: List<Candle>,
        rsiPeriod: Int = 14,
        swingLookback: Int = 5,
    ): List<Divergence> {
        if (candles.size < rsiPeriod + swingLookback * 2) return emptyList()
        val rsi = TechnicalIndicators.calculateRSI(candles, rsiPeriod)
        return detect(candles, rsi, "RSI", swingLookback)
    }

    /**
     * Detect divergences using MACD histogram as the oscillator.
     */
    fun detectMacdDivergences(
        candles: List<Candle>,
        swingLookback: Int = 5,
    ): List<Divergence> {
        if (candles.size < 40) return emptyList()
        val macd = TechnicalIndicators.calculateMACD(candles)
        return detect(candles, macd.histogram, "MACD", swingLookback)
    }

    // ========================================================================
    // PRIVATE
    // ========================================================================

    private fun detect(
        candles: List<Candle>,
        osc: DoubleArray,
        oscName: String,
        lookback: Int,
    ): List<Divergence> {
        val result = mutableListOf<Divergence>()
        val swingHighs = findSwings(candles, lookback, isHigh = true)
        val swingLows = findSwings(candles, lookback, isHigh = false)

        // Compare consecutive swing highs for bearish divergence
        for (k in 1 until swingHighs.size) {
            val a = swingHighs[k - 1]
            val b = swingHighs[k]
            val priceHigherHigh = candles[b].high > candles[a].high
            val oscLowerHigh = osc[b] < osc[a]
            val priceLowerHigh = candles[b].high < candles[a].high
            val oscHigherHigh = osc[b] > osc[a]
            when {
                priceHigherHigh && oscLowerHigh -> result.add(
                    Divergence(DivergenceType.REGULAR_BEARISH, a, b, candles[a].high, candles[b].high, oscName)
                )
                priceLowerHigh && oscHigherHigh -> result.add(
                    Divergence(DivergenceType.HIDDEN_BEARISH, a, b, candles[a].high, candles[b].high, oscName)
                )
            }
        }

        // Compare consecutive swing lows for bullish divergence
        for (k in 1 until swingLows.size) {
            val a = swingLows[k - 1]
            val b = swingLows[k]
            val priceLowerLow = candles[b].low < candles[a].low
            val oscHigherLow = osc[b] > osc[a]
            val priceHigherLow = candles[b].low > candles[a].low
            val oscLowerLow = osc[b] < osc[a]
            when {
                priceLowerLow && oscHigherLow -> result.add(
                    Divergence(DivergenceType.REGULAR_BULLISH, a, b, candles[a].low, candles[b].low, oscName)
                )
                priceHigherLow && oscLowerLow -> result.add(
                    Divergence(DivergenceType.HIDDEN_BULLISH, a, b, candles[a].low, candles[b].low, oscName)
                )
            }
        }
        return result.sortedBy { it.endIndex }
    }

    private fun findSwings(candles: List<Candle>, lookback: Int, isHigh: Boolean): List<Int> {
        val swings = mutableListOf<Int>()
        for (i in lookback until candles.size - lookback) {
            val isSwing = if (isHigh) {
                (i - lookback until i).all { candles[it].high <= candles[i].high } &&
                    (i + 1..i + lookback).all { candles[it].high <= candles[i].high }
            } else {
                (i - lookback until i).all { candles[it].low >= candles[i].low } &&
                    (i + 1..i + lookback).all { candles[it].low >= candles[i].low }
            }
            if (isSwing) swings.add(i)
        }
        return swings
    }
}
