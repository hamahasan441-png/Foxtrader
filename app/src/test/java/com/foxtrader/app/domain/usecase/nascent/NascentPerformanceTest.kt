package com.foxtrader.app.domain.usecase.nascent

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.nascent.confirmation.DirectPullbackConfirmation
import com.foxtrader.app.domain.usecase.nascent.confirmation.EngulfConfirmation
import com.foxtrader.app.domain.usecase.nascent.confirmation.SweepConfirmation
import com.foxtrader.app.domain.usecase.nascent.msu.Msu1Detector
import com.foxtrader.app.domain.usecase.nascent.msu.Msu2Detector
import com.foxtrader.app.domain.usecase.nascent.msu.Msu3Detector
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * Guards the cost of history reconstruction.
 *
 * The chart re-evaluates on every frame, so a walk that degrades super-linearly
 * with history would be felt as UI jank long before it showed up as a wrong
 * number. These bounds are deliberately loose — they are there to catch an
 * accidental quadratic, not to benchmark the machine.
 */
class NascentPerformanceTest {

    private val engine = NascentEngine(
        structureEngine = NascentStructureEngine(),
        liquidityEngine = NascentLiquidityEngine(),
        epaEngine = NascentEpaEngine(),
        directPullbackEngine = NascentDirectPullbackEngine(),
        tomEngine = NascentTomEngine(),
        msu1 = Msu1Detector(),
        msu2 = Msu2Detector(),
        msu3 = Msu3Detector(NascentTransactionEngine()),
        sweepConfirmation = SweepConfirmation(),
        engulfConfirmation = EngulfConfirmation(),
        directPullbackConfirmation = DirectPullbackConfirmation(),
    )

    @Test
    fun `a long history is processed in roughly linear time`() {
        val small = syntheticSeries(2_000)
        val large = syntheticSeries(8_000)

        // Warm the JIT so the first measurement is not the one that counts.
        engine.analyze("EURUSD", Timeframe.M5, small)
        engine.analyze("EURUSD", Timeframe.M5, large)

        val smallMs = measureTimeMillis { engine.analyze("EURUSD", Timeframe.M5, small) }
        val largeMs = measureTimeMillis { engine.analyze("EURUSD", Timeframe.M5, large) }

        // 4x the bars must not cost anywhere near 16x the time.
        val ratio = largeMs.toDouble() / smallMs.coerceAtLeast(1).toDouble()
        assertTrue(
            "8k bars took ${largeMs}ms vs ${smallMs}ms for 2k (ratio $ratio) — looks super-linear",
            ratio < 10.0,
        )
        assertTrue("8k bars took ${largeMs}ms, which is too slow for a chart frame", largeMs < 4_000)
    }

    @Test
    fun `history depth bounds the work actually done`() {
        val candles = syntheticSeries(8_000)
        engine.analyze("EURUSD", Timeframe.M5, candles)

        val full = measureTimeMillis {
            engine.analyze("EURUSD", Timeframe.M5, candles, NascentConfig(historyDepthBars = 8_000))
        }
        val bounded = measureTimeMillis {
            engine.analyze("EURUSD", Timeframe.M5, candles, NascentConfig(historyDepthBars = 500))
        }

        assertTrue(
            "capping history depth must reduce the walk, not just the output " +
                "(bounded ${bounded}ms vs full ${full}ms)",
            bounded <= full,
        )
    }

    /**
     * A live feed ticks far more often than bars close. Re-asking the same
     * question about the same closed-bar prefix must not rebuild the history.
     */
    @Test
    fun `repeating an identical request is served from cache`() {
        val candles = syntheticSeries(8_000)
        val cold = measureTimeMillis { engine.analyze("EURUSD", Timeframe.M5, candles) }
        val warm = measureTimeMillis { engine.analyze("EURUSD", Timeframe.M5, candles) }

        assertTrue(
            "a repeat request took ${warm}ms against a cold ${cold}ms — the cache is not being hit",
            warm < cold.coerceAtLeast(4) / 2,
        )
    }

    @Test
    fun `a new closed bar misses the cache and is recomputed`() {
        val candles = syntheticSeries(1_000)
        val first = engine.analyze("EURUSD", Timeframe.M5, candles)
        val extended = NascentFixtures.withFuture(candles, bars = 5)

        val second = engine.analyze("EURUSD", Timeframe.M5, extended)

        assertTrue(
            "appending bars must produce a fresh analysis, not the cached one",
            second.processedBars >= first.processedBars,
        )
    }

    /** Deterministic zig-zag with enough structure to exercise every detector. */
    private fun syntheticSeries(bars: Int): List<Candle> {
        val out = ArrayList<Candle>(bars)
        var time = NascentFixtures.START_TIME
        var price = 100.0
        var direction = 1.0
        for (i in 0 until bars) {
            if (i % 60 == 0) direction = -direction
            val open = price
            val close = open + direction * 0.08 + if (i % 7 == 0) direction * 0.04 else 0.0
            out += Candle(
                timestamp = time,
                open = open,
                high = maxOf(open, close) + 0.05,
                low = minOf(open, close) - 0.05,
                close = close,
                volume = 1_000.0,
            )
            time += NascentFixtures.M5_MILLIS
            price = close
        }
        return out
    }
}
