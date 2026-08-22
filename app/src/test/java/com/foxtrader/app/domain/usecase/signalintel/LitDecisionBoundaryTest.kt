package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.litx.DisplacementDetector
import com.foxtrader.app.domain.usecase.litx.PremiumDiscountCalculator
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Extra guard that malformed data never reaches an executable LiT decision. */
class LitDecisionBoundaryTest {
    private val engine = LitEngine(
        smcDetector = SmcDetector(),
        analyzeStructure = AnalyzeMarketStructureUseCase(),
        displacementDetector = DisplacementDetector(),
        premiumDiscount = PremiumDiscountCalculator(),
    )

    @Test
    fun `non finite provider price fails closed`() {
        val candles = validSeries().toMutableList()
        val last = candles.last()
        candles[candles.lastIndex] = last.copy(close = Double.NaN)

        val analysis = engine.analyze("EURUSD", Timeframe.M15, candles)
        assertNull(analysis.signal)
        assertEquals(true, analysis.narrative.contains("finite", ignoreCase = true))
    }

    private fun validSeries(): List<Candle> = (0 until 80).map { i ->
        val close = 100.0 + (i % 7) * 0.1
        Candle(
            timestamp = 1_700_000_000_000L + i * 15 * 60_000L,
            open = close,
            high = close + 0.2,
            low = close - 0.2,
            close = close,
            volume = 1000.0,
        )
    }
}
