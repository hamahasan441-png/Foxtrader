package com.foxtrader.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract the LiT May Madness chart study depends on.
 *
 * Both properties here are the difference between a study that draws a history
 * of entries and one that shows a single arrow at the right edge, so they are
 * pinned rather than left to a default someone might reasonably change.
 */
class LitChartContractTest {

    @Test
    fun `confirmed entries are always kept, whatever was persisted`() {
        // A stored config from an older build — or an experiment — must not be
        // able to turn the chart into a live-only study. Once a closed-bar
        // entry is confirmed its arrow belongs on the chart permanently.
        val persisted = LitConfig(historicalSignals = false, liveWindowBars = 40)

        val onChart = persisted.asLitMayMadnessSignalConfig()

        assertTrue("previously confirmed entries must stay on the chart", onChart.historicalSignals)
        assertEquals(LIT_MAY_MADNESS_LIVE_BARS, onChart.liveWindowBars)
    }

    @Test
    fun `the default study keeps its history`() {
        assertTrue(LitConfig().historicalSignals)
        assertTrue(LitConfig().asLitMayMadnessSignalConfig().historicalSignals)
    }

    @Test
    fun `the signal contract survives a round trip`() {
        // Sanitising an already-sanitised chart config must not quietly change
        // it, or the chart cache would see a different key on every frame.
        val once = LitConfig().asLitMayMadnessSignalConfig()
        assertEquals(once, once.asLitMayMadnessSignalConfig())
    }
}
