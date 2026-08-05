package com.foxtrader.app.domain.usecase.analysis

import com.foxtrader.app.domain.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for FibonacciEngine.
 * Validates retracement, extension, and projection level calculations.
 */
class FibonacciEngineTest {

    private lateinit var engine: FibonacciEngine

    @Before
    fun setup() {
        engine = FibonacciEngine()
    }

    @Test
    fun `retracements bullish produces correct levels`() {
        val swingLow = 100.0
        val swingHigh = 200.0
        val levels = engine.retracements(swingHigh, swingLow, Direction.BULLISH)

        // For bullish retracements: price = swingHigh - range * ratio
        // ratio 0.0 -> 200.0
        // ratio 0.382 -> 200 - 100*0.382 = 161.8
        // ratio 1.0 -> 100.0
        assertEquals(7, levels.size)
        assertEquals(200.0, levels[0].price, 0.01)  // 0% retracement = top
        assertEquals(176.4, levels[1].price, 0.01)  // 23.6%
        assertEquals(161.8, levels[2].price, 0.01)  // 38.2%
        assertEquals(150.0, levels[3].price, 0.01)  // 50%
        assertEquals(138.2, levels[4].price, 0.01)  // 61.8%
        assertEquals(121.4, levels[5].price, 0.01)  // 78.6%
        assertEquals(100.0, levels[6].price, 0.01)  // 100% = bottom
    }

    @Test
    fun `retracements bearish produces inverted levels`() {
        val swingLow = 100.0
        val swingHigh = 200.0
        val levels = engine.retracements(swingHigh, swingLow, Direction.BEARISH)

        // For bearish retracements: price = swingLow + range * ratio
        // ratio 0.0 -> 100.0
        // ratio 1.0 -> 200.0
        assertEquals(7, levels.size)
        assertEquals(100.0, levels[0].price, 0.01)  // 0% from bottom
        assertEquals(123.6, levels[1].price, 0.01)  // 23.6%
        assertEquals(138.2, levels[2].price, 0.01)  // 38.2%
        assertEquals(150.0, levels[3].price, 0.01)  // 50%
        assertEquals(161.8, levels[4].price, 0.01)  // 61.8%
        assertEquals(178.6, levels[5].price, 0.01)  // 78.6%
        assertEquals(200.0, levels[6].price, 0.01)  // 100%
    }

    @Test
    fun `extensions bullish projects targets above swing high`() {
        val swingLow = 100.0
        val swingHigh = 200.0
        val levels = engine.extensions(swingHigh, swingLow, Direction.BULLISH)

        // For bullish extensions: price = swingLow + range * ratio
        // ratio 1.272 -> 100 + 100*1.272 = 227.2
        assertEquals(7, levels.size)
        assertEquals(227.2, levels[0].price, 0.01)  // 127.2%
        assertEquals(241.4, levels[1].price, 0.01)  // 141.4%
        assertEquals(261.8, levels[2].price, 0.01)  // 161.8%
        assertEquals(300.0, levels[3].price, 0.01)  // 200%
        assertTrue("All extension targets above swingLow", levels.all { it.price > swingLow })
    }

    @Test
    fun `projections compute ABC targets correctly`() {
        val pointA = 100.0
        val pointB = 150.0
        val pointC = 120.0
        val levels = engine.projections(pointA, pointB, pointC)

        // abRange = 150 - 100 = 50
        // ratio 0.618 -> 120 + 50*0.618 = 150.9
        // ratio 1.0 -> 120 + 50*1.0 = 170.0
        // ratio 1.272 -> 120 + 50*1.272 = 183.6
        assertEquals(5, levels.size)
        assertEquals(150.9, levels[0].price, 0.01)
        assertEquals(170.0, levels[1].price, 0.01)
        assertEquals(183.6, levels[2].price, 0.01)
        assertEquals(200.9, levels[3].price, 0.01)  // 1.618
        assertEquals(220.0, levels[4].price, 0.01)  // 2.0
    }
}
