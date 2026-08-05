package com.foxtrader.app.domain.usecase.litx

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.LitXStage
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.sessions.SessionDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests for the LIT X engine. Uses the real (pure) detectors — the
 * engine is constructed exactly as Hilt would wire it, verifying the whole
 * orchestration compiles and behaves safely on deterministic inputs.
 */
class LitXEngineTest {

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

    private fun candle(i: Int, o: Double, h: Double, l: Double, c: Double, v: Double = 1000.0) =
        Candle(1_700_000_000_000L + i * 60_000L, o, h, l, c, v)

    @Test
    fun `insufficient data returns empty scanning analysis with no signal`() {
        val result = engine.analyze("EURUSD", Timeframe.M15, emptyList())
        assertEquals(LitXStage.SCANNING, result.stage)
        assertNull(result.signal)
        assertEquals("EURUSD", result.symbol)
        assertEquals(Timeframe.M15, result.timeframe)
    }

    @Test
    fun `flat ranging market produces no signal`() {
        val flat = (0 until 80).map { candle(it, 1.1000, 1.1004, 1.0996, 1.1000) }
        val result = engine.analyze("EURUSD", Timeframe.M15, flat)
        assertNull("a directionless market must not produce a setup", result.signal)
    }

    @Test
    fun `trending series is analyzed without throwing and echoes context`() {
        // A steady uptrend with normal bodies — exercises the full pipeline.
        val series = (0 until 120).map { i ->
            val base = 1.1000 + i * 0.0005
            candle(i, base, base + 0.0006, base - 0.0002, base + 0.0004)
        }
        val result = engine.analyze(
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            candles = series,
            htfBias = com.foxtrader.app.domain.model.Bias.BULLISH,
            htfAlignmentScore = 80,
        )
        assertEquals("EURUSD", result.symbol)
        assertEquals(Timeframe.H1, result.timeframe)
        // Stage is always defined; any produced signal must carry its confidence.
        assertTrue(result.stage in LitXStage.entries)
        result.signal?.let { assertTrue(it.confidence.factors.size == 11) }
    }
}
