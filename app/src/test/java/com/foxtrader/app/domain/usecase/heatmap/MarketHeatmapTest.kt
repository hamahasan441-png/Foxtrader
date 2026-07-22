package com.foxtrader.app.domain.usecase.heatmap

import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MarketHeatmap.
 * Includes regression coverage for NaN produced by empty input.
 */
class MarketHeatmapTest {

    private lateinit var heatmap: MarketHeatmap

    @Before
    fun setup() {
        heatmap = MarketHeatmap()
    }

    private fun series(start: Double, changePerBar: Double, count: Int = 30): List<Candle> =
        (0 until count).map { i ->
            val close = start + i * changePerBar
            Candle(1_000L + i * 60_000L, close, close + 1, close - 1, close, 1000.0)
        }

    @Test
    fun `empty input returns neutral result without NaN`() {
        // Regression: cells.map{}.average() produced NaN on empty input
        val result = heatmap.computeHeatmap(emptyMap())
        assertTrue(result.cells.isEmpty())
        assertNull(result.bestPerformer)
        assertNull(result.worstPerformer)
        assertEquals(0.0, result.averageChange, 0.0001)
        assertFalse(result.averageChange.isNaN())
        assertEquals(MarketHeatmap.MarketSentiment.NEUTRAL, result.marketSentiment)
    }

    @Test
    fun `bullish series produces positive change`() {
        val data = mapOf("EURUSD" to (AssetClass.FOREX to series(1.10, 0.001)))
        val result = heatmap.computeHeatmap(data, period = 20)
        assertEquals(1, result.cells.size)
        assertTrue(result.cells[0].changePercent > 0)
    }

    @Test
    fun `best and worst performers identified`() {
        val data = mapOf(
            "UP" to (AssetClass.CRYPTO to series(100.0, 1.0)),    // strong up
            "DOWN" to (AssetClass.CRYPTO to series(100.0, -0.5)), // down
        )
        val result = heatmap.computeHeatmap(data, period = 20)
        assertEquals("UP", result.bestPerformer?.symbol)
        assertEquals("DOWN", result.worstPerformer?.symbol)
    }

    @Test
    fun `symbols with insufficient data are skipped`() {
        val data = mapOf("SHORT" to (AssetClass.FOREX to series(1.0, 0.01, count = 5)))
        val result = heatmap.computeHeatmap(data, period = 20)
        assertTrue(result.cells.isEmpty())
    }
}
