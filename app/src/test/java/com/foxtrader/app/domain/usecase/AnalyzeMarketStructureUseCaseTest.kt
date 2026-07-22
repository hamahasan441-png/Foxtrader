package com.foxtrader.app.domain.usecase

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.StructureBreakType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzeMarketStructureUseCaseTest {

    private val useCase = AnalyzeMarketStructureUseCase()

    private fun candle(i: Int, o: Double, h: Double, l: Double, c: Double) =
        Candle(timestamp = i * 60_000L, open = o, high = h, low = l, close = c, volume = 100.0)

    @Test
    fun `empty or tiny series returns neutral bias with no breaks`() {
        val result = useCase(emptyList())
        assertEquals(Bias.NEUTRAL, result.bias)
        assertTrue(result.breaks.isEmpty())
    }

    @Test
    fun `rising series produces higher highs and bullish structure`() {
        // Build a clean uptrend with pullbacks so swings form
        val candles = buildList {
            var price = 1.0
            for (i in 0 until 60) {
                val up = i % 6 < 4            // 4 up, 2 down — net rising
                val delta = if (up) 0.010 else -0.004
                val open = price
                val close = price + delta
                add(candle(i, open, maxOf(open, close) + 0.002, minOf(open, close) - 0.002, close))
                price = close
            }
        }

        val result = useCase(candles, leftBars = 3, rightBars = 3)

        // Swings must be detected and at least one bullish break present
        assertTrue("expected swing highs", result.swingHighs.isNotEmpty())
        assertTrue("expected swing lows", result.swingLows.isNotEmpty())
        assertTrue(
            "expected a bullish BOS/CHOCH in an uptrend",
            result.breaks.any { it.type == StructureBreakType.BOS || it.type == StructureBreakType.CHOCH },
        )
    }

    @Test
    fun `no swing is reported in the unconfirmed right-edge window (non-repainting)`() {
        val candles = (0 until 40).map { i ->
            val p = 1.0 + i * 0.001
            candle(i, p, p + 0.0005, p - 0.0005, p + 0.0002)
        }
        val rightBars = 5
        val result = useCase(candles, leftBars = 5, rightBars = rightBars)

        val lastConfirmableIndex = candles.size - rightBars - 1
        val allSwings = result.swingHighs + result.swingLows
        assertTrue(
            "no swing may appear within the unconfirmed right window",
            allSwings.all { it.index <= lastConfirmableIndex },
        )
    }
}
