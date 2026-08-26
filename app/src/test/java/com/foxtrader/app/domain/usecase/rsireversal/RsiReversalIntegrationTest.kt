package com.foxtrader.app.domain.usecase.rsireversal

import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.feature.chart.presentation.RsiReversalEntryPreset
import com.foxtrader.app.feature.chart.presentation.RsiReversalStudySettings
import com.foxtrader.app.feature.chart.presentation.toEngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundaries between the engine and the rest of the app: the settings
 * mapper, the timeframe mapping (§15) and the backtest entry point (§33).
 */
class RsiReversalIntegrationTest {

    private val engine = RsiReversalEngine()

    // ------------------------------------------------------------------
    // Settings mapper
    // ------------------------------------------------------------------

    @Test
    fun `default settings map to the specification default preset`() {
        val config = RsiReversalStudySettings().toEngineConfig()

        assertEquals(14, config.rsiLength)
        assertEquals(2, config.pricePivotLeft)
        assertEquals(2, config.pricePivotRight)
        assertEquals(2, config.rsiPivotLeft)
        assertEquals(2, config.rsiPivotRight)
        assertEquals(BreakMode.CLOSE_BREAK, config.rsiBreakMode)
        assertEquals(BreakMode.WICK_BREAK, config.priceExtremeMode)
        assertEquals(EntryMode.BALANCED, config.entryMode)
        assertEquals(4.0, config.riskReward, 1e-9)
    }

    @Test
    fun `the mapper never weakens the repaint protection or the disabled filters`() {
        // §26 filters must stay off until researched, and the warmup exclusion
        // is repaint protection rather than a tuning knob.
        val config = RsiReversalStudySettings(rsiLength = 40).toEngineConfig()

        assertNull(config.warmupBarsOverride)
        assertEquals(400, config.warmupBars)
        assertEquals(0, config.minBarsBetweenPivots)
        assertEquals(0.0, config.minRsiDivergenceDistance, 1e-9)
        assertEquals(0.0, config.minPriceExtremeDistanceFraction, 1e-9)
    }

    @Test
    fun `settings are sanitized before they reach the engine`() {
        val config = RsiReversalStudySettings(
            rsiLength = -5,
            pricePivotStrength = 0,
            riskReward = 9_999.0,
            ltfConfirmationWindowBars = 0,
        ).toEngineConfig()

        assertTrue(config.rsiLength >= 2)
        assertTrue(config.pricePivotLeft >= 1)
        assertTrue(config.riskReward <= 20.0)
        assertTrue(config.ltfConfirmationWindowBars >= 1)
    }

    @Test
    fun `each entry preset maps to its engine mode`() {
        assertEquals(
            EntryMode.AGGRESSIVE,
            RsiReversalStudySettings(entryMode = RsiReversalEntryPreset.AGGRESSIVE).toEngineConfig().entryMode,
        )
        assertEquals(
            EntryMode.STRICT,
            RsiReversalStudySettings(entryMode = RsiReversalEntryPreset.STRICT).toEngineConfig().entryMode,
        )
    }

    // ------------------------------------------------------------------
    // §15 — timeframe mapping
    // ------------------------------------------------------------------

    @Test
    fun `the default mapping matches the specification`() {
        val config = RsiReversalConfig()
        assertEquals(Timeframe.H4, config.entryTimeframe(Timeframe.D1))
        assertEquals(Timeframe.H1, config.entryTimeframe(Timeframe.H4))
        assertEquals(Timeframe.M15, config.entryTimeframe(Timeframe.H1))
        assertEquals(Timeframe.M5, config.entryTimeframe(Timeframe.M30))
        assertEquals(Timeframe.M5, config.entryTimeframe(Timeframe.M15))
        assertEquals(Timeframe.M1, config.entryTimeframe(Timeframe.M5))
        assertNull("M1 has nothing below it", config.entryTimeframe(Timeframe.M1))
    }

    @Test
    fun `context timeframes invert the mapping`() {
        val config = RsiReversalConfig()
        assertEquals(Timeframe.H1, engine.contextTimeframeFor(Timeframe.M15, config))
        assertEquals(Timeframe.M5, engine.contextTimeframeFor(Timeframe.M1, config))
        assertNull(engine.contextTimeframeFor(Timeframe.W1, config))

        // §15 sends both 30m and 15m down to 5m, so the inverse is ambiguous.
        // It must resolve to the nearer context, not to whichever the mapping
        // happens to declare first.
        assertEquals(Timeframe.M15, engine.contextTimeframeFor(Timeframe.M5, config))
    }

    // ------------------------------------------------------------------
    // §33 — backtest entry point
    // ------------------------------------------------------------------

    @Test
    fun `the backtest function reports nothing when there is no context timeframe above`() {
        val candles = RsiReversalFixtures.m5Series(500)
        // W1 is not an entry timeframe in the mapping, so no context exists.
        assertNull(engine.signalAt(RsiReversalFixtures.SYMBOL, Timeframe.W1, candles, 400))
    }

    @Test
    fun `the backtest function is bounds safe`() {
        val candles = RsiReversalFixtures.m5Series(500)
        assertNull(engine.signalAt(RsiReversalFixtures.SYMBOL, Timeframe.M5, candles, -1))
        assertNull(engine.signalAt(RsiReversalFixtures.SYMBOL, Timeframe.M5, candles, 9_999))
        assertNull(engine.signalAt(RsiReversalFixtures.SYMBOL, Timeframe.M5, emptyList(), 0))
    }

    @Test
    fun `the backtest function never reads past the evaluated bar`() {
        // Truncating the series at the evaluated bar must not change the answer.
        val candles = RsiReversalFixtures.m5Series(4_000, seed = 13)
        val index = 3_000

        val withFuture = engine.signalAt(RsiReversalFixtures.SYMBOL, Timeframe.M5, candles, index, BACKTEST_CONFIG)
        val withoutFuture = engine.signalAt(
            RsiReversalFixtures.SYMBOL,
            Timeframe.M5,
            candles.take(index + 1),
            index,
            BACKTEST_CONFIG,
        )
        assertEquals(withFuture, withoutFuture)
    }

    @Test
    fun `backtest signals carry complete and correctly sided geometry`() {
        val candles = RsiReversalFixtures.m5Series(6_000, seed = 21)
        val signals = entryBars(candles).mapNotNull { index ->
            engine.signalAt(RsiReversalFixtures.SYMBOL, Timeframe.M5, candles, index, BACKTEST_CONFIG)
        }

        assertTrue("the fixture should produce backtestable signals", signals.isNotEmpty())
        signals.forEach { signal ->
            assertNotNull(signal.setupType)
            assertTrue(signal.entry.isFinite() && signal.entry > 0.0)
            assertTrue(signal.stopLoss.isFinite() && signal.takeProfit.isFinite())
            val risk = kotlin.math.abs(signal.entry - signal.stopLoss)
            val reward = kotlin.math.abs(signal.takeProfit - signal.entry)
            assertTrue("risk must be positive", risk > 0.0)
            assertEquals("target must be exactly 4R", 4.0, reward / risk, 1e-6)
            assertEquals(candles[signal.index].timestamp, signal.timestamp)
        }
    }

    @Test
    fun `the context series never includes an unfinished higher timeframe bar`() {
        // A signal reported at bar t must not depend on a context bar that only
        // closes after t. Probing the bars that actually confirm — rather than
        // an arbitrary range where both answers would be null — is what makes
        // this assertion mean something.
        val candles = RsiReversalFixtures.m5Series(6_000, seed = 21)
        val bars = entryBars(candles)
        assertTrue("fixture must supply bars to probe", bars.isNotEmpty())

        bars.forEach { index ->
            val full = engine.signalAt(RsiReversalFixtures.SYMBOL, Timeframe.M5, candles, index, BACKTEST_CONFIG)
            val truncated = engine.signalAt(
                RsiReversalFixtures.SYMBOL,
                Timeframe.M5,
                candles.take(index + 1),
                index,
                BACKTEST_CONFIG,
            )
            assertEquals("bar $index disagreed with its own prefix", full, truncated)
        }
    }

    /**
     * Entry-timeframe bar indices the engine confirms on.
     *
     * Sweeping every bar would be quadratic, and sampling every nth bar mostly
     * lands between confirmations. This asks the engine where its entries fall
     * and probes exactly those bars.
     */
    private fun entryBars(candles: List<com.foxtrader.app.domain.model.Candle>): List<Int> {
        val context = com.foxtrader.app.domain.usecase.tradepro.TimeframeResampler
            .resample(candles, Timeframe.M15)
        val confirmedAt = engine.analyze(
            symbol = RsiReversalFixtures.SYMBOL,
            timeframe = Timeframe.M15,
            candles = context,
            ltfCandles = candles,
            ltfTimeframe = Timeframe.M5,
            config = BACKTEST_CONFIG,
        ).signals.map { it.confirmedAt }.toSet()

        return candles.indices.filter { candles[it].timestamp in confirmedAt }
    }

    private companion object {
        /**
         * A wider entry window than the shipped default.
         *
         * On a synthetic random walk a sweep-and-reclaim followed by a change of
         * character inside 12 bars is genuinely rare. These tests are about the
         * backtest path's correctness, not its hit rate, so the window is opened
         * far enough to give them signals to assert on.
         */
        val BACKTEST_CONFIG = RsiReversalConfig(ltfConfirmationWindowBars = 48)
    }
}
