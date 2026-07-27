package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketExplanationEngineTest {

    private val engine = MarketExplanationEngine(
        analyzeStructure = AnalyzeMarketStructureUseCase(),
        smcDetector = SmcDetector(),
    )

    @Test
    fun `insufficient candles returns safe neutral explanation`() {
        val explanation = engine.explain("EURUSD", Timeframe.M15, candles(20))

        assertEquals(MarketDirectionalContext.NEUTRAL, explanation.directionalContext)
        assertTrue(explanation.tags.contains("INSUFFICIENT_DATA"))
        assertTrue(explanation.summary.contains("insufficient", ignoreCase = true))
    }

    @Test
    fun `bullish trend in premium creates caution warning and key levels`() {
        val data = trendingCandles(up = true)
        val explanation = engine.explain(
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
            candles = data,
            htfCandles = mapOf(Timeframe.H4 to data),
        )

        assertTrue(explanation.summary.contains("EURUSD"))
        assertTrue(explanation.keyLevels.isNotEmpty())
        assertTrue(explanation.mentorNotes.isNotEmpty())
        assertTrue(explanation.tags.isNotEmpty())
        assertFalse(explanation.liquidityNarrative.isBlank())
    }

    @Test
    fun `high volatility adds risk warning`() {
        val explanation = engine.explain("BTCUSDT", Timeframe.H1, volatileCandles())

        assertEquals(MarketVolatilityRegime.HIGH, explanation.volatilityRegime)
        assertTrue(explanation.warnings.any { it.contains("volatility", ignoreCase = true) })
    }

    private fun candles(count: Int): List<Candle> = (0 until count).map { i ->
        Candle(
            timestamp = i * 60_000L,
            open = 100.0 + i * 0.1,
            high = 100.8 + i * 0.1,
            low = 99.4 + i * 0.1,
            close = 100.4 + i * 0.1,
            volume = 100.0,
        )
    }

    private fun trendingCandles(up: Boolean): List<Candle> = (0 until 140).map { i ->
        val drift = if (up) i * 0.25 else -i * 0.25
        val close = 100.0 + drift
        Candle(
            timestamp = i * 60_000L,
            open = close - if (up) 0.18 else -0.18,
            high = close + 0.75,
            low = close - 0.75,
            close = close,
            volume = 100.0 + i,
        )
    }

    private fun volatileCandles(): List<Candle> = (0 until 100).map { i ->
        val close = 100.0 + (i % 3 - 1) * 2.0
        Candle(
            timestamp = i * 60_000L,
            open = close - 3.0,
            high = close + 6.0,
            low = close - 6.0,
            close = close,
            volume = 200.0,
        )
    }
}
