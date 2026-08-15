package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the Ichimoku incremental-resume guard.
 *
 * A stale `previous` snapshot that is *shorter* than the resume point (a rapid
 * indicator-toggle / timeframe-switch race) previously fed System.arraycopy an
 * out-of-range length and crashed the background compute — which surfaced to
 * the user as "the app crashes when I touch indicators". The guard must fall
 * back to a full recompute and still produce values identical to a fresh run.
 */
class IchimokuIncrementalGuardTest {

    private val tol = 1e-9

    private fun series(count: Int): List<Candle> =
        (1..count).map { i ->
            val p = 100.0 + i * 0.5
            Candle(1_000L + i * 60_000L, p - 0.5, p + 1.0, p - 1.0, p, 100.0)
        }

    @Test
    fun `previous shorter than the resume point falls back to a full recompute`() {
        val engine = IchimokuCloud()
        val candles = series(80)

        // Stale snapshot: only 10 bars long, far below recomputeFrom = 70.
        val stale = engine.calculate(series(10))

        // Must not throw and must equal a from-scratch computation.
        val incremental = engine.calculateIncremental(candles, stale, recomputeFrom = 70)
        val full = engine.calculate(candles)

        for (i in candles.indices) {
            assertEquals(full.tenkan[i], incremental.tenkan[i], tol)
            assertEquals(full.kijun[i], incremental.kijun[i], tol)
            assertEquals(full.senkouA[i], incremental.senkouA[i], tol)
            assertEquals(full.senkouB[i], incremental.senkouB[i], tol)
            assertEquals(full.chikou[i], incremental.chikou[i], tol)
        }
    }

    @Test
    fun `valid previous still resumes incrementally and matches full`() {
        val engine = IchimokuCloud()
        val candles = series(80)
        val partial = engine.calculate(candles.take(60))

        val incremental = engine.calculateIncremental(candles, partial, recomputeFrom = 58)
        val full = engine.calculate(candles)

        for (i in candles.indices) {
            assertEquals(full.tenkan[i], incremental.tenkan[i], tol)
            assertEquals(full.kijun[i], incremental.kijun[i], tol)
            assertEquals(full.senkouA[i], incremental.senkouA[i], tol)
            assertEquals(full.senkouB[i], incremental.senkouB[i], tol)
        }
    }
}
