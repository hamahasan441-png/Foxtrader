package com.foxtrader.app.domain.usecase.smt

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmtDivergenceDetectorTest {

    private val detector = SmtDivergenceDetector()

    @Test
    fun `detects bearish SMT when primary sweeps higher high and peer fails`() {
        val result = detector.detect(
            primarySymbol = "EURUSD",
            primaryCandles = primaryHigherHigh(),
            correlatedCandles = mapOf("GBPUSD" to peerLowerHigh()),
        )

        val smt = result.firstOrNull { it.direction == Direction.BEARISH }
        assertTrue("Expected bearish SMT", smt != null)
        assertEquals(SmtDivergenceDetector.SmtType.PRIMARY_SWEEP_PEER_FAIL, smt?.type)
        assertEquals("GBPUSD", smt?.peerSymbol)
        assertTrue("Correlation should be strong", (smt?.correlation ?: 0.0) > 0.9)
    }

    @Test
    fun `detects bullish SMT when peer sweeps lower low and primary holds`() {
        val result = detector.detect(
            primarySymbol = "EURUSD",
            primaryCandles = primaryHigherLow(),
            correlatedCandles = mapOf("GBPUSD" to peerLowerLow()),
        )

        val smt = result.firstOrNull { it.direction == Direction.BULLISH }
        assertTrue("Expected bullish SMT", smt != null)
        assertEquals(SmtDivergenceDetector.SmtType.PEER_SWEEP_PRIMARY_FAIL, smt?.type)
    }

    private fun primaryHigherHigh(): List<Candle> = baseCandles { index, high, low ->
        val adjustedHigh = when (index) {
            30 -> 110.0
            60 -> 111.0
            else -> high
        }
        adjustedHigh to low
    }

    private fun peerLowerHigh(): List<Candle> = baseCandles { index, high, low ->
        val adjustedHigh = when (index) {
            30 -> 110.0
            60 -> 109.5
            else -> high
        }
        adjustedHigh to low
    }

    private fun primaryHigherLow(): List<Candle> = baseCandles { index, high, low ->
        val adjustedLow = when (index) {
            30 -> 95.0
            60 -> 95.6
            else -> low
        }
        high to adjustedLow
    }

    private fun peerLowerLow(): List<Candle> = baseCandles { index, high, low ->
        val adjustedLow = when (index) {
            30 -> 95.0
            60 -> 94.2
            else -> low
        }
        high to adjustedLow
    }

    private fun baseCandles(levelOverride: (Int, Double, Double) -> Pair<Double, Double>): List<Candle> =
        (0 until 80).map { i ->
            val close = 100.0 + i * 0.08 + if (i % 2 == 0) 0.02 else -0.01
            val (high, low) = levelOverride(i, close + 0.25, close - 0.25)
            Candle(
                timestamp = i * 60_000L,
                open = close - 0.03,
                high = high,
                low = low,
                close = close,
                volume = 100.0,
            )
        }
}
