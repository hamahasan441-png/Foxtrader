package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrelationEngineTest {

    private val engine = CorrelationEngine()

    private fun candles(closes: List<Double>): List<Candle> =
        closes.mapIndexed { i, c -> Candle(i * 60_000L, c, c + 0.5, c - 0.5, c, 100.0) }

    /** A rising series with small oscillations. */
    private fun base(n: Int = 60): List<Double> =
        (0 until n).map { 100.0 + it * 0.5 + kotlin.math.sin(it / 3.0) * 2.0 }

    @Test
    fun `fewer than two symbols returns empty`() {
        val m = engine.compute(mapOf("EURUSD" to candles(base())))
        assertTrue(m.symbols.isEmpty())
    }

    @Test
    fun `identical return series correlate at 1`() {
        val closes = base()
        val m = engine.compute(mapOf("A" to candles(closes), "B" to candles(closes)))
        assertEquals(1.0, m.correlation("A", "B"), 1e-6)
    }

    @Test
    fun `an inverse series correlates at negative 1`() {
        val closes = base()
        // Build a series whose *percentage returns* are the exact negation of A's returns
        // (compound the negated return each step). Pearson of x and -x is exactly -1.
        val inverse = ArrayList<Double>()
        var price = 100.0
        inverse += price
        for (i in 1 until closes.size) {
            val returnA = (closes[i] - closes[i - 1]) / closes[i - 1]
            price *= (1.0 - returnA)
            inverse += price
        }
        val m = engine.compute(mapOf("A" to candles(closes), "B" to candles(inverse)))
        assertEquals(-1.0, m.correlation("A", "B"), 1e-6)
    }

    @Test
    fun `matrix is symmetric with a unit diagonal`() {
        val m = engine.compute(
            mapOf(
                "A" to candles(base()),
                "B" to candles(base().map { it * 1.1 + 3 }),
                "C" to candles(base().reversed()),
            ),
        )
        for (i in m.symbols.indices) {
            assertEquals(1.0, m.values[i][i], 1e-9)
            for (j in m.symbols.indices) {
                assertEquals(m.values[i][j], m.values[j][i], 1e-9)
            }
        }
    }

    @Test
    fun `all correlations are within negative 1 and 1`() {
        val m = engine.compute(
            mapOf(
                "A" to candles(base()),
                "B" to candles(base(80)),
                "C" to candles(base(50).map { it + kotlin.math.cos(it) }),
            ),
        )
        m.values.forEach { row -> row.forEach { assertTrue(it in -1.0..1.0) } }
    }

    @Test
    fun `highly correlated symbols form a cluster`() {
        val closes = base()
        val m = engine.compute(
            mapOf(
                "A" to candles(closes),
                "B" to candles(closes.map { it + 0.01 }), // ~identical returns
                "C" to candles(closes.map { it * 2.0 }),  // scaled -> still corr 1
            ),
            clusterThreshold = 0.7,
        )
        assertTrue("expected at least one cluster", m.clusters.isNotEmpty())
        assertTrue(m.clusters.first().size >= 2)
    }

    @Test
    fun `notable pairs are ranked by absolute strength`() {
        val m = engine.compute(
            mapOf(
                "A" to candles(base()),
                "B" to candles(base(70)),
                "C" to candles(base(90).map { it - kotlin.math.sin(it) }),
            ),
        )
        for (i in 0 until m.notablePairs.size - 1) {
            assertTrue(m.notablePairs[i].strength >= m.notablePairs[i + 1].strength)
        }
    }

    @Test
    fun `window aligns to the shortest series`() {
        val m = engine.compute(
            mapOf("A" to candles(base(100)), "B" to candles(base(40))),
        )
        // shortest returns = 39; window should not exceed it.
        assertTrue(m.windowBars <= 39)
    }

    @Test
    fun `computation is deterministic`() {
        val input = mapOf("A" to candles(base()), "B" to candles(base(70)), "C" to candles(base(80)))
        val a = engine.compute(input)
        val b = engine.compute(input)
        assertEquals(a.symbols, b.symbols)
        assertEquals(a.notablePairs.map { it.correlation }, b.notablePairs.map { it.correlation })
    }

    // -------------------------------------------------------------------------
    // BOUNDARY / EDGE CASES
    // -------------------------------------------------------------------------

    @Test
    fun `single-element series produces empty result`() {
        // Each symbol has only 1 candle, which is below MIN_BARS (10).
        val single = listOf(100.0)
        val m = engine.compute(mapOf("A" to candles(single), "B" to candles(single)))
        assertTrue("Series shorter than MIN_BARS should yield empty result", m.symbols.isEmpty())
    }

    @Test
    fun `constant series produces zero correlation`() {
        // All closes are the same value, so returns are all zero. Pearson is 0 when variance is zero.
        val constant = List(60) { 100.0 }
        val rising = base(60)
        val m = engine.compute(mapOf("A" to candles(constant), "B" to candles(rising)))
        assertEquals(0.0, m.correlation("A", "B"), 1e-9)
    }

    @Test
    fun `exactly MIN_BARS plus one candle produces a valid result`() {
        // MIN_BARS is 10, so 11 candles produce exactly 10 returns which is >= MIN_RETURNS (5).
        val short = base(11)
        val m = engine.compute(mapOf("A" to candles(short), "B" to candles(short)))
        assertEquals(2, m.symbols.size)
        assertEquals(1.0, m.correlation("A", "B"), 1e-6)
    }

    @Test
    fun `empty input map returns empty result`() {
        val m = engine.compute(emptyMap())
        assertTrue(m.symbols.isEmpty())
        assertTrue(m.notablePairs.isEmpty())
        assertTrue(m.clusters.isEmpty())
    }
}
