package com.foxtrader.app.domain.usecase.strategies

import com.foxtrader.app.domain.model.Candle
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
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class StrategyPackageEngineTest {

    private val smc = SmcDetector()
    private val structure = AnalyzeMarketStructureUseCase()
    private val ichimoku = IchimokuCloud()
    private val litX = LitXEngine(
        smcDetector = smc,
        analyzeStructure = structure,
        sessionDetector = SessionDetector(),
        displacementDetector = DisplacementDetector(),
        mitigationDetector = MitigationBlockDetector(),
        premiumDiscount = PremiumDiscountCalculator(),
        mssClassifier = MssClassifier(),
        scorer = LitXConfidenceScorer(),
    )
    private val library = StrategyLibrary(
        smcDetector = smc,
        analyzeStructure = structure,
        ichimokuCloud = ichimoku,
        litXEngine = litX,
    )

    @After
    fun resetSettings() {
        StrategyRuntimeSettingsRegistry.resetAll()
    }

    @Test
    fun `strategy package truncates future candles before every detector`() {
        val candles = series(180)
        val index = 120
        val prefix = candles.subList(0, index + 1)

        val fromFull = library.analyze(
            type = StrategyType.CONFLUENCE,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            candles = candles,
            index = index,
        )
        val fromPrefix = library.analyze(
            type = StrategyType.CONFLUENCE,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            candles = prefix,
            index = prefix.lastIndex,
        )

        assertEquals(fromPrefix.technical.ema9, fromFull.technical.ema9, 0.0)
        assertEquals(fromPrefix.technical.ema20, fromFull.technical.ema20, 0.0)
        assertEquals(fromPrefix.technical.ema21, fromFull.technical.ema21, 0.0)
        assertEquals(fromPrefix.technical.ema50, fromFull.technical.ema50, 0.0)
        assertEquals(fromPrefix.technical.rsi14, fromFull.technical.rsi14, 0.0)
        assertEquals(fromPrefix.technical.atr14, fromFull.technical.atr14, 0.0)
        assertEquals(fromPrefix.technical.adx14, fromFull.technical.adx14, 0.0)
        assertEquals(fromPrefix.technical.macdHistogram, fromFull.technical.macdHistogram, 0.0)
        assertEquals(fromPrefix.technical.ichimokuPosition, fromFull.technical.ichimokuPosition)
        assertArrayEquals(fromPrefix.technical.ichimoku.tenkan, fromFull.technical.ichimoku.tenkan, 0.0)
        assertArrayEquals(fromPrefix.technical.ichimoku.kijun, fromFull.technical.ichimoku.kijun, 0.0)
        assertArrayEquals(fromPrefix.technical.ichimoku.senkouA, fromFull.technical.ichimoku.senkouA, 0.0)
        assertArrayEquals(fromPrefix.technical.ichimoku.senkouB, fromFull.technical.ichimoku.senkouB, 0.0)
        assertEquals(fromPrefix.structure, fromFull.structure)
        assertEquals(fromPrefix.smc, fromFull.smc)
        assertEquals(fromPrefix.patterns, fromFull.patterns)
        assertEquals(fromPrefix.wyckoff, fromFull.wyckoff)
        assertEquals(fromPrefix.sessions, fromFull.sessions)
        assertEquals(fromPrefix.evidence, fromFull.evidence)
        assertEquals(fromPrefix.signal, fromFull.signal)
    }

    @Test
    fun `library executable signal is produced by the same package analysis`() {
        val candles = series(180)
        val definition = library.get(StrategyType.TREND_FOLLOWING, "EURUSD", Timeframe.H1)
        val index = candles.lastIndex

        val viaFunction = definition.function(candles, index)
        val viaPackage = library.analyze(
            type = StrategyType.TREND_FOLLOWING,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            candles = candles,
            index = index,
        ).signal

        assertEquals(viaPackage, viaFunction)
    }

    @Test
    fun `package exposes complete reusable market context`() {
        val candles = series(180)
        val analysis = library.analyze(
            type = StrategyType.SMART_MONEY,
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            candles = candles,
            index = candles.lastIndex,
        )

        assertEquals(candles.lastIndex, analysis.index)
        assertEquals(candles.last().timestamp, analysis.timestamp)
        assertTrue(analysis.technical.atr14.isFinite())
        assertTrue(analysis.technical.rsi14.isFinite())
        assertTrue(analysis.technical.adx14.isFinite())
        assertNotNull(analysis.structure)
        assertNotNull(analysis.smc.orderBlocks)
        assertNotNull(analysis.smc.fairValueGaps)
        assertNotNull(analysis.smc.liquidityPools)
        assertNotNull(analysis.smc.breakerBlocks)
        assertNotNull(analysis.smc.inversionFVGs)
        assertNotNull(analysis.smc.balancedPriceRanges)
        assertNotNull(analysis.smc.amdPatterns)
        assertNotNull(analysis.patterns)
        assertNotNull(analysis.wyckoff)
        assertNotNull(analysis.sessions)
        assertTrue(analysis.narrative.contains("SMC["))
        assertTrue(analysis.narrative.contains("PAT="))
        assertTrue(analysis.narrative.contains("WYC="))
    }

    @Test
    fun `all built in strategies resolve as package definitions`() {
        val all = library.all("EURUSD", Timeframe.H1)
        assertEquals(StrategyType.entries.size, all.size)
        StrategyType.entries.forEach { type ->
            val definition = all[type]
            assertNotNull(definition)
            assertTrue("$type should advertise package architecture", definition!!.name.contains("Package"))
        }
    }

    private fun series(size: Int): List<Candle> = (0 until size).map { i ->
        val trend = 100.0 + i * 0.08
        val wave = sin(i / 5.0) * 1.8
        val open = trend + wave
        val close = open + sin(i / 3.0) * 0.7
        Candle(
            timestamp = 1_735_689_600_000L + i * 3_600_000L,
            open = open,
            high = maxOf(open, close) + 1.2,
            low = minOf(open, close) - 1.2,
            close = close,
            volume = 1_000.0 + (i % 9) * 175.0,
        )
    }
}
