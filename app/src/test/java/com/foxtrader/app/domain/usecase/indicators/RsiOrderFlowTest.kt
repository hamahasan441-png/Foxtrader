package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RsiOrderFlowTest {

    @Test
    fun `detects dual confirmed bullish divergence`() {
        val candles = bullishDivergenceCandles()
        val result = RsiOrderFlow.calculate(candles)

        val bull = result.divergences.firstOrNull {
            it.type == RsiOrderFlow.DivergenceType.REGULAR_BULLISH
        }

        assertTrue("Expected a regular bullish RSI plus flow divergence", bull != null)
        requireNotNull(bull)
        assertEquals(bull.endIndex + 3, bull.confirmedIndex)
        assertTrue(bull.endRsi > bull.startRsi)
        assertTrue(bull.endFlow > bull.startFlow)
        assertTrue(bull.strength in 0..100)
    }

    @Test
    fun `pivot cannot signal before right side confirmation bars exist`() {
        val full = bullishDivergenceCandles()

        // The second low is at index 36 with pivotRight=3. Index 39 must be
        // present before that pivot and its divergence are knowable.
        val beforeConfirmation = RsiOrderFlow.calculate(full.take(39))
        val atConfirmation = RsiOrderFlow.calculate(full.take(40))

        assertFalse(
            beforeConfirmation.divergences.any {
                it.type == RsiOrderFlow.DivergenceType.REGULAR_BULLISH && it.endIndex == 36
            },
        )
        assertTrue(
            atConfirmation.divergences.any {
                it.type == RsiOrderFlow.DivergenceType.REGULAR_BULLISH &&
                    it.endIndex == 36 && it.confirmedIndex == 39
            },
        )
    }

    @Test
    fun `future bars cannot mutate already confirmed divergence`() {
        val base = bullishDivergenceCandles().take(40)
        val baseResult = RsiOrderFlow.calculate(base)
        val confirmed = baseResult.divergences.filter { it.confirmedIndex <= base.lastIndex }
        assertTrue(confirmed.isNotEmpty())

        val extended = base + List(12) { i ->
            val p = if (i % 2 == 0) 180.0 + i else 55.0 - i
            Candle(
                timestamp = 1_700_000_000_000L + (base.size + i) * 60_000L,
                open = p,
                high = p + 5.0,
                low = (p - 5.0).coerceAtLeast(1.0),
                close = p + if (i % 2 == 0) 2.0 else -2.0,
                volume = 10_000.0 + i,
            )
        }
        val extendedResult = RsiOrderFlow.calculate(extended)
        val sameHistoricalWindow = extendedResult.divergences.filter { it.confirmedIndex <= base.lastIndex }

        assertEquals(confirmed, sameHistoricalWindow)
    }

    @Test
    fun `zero volume feed stays finite and explicitly reports no volume coverage`() {
        val candles = bullishDivergenceCandles().map { it.copy(volume = 0.0) }
        val result = RsiOrderFlow.calculate(candles)

        assertEquals(0.0, result.positiveVolumeCoverage, 0.0)
        assertEquals(candles.size, result.flow.size)
        assertTrue(result.flow.all { it.isFinite() && it in 0.0..100.0 })
        assertTrue(result.delta.all { it.isFinite() })
        assertTrue(result.cumulativeDelta.all { it.isFinite() })
    }

    private fun bullishDivergenceCandles(): List<Candle> {
        val closes = listOf(
            100.0,
            101.0, 102.0, 103.0, 104.0, 103.0, 102.0, 101.0, 100.0,
            99.0, 98.0, 97.0, 96.0, 95.0, 94.0, 93.0, 92.0, 91.0,
            90.0, 89.0, 88.0,
            90.0, 92.0, 94.0, 96.0, 98.0,
            97.0, 96.0, 95.0, 94.0, 93.0, 92.0, 91.0, 90.0, 89.0, 87.5,
            90.0, 92.0, 94.0, 96.0, 98.0,
        )
        return closes.mapIndexed { index, close ->
            val open = if (index == 0) close else closes[index - 1]
            Candle(
                timestamp = 1_700_000_000_000L + index * 60_000L,
                open = open,
                high = maxOf(open, close) + 0.5,
                low = minOf(open, close) - 0.5,
                close = close,
                volume = 100.0,
            )
        }
    }
}
