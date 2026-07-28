package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.analysis.FibonacciEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Phase 19-33 indicator/analysis engines.
 */
class NewIndicatorsTest {

    private val tol = 1e-4

    private fun series(prices: List<Double>): List<Candle> =
        prices.mapIndexed { i, p ->
            Candle(1_000L + i * 60_000L, p - 0.5, p + 1.0, p - 1.0, p, 100.0 + i)
        }

    private val up = series((1..60).map { 100.0 + it * 0.5 })

    // --- Pivot Points ---
    @Test fun `classic pivot is average of HLC`() {
        val p = PivotPoints().calculate(110.0, 90.0, 100.0, method = PivotPoints.Method.CLASSIC)
        assertEquals(100.0, p.pivot, tol)
        assertTrue(p.r1 > p.pivot)
        assertTrue(p.s1 < p.pivot)
    }

    @Test fun `fibonacci pivot levels ordered`() {
        val p = PivotPoints().calculate(110.0, 90.0, 105.0, method = PivotPoints.Method.FIBONACCI)
        assertTrue(p.r3 > p.r2 && p.r2 > p.r1 && p.r1 > p.pivot)
        assertTrue(p.pivot > p.s1 && p.s1 > p.s2 && p.s2 > p.s3)
    }

    // --- Bollinger Bands ---
    @Test fun `bollinger upper above lower and price near bands`() {
        val bb = BollingerBands().calculate(up, period = 20)
        val i = up.lastIndex
        assertTrue(bb.upper[i] > bb.middle[i])
        assertTrue(bb.middle[i] > bb.lower[i])
        assertTrue(bb.percentB[i] in -0.5..1.5)
    }

    @Test fun `incremental bollinger matches full recomputation`() {
        val engine = BollingerBands()
        val full = engine.calculate(up, period = 20)
        val partial = engine.calculate(up.take(40), period = 20)
        val incremental = engine.calculateIncremental(up, partial, recomputeFrom = 38, period = 20)
        for (i in full.middle.indices) {
            assertEquals(full.middle[i], incremental.middle[i], tol)
            assertEquals(full.upper[i], incremental.upper[i], tol)
            assertEquals(full.lower[i], incremental.lower[i], tol)
        }
    }

    // --- Stochastic ---
    @Test fun `stochastic in uptrend is elevated`() {
        val stoch = StochasticOscillator().calculate(up)
        assertTrue(stoch.percentK.last() > 50.0)
        assertTrue(stoch.percentK.all { it in 0.0..100.0 })
    }

    // --- SuperTrend ---
    @Test fun `supertrend bullish in uptrend`() {
        val st = SuperTrend().calculate(up)
        assertEquals(Direction.BULLISH, SuperTrend().currentTrend(st))
    }

    @Test fun `incremental supertrend matches full recomputation`() {
        val engine = SuperTrend()
        val full = engine.calculate(up)
        val partial = engine.calculate(up.take(40))
        val incremental = engine.calculateIncremental(up, partial, recomputeFrom = 38)
        for (i in full.values.indices) {
            assertEquals(full.values[i], incremental.values[i], tol)
            assertEquals(full.direction[i], incremental.direction[i])
        }
    }

    // --- Parabolic SAR ---
    @Test fun `parabolic sar produces values for each bar`() {
        val sar = ParabolicSar().calculate(up)
        assertEquals(up.size, sar.sar.size)
    }

    @Test fun `incremental parabolic sar matches full recomputation`() {
        val engine = ParabolicSar()
        val full = engine.calculate(up)
        val partial = engine.calculate(up.take(40))
        val incremental = engine.calculateIncremental(up, partial, recomputeFrom = 38)
        for (i in full.sar.indices) {
            assertEquals(full.sar[i], incremental.sar[i], tol)
            assertEquals(full.isUptrend[i], incremental.isUptrend[i])
        }
    }

    // --- Ichimoku ---
    @Test fun `ichimoku price above cloud in uptrend`() {
        val ich = IchimokuCloud()
        val result = ich.calculate(up)
        assertEquals(IchimokuCloud.CloudPosition.ABOVE, ich.cloudPosition(up, result))
    }

    @Test fun `incremental ichimoku matches full recomputation`() {
        val engine = IchimokuCloud()
        val full = engine.calculate(up)
        val partial = engine.calculate(up.take(40))
        val incremental = engine.calculateIncremental(up, partial, recomputeFrom = 38)
        for (i in full.tenkan.indices) {
            assertEquals(full.tenkan[i], incremental.tenkan[i], tol)
            assertEquals(full.kijun[i], incremental.kijun[i], tol)
            assertEquals(full.senkouA[i], incremental.senkouA[i], tol)
            assertEquals(full.senkouB[i], incremental.senkouB[i], tol)
        }
    }

    // --- Volume Indicators ---
    @Test fun `obv rises in uptrend`() {
        val obv = VolumeIndicators().obv(up)
        assertTrue(obv.last() > 0)
    }

    // --- Fibonacci ---
    @Test fun `fib retracements bracket the range`() {
        val fibs = FibonacciEngine().retracements(150.0, 100.0, Direction.BULLISH)
        assertEquals(150.0, fibs.first().price, tol) // 0%
        assertEquals(100.0, fibs.last().price, tol)  // 100%
    }

    // --- Channels ---
    @Test fun `donchian upper is highest high`() {
        val ch = ChannelIndicators().donchian(up, period = 10)
        val i = up.lastIndex
        val expectedHigh = up.subList(i - 9, i + 1).maxOf { it.high }
        assertEquals(expectedHigh, ch.upper[i], tol)
    }

    // --- Empty safety ---
    @Test fun `engines handle empty input without crashing`() {
        val empty = emptyList<Candle>()
        assertEquals(0, BollingerBands().calculate(empty).middle.size)
        assertEquals(0, StochasticOscillator().calculate(empty).percentK.size)
        assertEquals(0, SuperTrend().calculate(empty).values.size)
        assertEquals(0, ParabolicSar().calculate(empty).sar.size)
        assertEquals(0, VolumeIndicators().obv(empty).size)
    }
}
