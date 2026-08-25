package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SignalSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-rule-set outcome statistics.
 *
 * This is the measurement that answers "is SNIPER actually better than
 * PRECISION". It cannot be answered from [SignalOutcomeEvaluator.SourceStats],
 * because every LiT Adventure mode reports under `SignalSource.LITX` — a
 * combined figure averages a mode that fires constantly with one that fires
 * rarely and describes neither.
 */
class SignalOutcomeEvaluatorVariantTest {

    private val evaluator = SignalOutcomeEvaluator()

    private fun candle(i: Int, high: Double, low: Double) = Candle(
        timestamp = 1_700_000_000_000L + i * 60_000L,
        open = (high + low) / 2,
        high = high,
        low = low,
        close = (high + low) / 2,
        volume = 1_000.0,
    )

    /** Flat series; specific bars are widened to hit a level. */
    private fun series(size: Int, hits: Map<Int, Pair<Double, Double>>) =
        (0 until size).map { i ->
            val (h, l) = hits[i] ?: (100.2 to 99.8)
            candle(i, h, l)
        }

    private fun longSignal(id: String, bar: Int, variant: String?) = ChartSignal(
        id = id,
        source = SignalSource.LITX,
        direction = Direction.BULLISH,
        entry = 100.0,
        sl = 99.0,
        tp = 102.0,
        barIndex = bar,
        timestamp = 1_700_000_000_000L + bar * 60_000L,
        confidence = 80.0,
        isLive = false,
        variant = variant,
    )

    @Test
    fun `outcomes are partitioned by rule set not lumped under one source`() {
        // Bar 5 signal wins (TP touched at bar 6); bar 20 signal loses.
        val candles = series(40, mapOf(6 to (102.5 to 99.8), 21 to (100.2 to 98.5)))
        val signals = listOf(
            longSignal("a", 5, "SNIPER"),
            longSignal("b", 20, "PRECISION"),
        )

        val records = evaluator.evaluate(signals, candles)
        val combined = evaluator.summarize(records)
        val byVariant = evaluator.summarizeByVariant(records)

        assertEquals("both modes collapse into one source row", 1, combined.size)
        assertEquals(2, byVariant.size)

        val sniper = byVariant.single { it.variant == "SNIPER" }
        val precision = byVariant.single { it.variant == "PRECISION" }
        assertEquals(1, sniper.stats.wins)
        assertEquals(0, sniper.stats.losses)
        assertEquals(0, precision.stats.wins)
        assertEquals(1, precision.stats.losses)
    }

    @Test
    fun `the sample size gate still applies after partitioning`() {
        // Partitioning divides an already small sample, so this matters more
        // here than anywhere else.
        val candles = series(60, (1 until 60).associateWith { 102.5 to 99.8 })
        val signals = (0 until 5).map { longSignal("s$it", it, "SNIPER") }

        val stats = evaluator.summarizeByVariant(evaluator.evaluate(signals, candles)).single()
        assertEquals(5, stats.stats.resolved)
        assertFalse(
            "5 resolved signals is not evidence, even for one mode",
            stats.stats.rateIsMeaningful,
        )
        assertNull(stats.stats.reportableWinRate)
    }

    @Test
    fun `sources with a single rule set are omitted rather than given a placeholder row`() {
        val candles = series(40, mapOf(6 to (102.5 to 99.8)))
        val records = evaluator.evaluate(listOf(longSignal("a", 5, variant = null)), candles)

        assertEquals("the record itself still exists", 1, records.size)
        assertTrue(
            "a source with no partition must not produce an invented variant row",
            evaluator.summarizeByVariant(records).isEmpty(),
        )
        assertEquals(1, evaluator.summarize(records).size)
    }

    @Test
    fun `variant survives every outcome path including unresolved`() {
        // Flat series: neither SL nor TP is touched, so the signal expires.
        val candles = series(40, emptyMap())
        val records = evaluator.evaluate(listOf(longSignal("a", 5, "MOMENTUM")), candles)

        assertEquals(SignalOutcomeEvaluator.Outcome.UNRESOLVED, records.single().outcome)
        assertEquals(
            "an unresolved record must stay attributable to its mode",
            "MOMENTUM",
            records.single().variant,
        )
    }

    @Test
    fun `variant rows are ordered deterministically`() {
        val candles = series(60, mapOf(6 to (102.5 to 99.8), 21 to (102.5 to 99.8), 31 to (102.5 to 99.8)))
        val records = evaluator.evaluate(
            listOf(
                longSignal("c", 30, "SWEEP_REVERSAL"),
                longSignal("a", 5, "MOMENTUM"),
                longSignal("b", 20, "PRECISION"),
            ),
            candles,
        )
        assertEquals(
            listOf("MOMENTUM", "PRECISION", "SWEEP_REVERSAL"),
            evaluator.summarizeByVariant(records).map { it.variant },
        )
    }
}
