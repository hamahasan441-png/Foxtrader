package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.litx.DisplacementDetector
import com.foxtrader.app.domain.usecase.litx.PremiumDiscountCalculator
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ensures decisions at a historical cutoff are invariant to arbitrary future bars. */
class LitNoFutureMutationTest {

    private val engine = LitEngine(
        smcDetector = SmcDetector(),
        analyzeStructure = AnalyzeMarketStructureUseCase(),
        displacementDetector = DisplacementDetector(),
        premiumDiscount = PremiumDiscountCalculator(),
    )

    @Test
    fun `future bars cannot mutate a decision recomputed at the historical cutoff`() {
        val timeframe = Timeframe.M15
        val full = series(130, timeframe)
        val cutoff = 89
        val prefix = full.subList(0, cutoff + 1)
        val baseline = engine.analyze("EURUSD", timeframe, prefix)

        val mutatedFuture = full.toMutableList()
        for (index in cutoff + 1..mutatedFuture.lastIndex) {
            val c = mutatedFuture[index]
            val factor = if (index % 2 == 0) 1.35 else 0.72
            val center = c.close * factor
            mutatedFuture[index] = c.copy(
                open = center,
                high = center * 1.03,
                low = center * 0.97,
                close = center,
                volume = c.volume * 15.0,
            )
        }

        val replayPrefix = mutatedFuture.subList(0, cutoff + 1)
        val replay = engine.analyze("EURUSD", timeframe, replayPrefix)

        assertEquals(baseline.stage, replay.stage)
        assertEquals(baseline.context, replay.context)
        assertEquals(baseline.signal, replay.signal)
        assertEquals(baseline.narrative, replay.narrative)
    }

    private fun series(count: Int, timeframe: Timeframe): List<Candle> {
        val step = timeframe.minutes.toLong() * 60_000L
        val pattern = doubleArrayOf(0.0, 1.6, 3.2, 2.2, 0.7, -1.1, -2.8, -1.7, 0.3, 2.0, 4.0, 2.6)
        return (0 until count).map { index ->
            val cycle = index / pattern.size
            val close = 100.0 + pattern[index % pattern.size] + cycle * 0.75
            val open = close - if (index % 2 == 0) 0.22 else -0.17
            Candle(
                timestamp = 1_700_000_000_000L + index * step,
                open = open,
                high = maxOf(open, close) + 0.30,
                low = minOf(open, close) - 0.30,
                close = close,
                volume = 900.0 + index,
            )
        }
    }
}
