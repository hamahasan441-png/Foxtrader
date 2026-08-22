package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.litx.DisplacementDetector
import com.foxtrader.app.domain.usecase.litx.PremiumDiscountCalculator
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Provider-parity contract for the LiT layer.
 *
 * Provider adapters may differ in transport metadata, but once they normalize
 * to the same ordered OHLCV candles the canonical LiT decision must be
 * identical. This test deliberately compares separately allocated candle
 * series to catch any accidental dependence on object identity or provider
 * side-channel state inside the engine.
 */
class LitProviderParityTest {

    private val engine = LitEngine(
        smcDetector = SmcDetector(),
        analyzeStructure = AnalyzeMarketStructureUseCase(),
        displacementDetector = DisplacementDetector(),
        premiumDiscount = PremiumDiscountCalculator(),
    )

    @Test
    fun `equivalent normalized provider candles produce identical LiT decisions`() {
        val timeframe = Timeframe.M15
        val providerA = normalizedSeries(120, timeframe)
        val providerB = providerA.map { candle ->
            Candle(
                timestamp = candle.timestamp,
                open = candle.open,
                high = candle.high,
                low = candle.low,
                close = candle.close,
                volume = candle.volume,
            )
        }

        val a = engine.analyze("EURUSD", timeframe, providerA)
        val b = engine.analyze("EURUSD", timeframe, providerB)

        assertEquals(a.stage, b.stage)
        assertEquals(a.context, b.context)
        assertEquals(a.signal, b.signal)
        assertEquals(a.narrative, b.narrative)
    }

    private fun normalizedSeries(count: Int, timeframe: Timeframe): List<Candle> {
        val step = timeframe.minutes.toLong() * 60_000L
        val pattern = doubleArrayOf(0.0, 1.3, 2.7, 1.9, 0.4, -1.2, -2.6, -1.4, 0.6, 2.1, 3.5, 2.2)
        return (0 until count).map { index ->
            val cycle = index / pattern.size
            val close = 1.1000 + (pattern[index % pattern.size] + cycle * 0.2) * 0.0010
            val open = close - if (index % 2 == 0) 0.0002 else -0.00015
            Candle(
                timestamp = 1_700_000_000_000L + index * step,
                open = open,
                high = maxOf(open, close) + 0.0003,
                low = minOf(open, close) - 0.0003,
                close = close,
                volume = 1_000.0 + index,
            )
        }
    }
}
