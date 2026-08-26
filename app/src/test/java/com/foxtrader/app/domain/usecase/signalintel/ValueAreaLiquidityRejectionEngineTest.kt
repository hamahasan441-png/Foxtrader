package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueAreaLiquidityRejectionEngineTest {
    private val engine = ValueAreaLiquidityRejectionEngine()

    @Test
    fun `active profile is built only from the completed previous session`() {
        val day = 86_400_000L
        val prior = List(24) { index ->
            val price = 100.0 + index * 0.10
            Candle(index * 3_600_000L, price, price + 0.60, price - 0.40, price + 0.20, 100.0 + index)
        }
        val current = listOf(Candle(day, 103.0, 103.5, 102.8, 103.2, 120.0))
        val config = ValueAreaLiquidityRejectionEngine.Config(minPreviousSessionBars = 20)

        val first = engine.analyze("EURUSD", Timeframe.H1, prior + current, config).activeProfile
        val futureCurrentBar = Candle(day + 3_600_000L, 103.2, 500.0, 1.0, 103.3, 999_999.0)
        val second = engine.analyze("EURUSD", Timeframe.H1, prior + current + futureCurrentBar, config).activeProfile

        assertNotNull(first)
        assertEquals(first?.poc, second?.poc)
        assertEquals(first?.vah, second?.vah)
        assertEquals(first?.valueAreaLow, second?.valueAreaLow)
        assertEquals(prior.last().timestamp, first?.sourceEndTimestamp)
    }

    @Test
    fun `daily and synthetic non-time contexts do not produce signals`() {
        val candles = List(80) { index -> Candle(index * 86_400_000L, 100.0, 101.0, 99.0, 100.5, 10.0) }
        val analysis = engine.analyze("EURUSD", Timeframe.D1, candles)
        assertTrue(analysis.signals.isEmpty())
        assertEquals(null, analysis.activeProfile)
    }
}
