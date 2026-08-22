package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.usecase.signalintel.SignalEvidenceReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers how [SignalComputer] folds strategy-library signals into the unified
 * chart signal list, and the rule that only *live* signals participate in
 * confluence.
 */
class StrategySignalIntegrationTest {

    private val computer = SignalComputer(SignalEvidenceReducer())

    private val candles = List(10) { i ->
        Candle(
            timestamp = 1_700_000_000_000L + i * 60_000L,
            open = 1.08, high = 1.081, low = 1.079, close = 1.0805, volume = 100.0,
        )
    }

    private fun strategySignal(
        barIndex: Int,
        direction: Direction,
        isLive: Boolean,
        confidence: Double = 0.6,
    ): ChartSignal {
        // Match SignalComputer.isRenderable geometry: stops/targets must sit on
        // the correct side of entry for the trade direction or the marker is dropped.
        val entry = 1.0800
        val (sl, tp) = when (direction) {
            Direction.BULLISH -> 1.0780 to 1.0860
            Direction.BEARISH -> 1.0820 to 1.0740
        }
        return ChartSignal(
            id = "strategy_$barIndex",
            source = SignalSource.STRATEGY,
            direction = direction,
            entry = entry,
            sl = sl,
            tp = tp,
            barIndex = barIndex,
            timestamp = candles[barIndex].timestamp,
            confidence = confidence,
            isLive = isLive,
            label = "SMC Order Block Retest",
        )
    }

    @Test
    fun `strategy signals are passed through to the chart signal list`() {
        val result = computer.computeSignals(
            litXAnalysis = null,
            tradeProAnalysis = null,
            smtDivergences = emptyList(),
            candles = candles,
            strategySignals = listOf(strategySignal(9, Direction.BULLISH, isLive = true)),
        )

        assertEquals(1, result.size)
        assertEquals(SignalSource.STRATEGY, result[0].source)
        assertEquals("SMC Order Block Retest", result[0].label)
    }

    @Test
    fun `strategy signals alone receive no confluence boost`() {
        val signals = listOf(
            strategySignal(7, Direction.BULLISH, isLive = false),
            strategySignal(9, Direction.BULLISH, isLive = true),
        )
        val result = computer.computeSignals(
            litXAnalysis = null,
            tradeProAnalysis = null,
            smtDivergences = emptyList(),
            candles = candles,
            strategySignals = signals,
        )

        // Same source repeated is not independent confirmation.
        for (signal in result) {
            assertEquals(0.6, signal.confidence, 1e-9)
        }
    }

    @Test
    fun `historical strategy signals never boost a live signal`() {
        // A stale bearish marker plus a live bearish SMT divergence: the stale
        // one must not vouch for the live one, because it describes a bar that
        // already closed.
        val stale = strategySignal(3, Direction.BEARISH, isLive = false)
        val result = computer.computeSignals(
            litXAnalysis = null,
            tradeProAnalysis = null,
            smtDivergences = emptyList(),
            candles = candles,
            strategySignals = listOf(stale, strategySignal(9, Direction.BEARISH, isLive = true)),
        )

        val live = result.single { it.isLive }
        assertEquals(0.6, live.confidence, 1e-9)
    }

    @Test
    fun `risk reward is derived from entry stop and target`() {
        val signal = strategySignal(9, Direction.BULLISH, isLive = true)
        // risk = 1.0800 - 1.0780 = 0.0020, reward = 1.0860 - 1.0800 = 0.0060
        assertEquals(3.0, signal.riskReward!!, 1e-6)
    }

    @Test
    fun `risk reward is null when no stop or target is defined`() {
        val marker = strategySignal(9, Direction.BULLISH, isLive = true).copy(sl = 0.0, tp = 0.0)
        assertTrue(marker.riskReward == null)
    }
}
