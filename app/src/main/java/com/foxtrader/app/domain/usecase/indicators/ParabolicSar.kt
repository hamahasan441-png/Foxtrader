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
    )

    fun calculate(
        candles: List<Candle>,
        accelerationStart: Double = 0.02,
        accelerationStep: Double = 0.02,
        accelerationMax: Double = 0.2,
    ): SarResult {
        val n = candles.size
        val sar = DoubleArray(n)
        val up = BooleanArray(n)
        if (n < 2) return SarResult(sar, up)

        var uptrend = candles[1].close >= candles[0].close
        var af = accelerationStart
        var ep = if (uptrend) candles[0].high else candles[0].low
        sar[0] = if (uptrend) candles[0].low else candles[0].high
        up[0] = uptrend

        for (i in 1 until n) {
            val prevSar = sar[i - 1]
            var current = prevSar + af * (ep - prevSar)

            if (uptrend) {
                // SAR can't be above the prior two lows
                current = min(current, candles[i - 1].low)
                if (i >= 2) current = min(current, candles[i - 2].low)
                if (candles[i].low < current) {
                    // Reversal to downtrend
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
        }
        return SarResult(sar, up)
    }
}
