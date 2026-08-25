package com.foxtrader.app.domain.usecase.smt

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmtSignalEngineTest {

    private val engine = SmtSignalEngine(SmtDivergenceDetector())

    @Test
    fun `emits executable setup only on causal confirmation bar`() {
        val primary = primaryHigherHigh()
        val peers = mapOf("GBPUSD" to peerLowerHigh())

        val signal = engine.signalAt("EURUSD", primary, peers, index = 63)

        assertNotNull(signal)
        assertEquals(Direction.BEARISH, signal?.direction)
        assertEquals(63, signal?.index)
        assertEquals(primary[63].timestamp, signal?.timestamp)
        assertTrue(signal!!.stopLoss > signal.entry)
        assertTrue(signal.takeProfit < signal.entry)
        assertTrue(signal.setupType.orEmpty().startsWith("SMT GBPUSD"))
        assertNull("An already-confirmed event must not print again", engine.signalAt("EURUSD", primary, peers, index = 64))
    }

    @Test
    fun `future candles cannot change historical signal geometry`() {
        val primary = primaryHigherHigh()
        val peers = mapOf("GBPUSD" to peerLowerHigh())

        val fromFullSeries = engine.signalAt("EURUSD", primary, peers, index = 63)
        val fromPrefix = engine.signalAt(
            "EURUSD",
            primary.take(64),
            peers.mapValues { it.value.take(64) },
            index = 63,
        )

        assertEquals(fromPrefix, fromFullSeries)
    }

    @Test
    fun `fails closed without trustworthy peer history`() {
        assertNull(engine.signalAt("EURUSD", primaryHigherHigh(), emptyMap(), index = 63))
    }

    private fun primaryHigherHigh(): List<Candle> = baseCandles { index, high, low ->
        (if (index == 30) 110.0 else if (index == 60) 111.0 else high) to low
    }

    private fun peerLowerHigh(): List<Candle> = baseCandles { index, high, low ->
        (if (index == 30) 110.0 else if (index == 60) 109.5 else high) to low
    }

    private fun baseCandles(levelOverride: (Int, Double, Double) -> Pair<Double, Double>): List<Candle> =
        (0 until 80).map { index ->
            val close = 100.0 + index * 0.08 + if (index % 2 == 0) 0.02 else -0.01
            val (high, low) = levelOverride(index, close + 0.25, close - 0.25)
            Candle(
                timestamp = index * 60_000L,
                open = close - 0.03,
                high = high,
                low = low,
                close = close,
                volume = 100.0,
            )
        }
}
