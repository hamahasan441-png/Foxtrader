package com.foxtrader.app.domain.usecase.correlation

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for CorrelationMatrix.
 * Validates Pearson correlation computation and matrix structure.
 */
class CorrelationMatrixTest {

    private lateinit var matrix: CorrelationMatrix

    @Before
    fun setup() {
        matrix = CorrelationMatrix()
    }

    private fun candle(
        close: Double, timestamp: Long = 0L,
    ) = Candle(timestamp, close, close + 0.5, close - 0.5, close, 100.0)

    /**
     * Build a series of candles with linearly increasing closes.
     */
    private fun buildTrendingSeries(start: Double, step: Double, count: Int): List<Candle> {
        return (0 until count).map { i ->
            candle(start + step * i, timestamp = i * 60000L)
        }
    }

    @Test
    fun `computeMatrix with identical series produces correlation of 1`() {
        val series = buildTrendingSeries(100.0, 1.0, 50)
        val dataMap = mapOf("EURUSD" to series, "GBPUSD" to series)
        val result = matrix.computeMatrix(dataMap, period = 40)

        assertEquals(2, result.symbols.size)
        assertTrue("Should have one pair", result.pairs.isNotEmpty())
        val pair = result.pairs.first()
        assertEquals("Identical series should have correlation ~1.0", 1.0, pair.correlation, 0.01)
        assertEquals(CorrelationMatrix.CorrelationStrength.STRONG_POSITIVE, pair.strength)
    }

    @Test
    fun `computeMatrix with inversely correlated series produces negative correlation`() {
        val seriesA = buildTrendingSeries(100.0, 1.0, 50)
        // Inversely correlated: descending
        val seriesB = buildTrendingSeries(200.0, -1.0, 50)
        val dataMap = mapOf("EURUSD" to seriesA, "USDJPY" to seriesB)
        val result = matrix.computeMatrix(dataMap, period = 40)

        assertTrue("Should have one pair", result.pairs.isNotEmpty())
        val pair = result.pairs.first()
        assertTrue("Inverse series should be strongly negative: ${pair.correlation}",
            pair.correlation < -0.7)
        assertEquals(CorrelationMatrix.CorrelationStrength.STRONG_NEGATIVE, pair.strength)
    }

    @Test
    fun `computeMatrix self-correlation is always 1`() {
        val series = buildTrendingSeries(100.0, 0.5, 50)
        val dataMap = mapOf("A" to series, "B" to series)
        val result = matrix.computeMatrix(dataMap, period = 40)

        // Diagonal should be 1.0
        for (i in result.symbols.indices) {
            assertEquals("Self-correlation at [$i][$i] should be 1.0", 1.0, result.matrix[i][i], 0.001)
        }
    }

    @Test
    fun `computeMatrix with insufficient data produces zero correlation`() {
        // Only 3 candles - less than the 5-point minimum for Pearson
        val shortSeries = buildTrendingSeries(100.0, 1.0, 3)
        val dataMap = mapOf("A" to shortSeries, "B" to shortSeries)
        val result = matrix.computeMatrix(dataMap, period = 2)

        assertTrue("Should have a pair", result.pairs.isNotEmpty())
        assertEquals("Too few data points should yield 0 correlation", 0.0, result.pairs.first().correlation, 0.001)
    }

    @Test
    fun `getHedgingPairs returns only strongly negative correlations`() {
        val seriesA = buildTrendingSeries(100.0, 1.0, 50)
        val seriesB = buildTrendingSeries(200.0, -1.0, 50)
        val seriesC = buildTrendingSeries(150.0, 1.0, 50) // same direction as A
        val dataMap = mapOf("A" to seriesA, "B" to seriesB, "C" to seriesC)
        val result = matrix.computeMatrix(dataMap, period = 40)
        val hedging = matrix.getHedgingPairs(result)

        for (pair in hedging) {
            assertEquals("Hedging pair should be STRONG_NEGATIVE",
                CorrelationMatrix.CorrelationStrength.STRONG_NEGATIVE, pair.strength)
            assertTrue("Correlation should be < -0.7", pair.correlation < -0.7)
        }
    }
}
