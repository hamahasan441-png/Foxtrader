package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.MarketDataFreshness
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyExternalContextAnalyzerTest {

    @Test
    fun `legacy context cutoff truncates future bars and blank peer symbols`() {
        val context = StrategyMarketContext(
            provider = DataProvider.DERIV,
            freshness = MarketDataFreshness.LIVE,
            peerCandles = mapOf(
                "PEER" to listOf(candleMinutes(1), candleMinutes(2), candleMinutes(8)),
                "" to listOf(candleMinutes(1)),
            ),
            higherTimeframeCandles = mapOf(
                Timeframe.H1 to listOf(candleMinutes(1), candleMinutes(3), candleMinutes(9)),
            ),
        )

        val causal = context.causalAt(cutoffTimestamp = 3 * MINUTE)

        assertTrue(causal.peerCandles.keys == setOf("PEER"))
        assertTrue(causal.peerCandles.getValue("PEER").all { it.timestamp <= 3 * MINUTE })
        assertTrue(causal.higherTimeframeCandles.getValue(Timeframe.H1).all { it.timestamp <= 3 * MINUTE })
        assertTrue(causal.decisionEligible)
    }

    @Test
    fun `timeframe aware cutoff rejects higher timeframe candle that has not closed`() {
        // Primary H1 decision bar opens at 12:00 and closes at 13:00.
        // The 08:00 H4 candle closed at 12:00 and is causal. The 12:00 H4
        // candle closes at 16:00 and must not be visible to the 13:00 decision.
        val decisionOpen = 12 * HOUR
        val closedH4 = candleAt(8 * HOUR)
        val stillOpenH4 = candleAt(12 * HOUR)
        val context = StrategyMarketContext(
            higherTimeframeCandles = mapOf(
                Timeframe.H4 to listOf(closedH4, stillOpenH4),
            ),
        )

        val causal = context.causalAt(
            decisionBarOpenTimestamp = decisionOpen,
            primaryTimeframe = Timeframe.H1,
        )

        assertEquals(listOf(closedH4), causal.higherTimeframeCandles[Timeframe.H4])
    }

    @Test
    fun `external analyzer never admits future peer or HTF candles`() {
        val primary = (0 until 20).map { candleMinutes(it) }
        val futureOnlyPeer = (20 until 80).map { candleMinutes(it) }
        val futureOnlyHtf = (20 until 40).map { candleMinutes(it) }
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

    private fun candleMinutes(index: Int): Candle = candleAt(index * MINUTE)

    private fun candleAt(timestamp: Long): Candle {
        val index = timestamp.toDouble() / MINUTE
        val close = 100.0 + index * 0.1
        return Candle(
            timestamp = timestamp,
            open = close - 0.05,
            high = close + 0.20,
            low = close - 0.20,
            close = close,
            volume = 100.0 + index,
        )
    }

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
    }
}
