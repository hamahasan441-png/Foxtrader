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

    /**
     * Smallest legal look-back for any period-based indicator.
     *
     * Periods reach this layer from user-editable surfaces (the plugin SDK
     * reads `params["period"]` straight from a script, indicator settings are
     * user-editable). A zero or negative period previously produced either a
     * division by zero (silent NaN poisoning every downstream signal) or a
     * negative array index (hard crash). Clamping once, here, keeps every
     * indicator total: bad configuration degrades to a degenerate-but-valid
     * series instead of taking the chart — or the whole app — down.
     */
    private const val MIN_PERIOD = 1

    private fun sanitizePeriod(period: Int): Int = if (period < MIN_PERIOD) MIN_PERIOD else period

    // ========================================================================
    // MOVING AVERAGES
    // ========================================================================

    /** Exponential Moving Average */
    fun calculateEMA(candles: List<Candle>, period: Int): DoubleArray =
        calculateEMAIncremental(candles, period, previous = null, recomputeFrom = 0)

    /**
     * Incremental EMA recomputation.
     *
     * Reuses [previous] values before [recomputeFrom] when possible, then
     * resumes the recursive EMA calculation from that anchor onward.
     */
    fun calculateEMAIncremental(
        candles: List<Candle>,
        period: Int,
        previous: DoubleArray?,
        recomputeFrom: Int,
    ): DoubleArray {
        val ema = DoubleArray(candles.size)
        if (candles.isEmpty()) return ema
        // period == -1 makes k = 2/0 = Infinity, and every subsequent value NaN.
        val safePeriod = sanitizePeriod(period)
        val k = 2.0 / (safePeriod + 1)
        val start = recomputeFrom.coerceIn(0, candles.lastIndex)

        if (previous != null && previous.size >= start && start > 0) {
            System.arraycopy(previous, 0, ema, 0, start)
            ema[start - 1] = previous[start - 1]
            for (i in start until candles.size) {
                ema[i] = candles[i].close * k + ema[i - 1] * (1 - k)
            }
        } else {
            ema[0] = candles[0].close
            for (i in 1 until candles.size) {
                ema[i] = candles[i].close * k + ema[i - 1] * (1 - k)
            }
        }
        return ema
    }

    /** Simple Moving Average */
    fun calculateSMA(candles: List<Candle>, period: Int): DoubleArray {
        val sma = DoubleArray(candles.size)
        // A non-positive period both divides by zero (NaN) and indexes
        // candles[i - period] past the end of the list (IndexOutOfBounds).
        val safePeriod = sanitizePeriod(period)
        var sum = 0.0
        for (i in candles.indices) {
            sum += candles[i].close
            if (i >= safePeriod) sum -= candles[i - safePeriod].close
            sma[i] = if (i >= safePeriod - 1) sum / safePeriod else sum / (i + 1)
        }
        return sma
    }

    // ========================================================================
    // VWAP — Volume Weighted Average Price (session-anchored, resets daily)
    // ========================================================================

    fun calculateVWAP(candles: List<Candle>): DoubleArray =
        calculateVWAPIncremental(candles, previous = null, recomputeFrom = 0)

    /**
     * Incremental VWAP recomputation.
     *
     * Because VWAP resets at the UTC day boundary, recomputation resumes from
     * the start of the day containing [recomputeFrom]. Earlier completed days
     * are copied from [previous] when available.
     */
    fun calculateVWAPIncremental(
        candles: List<Candle>,
        previous: DoubleArray?,
        recomputeFrom: Int,
    ): DoubleArray {
        val vwap = DoubleArray(candles.size)
        if (candles.isEmpty()) return vwap

        val requestedStart = recomputeFrom.coerceIn(0, candles.lastIndex)
        val start = findUtcDayStartIndex(candles, requestedStart)
        var cumulativeTPV = 0.0
        var cumulativeVolume = 0.0
        var currentDay = -1L

        if (previous != null && previous.size >= start && start > 0) {
            System.arraycopy(previous, 0, vwap, 0, start)
        }

        for (i in start until candles.size) {
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

    fun calculateADX(candles: List<Candle>, period: Int = 14): ADXResult =
        calculateADXIncremental(candles, period, previous = null, recomputeFrom = 0)

    fun calculateADXIncremental(
        candles: List<Candle>,
        period: Int = 14,
        previous: ADXResult?,
        recomputeFrom: Int,
    ): ADXResult {
        val len = candles.size
        val adx = DoubleArray(len)
        val plusDI = DoubleArray(len)
        val minusDI = DoubleArray(len)

        // Clamp before the `len < period * 2` guard: with period <= 0 that
        // guard passes for any series and the seeding loop below then reads
        // tr[-1] / writes adx[-1].
        @Suppress("NAME_SHADOWING") val period = sanitizePeriod(period)
        if (len < period * 2) return ADXResult(adx, plusDI, minusDI)

        val startIndex = if (previous != null && recomputeFrom > 0) max(0, recomputeFrom - period * 2) else 0
        if (previous != null && startIndex > 0) {
            System.arraycopy(previous.adx, 0, adx, 0, minOf(startIndex, previous.adx.size))
            System.arraycopy(previous.plusDI, 0, plusDI, 0, minOf(startIndex, previous.plusDI.size))
            System.arraycopy(previous.minusDI, 0, minusDI, 0, minOf(startIndex, previous.minusDI.size))
        }

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
        // With period <= 0 the size guard passes and the seed loop reads
        // candles[i - 1] at i = 0 (or writes rsi[period] at a negative index).
        @Suppress("NAME_SHADOWING") val period = sanitizePeriod(period)
        if (candles.size < period + 1) return rsi

        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val change = candles[i].close - candles[i - 1].close
            if (change > 0) avgGain += change else avgLoss += abs(change)
        }
        avgGain /= period
        avgLoss /= period

        // Emit the first RSI value at index [period] from the seed averages.
        // Previously this bar was skipped (the loop began at period + 1), leaving
        // rsi[period] stuck at the 50.0 default — one incorrect bar that could
        // suppress a genuine oversold/overbought signal on that candle.
        rsi[period] = run {
            val rs = if (avgLoss > 0) avgGain / avgLoss else 100.0
            100.0 - 100.0 / (1.0 + rs)
        }

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
        val fastEma: DoubleArray,
        val slowEma: DoubleArray,
    )

    fun calculateMACD(candles: List<Candle>, fast: Int = 12, slow: Int = 26, signalPeriod: Int = 9): MACDResult =
        calculateMACDIncremental(candles, previous = null, recomputeFrom = 0, fast = fast, slow = slow, signalPeriod = signalPeriod)

    fun calculateMACDIncremental(
        candles: List<Candle>,
        previous: MACDResult?,
        recomputeFrom: Int,
        fast: Int = 12,
        slow: Int = 26,
        signalPeriod: Int = 9,
    ): MACDResult {
        val emaFast = calculateEMAIncremental(candles, fast, previous?.fastEma, recomputeFrom)
        val emaSlow = calculateEMAIncremental(candles, slow, previous?.slowEma, recomputeFrom)
        val macdLine = DoubleArray(candles.size) { emaFast[it] - emaSlow[it] }

        val signalLine = DoubleArray(candles.size)
        // signalPeriod == -1 makes k Infinity and NaNs the entire signal line.
        val k = 2.0 / (sanitizePeriod(signalPeriod) + 1)
        val startIndex = if (previous != null && recomputeFrom > 0) max(0, recomputeFrom - 1) else 0
        if (previous != null && startIndex > 0 && previous.signal.size >= startIndex) {
            System.arraycopy(previous.signal, 0, signalLine, 0, startIndex)
        }
        if (candles.isNotEmpty()) {
            if (startIndex == 0) signalLine[0] = macdLine[0]
            for (i in max(1, startIndex) until macdLine.size) {
                signalLine[i] = macdLine[i] * k + signalLine[i - 1] * (1 - k)
            }
        }

        val histogram = DoubleArray(candles.size) { macdLine[it] - signalLine[it] }
        return MACDResult(macdLine, signalLine, histogram, emaFast, emaSlow)
    }

    // ========================================================================
    // ATR — Average True Range
    // ========================================================================

    fun calculateATR(candles: List<Candle>, period: Int = 14): DoubleArray =
        calculateATRIncremental(candles, period, previous = null, recomputeFrom = 0)

    fun calculateATRIncremental(
        candles: List<Candle>,
        period: Int = 14,
        previous: DoubleArray?,
        recomputeFrom: Int,
    ): DoubleArray {
        val atr = DoubleArray(candles.size)
        if (candles.size < 2) return atr

        // period <= 0 makes `atr[period - 1]` a negative index and divides the
        // Wilder smoothing by zero. ATR feeds stop-loss distances and position
        // sizing, so a NaN here would silently size a real order.
        @Suppress("NAME_SHADOWING") val period = sanitizePeriod(period)

        val tr = DoubleArray(candles.size)
        tr[0] = candles[0].range
        for (i in 1 until candles.size) {
            val high = candles[i].high
            val low = candles[i].low
            val prevClose = candles[i - 1].close
            tr[i] = maxOf(high - low, abs(high - prevClose), abs(low - prevClose))
        }

        if (candles.size >= period) {
            val startIndex = if (previous != null && recomputeFrom > 0) max(0, recomputeFrom - 1) else 0
            if (previous != null && startIndex > 0 && previous.size >= startIndex) {
                System.arraycopy(previous, 0, atr, 0, startIndex)
            }
            if (startIndex <= period - 1) {
                var sum = 0.0
                for (i in 0 until period) sum += tr[i]
                atr[period - 1] = sum / period
            }
            val firstComputed = max(period, startIndex)
            for (i in firstComputed until candles.size) {
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
        @Suppress("NAME_SHADOWING") val period = sanitizePeriod(period)
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
        // A negative period would start the loop at a negative index and read
        // candles[i - period] beyond the last element.
        @Suppress("NAME_SHADOWING") val period = sanitizePeriod(period)
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
        // A zero close (malformed provider bar, or a delisted/halted symbol
        // padded with zeros) makes the percentage return 0/0 = NaN, and a
        // single NaN propagates through mean/variance to poison the whole
        // series. Volatility feeds stop distances and volatility-based position
        // sizing, so it must never return a non-finite number.
        val returns = ArrayList<Double>(candles.size - 1)
        for (i in 0 until candles.size - 1) {
            val prevClose = candles[i].close
            if (prevClose == 0.0) continue
            val r = (candles[i + 1].close - prevClose) / prevClose
            if (r.isFinite()) returns += r
        }
        if (returns.isEmpty()) return 0.0
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / returns.size
        val result = sqrt(variance) * candles.last().close
        return if (result.isFinite()) result else 0.0
    }

    private fun findUtcDayStartIndex(candles: List<Candle>, index: Int): Int {
        val targetDay = candles[index].timestamp / 86_400_000L
        var cursor = index
        while (cursor > 0 && candles[cursor - 1].timestamp / 86_400_000L == targetDay) {
            cursor--
        }
        return cursor
    }
}
