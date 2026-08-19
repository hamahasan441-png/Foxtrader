package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stale-snapshot resume guards for the incremental indicator engines.
 *
 * Scenario under test: a rapid indicator toggle / timeframe switch leaves the
 * coordinator holding a `previous` result that is *shorter* than the resume
 * point. Each engine must fall back to a full recompute — never crash
 * (Ichimoku's arraycopy did) and never silently seed its recursion from a
 * zeroed prefix (SuperTrend / Bollinger / PSAR / VWAP / MACD did), which drew
 * visibly wrong overlay lines.
 */
class IncrementalResumeGuardTest {

    private val tol = 1e-9

    private fun series(count: Int): List<Candle> =
        (1..count).map { i ->
            val p = 100.0 + i * 0.5 + (i % 7) * 0.3
            Candle(1_000L + i * 60_000L, p - 0.4, p + 1.1, p - 1.2, p, 90.0 + i)
        }

    @Test
    fun `supertrend with a short stale previous matches a full recompute`() {
        val engine = SuperTrend()
        val candles = series(80)
        val stale = engine.calculate(candles.take(10))

        val incremental = engine.calculateIncremental(candles, stale, recomputeFrom = 70)
        val full = engine.calculate(candles)

        for (i in candles.indices) {
            assertEquals("values[$i]", full.values[i], incremental.values[i], tol)
            assertEquals("direction[$i]", full.direction[i], incremental.direction[i])
        }
    }

    @Test
    fun `bollinger with a short stale previous matches a full recompute`() {
        val engine = BollingerBands()
        val candles = series(80)
        val stale = engine.calculate(candles.take(10))

        val incremental = engine.calculateIncremental(candles, stale, recomputeFrom = 70)
        val full = engine.calculate(candles)

        for (i in candles.indices) {
            assertEquals("middle[$i]", full.middle[i], incremental.middle[i], tol)
            assertEquals("upper[$i]", full.upper[i], incremental.upper[i], tol)
            assertEquals("lower[$i]", full.lower[i], incremental.lower[i], tol)
        }
    }

    @Test
    fun `parabolic sar with a short stale previous matches a full recompute`() {
        val engine = ParabolicSar()
        val candles = series(80)
        val stale = engine.calculate(candles.take(10))

        val incremental = engine.calculateIncremental(candles, stale, recomputeFrom = 70)
        val full = engine.calculate(candles)

        for (i in candles.indices) {
            assertEquals("sar[$i]", full.sar[i], incremental.sar[i], tol)
            assertEquals("isUptrend[$i]", full.isUptrend[i], incremental.isUptrend[i])
        }
    }

    @Test
    fun `vwap with a short stale previous matches a full recompute`() {
        val candles = series(80)
        val stale = TechnicalIndicators.calculateVWAP(candles.take(10))

        val incremental = TechnicalIndicators.calculateVWAPIncremental(candles, stale, recomputeFrom = 70)
        val full = TechnicalIndicators.calculateVWAP(candles)

        for (i in candles.indices) {
            assertEquals("vwap[$i]", full[i], incremental[i], tol)
        }
    }

    @Test
    fun `macd with a short stale previous matches a full recompute`() {
        val candles = series(80)
        val stale = TechnicalIndicators.calculateMACD(candles.take(10))

        val incremental = TechnicalIndicators.calculateMACDIncremental(candles, stale, recomputeFrom = 70)
        val full = TechnicalIndicators.calculateMACD(candles)

        for (i in candles.indices) {
            assertEquals("macd[$i]", full.macd[i], incremental.macd[i], tol)
            assertEquals("signal[$i]", full.signal[i], incremental.signal[i], tol)
            assertEquals("histogram[$i]", full.histogram[i], incremental.histogram[i], tol)
        }
    }

    @Test
    fun `atr with a short stale previous matches a full recompute`() {
        val candles = series(80)
        val stale = TechnicalIndicators.calculateATR(candles.take(10))

        val incremental = TechnicalIndicators.calculateATRIncremental(
            candles, period = 14, previous = stale, recomputeFrom = 70,
        )
        val full = TechnicalIndicators.calculateATR(candles)

        for (i in candles.indices) {
            assertEquals("atr[$i]", full[i], incremental[i], tol)
        }
    }
}
