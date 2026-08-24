package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RsiOrderFlowSignalEngineTest {
    private val engine = RsiOrderFlowSignalEngine()
    private val config = RsiOrderFlowSignalEngine.Config(minStrength = 0)

    @Test
    fun `signal cannot appear before divergence confirmation bar`() {
        val candles = bullishDivergenceCandles()

        val before = engine.analyze("TEST", Timeframe.M1, candles.take(39), config)
        val atConfirmation = engine.analyze("TEST", Timeframe.M1, candles.take(40), config)

        assertFalse(before.signals.any { it.pivotIndex == 36 })
        val signal = atConfirmation.signals.firstOrNull { it.pivotIndex == 36 }
        assertNotNull(signal)
        requireNotNull(signal)
        assertEquals(39, signal.confirmationIndex)
        assertEquals(candles[39].timestamp, signal.timestamp)
        assertEquals(Direction.BULLISH, signal.direction)
        assertTrue(signal.stopLoss < signal.entry)
        assertTrue(signal.takeProfit > signal.entry)
    }

    @Test
    fun `future bars cannot mutate already confirmed signal geometry`() {
        val base = bullishDivergenceCandles().take(40)
        val confirmed = engine.analyze("TEST", Timeframe.M1, base, config).signals
            .first { it.pivotIndex == 36 }

        val extended = base + List(12) { i ->
            val p = if (i % 2 == 0) 180.0 + i else 55.0 - i
            Candle(
                timestamp = 1_700_000_000_000L + (base.size + i) * 60_000L,
                open = p,
                high = p + 5.0,
                low = (p - 5.0).coerceAtLeast(1.0),
                close = p + if (i % 2 == 0) 2.0 else -2.0,
                volume = 10_000.0 + i,
            )
        }
        val historical = engine.analyze("TEST", Timeframe.M1, extended, config).signals
            .first { it.pivotIndex == 36 && it.confirmationIndex == 39 }

        assertEquals(confirmed, historical)
    }

    @Test
    fun `backtest adapter emits only on first knowable confirmation bar`() {
        val candles = bullishDivergenceCandles()
        val strategy = engine.strategyFunction("TEST", Timeframe.M1, config)

        assertEquals(null, strategy(candles.take(39), 38))
        val signal = strategy(candles.take(40), 39)
        assertNotNull(signal)
        requireNotNull(signal)
        assertEquals(39, signal.index)
        assertEquals(candles[39].timestamp, signal.timestamp)
        assertEquals(Direction.BULLISH, signal.direction)
        assertTrue(signal.stopLoss < signal.entry)
        assertTrue(signal.takeProfit > signal.entry)
    }

    @Test
    fun `signal analysis remains finite when provider volume is absent`() {
        val candles = bullishDivergenceCandles().map { it.copy(volume = 0.0) }
        val result = engine.analyze("TEST", Timeframe.M1, candles, config)

        assertEquals(0.0, result.positiveVolumeCoverage, 0.0)
        assertTrue(result.signals.all {
            it.entry.isFinite() && it.stopLoss.isFinite() && it.takeProfit.isFinite()
        })
    }

    private fun bullishDivergenceCandles(): List<Candle> {
        val closes = listOf(
            100.0,
            101.0, 102.0, 103.0, 104.0, 103.0, 102.0, 101.0, 100.0,
            99.0, 98.0, 97.0, 96.0, 95.0, 94.0, 93.0, 92.0, 91.0,
            90.0, 89.0, 88.0,
            90.0, 92.0, 94.0, 96.0, 98.0,
            97.0, 96.0, 95.0, 94.0, 93.0, 92.0, 91.0, 90.0, 89.0, 87.5,
            90.0, 92.0, 94.0, 96.0, 98.0,
        )
        return closes.mapIndexed { index, close ->
            val open = if (index == 0) close else closes[index - 1]
            Candle(
                timestamp = 1_700_000_000_000L + index * 60_000L,
                open = open,
                high = maxOf(open, close) + 0.5,
                low = minOf(open, close) - 0.5,
                close = close,
                volume = 100.0,
            )
        }
    }
}
