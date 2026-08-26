package com.foxtrader.app.domain.usecase.nascent

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.nascent.confirmation.DirectPullbackConfirmation
import com.foxtrader.app.domain.usecase.nascent.confirmation.EngulfConfirmation
import com.foxtrader.app.domain.usecase.nascent.confirmation.SweepConfirmation
import com.foxtrader.app.domain.usecase.nascent.model.ConfirmationType
import com.foxtrader.app.domain.usecase.nascent.model.NascentMode
import com.foxtrader.app.domain.usecase.nascent.model.SetupType
import com.foxtrader.app.domain.usecase.nascent.model.SignalConfidence
import com.foxtrader.app.domain.usecase.nascent.model.SignalState
import com.foxtrader.app.domain.usecase.nascent.msu.Msu1Detector
import com.foxtrader.app.domain.usecase.nascent.msu.Msu2Detector
import com.foxtrader.app.domain.usecase.nascent.msu.Msu3Detector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NascentEngineTest {

    private val engine = NascentEngine(
        structureEngine = NascentStructureEngine(),
        liquidityEngine = NascentLiquidityEngine(),
        epaEngine = NascentEpaEngine(),
        directPullbackEngine = NascentDirectPullbackEngine(),
        tomEngine = NascentTomEngine(),
        msu1 = Msu1Detector(),
        msu2 = Msu2Detector(),
        msu3 = Msu3Detector(NascentTransactionEngine()),
        sweepConfirmation = SweepConfirmation(),
        engulfConfirmation = EngulfConfirmation(),
        directPullbackConfirmation = DirectPullbackConfirmation(),
    )

    private val config = NascentConfig(minConfidence = SignalConfidence.WATCH)

    // ------------------------------------------------------------------
    // Golden fixtures
    // ------------------------------------------------------------------

    @Test
    fun `golden msu1 bearish fixture produces a locked continuation signal`() {
        val candles = NascentFixtures.msu1BearishSeries()

        val signals = engine.analyze("EURUSD", Timeframe.M5, candles, config).signals

        val signal = signals.firstOrNull { it.setupType == SetupType.MSU1 }
        assertNotNull("the MSU1 bearish fixture must produce a Type 1 signal", signal)
        assertEquals(Direction.BEARISH, signal!!.direction)
        assertEquals(ConfirmationType.ENGULFING, signal.confirmationType)
        assertEquals(SignalState.LOCKED, signal.state)
        assertEquals(Timeframe.H1, signal.externalTimeframe)
        assertEquals(Timeframe.M5, signal.internalTimeframe)
        assertTrue("stop must sit above entry on a short", signal.invalidationPrice!! > signal.entryPrice)
        assertTrue("target must sit below entry on a short", signal.targetPrice!! < signal.entryPrice)
    }

    @Test
    fun `golden msu1 bullish fixture produces the mirrored signal`() {
        val candles = NascentFixtures.msu1BullishSeries()

        val signals = engine.analyze("EURUSD", Timeframe.M5, candles, config).signals

        val signal = signals.firstOrNull { it.setupType == SetupType.MSU1 }
        assertNotNull("the MSU1 bullish fixture must produce a Type 1 signal", signal)
        assertEquals(Direction.BULLISH, signal!!.direction)
        assertTrue(signal.invalidationPrice!! < signal.entryPrice)
        assertTrue(signal.targetPrice!! > signal.entryPrice)
    }

    // ------------------------------------------------------------------
    // The non-repaint contract
    // ------------------------------------------------------------------

    /**
     * The single most important assertion in this suite.
     *
     * Running the engine over the first `t + 1` candles must return exactly the
     * signals the full-series run reports at bars `<= t`. If this holds for
     * every cut, then historical reconstruction, chart replay and live
     * evaluation cannot disagree — which is the property that makes one shared
     * engine legitimate.
     */
    @Test
    fun `incremental replay equals batch reconstruction`() {
        val candles = NascentFixtures.msu1BearishSeries()
        val full = engine.analyze("EURUSD", Timeframe.M5, candles, config).signals

        for (cut in listOf(400, 500, 600, 626, 640, candles.lastIndex)) {
            if (cut > candles.lastIndex) continue
            val prefix = candles.subList(0, cut + 1)
            val prefixSignals = engine.analyze("EURUSD", Timeframe.M5, prefix, config).signals
            assertEquals(
                "replay and batch disagree at cut $cut",
                full.filter { it.barIndex <= cut },
                prefixSignals,
            )
        }
    }

    @Test
    fun `a locked signal survives future candles unchanged`() {
        val candles = NascentFixtures.msu1BearishSeries()
        val before = engine.analyze("EURUSD", Timeframe.M5, candles, config).signals
        assertTrue("fixture must produce a signal to test against", before.isNotEmpty())

        val extended = NascentFixtures.withFuture(candles, bars = 40)
        val after = engine.analyze("EURUSD", Timeframe.M5, extended, config).signals

        assertEquals(
            "historical arrows must never move or vanish",
            before,
            after.filter { it.barIndex <= candles.lastIndex },
        )
    }

    @Test
    fun `signal ids are unique so the chart cannot draw duplicates`() {
        val candles = NascentFixtures.msu1BearishSeries()
        val signals = engine.analyze("EURUSD", Timeframe.M5, candles, config).signals
        assertEquals(signals.map { it.id }.distinct().size, signals.size)
    }

    // ------------------------------------------------------------------
    // The key-level gate
    // ------------------------------------------------------------------

    /**
     * The gate that the whole hierarchy rests on. A series with no completed
     * external structure has no valid location, so it must produce nothing at
     * all — however MSU-shaped its internal price action happens to look.
     */
    @Test
    fun `no external structure means no signal`() {
        val short = NascentFixtures.SeriesBuilder(100.0)
            .leg(to = 104.0, bars = 8)
            .leg(to = 100.0, bars = 8)
            .leg(to = 105.0, bars = 8)
            .build()

        val analysis = engine.analyze("EURUSD", Timeframe.M5, short, config)

        assertTrue(analysis.signals.isEmpty())
        assertTrue(
            "the engine must say why it stayed silent, not just return nothing",
            analysis.notes.isNotEmpty(),
        )
    }

    @Test
    fun `the monthly chart has no external timeframe and is refused explicitly`() {
        val analysis = engine.analyze(
            "EURUSD",
            Timeframe.MN,
            NascentFixtures.msu1BearishSeries(),
            config,
        )

        assertTrue(analysis.signals.isEmpty())
        assertTrue(analysis.notes.first().contains("No external timeframe"))
    }

    @Test
    fun `external structure is mapped one step above the chart timeframe`() {
        assertEquals(Timeframe.M15, NascentConfig.externalFor(Timeframe.M1))
        assertEquals(Timeframe.H1, NascentConfig.externalFor(Timeframe.M5))
        assertEquals(Timeframe.H4, NascentConfig.externalFor(Timeframe.M15))
        assertEquals(Timeframe.H4, NascentConfig.externalFor(Timeframe.H1))
        assertEquals(Timeframe.D1, NascentConfig.externalFor(Timeframe.H4))
        assertEquals(Timeframe.W1, NascentConfig.externalFor(Timeframe.D1))
        assertEquals(Timeframe.MN, NascentConfig.externalFor(Timeframe.W1))
        assertEquals(null, NascentConfig.externalFor(Timeframe.MN))
    }

    // ------------------------------------------------------------------
    // Evidence discipline
    // ------------------------------------------------------------------

    @Test
    fun `source strict mode never emits research-only setups`() {
        val candles = NascentFixtures.msu1BearishSeries()
        val strict = engine.analyze(
            "EURUSD",
            Timeframe.M5,
            candles,
            config.copy(mode = NascentMode.SOURCE_STRICT),
        ).signals

        assertTrue(
            "strict mode must not emit a TOM-completion family",
            strict.none { it.setupType == SetupType.EPA_DP_TOM },
        )
    }

    @Test
    fun `confidence never rescues an invalid setup`() {
        val candles = NascentFixtures.msu1BearishSeries()
        val signals = engine.analyze("EURUSD", Timeframe.M5, candles, config).signals

        assertTrue(
            "an emitted signal is valid by construction and never graded INVALID",
            signals.none { it.confidence == SignalConfidence.INVALID },
        )
    }

    @Test
    fun `raising the confidence floor can only remove signals`() {
        val candles = NascentFixtures.msu1BearishSeries()
        val permissive = engine.analyze("EURUSD", Timeframe.M5, candles, config).signals
        val strictGrade = engine.analyze(
            "EURUSD",
            Timeframe.M5,
            candles,
            config.copy(minConfidence = SignalConfidence.A_PLUS),
        ).signals

        assertTrue(strictGrade.size <= permissive.size)
        assertTrue(strictGrade.all { it.confidence == SignalConfidence.A_PLUS })
    }

    // ------------------------------------------------------------------
    // Backtest parity
    // ------------------------------------------------------------------

    /**
     * Live and backtest must be the same decision, not two implementations that
     * happen to agree today.
     */
    @Test
    fun `signalAt reproduces the chart signal exactly`() {
        val candles = NascentFixtures.msu1BearishSeries()
        val chart = engine.analyze("EURUSD", Timeframe.M5, candles, config).signals.first()

        val strategySignal = engine.signalAt("EURUSD", Timeframe.M5, candles, chart.barIndex, config)

        assertNotNull("the backtest adapter must see the same setup", strategySignal)
        assertEquals(chart.direction, strategySignal!!.direction)
        assertEquals(chart.entryPrice, strategySignal.entry, 1e-9)
        assertEquals(chart.invalidationPrice!!, strategySignal.stopLoss, 1e-9)
        assertEquals(chart.targetPrice!!, strategySignal.takeProfit, 1e-9)
    }

    @Test
    fun `signalAt returns nothing on a bar that confirmed no setup`() {
        val candles = NascentFixtures.msu1BearishSeries()
        val chart = engine.analyze("EURUSD", Timeframe.M5, candles, config).signals.first()

        assertEquals(null, engine.signalAt("EURUSD", Timeframe.M5, candles, chart.barIndex - 1, config))
    }

    // ------------------------------------------------------------------
    // Explainability
    // ------------------------------------------------------------------

    @Test
    fun `diagnostics explain why a bar produced nothing`() {
        val candles = NascentFixtures.msu1BearishSeries()

        val analysis = engine.analyze(
            "EURUSD",
            Timeframe.M5,
            candles,
            config.copy(collectDiagnostics = true, liveWindowBars = 100),
        )

        assertTrue("diagnostics must be collected for the live window", analysis.diagnostics.isNotEmpty())
        assertTrue(
            "every diagnostic names the gates it evaluated",
            analysis.diagnostics.all { it.gates.isNotEmpty() },
        )
    }

    @Test
    fun `diagnostics stay off by default so the chart pays nothing for them`() {
        val candles = NascentFixtures.msu1BearishSeries()
        val analysis = engine.analyze("EURUSD", Timeframe.M5, candles, config)
        assertTrue(analysis.diagnostics.isEmpty())
    }
}
