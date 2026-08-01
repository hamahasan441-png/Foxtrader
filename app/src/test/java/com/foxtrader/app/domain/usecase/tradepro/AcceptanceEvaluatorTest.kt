package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.AcceptanceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceEvaluatorTest {

    private val evaluator = AcceptanceEvaluator()

    private fun c(i: Int, open: Double, high: Double, low: Double, close: Double) =
        Candle(i * 60_000L, open, high, low, close, 100.0)

    @Test
    fun `holds and extends beyond level is accepted`() {
        val level = 100.0
        val candles = listOf(
            c(0, 99.0, 100.0, 98.0, 99.5),
            c(1, 99.5, 100.5, 99.0, 99.8),
            c(2, 99.8, 101.0, 99.5, 100.5), // break bar
            c(3, 100.5, 101.5, 100.2, 101.0),
            c(4, 101.0, 102.5, 100.8, 102.0), // higher high => new structure
            c(5, 102.0, 103.0, 101.5, 102.8),
        )
        val r = evaluator.evaluate(candles, breakIndex = 2, level = level, direction = Direction.BULLISH, minBars = 2)
        assertEquals(AcceptanceState.ACCEPTED, r.state)
        assertTrue(r.formedNewStructure)
        assertTrue(r.barsHeld >= 2)
    }

    @Test
    fun `immediate snap-back is rejected`() {
        val level = 100.0
        val candles = listOf(
            c(0, 101.0, 101.5, 100.0, 100.5),
            c(1, 100.5, 101.0, 99.0, 99.2), // break bar (still above? close 99.2 below level)
            c(2, 99.2, 99.5, 98.0, 98.5), // first post bar closes below level
            c(3, 98.5, 99.0, 97.0, 97.5),
        )
        val r = evaluator.evaluate(candles, breakIndex = 1, level = level, direction = Direction.BULLISH, minBars = 2)
        assertEquals(AcceptanceState.REJECTED, r.state)
        assertEquals(0, r.barsHeld)
    }

    @Test
    fun `close back through level after holding is rejected`() {
        val level = 100.0
        val candles = listOf(
            c(0, 99.0, 100.0, 98.0, 99.5),
            c(1, 99.5, 101.0, 99.0, 100.5), // break bar
            c(2, 100.5, 101.5, 100.2, 101.0), // hold
            c(3, 101.0, 101.2, 100.1, 100.4), // hold
            c(4, 100.4, 100.6, 98.0, 98.5), // closes back below level -> violation
        )
        val r = evaluator.evaluate(candles, breakIndex = 1, level = level, direction = Direction.BULLISH, minBars = 2)
        assertEquals(AcceptanceState.REJECTED, r.state)
    }

    @Test
    fun `single post bar is pending`() {
        val level = 100.0
        val candles = listOf(
            c(0, 99.0, 100.0, 98.0, 99.5),
            c(1, 99.5, 101.0, 99.0, 100.5), // break bar
            c(2, 100.5, 101.0, 100.2, 100.8), // only one post bar, no new structure yet
        )
        val r = evaluator.evaluate(candles, breakIndex = 1, level = level, direction = Direction.BULLISH, minBars = 2)
        assertEquals(AcceptanceState.PENDING, r.state)
    }

    @Test
    fun `no bars after break is pending`() {
        val candles = listOf(c(0, 99.0, 101.0, 98.0, 100.5))
        val r = evaluator.evaluate(candles, breakIndex = 0, level = 100.0, direction = Direction.BULLISH)
        assertEquals(AcceptanceState.PENDING, r.state)
    }
}
