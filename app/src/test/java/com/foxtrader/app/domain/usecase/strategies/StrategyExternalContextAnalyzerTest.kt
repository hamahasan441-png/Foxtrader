package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.MarketDataFreshness
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyExternalContextAnalyzerTest {

    @Test
    fun `context truncates peer and higher timeframe bars at primary decision timestamp`() {
        val context = StrategyMarketContext(
            provider = DataProvider.DERIV,
            freshness = MarketDataFreshness.LIVE,
            peerCandles = mapOf(
                "PEER" to listOf(candle(1), candle(2), candle(8)),
                "" to listOf(candle(1)),
            ),
            higherTimeframeCandles = mapOf(
                Timeframe.H1 to listOf(candle(1), candle(3), candle(9)),
            ),
        )

        val causal = context.causalAt(cutoffTimestamp = 3 * MINUTE)

        assertTrue(causal.peerCandles.keys == setOf("PEER"))
        assertTrue(causal.peerCandles.getValue("PEER").all { it.timestamp <= 3 * MINUTE })
        assertTrue(causal.higherTimeframeCandles.getValue(Timeframe.H1).all { it.timestamp <= 3 * MINUTE })
        assertTrue(causal.decisionEligible)
    }

    @Test
    fun `external analyzer never admits future peer or HTF candles`() {
        val primary = (0 until 20).map { candle(it) }
        val futureOnlyPeer = (20 until 80).map { candle(it) }
        val futureOnlyHtf = (20 until 40).map { candle(it) }
        val analyzer = StrategyExternalContextAnalyzer(
            smtDetector = SmtDivergenceDetector(),
            analyzeStructure = AnalyzeMarketStructureUseCase(),
        )

        val result = analyzer.analyze(
            primarySymbol = "EURUSD",
            primaryTimeframe = Timeframe.M15,
            primaryCandles = primary,
            context = StrategyMarketContext(
                peerCandles = mapOf("GBPUSD" to futureOnlyPeer),
                higherTimeframeCandles = mapOf(Timeframe.H1 to futureOnlyHtf),
            ),
        )

        assertTrue(result.context.peerCandles.isEmpty())
        assertTrue(result.context.higherTimeframeCandles.isEmpty())
        assertTrue(result.smtDivergences.isEmpty())
        assertTrue(result.higherTimeframeBiases.isEmpty())
    }

    @Test
    fun `simulated freshness is not decision eligible`() {
        val context = StrategyMarketContext(
            provider = DataProvider.SAMPLE,
            freshness = MarketDataFreshness.SIMULATED,
        )

        assertFalse(context.decisionEligible)
    }

    private fun candle(index: Int): Candle {
        val close = 100.0 + index * 0.1
        return Candle(
            timestamp = index * MINUTE,
            open = close - 0.05,
            high = close + 0.20,
            low = close - 0.20,
            close = close,
            volume = 100.0 + index,
        )
    }

    companion object {
        private const val MINUTE = 60_000L
    }
}
