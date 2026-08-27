package com.foxtrader.app.domain.usecase.compass

import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.liquiditysweep.LiquiditySweepEngine
import com.foxtrader.app.domain.usecase.signalintel.AccumulationManipulationDistributionEngine
import com.foxtrader.app.domain.usecase.signalintel.PivotSweepDivergenceEngine
import com.foxtrader.app.domain.usecase.signalintel.RsiOrderFlowSignalEngine
import com.foxtrader.app.domain.usecase.signalintel.ValueAreaLiquidityRejectionEngine
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import com.foxtrader.app.domain.usecase.virginwick.VirginWickEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The primary layer Compass learns from.
 *
 * Its one non-obvious requirement is that a longer series must never change
 * what it reported about a shorter one. Three of the member engines keep only
 * their most recent results by default, which is sensible for a chart and
 * ruinous for a learning layer: history Compass had already learned from would
 * quietly disappear as the series grew.
 */
class CompassCallSourceTest {

    private val source = CompassCallSource(
        LiquiditySweepEngine(AnalyzeMarketStructureUseCase()),
        VirginWickEngine(SmcDetector()),
        RsiOrderFlowSignalEngine(),
        PivotSweepDivergenceEngine(),
        ValueAreaLiquidityRejectionEngine(),
        AccumulationManipulationDistributionEngine(),
    )

    private fun calls(candles: List<com.foxtrader.app.domain.model.Candle>) =
        source.calls(CompassFixtures.SYMBOL, Timeframe.M5, candles)

    private fun key(call: CompassRawCall) = "${call.source}@${call.index}${call.direction.name}"

    @Test
    fun `a longer series never revises what a shorter one reported`() {
        // Enough bars that the members' default caps of 160 would bite: without
        // Compass asking for the uncapped view, the oldest calls vanish here.
        val candles = CompassFixtures.reverting(24_000, seed = 1)
        val full = calls(candles)

        listOf(8_000, 14_000, 20_000).forEach { cutoff ->
            val prefix = calls(candles.take(cutoff)).map(::key).toSet()
            val fullInsidePrefix = full.filter { it.index < cutoff }.map(::key).toSet()

            assertTrue("nothing to compare at $cutoff", fullInsidePrefix.isNotEmpty())
            assertEquals(
                "the completed history dropped calls the prefix reported at $cutoff",
                emptySet<String>(),
                fullInsidePrefix - prefix,
            )
            assertEquals(
                "the prefix reported calls the completed history does not, at $cutoff",
                emptySet<String>(),
                prefix - fullInsidePrefix,
            )
        }
    }

    @Test
    fun `enough calls survive to exceed the member caps`() {
        // If this ever falls below the cap the test above stops proving
        // anything, because the caps would never have engaged.
        val perSource = calls(CompassFixtures.reverting(24_000, seed = 1)).groupingBy { it.source }.eachCount()
        assertTrue(
            "no member produced enough calls to exercise the cap: $perSource",
            perSource.values.any { it > 60 },
        )
    }

    @Test
    fun `calls are ordered and well formed`() {
        val calls = calls(CompassFixtures.reverting(12_000, seed = 2))
        assertTrue(calls.isNotEmpty())
        assertEquals("calls must be in bar order", calls.map { it.index }.sorted(), calls.map { it.index })
        assertTrue("every call must name its origin", calls.all { it.source.isNotBlank() })
        assertTrue("indices must be real bars", calls.all { it.index >= 0 })
    }

    @Test
    fun `degenerate series produce no calls rather than throwing`() {
        assertTrue(calls(emptyList()).isEmpty())
        assertTrue(calls(CompassFixtures.walk(3)).isEmpty())
        assertTrue(calls(List(500) { CompassFixtures.bar(it, 1.1, 1.1, 1.1, 1.1) }).isEmpty())
    }
}
