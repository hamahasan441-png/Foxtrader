package com.foxtrader.app.domain.usecase.binary

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivBinary3mSignalEngineTest {
    private val engine = DerivBinary3mSignalEngine()

    @Test
    fun `trend pullback model emits a bullish closed-bar signal`() {
        val candles = bullishPullbackSeries()
        val signals = engine.evaluateAll(candles, minConfidence = 60)

        assertTrue("Expected at least one deterministic setup", signals.isNotEmpty())
        assertTrue(signals.all { it.direction == Direction.BULLISH })
        assertTrue(signals.all { it.signalIndex >= DerivBinary3mSignalEngine.MIN_BARS })
        assertTrue(signals.all { it.confidence in 60..100 })
    }

    @Test
    fun `signal is prefix stable and cannot repaint from future candles`() {
        val candles = bullishPullbackSeries()
        val first = engine.evaluateAll(candles, minConfidence = 60).first()
        val prefix = candles.subList(0, first.signalIndex + 1)

        val fromPrefix = engine.evaluate(prefix, prefix.lastIndex, minConfidence = 60)
        val fromFull = engine.evaluate(candles, first.signalIndex, minConfidence = 60)

        assertNotNull(fromPrefix)
        assertEquals(fromPrefix, fromFull)
        assertEquals(first, fromFull)
    }

    @Test
    fun `unfinished history cannot generate an early setup`() {
        val candles = bullishPullbackSeries(size = 50)
        assertTrue(engine.evaluateAll(candles, minConfidence = 60).isEmpty())
    }

    private fun bullishPullbackSeries(size: Int = 220): List<Candle> {
        val out = ArrayList<Candle>(size)
        var price = 100.0
        val start = 1_700_000_000_000L
        repeat(size) { i ->
            val mod = i % 12
            val delta = when (mod) {
                8 -> -0.07
                9 -> -0.06
                10 -> 0.11
                else -> 0.025
            }
            val open = price
            val close = open + delta
            val low = minOf(open, close) - if (mod == 10) 0.05 else 0.015
            val high = maxOf(open, close) + 0.015
            out += Candle(
                timestamp = start + i * 60_000L,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = 0.0,
            )
            price = close
        }
        return out
    }
}
