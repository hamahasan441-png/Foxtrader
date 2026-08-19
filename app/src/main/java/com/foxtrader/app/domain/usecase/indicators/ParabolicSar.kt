package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * Parabolic SAR (Stop and Reverse) — trailing stop / trend reversal indicator.
 *
 * Places dots above (downtrend) or below (uptrend) price. When price crosses
 * the SAR, the trend flips. Acceleration factor increases with new extremes.
 *
 * Non-repainting: each SAR depends only on prior bars.
 */
class ParabolicSar @Inject constructor() {

    data class SarResult(
        val sar: DoubleArray,
        val isUptrend: BooleanArray,
        val accelerationFactor: DoubleArray,
        val extremePoint: DoubleArray,
    )

    fun calculate(
        candles: List<Candle>,
        accelerationStart: Double = 0.02,
        accelerationStep: Double = 0.02,
        accelerationMax: Double = 0.2,
    ): SarResult = calculateIncremental(
        candles = candles,
        previous = null,
        recomputeFrom = 0,
        accelerationStart = accelerationStart,
        accelerationStep = accelerationStep,
        accelerationMax = accelerationMax,
    )

    fun calculateIncremental(
        candles: List<Candle>,
        previous: SarResult?,
        recomputeFrom: Int,
        accelerationStart: Double = 0.02,
        accelerationStep: Double = 0.02,
        accelerationMax: Double = 0.2,
    ): SarResult {
        val n = candles.size
        val sar = DoubleArray(n)
        val up = BooleanArray(n)
        val afSeries = DoubleArray(n)
        val epSeries = DoubleArray(n)
        if (n < 2) return SarResult(sar, up, afSeries, epSeries)

        val requestedStart = if (previous != null && recomputeFrom > 0) max(0, recomputeFrom - 1) else 0
        // `SAFETY` The seed reads previous[startIndex - 1] below, so resuming is
        // only legal when the previous snapshot actually covers the resume
        // point. A stale/short snapshot (rapid toggle or timeframe race) must
        // fall back to a full recompute instead of indexing out of bounds.
        val canReuse = previous != null &&
            requestedStart > 0 &&
            previous.sar.size >= requestedStart &&
            previous.isUptrend.size >= requestedStart &&
            previous.accelerationFactor.size >= requestedStart &&
            previous.extremePoint.size >= requestedStart
        val startIndex = if (canReuse) requestedStart else 0
        if (canReuse && previous != null) {
            System.arraycopy(previous.sar, 0, sar, 0, startIndex)
            System.arraycopy(previous.isUptrend, 0, up, 0, startIndex)
            System.arraycopy(previous.accelerationFactor, 0, afSeries, 0, startIndex)
            System.arraycopy(previous.extremePoint, 0, epSeries, 0, startIndex)
        }

        var uptrend = if (canReuse && previous != null) previous.isUptrend[startIndex - 1]
        else candles[1].close >= candles[0].close
        var af = if (canReuse && previous != null) previous.accelerationFactor[startIndex - 1]
        else accelerationStart
        var ep = if (canReuse && previous != null) previous.extremePoint[startIndex - 1]
        else if (uptrend) candles[0].high else candles[0].low

        if (startIndex == 0) {
            sar[0] = if (uptrend) candles[0].low else candles[0].high
            up[0] = uptrend
            afSeries[0] = af
            epSeries[0] = ep
        }

        for (i in max(1, startIndex) until n) {
            val prevSar = sar[i - 1]
            var current = prevSar + af * (ep - prevSar)

            if (uptrend) {
                current = min(current, candles[i - 1].low)
                if (i >= 2) current = min(current, candles[i - 2].low)
                if (candles[i].low < current) {
                    uptrend = false
                    current = ep
                    ep = candles[i].low
                    af = accelerationStart
                } else if (candles[i].high > ep) {
                    ep = candles[i].high
                    af = min(af + accelerationStep, accelerationMax)
                }
            } else {
                current = max(current, candles[i - 1].high)
                if (i >= 2) current = max(current, candles[i - 2].high)
                if (candles[i].high > current) {
                    uptrend = true
                    current = ep
                    ep = candles[i].high
                    af = accelerationStart
                } else if (candles[i].low < ep) {
                    ep = candles[i].low
                    af = min(af + accelerationStep, accelerationMax)
                }
            }
            sar[i] = current
            up[i] = uptrend
            afSeries[i] = af
            epSeries[i] = ep
        }
        return SarResult(sar, up, afSeries, epSeries)
    }
}
