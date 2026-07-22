package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pure-Kotlin technical indicator calculations.
 * No look-ahead bias — all indicators use only past/current data.
 * Thread-safe: no mutable state; every function is a pure transform.
 */
object TechnicalIndicators {

    // ========================================================================
    // MOVING AVERAGES
    // ========================================================================

    /** Exponential Moving Average */
    fun calculateEMA(candles: List<Candle>, period: Int): DoubleArray {
        val ema = DoubleArray(candles.size)
        if (candles.isEmpty()) return ema
        val k = 2.0 / (period + 1)
        ema[0] = candles[0].close
        for (i in 1 until candles.size) {
            ema[i] = candles[i].close * k + ema[i - 1] * (1 - k)
        }
        return ema
    }

    /** Simple Moving Average */
    fun calculateSMA(candles: List<Candle>, period: Int): DoubleArray {
        val sma = DoubleArray(candles.size)
        var sum = 0.0
        for (i in candles.indices) {
            sum += candles[i].close
            if (i >= period) sum -= candles[i - period].close
            sma[i] = if (i >= period - 1) sum / period else sum / (i + 1)
        }
        return sma
    }

    // ========================================================================
    // VWAP — Volume Weighted Average Price (session-anchored, resets daily)
    // ========================================================================

    fun calculateVWAP(candles: List<Candle>): DoubleArray {
        val vwap = DoubleArray(candles.size)
        var cumulativeTPV = 0.0
        var cumulativeVolume = 0.0
        var currentDay = -1L

        for (i in candles.indices) {
            val c = candles[i]
            val day = c.timestamp / 86_400_000L // UTC day boundary

            if (day != currentDay) {
                cumulativeTPV = 0.0
                cumulativeVolume = 0.0
                currentDay = day
            }

            val typicalPrice = (c.high + c.low + c.close) / 3.0
            val volume = if (c.volume > 0.0) c.volume else 1.0
            cumulativeTPV += typicalPrice * volume
            cumulativeVolume += volume

            vwap[i] = if (cumulativeVolume > 0.0) cumulativeTPV / cumulativeVolume else typicalPrice
        }
        return vwap
    }

    // ========================================================================
    // ADX — Average Directional Index (trend strength)
    // ========================================================================

    data class ADXResult(
        val adx: DoubleArray,
        val plusDI: DoubleArray,
        val minusDI: DoubleArray,
    )

    fun calculateADX(candles: List<Candle>, period: Int = 14): ADXResult {
        val len = candles.size
        val adx = DoubleArray(len)
        val plusDI = DoubleArray(len)
        val minusDI = DoubleArray(len)

        if (len < period * 2) return ADXResult(adx, plusDI, minusDI)

        val tr = DoubleArray(len)
        val plusDM = DoubleArray(len)
        val minusDM = DoubleArray(len)

        for (i in 1 until len) {
            val high = candles[i].high
            val low = candles[i].low
            val prevHigh = candles[i - 1].high
            val prevLow = candles[i - 1].low
            val prevClose = candles[i - 1].close

            tr[i] = maxOf(high - low, abs(high - prevClose), abs(low - prevClose))

            val upMove = high - prevHigh
            val downMove = prevLow - low
            plusDM[i] = if (upMove > downMove && upMove > 0) upMove else 0.0
            minusDM[i] = if (downMove > upMove && downMove > 0) downMove else 0.0
        }

        // Wilder's smoothing — initial sum
        var smoothedTR = 0.0
        var smoothedPlusDM = 0.0
        var smoothedMinusDM = 0.0
        for (i in 1..period) {
            smoothedTR += tr[i]
            smoothedPlusDM += plusDM[i]
            smoothedMinusDM += minusDM[i]
        }

        val dx = DoubleArray(len)
        for (i in period until len) {
            if (i > period) {
                smoothedTR = smoothedTR - smoothedTR / period + tr[i]
                smoothedPlusDM = smoothedPlusDM - smoothedPlusDM / period + plusDM[i]
                smoothedMinusDM = smoothedMinusDM - smoothedMinusDM / period + minusDM[i]
            }

            plusDI[i] = if (smoothedTR > 0) (smoothedPlusDM / smoothedTR) * 100 else 0.0
            minusDI[i] = if (smoothedTR > 0) (smoothedMinusDM / smoothedTR) * 100 else 0.0

            val diSum = plusDI[i] + minusDI[i]
            dx[i] = if (diSum > 0) (abs(plusDI[i] - minusDI[i]) / diSum) * 100 else 0.0
        }

        // ADX = smoothed DX
        var adxSum = 0.0
        for (i in period until minOf(period * 2, len)) adxSum += dx[i]
        if (period * 2 - 1 < len) adx[period * 2 - 1] = adxSum / period

        for (i in period * 2 until len) {
            adx[i] = (adx[i - 1] * (period - 1) + dx[i]) / period
        }

        return ADXResult(adx, plusDI, minusDI)
    }

    // ========================================================================
    // RSI — Relative Strength Index
    // ========================================================================

    fun calculateRSI(candles: List<Candle>, period: Int = 14): DoubleArray {
        val rsi = DoubleArray(candles.size) { 50.0 }
        if (candles.size < period + 1) return rsi

        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val change = candles[i].close - candles[i - 1].close
            if (change > 0) avgGain += change else avgLoss += abs(change)
        }
        avgGain /= period
        avgLoss /= period

        for (i in period + 1 until candles.size) {
            val change = candles[i].close - candles[i - 1].close
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) abs(change) else 0.0

            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period

            val rs = if (avgLoss > 0) avgGain / avgLoss else 100.0
            rsi[i] = 100.0 - 100.0 / (1.0 + rs)
        }
        return rsi
    }

    // ========================================================================
    // MACD — Moving Average Convergence Divergence
    // ========================================================================

    data class MACDResult(
        val macd: DoubleArray,
        val signal: DoubleArray,
        val histogram: DoubleArray,
    )

    fun calculateMACD(candles: List<Candle>, fast: Int = 12, slow: Int = 26, signalPeriod: Int = 9): MACDResult {
        val emaFast = calculateEMA(candles, fast)
        val emaSlow = calculateEMA(candles, slow)
        val macdLine = DoubleArray(candles.size) { emaFast[it] - emaSlow[it] }

        // Signal line = EMA of MACD
        val signalLine = DoubleArray(candles.size)
        val k = 2.0 / (signalPeriod + 1)
        signalLine[0] = macdLine[0]
        for (i in 1 until macdLine.size) {
            signalLine[i] = macdLine[i] * k + signalLine[i - 1] * (1 - k)
        }

        val histogram = DoubleArray(candles.size) { macdLine[it] - signalLine[it] }
        return MACDResult(macdLine, signalLine, histogram)
    }

    // ========================================================================
    // ATR — Average True Range
    // ========================================================================

    fun calculateATR(candles: List<Candle>, period: Int = 14): DoubleArray {
        val atr = DoubleArray(candles.size)
        if (candles.size < 2) return atr

        // True Range array
        val tr = DoubleArray(candles.size)
        tr[0] = candles[0].range
        for (i in 1 until candles.size) {
            val high = candles[i].high
            val low = candles[i].low
            val prevClose = candles[i - 1].close
            tr[i] = maxOf(high - low, abs(high - prevClose), abs(low - prevClose))
        }

        // First ATR = SMA of first `period` TR values
        if (candles.size >= period) {
            var sum = 0.0
            for (i in 0 until period) sum += tr[i]
            atr[period - 1] = sum / period

            // Subsequent = Wilder's smoothing
            for (i in period until candles.size) {
                atr[i] = (atr[i - 1] * (period - 1) + tr[i]) / period
            }
        }
        return atr
    }

    // ========================================================================
    // RELATIVE VOLUME
    // ========================================================================

    fun calculateRelativeVolume(candles: List<Candle>, period: Int = 20): DoubleArray {
        val relVol = DoubleArray(candles.size) { 1.0 }
        for (i in candles.indices) {
            val start = max(0, i - period)
            var sum = 0.0
            var count = 0
            for (j in start until i) {
                sum += candles[j].volume
                count++
            }
            val avgVol = if (count > 0) sum / count else candles[i].volume
            relVol[i] = if (avgVol > 0) candles[i].volume / avgVol else 1.0
        }
        return relVol
    }

    // ========================================================================
    // MOMENTUM — Rate of Change (%)
    // ========================================================================

    fun calculateMomentum(candles: List<Candle>, period: Int = 10): DoubleArray {
        val momentum = DoubleArray(candles.size)
        for (i in period until candles.size) {
            val prev = candles[i - period].close
            momentum[i] = if (prev != 0.0) ((candles[i].close - prev) / prev) * 100.0 else 0.0
        }
        return momentum
    }

    // ========================================================================
    // VOLATILITY — Standard deviation of returns in price units
    // ========================================================================

    fun calculateVolatility(candles: List<Candle>): Double {
        if (candles.size < 2) return 0.0
        val returns = DoubleArray(candles.size - 1) { i ->
            (candles[i + 1].close - candles[i].close) / candles[i].close
        }
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / returns.size
        return sqrt(variance) * candles.last().close
    }
}
