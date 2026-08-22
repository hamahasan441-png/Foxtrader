package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.litx.DisplacementDetector
import com.foxtrader.app.domain.usecase.litx.PremiumDiscountCalculator
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression gate for the canonical LiT live/backtest boundary.
 *
 * Live evaluation must use the exact same closed-bar prefix that historical
 * replay/backtest sees at a bar. A still-forming candle is renderable market
 * data, but it is not signal evidence and must not mutate the LiT decision.
 */
class LitLiveBacktestParityTest {

    private val engine = LitEngine(
        smcDetector = SmcDetector(),
        analyzeStructure = AnalyzeMarketStructureUseCase(),
        displacementDetector = DisplacementDetector(),
        premiumDiscount = PremiumDiscountCalculator(),
    )

    @Test
    fun `every live closed prefix matches the same backtest prefix`() {
        val timeframe = Timeframe.M15
        val candles = wave(140, timeframe)
        val duration = timeframe.minutes.toLong() * 60_000L

        for (cutoff in 59 until candles.lastIndex) {
            val now = candles[cutoff].timestamp + duration
            val livePrefix = ConfirmedBarPolicy.confirmedPrefix(candles, timeframe, now)
            val backtestPrefix = candles.subList(0, cutoff + 1)

            assertEquals("confirmed prefix drift at cutoff $cutoff", backtestPrefix.size, livePrefix.size)
            assertEquals(backtestPrefix, livePrefix)

            val live = engine.analyze("EURUSD", timeframe, livePrefix)
            val backtest = engine.analyze("EURUSD", timeframe, backtestPrefix)

            assertEquals("stage mismatch at cutoff $cutoff", backtest.stage, live.stage)
            assertEquals("context mismatch at cutoff $cutoff", backtest.context, live.context)
            assertEquals("signal mismatch at cutoff $cutoff", backtest.signal, live.signal)
        }
    }

    @Test
    fun `forming candle mutation cannot change canonical LiT decision`() {
        val timeframe = Timeframe.M15
        val candles = wave(110, timeframe)
        val duration = timeframe.minutes.toLong() * 60_000L
        val formingIndex = candles.lastIndex
        val now = candles[formingIndex].timestamp + duration / 2

        val originalPrefix = ConfirmedBarPolicy.confirmedPrefix(candles, timeframe, now)
        assertEquals(formingIndex, originalPrefix.size)

        val mutated = candles.toMutableList()
        val forming = mutated[formingIndex]
        mutated[formingIndex] = forming.copy(
            open = forming.open * 1.25,
            high = forming.high * 1.60,
            low = forming.low * 0.60,
            close = forming.close * 1.35,
            volume = forming.volume * 20.0,
        )
        val mutatedPrefix = ConfirmedBarPolicy.confirmedPrefix(mutated, timeframe, now)

        assertEquals(originalPrefix, mutatedPrefix)

        val before = engine.analyze("EURUSD", timeframe, originalPrefix)
        val after = engine.analyze("EURUSD", timeframe, mutatedPrefix)

        assertEquals(before.stage, after.stage)
        assertEquals(before.context, after.context)
        assertEquals(before.signal, after.signal)
    }

    @Test
    fun `duplicate or out of order provider bars fail closed before LiT`() {
        val timeframe = Timeframe.M15
        val valid = wave(80, timeframe)
        val duplicate = valid.toMutableList().apply {
            this[lastIndex] = this[lastIndex].copy(timestamp = this[lastIndex - 1].timestamp)
        }
        val outOfOrder = valid.toMutableList().apply {
            this[lastIndex] = this[lastIndex].copy(timestamp = this[lastIndex - 1].timestamp - 1L)
        }

        val duplicateAnalysis = engine.analyze("EURUSD", timeframe, duplicate)
        val outOfOrderAnalysis = engine.analyze("EURUSD", timeframe, outOfOrder)

        assertTrue(duplicateAnalysis.signal == null)
        assertTrue(outOfOrderAnalysis.signal == null)
        assertTrue(duplicateAnalysis.narrative.contains("timestamp", ignoreCase = true))
        assertTrue(outOfOrderAnalysis.narrative.contains("timestamp", ignoreCase = true))
    }

    private fun wave(count: Int, timeframe: Timeframe): List<Candle> {
        val pattern = doubleArrayOf(0.0, 1.8, 3.8, 2.6, 0.8, -1.4, -3.2, -2.0, 0.2, 2.4, 4.8, 3.0)
        val step = timeframe.minutes.toLong() * 60_000L
        return (0 until count).map { index ->
            val cycle = index / pattern.size
            val close = 100.0 + pattern[index % pattern.size] + cycle * 0.9
            val open = close - if (index % 2 == 0) 0.25 else -0.18
            Candle(
                timestamp = 1_700_000_000_000L + index * step,
                open = open,
                high = maxOf(open, close) + 0.32,
                low = minOf(open, close) - 0.32,
                close = close,
                volume = 1_000.0 + index,
            )
        }
    }
}
