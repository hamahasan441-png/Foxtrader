package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SignalSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartSignalVisibilityTest {
    @Test
    fun `active LiT Adventure keeps every confirmed historical arrow`() {
        val oldLit = signal("old-lit", SignalSource.LITX, barIndex = 5)
        val oldOther = signal("old-other", SignalSource.SMT, barIndex = 6)
        val recentOther = signal("recent", SignalSource.SMT, barIndex = 195)

        val visible = selectVisibleChartSignals(
            signals = listOf(oldLit, oldOther, recentOther),
            showSignalHistory = false,
            litAdventureEnabled = true,
            displayCandleCount = 200,
        )

        assertTrue(visible.contains(oldLit))
        assertFalse(visible.contains(oldOther))
        assertTrue(visible.contains(recentOther))
    }

    @Test
    fun `disabled LiT Adventure follows the global recent-only filter`() {
        val oldLit = signal("old-lit", SignalSource.LITX, barIndex = 5)

        val visible = selectVisibleChartSignals(
            signals = listOf(oldLit),
            showSignalHistory = false,
            litAdventureEnabled = false,
            displayCandleCount = 200,
        )

        assertTrue(visible.isEmpty())
    }

    @Test
    fun `global history returns the original complete signal list`() {
        val signals = listOf(
            signal("lit", SignalSource.LITX, barIndex = 1),
            signal("smt", SignalSource.SMT, barIndex = 2),
        )

        assertEquals(
            signals,
            selectVisibleChartSignals(signals, true, litAdventureEnabled = true, displayCandleCount = 3),
        )
    }

    private fun signal(id: String, source: SignalSource, barIndex: Int) = ChartSignal(
        id = id,
        source = source,
        direction = Direction.BULLISH,
        entry = 1.1,
        sl = 1.0,
        tp = 1.2,
        barIndex = barIndex,
        timestamp = 1_700_000_000_000L + barIndex,
        confidence = 0.8,
        isLive = false,
    )
}
