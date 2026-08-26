package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccumulationManipulationDistributionEngineTest {
    private val engine = AccumulationManipulationDistributionEngine()

    @Test
    fun `bullish accumulation-manipulation-distribution cycle produces a signal`() {
        val fixture = bullishFixture()
        val signals = engine.analyze("EURUSD", Timeframe.M15, fixture, permissiveFastConfig()).signals

        val signal = signals.firstOrNull { it.sweepIndex == 10 }
        assertNotNull("the designed manipulation sweep at index 10 must confirm a signal", signal)
        assertEquals(Direction.BULLISH, signal!!.direction)
        assertTrue("accumulation range must precede the sweep", signal.accumulationEndIndex < signal.sweepIndex)
        assertTrue("distribution confirms no earlier than the sweep", signal.confirmationIndex >= signal.sweepIndex)
        assertTrue(signal.stopLoss < signal.entry)
        assertTrue(signal.takeProfit > signal.entry)
        assertTrue(signal.accumulationHigh > signal.accumulationLow)
        assertEquals("the longest qualifying accumulation window wins", 0, signal.accumulationStartIndex)
        assertEquals(9, signal.accumulationEndIndex)
    }

    @Test
    fun `bearish accumulation-manipulation-distribution cycle produces a signal`() {
        val fixture = bearishFixture()
        val signals = engine.analyze("EURUSD", Timeframe.M15, fixture, permissiveFastConfig()).signals

        val signal = signals.firstOrNull { it.sweepIndex == 10 }
        assertNotNull("the designed manipulation sweep at index 10 must confirm a signal", signal)
        assertEquals(Direction.BEARISH, signal!!.direction)
        assertTrue(signal.stopLoss > signal.entry)
        assertTrue(signal.takeProfit < signal.entry)
    }

    /** A signal must never move or vanish once later candles arrive. */
    @Test
    fun `historical signals are stable when future candles are appended`() {
        val fixture = bullishFixture()
        val config = permissiveFastConfig()
        val first = engine.analyze("EURUSD", Timeframe.M15, fixture, config).signals
        assertTrue("fixture must produce a real historical AMD signal", first.isNotEmpty())

        val future = (1..12).map { step ->
            val prior = fixture.last().close + step * 0.2
            candle(fixture.last().timestamp + step * M15, prior, prior + 0.4, prior - 0.3, prior + 0.1)
        }
        val second = engine.analyze("EURUSD", Timeframe.M15, fixture + future, config).signals
        val oldFingerprints = first.map(::fingerprint)

        assertEquals(oldFingerprints, second.filter { it.timestamp <= fixture.last().timestamp }.map(::fingerprint))
    }

    /**
     * Unlike the session-clock PSD/VALR engines, AMD is detected structurally
     * (range compression relative to ATR), so the exact same cycle shape must
     * still confirm on a daily chart.
     */
    @Test
    fun `works on the daily timeframe`() {
        val signals = engine.analyze("EURUSD", Timeframe.D1, bullishFixture(), permissiveFastConfig()).signals
        assertTrue("AMD must not be gated to intraday timeframes", signals.isNotEmpty())
    }

    @Test
    fun `production defaults produce a visible signal after ATR warmup`() {
        var time = 1_700_006_400_000L
        val candles = mutableListOf<Candle>()
        repeat(20) { index ->
            val close = if (index % 2 == 0) 100.02 else 99.98
            candles += candle(time, 100.0, 100.05, 99.95, close)
            time += M15
        }
        candles += candle(time, 100.0, 100.10, 99.70, 100.02); time += M15
        candles += candle(time, 100.02, 100.30, 99.98, 100.28)

        val signals = engine.analyze("EURUSD", Timeframe.M15, candles)

        assertTrue("default chart settings must emit the closed-bar AMD cycle", signals.isNotEmpty())
        assertEquals(Direction.BULLISH, signals.last().direction)
        assertEquals(candles.lastIndex, signals.last().confirmationIndex)
    }

    @Test
    fun `backtest signalAt agrees with the confirmed chart signal`() {
        val fixture = bullishFixture()
        val config = permissiveFastConfig()
        val analysisSignal = engine.analyze("EURUSD", Timeframe.M15, fixture, config).signals
            .first { it.sweepIndex == 10 }

        val strategySignal = engine.signalAt(
            "EURUSD",
            Timeframe.M15,
            fixture,
            analysisSignal.confirmationIndex,
            config,
        )

        assertNotNull(strategySignal)
        assertEquals(analysisSignal.direction, strategySignal!!.direction)
        assertEquals(analysisSignal.entry, strategySignal.entry, 1e-9)
        assertEquals(analysisSignal.stopLoss, strategySignal.stopLoss, 1e-9)
        assertEquals(analysisSignal.takeProfit, strategySignal.takeProfit, 1e-9)
    }

    private fun permissiveFastConfig() = AccumulationManipulationDistributionEngine.Config(
        mode = AccumulationManipulationDistributionEngine.Mode.FAST,
        atrPeriod = 3,
        minAccumulationBars = 5,
        maxAccumulationBars = 10,
        accumulationRangeAtrMultiple = 2.5,
        minSweepAtr = 0.5,
        minRejectionWickFraction = 0.20,
        minCloseLocation = 0.55,
        maxReclaimBars = 2,
        maxConfirmBars = 3,
        displacementAtrMultiple = 0.1,
        minScore = 0,
        cooldownBars = 0,
    )

    /**
     * Bars 0-9 form a tight consolidation (accumulation). Bar 10 sweeps below
     * the range low with a deep rejecting wick (manipulation) but closes back
     * inside the range only on bar 11 (distribution confirmation), so the
     * three AMD phases land on distinct, verifiable bars.
     */
    private fun bullishFixture(): List<Candle> {
        var time = 1_700_006_400_000L
        val out = mutableListOf<Candle>()
        out += candle(time, 100.00, 100.15, 99.90, 100.05); time += M15
        out += candle(time, 100.05, 100.20, 99.95, 100.00); time += M15
        out += candle(time, 100.00, 100.10, 99.88, 99.95); time += M15
        out += candle(time, 99.95, 100.12, 99.85, 100.05); time += M15
        out += candle(time, 100.05, 100.18, 99.92, 100.00); time += M15
        out += candle(time, 100.00, 100.15, 99.90, 100.08); time += M15
        out += candle(time, 100.08, 100.20, 99.95, 100.02); time += M15
        out += candle(time, 100.02, 100.15, 99.85, 99.98); time += M15
        out += candle(time, 99.98, 100.10, 99.88, 100.05); time += M15
        out += candle(time, 100.05, 100.18, 99.90, 100.00); time += M15
        // Manipulation: deep sweep below the accumulation low (99.85), strong rejecting wick.
        out += candle(time, 100.00, 100.05, 99.30, 99.85); time += M15
        // Distribution: closes back through the range with a wide bullish body.
        out += candle(time, 99.85, 100.20, 99.80, 100.15); time += M15
        out += candle(time, 100.15, 100.30, 100.05, 100.20)
        return out
    }

    /** Mirror of [bullishFixture]: same accumulation range, sweep above the high. */
    private fun bearishFixture(): List<Candle> {
        var time = 1_700_006_400_000L
        val out = mutableListOf<Candle>()
        out += candle(time, 100.00, 100.15, 99.90, 100.05); time += M15
        out += candle(time, 100.05, 100.20, 99.95, 100.00); time += M15
        out += candle(time, 100.00, 100.10, 99.88, 99.95); time += M15
        out += candle(time, 99.95, 100.12, 99.85, 100.05); time += M15
        out += candle(time, 100.05, 100.18, 99.92, 100.00); time += M15
        out += candle(time, 100.00, 100.15, 99.90, 100.08); time += M15
        out += candle(time, 100.08, 100.20, 99.95, 100.02); time += M15
        out += candle(time, 100.02, 100.15, 99.85, 99.98); time += M15
        out += candle(time, 99.98, 100.10, 99.88, 100.05); time += M15
        out += candle(time, 100.05, 100.18, 99.90, 100.00); time += M15
        // Manipulation: deep sweep above the accumulation high (100.20), strong rejecting wick,
        // close held above the range so the reclaim is not yet decidable on this bar.
        out += candle(time, 100.05, 100.75, 99.95, 100.25); time += M15
        // Distribution: closes back through the range with a wide bearish body.
        out += candle(time, 100.25, 100.30, 99.70, 99.85); time += M15
        out += candle(time, 99.85, 99.90, 99.70, 99.75)
        return out
    }

    private fun candle(t: Long, o: Double, h: Double, l: Double, c: Double) =
        Candle(timestamp = t, open = o, high = h, low = l, close = c, volume = 1_000.0)

    private fun fingerprint(signal: AccumulationManipulationDistributionEngine.Signal): String =
        "${signal.timestamp}|${signal.direction}|${signal.entry}|${signal.stopLoss}|${signal.takeProfit}|${signal.confidence}"

    private companion object { const val M15 = 15L * 60_000L }
}
