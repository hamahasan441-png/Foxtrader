package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.indicators.RsiOrderFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PivotSweepDivergenceEngineTest {
    private val engine = PivotSweepDivergenceEngine()

    @Test
    fun `classic levels use only the previous completed trading day`() {
        val levels = engine.classicalLevels(dayKey = 2L, high = 110.0, low = 90.0, close = 100.0)

        assertEquals(100.0, levels.pivot, 1e-9)
        assertEquals(110.0, levels.r1, 1e-9)
        assertEquals(120.0, levels.r2, 1e-9)
        assertEquals(90.0, levels.s1, 1e-9)
        assertEquals(80.0, levels.s2, 1e-9)
        assertEquals(90.0, levels.previousLow, 1e-9)
    }

    @Test
    fun `historical arrows are stable when future candles are appended`() {
        val fixture = bullishFixture()
        val config = permissiveFastConfig()
        val first = engine.analyze("EURUSD", Timeframe.M15, fixture, config).signals
        assertTrue("fixture must produce a real historical PSD signal", first.isNotEmpty())

        val future = (1..12).map { step ->
            val prior = fixture.last().close + step * 0.2
            candle(fixture.last().timestamp + step * M15, prior, prior + 0.4, prior - 0.3, prior + 0.1)
        }
        val second = engine.analyze("EURUSD", Timeframe.M15, fixture + future, config).signals
        val oldFingerprints = first.map(::fingerprint)

        assertEquals(oldFingerprints, second.filter { it.timestamp <= fixture.last().timestamp }.map(::fingerprint))
        assertTrue(first.all { it.confirmationIndex > it.sweepIndex })
        assertTrue(first.all { it.direction == Direction.BULLISH })
    }

    @Test
    fun `daily timeframe cannot manufacture an intraday sweep signal`() {
        assertTrue(engine.analyze("EURUSD", Timeframe.D1, bullishFixture(), permissiveFastConfig()).signals.isEmpty())
    }

    private fun permissiveFastConfig() = PivotSweepDivergenceEngine.Config(
        mode = PivotSweepDivergenceEngine.Mode.FAST,
        divergence = RsiOrderFlow.Config(
            rsiPeriod = 2,
            flowPeriod = 2,
            flowSmoothing = 2,
            pivotLeft = 1,
            pivotRight = 1,
            minPivotSeparation = 3,
            maxPivotSeparation = 40,
            minRsiDifference = 0.0,
            minFlowDifference = 0.0,
            minPriceChangeFraction = 0.0,
        ),
        atrPeriod = 2,
        minSweepAtr = 0.0,
        minRejectionWickFraction = 0.20,
        minCloseLocation = 0.55,
        structureLookback = 3,
        maxConfirmBars = 2,
        displacementAtrMultiple = 0.1,
        minScore = 0,
        cooldownBars = 0,
    )

    private fun bullishFixture(): List<Candle> {
        val out = mutableListOf<Candle>()
        var time = 1_700_006_400_000L // exact UTC day boundary
        // Previous completed day: H=110, L=90, C≈100.
        repeat(32) { i ->
            val open = 100.0 + if (i % 2 == 0) -0.2 else 0.2
            val close = 100.0 + if (i % 2 == 0) 0.2 else -0.2
            val high = if (i == 4) 110.0 else maxOf(open, close) + 0.4
            val low = if (i == 5) 90.0 else minOf(open, close) - 0.4
            out += candle(time, open, high, low, close)
            time += M15
        }
        // Advance to next UTC trading day without inventing bars in the gap.
        time = 1_700_092_800_000L
        var price = 100.0
        repeat(10) {
            val close = price - 0.85
            out += candle(time, price, price + 0.15, close - 0.15, close)
            price = close
            time += M15
        }
        // First momentum low, then a sharp rebound.
        out += candle(time, 91.5, 92.0, 90.6, 91.0); time += M15
        out += candle(time, 91.0, 93.4, 90.9, 93.2); time += M15
        repeat(5) {
            val close = price + 1.05
            out += candle(time, price, close + 0.2, price - 0.1, close)
            price = close
            time += M15
        }
        // Gentle decline keeps RSI/flow above the first momentum low.
        repeat(8) {
            val close = price - 0.48
            out += candle(time, price, price + 0.12, close - 0.12, close)
            price = close
            time += M15
        }
        // Lower-low liquidity sweep of PDL=90 with a strong reclaim.
        out += candle(time, 91.2, 93.0, 88.8, 92.7); time += M15
        // Right-pivot / closed-bar confirmation; large body for displacement.
        out += candle(time, 92.7, 96.2, 92.5, 96.0); time += M15
        out += candle(time, 96.0, 96.5, 95.6, 96.2)
        return out
    }

    private fun candle(t: Long, o: Double, h: Double, l: Double, c: Double) =
        Candle(timestamp = t, open = o, high = h, low = l, close = c, volume = 1_000.0)

    private fun fingerprint(signal: PivotSweepDivergenceEngine.Signal): String =
        "${signal.timestamp}|${signal.direction}|${signal.levelName}|${signal.entry}|${signal.stopLoss}|${signal.takeProfit}|${signal.confidence}"

    private companion object { const val M15 = 15L * 60_000L }
}
