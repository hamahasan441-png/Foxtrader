package com.foxtrader.app.domain.usecase.scanner

import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.MarketDataFreshness
import com.foxtrader.app.domain.model.ScannerRiskLevel
import com.foxtrader.app.domain.model.ScreenerResult
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import com.foxtrader.app.domain.usecase.strategies.StrategyExternalContextAnalyzer
import com.foxtrader.app.domain.usecase.strategies.StrategyMarketContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerContextEnricherTest {
    private val enricher = ScannerContextEnricher()

    @Test
    fun `precomputed HTF and SMT context promotes aligned low risk scan`() {
        val result = enricher.enrich(
            base = baseResult(),
            external = external(
                freshness = MarketDataFreshness.LIVE,
                htf = mapOf(Timeframe.H4 to Bias.BULLISH, Timeframe.D1 to Bias.BULLISH),
                smt = listOf(smt(Direction.BULLISH, "GBPUSD")),
            ),
        )

        assertEquals(1.0, result.mtfAlignment, 1e-9)
        assertTrue(result.smtConfirmed)
        assertEquals("GBPUSD", result.smtPeer)
        assertTrue(result.actionable)
        assertTrue(result.score > 72)
    }

    @Test
    fun `simulated context can never become actionable`() {
        val result = enricher.enrich(
            base = baseResult(),
            external = external(
                freshness = MarketDataFreshness.SIMULATED,
                htf = mapOf(Timeframe.H4 to Bias.BULLISH),
                smt = emptyList(),
            ),
        )

        assertFalse(result.actionable)
        assertEquals(0.25, result.riskMultiplier, 1e-9)
        assertTrue(result.tags.any { it.contains("data blocked") })
    }

    @Test
    fun `opposing SMT context fails actionability and reduces score`() {
        val base = baseResult()
        val result = enricher.enrich(
            base = base,
            external = external(
                freshness = MarketDataFreshness.LIVE,
                htf = mapOf(Timeframe.H4 to Bias.BULLISH, Timeframe.D1 to Bias.BULLISH),
                smt = listOf(smt(Direction.BEARISH, "GBPUSD")),
            ),
        )

        assertFalse(result.smtConfirmed)
        assertFalse(result.actionable)
        assertTrue(result.tags.contains("SMT conflict"))
        assertTrue(result.score < base.score + 12)
    }

    private fun external(
        freshness: MarketDataFreshness,
        htf: Map<Timeframe, Bias>,
        smt: List<SmtDivergenceDetector.SmtDivergence>,
    ) = StrategyExternalContextAnalyzer.Analysis(
        context = StrategyMarketContext(freshness = freshness),
        smtDivergences = smt,
        higherTimeframeBiases = htf,
        evidence = emptyList(),
    )

    private fun smt(
        direction: Direction,
        peer: String,
    ) = SmtDivergenceDetector.SmtDivergence(
        primarySymbol = "EURUSD",
        peerSymbol = peer,
        direction = direction,
        type = SmtDivergenceDetector.SmtType.PRIMARY_SWEEP_PEER_FAIL,
        primaryIndex = 80,
        peerIndex = 80,
        primaryPrice = 1.1,
        peerPrice = 1.2,
        correlation = 0.8,
        confidence = 82.0,
        detail = "test",
        confirmationIndex = 82,
    )

    private fun baseResult() = ScreenerResult(
        symbol = "EURUSD",
        assetClass = AssetClass.FOREX,
        strategy = StrategyType.CONFLUENCE,
        direction = Direction.BULLISH,
        score = 72,
        bias = Bias.BULLISH,
        trendStrength = 60.0,
        momentum = 60.0,
        volatility = 30.0,
        setupQuality = 70.0,
        categories = emptyList(),
        tags = emptyList(),
        lastPrice = 1.10,
        changePercent = 0.5,
        riskLevel = ScannerRiskLevel.LOW,
    )
}
