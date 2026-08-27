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
import com.foxtrader.app.domain.usecase.liquiditysweep.LiquiditySweepEngine
import com.foxtrader.app.domain.usecase.signalintel.AccumulationManipulationDistributionEngine
import com.foxtrader.app.domain.usecase.signalintel.PivotSweepDivergenceEngine
import com.foxtrader.app.domain.usecase.signalintel.RsiOrderFlowSignalEngine
import com.foxtrader.app.domain.usecase.signalintel.ValueAreaLiquidityRejectionEngine
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import com.foxtrader.app.domain.usecase.virginwick.VirginWickEngine
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.feature.chart.presentation.ChartDataController
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
    }
}
