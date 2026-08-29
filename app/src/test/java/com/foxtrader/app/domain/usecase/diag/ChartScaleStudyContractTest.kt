package com.foxtrader.app.domain.usecase.diag

import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.apex.ApexConfig
import com.foxtrader.app.domain.usecase.apex.ApexEngine
import com.foxtrader.app.domain.usecase.apex.ApexVoteCollector
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.compass.CompassCallSource
import com.foxtrader.app.domain.usecase.compass.CompassConfig
import com.foxtrader.app.domain.usecase.compass.CompassEngine
import com.foxtrader.app.domain.usecase.crucible.CrucibleConfig
import com.foxtrader.app.domain.usecase.crucible.CrucibleEngine
import com.foxtrader.app.domain.usecase.keystone.KeystoneConfig
import com.foxtrader.app.domain.usecase.keystone.KeystoneEngine
import com.foxtrader.app.domain.usecase.keystone.KeystoneFixtures
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePeerSeries
import com.foxtrader.app.domain.usecase.keystone.model.KeystonePolarity
import com.foxtrader.app.domain.usecase.liquiditysweep.LiquiditySweepEngine
import com.foxtrader.app.domain.usecase.signalintel.AccumulationManipulationDistributionEngine
import com.foxtrader.app.domain.usecase.signalintel.PivotSweepDivergenceEngine
import com.foxtrader.app.domain.usecase.signalintel.RsiOrderFlowSignalEngine
import com.foxtrader.app.domain.usecase.signalintel.ValueAreaLiquidityRejectionEngine
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import com.foxtrader.app.domain.usecase.virginwick.VirginWickEngine
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.feature.chart.presentation.ChartDataController
import com.foxtrader.app.data.remote.dukascopy.DukascopyDataSource
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ChartScaleStudyContractTest {

    private fun series(size: Int, seed: Int = 1): List<Candle> {
        val random = Random(seed)
        val mid = 1.1000
        var price = mid
        return (0 until size).map { i ->
            val pull = -(price - mid) / 0.0060 * 0.00050
            val noise = (random.nextDouble() - 0.5) * 0.0009
            val o = price
            val c = o + pull + noise
            price = c
            val w = abs(c - o) * 0.6 + 0.00006
            Candle(1_700_000_000_000L + i * 300_000L, o, maxOf(o, c) + w, minOf(o, c) - w, c, 1000.0)
        }
    }

    /**
     * A trending market with pullbacks and real impulse candles — what a chart
     * usually looks like, and what the reverting fixture above is not.
     *
     * Both matter. The studies were only ever measured on a mean-reverting
     * channel, and a trending market gives the member engines a different diet
     * entirely; a study that works on one and not the other is a study that
     * works by accident.
     */
    private fun trending(size: Int, seed: Int = 1): List<Candle> {
        val random = Random(seed)
        var price = 1.1350
        var phase = 0
        var up = true
        return (0 until size).map { index ->
            if (phase >= (if (up) 60 else 25) + random.nextInt(20)) {
                up = !up
                phase = 0
            }
            phase++
            val impulse = random.nextInt(20) == 0
            val drift = if (up) 0.000045 else -0.000055
            val step = if (impulse) {
                (if (up) 1.0 else -1.0) * (0.0010 + random.nextDouble() * 0.0008)
            } else {
                drift + (random.nextDouble() - 0.5) * 0.00075
            }
            val open = price
            val close = open + step
            price = close
            val wick = if (impulse) abs(step) * 0.06 else abs(step) * 0.7 + 0.00007
            Candle(
                timestamp = 1_700_000_000_000L + index * 900_000L,
                open = open,
                high = maxOf(open, close) + wick,
                low = minOf(open, close) - wick,
                close = close,
                volume = 1_000.0,
            )
        }
    }

    private fun apex() = ApexEngine(
        ApexVoteCollector(
            LiquiditySweepEngine(AnalyzeMarketStructureUseCase()),
            VirginWickEngine(SmcDetector()),
            RsiOrderFlowSignalEngine(),
            PivotSweepDivergenceEngine(),
            ValueAreaLiquidityRejectionEngine(),
            AccumulationManipulationDistributionEngine(),
        ),
    )

    private fun keystone() = KeystoneEngine(AnalyzeMarketStructureUseCase())

    /**
     * A peer that genuinely moves with the primary.
     *
     * Two independently generated walks are uncorrelated, and a divergence
     * between uncorrelated markets is not evidence of anything — the engine
     * would refuse them on the correlation test and this fixture would prove
     * only that the test works. Building the peer from the primary's own
     * returns plus noise produces a pair that correlates the way a real one
     * does, and that disagrees where a real one disagrees.
     */
    private fun correlatedPeer(
        primary: List<Candle>,
        beta: Double = 0.85,
        seed: Int = 99,
    ): List<KeystonePeerSeries> {
        val random = Random(seed)
        var price = primary.first().open
        val bars = primary.mapIndexed { i, bar ->
            val primaryStep = if (i == 0) 0.0 else bar.close - primary[i - 1].close
            val step = beta * primaryStep + (1.0 - beta) * (random.nextDouble() - 0.5) * 0.0018
            val open = price
            val close = open + step
            price = close
            val wick = abs(step) * 0.6 + 0.00006
            Candle(bar.timestamp, open, maxOf(open, close) + wick, minOf(open, close) - wick, close, 1_000.0)
        }
        return listOf(KeystonePeerSeries("GBPUSD", bars, KeystonePolarity.POSITIVE))
    }

    private fun compass() = CompassEngine(
        CompassCallSource(
            LiquiditySweepEngine(AnalyzeMarketStructureUseCase()),
            VirginWickEngine(SmcDetector()),
            RsiOrderFlowSignalEngine(),
            PivotSweepDivergenceEngine(),
            ValueAreaLiquidityRejectionEngine(),
            AccumulationManipulationDistributionEngine(),
        ),
    )

    /**
     * At the history the chart actually holds, every gated study must at least
     * run and say something.
     *
     * This is the contract that was broken. The chart fetched 500 bars while
     * these engines were validated on thirty thousand, so on a real chart Apex
     * saw a single candidate against a 30-trade requirement, Compass six calls
     * against forty, and Crucible refused to start at all. All three drew
     * nothing and — because no status was surfaced — gave no reason, which is
     * indistinguishable from being broken.
     */
    @Test
    fun `every gated study runs and explains itself at chart history depth`() {
        val candles = series(ChartDataController.CHART_HISTORY_BARS)

        val apex = apex().analyze("EURUSD", Timeframe.M5, candles, ApexConfig.intraday())
        assertTrue("Apex must reach its members at chart depth", apex.votes.isNotEmpty())
        assertTrue("Apex must say what it is doing", apex.statusText.isNotBlank())

        val compass = compass().analyze("EURUSD", Timeframe.M5, candles, CompassConfig.intraday())
        assertTrue("Compass must produce calls at chart depth", compass.calls.isNotEmpty())
        assertTrue("Compass must say what it is doing", compass.statusText.isNotBlank())
        assertTrue(
            "Compass must get far enough to report a measurement rather than a bar count: " +
                compass.statusText,
            compass.calls.size >= CompassConfig.intraday().minCalibrationSample,
        )

        val crucible = CrucibleEngine().analyze("EURUSD", Timeframe.M5, candles, CrucibleConfig.intraday())
        assertTrue(
            "Crucible must be able to start at chart depth: ${crucible.statusText}",
            crucible.observations > 0,
        )
        assertTrue("Crucible must say what it is doing", crucible.statusText.isNotBlank())

        // Keystone is selective by design and may legitimately find nothing on
        // a given stretch of market. What it may never do is find nothing
        // silently: it has to reach the market, and it has to be able to say
        // which step it stood down on.
        val keystone = keystone().analyze(
            "EURUSD", Timeframe.M5, candles, correlatedPeer(candles), KeystoneConfig.intraday(),
        )
        assertTrue(
            "Keystone must reach the market at chart depth: ${keystone.note}",
            keystone.sweeps.isNotEmpty(),
        )
        assertTrue(
            "Keystone must say why it published nothing",
            keystone.signals.isNotEmpty() || keystone.note != null,
        )
        assertTrue(
            "Keystone must record which step it stood down on",
            keystone.signals.isNotEmpty() || keystone.rejections.isNotEmpty(),
        )
    }

    @Test
    fun `the chart asks for enough history for the studies it offers`() {
        // The engines' own minimums are meaningless if the chart never fetches
        // that much. This is the link that was missing.
        assertTrue(
            "the chart fetches less history than Crucible needs to run at all",
            ChartDataController.CHART_HISTORY_BARS >= CrucibleEngine.MIN_BARS,
        )
        assertTrue(
            "the chart fetches less history than Compass needs to calibrate",
            ChartDataController.CHART_HISTORY_BARS >= CompassEngine.MIN_BARS,
        )
        assertTrue(
            "the chart fetches less history than the LiT history scan reads",
            ChartDataController.CHART_HISTORY_BARS >= 5_000,
        )
        assertTrue(
            "the chart fetches less history than Keystone needs to run at all",
            ChartDataController.CHART_HISTORY_BARS >= KeystoneEngine.MIN_BARS,
        )
    }

    /**
     * The defaults must actually draw something on the history the chart holds.
     *
     * This is the guard that was missing, and its absence let three studies ship
     * that could not draw an arrow on any real chart. Their thresholds were
     * enforced honestly against measured outcomes — they were simply set above
     * what the data supports, so the studies were silent rather than wrong, and
     * an indicator that never draws is not an indicator.
     *
     * A signal here is not a claim that the signal is good. Each one carries the
     * accuracy actually measured for it, and that number is what a trader should
     * judge it by.
     */
    @Test
    fun `the default settings publish signals at chart history depth`() {
        val candles = series(ChartDataController.CHART_HISTORY_BARS)

        val apex = apex().analyze("EURUSD", Timeframe.M5, candles, ApexConfig.intraday())
        assertTrue(
            "Apex published nothing under its own defaults: ${apex.statusText}",
            apex.signals.isNotEmpty(),
        )
        assertTrue(
            "every Apex signal must carry the record it was published under",
            apex.signals.all { it.precisionAtPublication.resolved >= 0 },
        )

        val compass = compass().analyze("EURUSD", Timeframe.M5, candles, CompassConfig.intraday())
        assertTrue(
            "Compass published nothing under its own defaults: ${compass.statusText}",
            compass.signals.isNotEmpty(),
        )

        val crucible = CrucibleEngine().analyze("EURUSD", Timeframe.M5, candles, CrucibleConfig.intraday())
        assertTrue(
            "Crucible published nothing under its own defaults: ${crucible.statusText}",
            crucible.signals.isNotEmpty(),
        )
    }

    @Test
    fun `a study does not put an arrow on every other bar`() {
        // The opposite failure, and just as useless. A rule describes a state
        // that persists, so emitting on every bar it holds turns a signal into
        // a shaded region drawn one arrow at a time.
        val candles = series(ChartDataController.CHART_HISTORY_BARS)
        val crucible = CrucibleEngine().analyze("EURUSD", Timeframe.M5, candles, CrucibleConfig.intraday())

        assertTrue(
            "Crucible emitted ${crucible.signals.size} signals over ${candles.size} bars",
            crucible.signals.size < candles.size / 10,
        )

        val keystone = keystone().analyze(
            "EURUSD", Timeframe.M5, candles, correlatedPeer(candles), KeystoneConfig.intraday(),
        )
        assertTrue(
            "Keystone emitted ${keystone.signals.size} signals over ${candles.size} bars",
            keystone.signals.size < candles.size / 10,
        )
    }

    /**
     * Keystone must be able to draw at the depth the chart holds.
     *
     * Deliberately measured on a series that contains the sequence rather than
     * on a random walk, because Keystone asks for four specific things at once
     * and a walk owes it none of them. What this pins is the thing that failed
     * before: that the study is *reachable* at chart-history depth rather than
     * needing more bars than any chart will ever hand it.
     *
     * The honest counterpart is recorded rather than asserted. On the synthetic
     * trending series above, 5 000 bars produced 235 sweeps, of which 109
     * opposed the higher-timeframe bias, 45 never displaced, and 9 of the 11
     * that reached an entry had no divergence to support them — so the intraday
     * preset published nothing and the swing preset published four. That is
     * selectivity, not silence: the engine names the step it stood down on
     * every time. It is not evidence that the model is rare on real markets,
     * because a generated walk has no reason to produce this sequence at all.
     */
    @Test
    fun `Keystone publishes at chart history depth when the sequence is present`() {
        val built = KeystoneFixtures.sequence(cycles = 120)
        assertTrue(
            "the fixture must be at least as long as the history the chart holds",
            built.primary.size >= ChartDataController.CHART_HISTORY_BARS,
        )
        val candles = built.primary.take(ChartDataController.CHART_HISTORY_BARS)
        val peer = built.peer.take(ChartDataController.CHART_HISTORY_BARS)

        val keystone = keystone().analyze(
            symbol = KeystoneFixtures.SYMBOL,
            timeframe = Timeframe.M15,
            candles = candles,
            peers = listOf(KeystonePeerSeries(KeystoneFixtures.PEER, peer, KeystonePolarity.POSITIVE)),
            config = KeystoneConfig.intraday(),
        )

        assertTrue(
            "Keystone published nothing under its own defaults at chart depth: ${keystone.note}",
            keystone.signals.isNotEmpty(),
        )
        assertTrue(
            "every Keystone signal must carry the divergence it claims",
            keystone.signals.all { it.divergence != null },
        )
        assertTrue(
            "the verdict must state that the win rate is not a criterion",
            keystone.acceptance.summary.contains("not a criterion"),
        )
    }

    @Test
    fun `the studies publish on a trending market too`() {
        // A real chart trends far more often than it oscillates, and these
        // studies had only ever been measured on a mean-reverting channel.
        val candles = trending(ChartDataController.CHART_HISTORY_BARS)

        val apex = apex().analyze("EURUSD", Timeframe.M15, candles, ApexConfig.intraday())
        assertTrue("Apex drew nothing on a trending market: ${apex.statusText}", apex.signals.isNotEmpty())

        val compass = compass().analyze("EURUSD", Timeframe.M15, candles, CompassConfig.intraday())
        assertTrue(
            "Compass drew nothing on a trending market: ${compass.statusText}",
            compass.signals.isNotEmpty(),
        )
    }

    @Test
    fun `the provider will hand over as much history as the chart asks for`() {
        // The chart's request was silently clamped to a fifth of what it asked
        // for, which is a ceiling no setting could lift. Compass needs roughly
        // two thousand bars before it can calibrate at all, so the clamp and the
        // requirement were set against each other and the study stayed quiet.
        assertTrue(
            "the data source clamps below what the chart requests",
            DukascopyDataSource.MAX_CANDLE_LIMIT >= ChartDataController.CHART_HISTORY_BARS,
        )
    }
}
