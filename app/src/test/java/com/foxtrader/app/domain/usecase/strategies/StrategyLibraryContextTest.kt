package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.MarketDataFreshness
import com.foxtrader.app.domain.model.StrategyType
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
import com.foxtrader.app.domain.usecase.litx.DisplacementDetector
import com.foxtrader.app.domain.usecase.litx.LitXConfidenceScorer
import com.foxtrader.app.domain.usecase.litx.LitXEngine
import com.foxtrader.app.domain.usecase.litx.MitigationBlockDetector
import com.foxtrader.app.domain.usecase.litx.MssClassifier
import com.foxtrader.app.domain.usecase.litx.PremiumDiscountCalculator
import com.foxtrader.app.domain.usecase.sessions.SessionDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class StrategyLibraryContextTest {
    private val smc = SmcDetector()
    private val structure = AnalyzeMarketStructureUseCase()
    private val library = StrategyLibrary(
        smcDetector = smc,
        analyzeStructure = structure,
        ichimokuCloud = IchimokuCloud(),
        litXEngine = LitXEngine(
            smcDetector = smc,
            analyzeStructure = structure,
            sessionDetector = SessionDetector(),
            displacementDetector = DisplacementDetector(),
            mitigationDetector = MitigationBlockDetector(),
            premiumDiscount = PremiumDiscountCalculator(),
            mssClassifier = MssClassifier(),
            scorer = LitXConfidenceScorer(),
        ),
    )

    @Test
    fun `contextual library analysis applies one causal cutoff to every external series`() {
        val primary = series(150, Timeframe.H1)
        val decisionIndex = 100
        val cutoff = primary[decisionIndex].timestamp
        val context = StrategyMarketContext(
            provider = DataProvider.DERIV,
            freshness = MarketDataFreshness.LIVE,
            peerCandles = mapOf("GBPUSD" to series(170, Timeframe.H1)),
            higherTimeframeCandles = mapOf(Timeframe.H4 to series(90, Timeframe.H4)),
        )

        val result = library.analyzeWithContext(
            type = StrategyType.CONFLUENCE,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            candles = primary,
            index = decisionIndex,
            context = context,
        )

        assertTrue(result.externalAnalysis.context.peerCandles.values.flatten().all { it.timestamp <= cutoff })
        assertTrue(result.externalAnalysis.context.higherTimeframeCandles.values.flatten().all { it.timestamp <= cutoff })
        assertTrue(result.allEvidence.any { it.source == "PROVIDER" })
        assertTrue(result.allEvidence.any { it.source == "FRESHNESS" })
        assertTrue(result.decisionEligible)
    }

    @Test
    fun `full history and truncated history produce identical contextual evidence at same decision bar`() {
        val primary = series(150, Timeframe.H1)
        val decisionIndex = 100
        val prefix = primary.subList(0, decisionIndex + 1)
        val context = StrategyMarketContext(
            provider = DataProvider.DERIV,
            freshness = MarketDataFreshness.CACHED,
            peerCandles = mapOf("GBPUSD" to series(170, Timeframe.H1)),
            higherTimeframeCandles = mapOf(Timeframe.H4 to series(90, Timeframe.H4)),
        )

        val fromFull = library.analyzeWithContext(
            type = StrategyType.CONFLUENCE,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            candles = primary,
            index = decisionIndex,
            context = context,
        )
        val fromPrefix = library.analyzeWithContext(
            type = StrategyType.CONFLUENCE,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            candles = prefix,
            index = prefix.lastIndex,
            context = context,
        )

        assertEquals(fromPrefix.packageAnalysis.evidence, fromFull.packageAnalysis.evidence)
        assertEquals(fromPrefix.externalAnalysis.evidence, fromFull.externalAnalysis.evidence)
        assertEquals(fromPrefix.externalAnalysis.smtDivergences, fromFull.externalAnalysis.smtDivergences)
        assertEquals(fromPrefix.externalAnalysis.higherTimeframeBiases, fromFull.externalAnalysis.higherTimeframeBiases)
        assertEquals(fromPrefix.allEvidence, fromFull.allEvidence)
    }

    @Test
    fun `simulated contextual data cannot authorize a package signal`() {
        val primary = series(150, Timeframe.H1)
        val result = library.analyzeWithContext(
            type = StrategyType.CONFLUENCE,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            candles = primary,
            index = primary.lastIndex,
            context = StrategyMarketContext(
                provider = DataProvider.SAMPLE,
                freshness = MarketDataFreshness.SIMULATED,
            ),
        )

        assertFalse(result.decisionEligible)
        assertEquals(null, result.signal)
    }

    private fun series(size: Int, timeframe: Timeframe): List<Candle> = (0 until size).map { i ->
        val trend = 100.0 + i * 0.06
        val wave = sin(i / 5.0) * 1.4
        val open = trend + wave
        val close = open + sin(i / 3.0) * 0.5
        Candle(
            timestamp = START + i * timeframe.minutes.toLong() * 60_000L,
            open = open,
            high = maxOf(open, close) + 0.9,
            low = minOf(open, close) - 0.9,
            close = close,
            volume = 1_000.0 + (i % 7) * 80.0,
        )
    }

    companion object {
        private const val START = 1_735_689_600_000L
    }
}
