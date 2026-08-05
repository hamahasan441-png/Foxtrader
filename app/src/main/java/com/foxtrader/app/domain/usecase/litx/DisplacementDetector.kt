package com.foxtrader.app.domain.usecase.litx

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Displacement
import javax.inject.Inject
import kotlin.math.max

/**
 * Detects the most recent displacement leg — a strong, high-body impulse candle
 * (optionally leaving a Fair Value Gap) that marks institutional intent.
 *
 * Non-repainting: only closed bars are considered, and the FVG confirmation for
 * a candle at index `i` requires bar `i+1` to already exist.
 *
 * `PERF` Pure math; scans only a trailing [lookback] window.
 */
class DisplacementDetector @Inject constructor() {

    fun detectLatest(
        candles: List<Candle>,
        atrMultiple: Double = 1.2,
        bodyRatioMin: Double = 0.6,
        lookback: Int = 30,
    ): Displacement? {
        if (candles.size < MIN_BARS) return null

        // Average-range volatility proxy (same convention SmcDetector uses).
        val vol = candles.takeLast(VOL_WINDOW).map { it.range }.average().coerceAtLeast(1e-9)
        val start = max(1, candles.size - lookback)

        var best: Displacement? = null
        for (i in start until candles.size) {
            val c = candles[i]
            if (c.range <= 0.0) continue
            val bodyRatio = c.bodySize / c.range
            val atrMult = c.bodySize / vol
            if (bodyRatio < bodyRatioMin || atrMult < atrMultiple) continue

            val direction = if (c.isBullish) Direction.BULLISH else Direction.BEARISH
            // Three-candle FVG left by the impulse (needs the following bar).
            val hasFvg = when {
                i + 1 >= candles.size -> false
                direction == Direction.BULLISH -> candles[i + 1].low > candles[i - 1].high
                else -> candles[i + 1].high < candles[i - 1].low
            }

            if (best == null || atrMult > best.atrMultiple) {
                best = Displacement(
                    direction = direction,
                    startIndex = i,
                    endIndex = i,
                    startPrice = c.open,
                    endPrice = c.close,
                    bodyToRangeRatio = bodyRatio,
                    atrMultiple = atrMult,
                    hasFairValueGap = hasFvg,
                )
            }
        }
        return best
    }

    private companion object {
        const val MIN_BARS = 20
        const val VOL_WINDOW = 14
    }
}
