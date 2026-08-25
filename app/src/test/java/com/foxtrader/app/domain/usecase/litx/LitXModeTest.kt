package com.foxtrader.app.domain.usecase.litx

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.LitXConfig
import com.foxtrader.app.domain.model.LitXMode
import com.foxtrader.app.domain.model.SignalProfile
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.sessions.SessionDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * LiT Adventure mode contract.
 *
 * A [LitXMode] is only worth having if it is a different rule set, not a
 * different set of numbers. These tests pin the three properties that make that
 * claim checkable:
 *
 *  1. SNIPER is strictly a subset of PRECISION on identical inputs and
 *     identical thresholds — it adds gates and removes none.
 *  2. The modes actually disagree on real data; if every mode returned the same
 *     signals the feature would be decoration.
 *  3. No mode weakens the causal contract: every emitted signal is still
 *     confirmed on the newest bar, whatever the mode.
 */
class LitXModeTest {

    private val engine = LitXEngine(
        smcDetector = SmcDetector(),
        analyzeStructure = AnalyzeMarketStructureUseCase(),
        sessionDetector = SessionDetector(),
        displacementDetector = DisplacementDetector(),
        mitigationDetector = MitigationBlockDetector(),
        premiumDiscount = PremiumDiscountCalculator(),
        mssClassifier = MssClassifier(),
        scorer = LitXConfidenceScorer(),
    )

    /**
     * Thresholds are held constant across modes so any difference in output is
     * attributable to the rule set alone, not to preset numbers.
     */
    private fun configFor(mode: LitXMode) = LitXConfig(
        minGrade = com.foxtrader.app.domain.model.LitXGrade.B,
        minRiskReward = 1.2,
        requireHtfAlignment = false,
        requireStrongMss = false,
        requireDirectionalZone = false,
        minConfidenceScore = 55,
        profile = SignalProfile.INTRADAY,
        mode = mode,
    )

    @Test
    fun `sniper never fires where precision does not`() {
        var sniperCount = 0
        var precisionCount = 0
        val violations = mutableListOf<String>()

        for (seed in 0 until SERIES_COUNT) {
            val series = walk(seed)
            for (end in WARMUP..series.size) {
                val window = series.subList(0, end)
                val sniper = signalOf(window, LitXMode.SNIPER)
                val precision = signalOf(window, LitXMode.PRECISION)
                if (sniper != null) sniperCount++
                if (precision != null) precisionCount++
                if (sniper != null && precision == null) {
                    violations += "seed=$seed bars=$end: SNIPER emitted where PRECISION did not"
                }
                if (sniper != null && precision != null && sniper.direction != precision.direction) {
                    violations += "seed=$seed bars=$end: SNIPER/PRECISION disagree on direction"
                }
            }
        }

        assertTrue(
            "fixture must produce PRECISION setups, otherwise this test is vacuous",
            precisionCount > 0,
        )
        assertTrue(violations.take(5).joinToString("\n"), violations.isEmpty())
        assertTrue(
            "SNIPER ($sniperCount) must not be more permissive than PRECISION ($precisionCount)",
            sniperCount <= precisionCount,
        )
    }

    @Test
    fun `modes disagree on at least some bars`() {
        val counts = LitXMode.entries.associateWith { 0 }.toMutableMap()
        var disagreements = 0

        for (seed in 0 until SERIES_COUNT) {
            val series = walk(seed)
            for (end in WARMUP..series.size) {
                val window = series.subList(0, end)
                val fired = LitXMode.entries.filter { mode ->
                    val hit = signalOf(window, mode) != null
                    if (hit) counts[mode] = counts.getValue(mode) + 1
                    hit
                }
                if (fired.isNotEmpty() && fired.size < LitXMode.entries.size) disagreements++
            }
        }

        assertTrue(
            "modes produced identical output everywhere, so they are not distinct rule sets " +
                "(counts=$counts)",
            disagreements > 0,
        )
    }

    @Test
    fun `every mode emits only on the newest confirmed bar`() {
        for (seed in 0 until SERIES_COUNT) {
            val series = walk(seed)
            for (mode in LitXMode.entries) {
                for (end in WARMUP..series.size) {
                    val window = series.subList(0, end)
                    val signal = signalOf(window, mode) ?: continue
                    assertEquals(
                        "$mode emitted a signal anchored off the right edge (seed=$seed bars=$end)",
                        window.lastIndex,
                        signal.confirmationIndex,
                    )
                }
            }
        }
    }

    @Test
    fun `every emitted signal records the mode that produced it`() {
        var checked = 0
        for (seed in 0 until SERIES_COUNT) {
            val series = walk(seed)
            for (mode in LitXMode.entries) {
                for (end in WARMUP..series.size) {
                    val signal = signalOf(series.subList(0, end), mode) ?: continue
                    checked++
                    assertTrue(
                        "signal from $mode is not attributable to its rule set: ${signal.confirmations}",
                        signal.confirmations.contains("MODE_${mode.name}"),
                    )
                }
            }
        }
        assertTrue("no signals were produced, so traceability was never exercised", checked > 0)
    }

    @Test
    fun `default mode is the historical precision rule set`() {
        assertEquals(LitXMode.PRECISION, LitXConfig().mode)
        assertEquals(LitXMode.PRECISION, LitXConfig.preset(SignalProfile.INTRADAY).mode)
    }

    @Test
    fun `mode presets keep their own gates`() {
        val sniper = LitXConfig.preset(LitXMode.SNIPER)
        assertTrue("sniper must not relax HTF alignment", sniper.requireHtfAlignment)
        assertTrue("sniper must not relax MSS strength", sniper.requireStrongMss)

        val reversal = LitXConfig.preset(LitXMode.SWEEP_REVERSAL)
        assertTrue(
            "a counter-trend reversal mode cannot require trend agreement",
            !reversal.requireHtfAlignment,
        )

        val momentum = LitXConfig.preset(LitXMode.MOMENTUM)
        assertTrue(
            "a continuation entry cannot require a discount/premium origin",
            !momentum.requireDirectionalZone,
        )
    }

    private fun signalOf(candles: List<Candle>, mode: LitXMode) =
        engine.analyze("EURUSD", Timeframe.M15, candles, configFor(mode)).signal

    /**
     * Deterministic random walk with enough structure (sweeps, impulses,
     * pullbacks) for the pipeline to reach validation on some windows.
     */
    private fun walk(seed: Int): List<Candle> {
        val random = Random(seed)
        val out = ArrayList<Candle>(BARS)
        var price = 1.1000
        var drift = 0.00004
        for (i in 0 until BARS) {
            if (i % 40 == 0) drift = random.nextDouble(-0.00012, 0.00012)
            val impulse = if (random.nextInt(18) == 0) random.nextDouble(-0.0020, 0.0020) else 0.0
            val open = price
            price += drift + random.nextDouble(-0.0006, 0.0006) + impulse
            val close = price
            val high = maxOf(open, close) + random.nextDouble(0.00005, 0.0004)
            val low = minOf(open, close) - random.nextDouble(0.00005, 0.0004)
            out += Candle(
                timestamp = BASE_TIMESTAMP + i * 900_000L,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = random.nextDouble(600.0, 2_400.0),
            )
        }
        return out
    }

    private companion object {
        const val SERIES_COUNT = 12
        const val BARS = 220
        const val WARMUP = 80
        const val BASE_TIMESTAMP = 1_700_000_000_000L
    }
}
