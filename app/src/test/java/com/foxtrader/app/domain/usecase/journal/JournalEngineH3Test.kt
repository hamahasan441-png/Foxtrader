package com.foxtrader.app.domain.usecase.journal

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for JournalEngine.computeStats and closeTrade (H3 additions).
 */
class JournalEngineH3Test {

    private lateinit var engine: JournalEngine

    @Before
    fun setup() {
        engine = JournalEngine()
    }

    private fun entry(
        pnl: Double?,
        direction: Direction = Direction.BULLISH,
        entryPrice: Double = 1.10000,
        stopLoss: Double = 1.09500,
    ) = JournalEntry(
        id = "t-${System.nanoTime()}",
        symbol = "EURUSD",
        direction = direction,
        timeframe = Timeframe.H1,
        entryPrice = entryPrice,
        exitPrice = if (pnl != null) entryPrice + pnl / 100_000.0 else null,
        stopLoss = stopLoss,
        takeProfit = 1.11000,
        volume = 1.0,
        entryTime = System.currentTimeMillis(),
        exitTime = if (pnl != null) System.currentTimeMillis() + 3600_000 else null,
        pnl = pnl,
        rMultiple = if (pnl != null) (pnl / abs(entryPrice - stopLoss)) / 100_000.0 else null,
        setupType = "Test",
        emotionTag = EmotionTag.NEUTRAL,
    )

    @Test
    fun `computeStats returns zero stats for empty list`() {
        val stats = engine.computeStats(emptyList())
        assertEquals(0, stats.totalTrades)
        assertEquals(0.0, stats.winRate, 0.001)
    }

    @Test
    fun `computeStats calculates win rate correctly`() {
        val entries = listOf(
            entry(pnl = 500.0),   // win
            entry(pnl = 300.0),   // win
            entry(pnl = -200.0),  // loss
        )
        val stats = engine.computeStats(entries)
        assertEquals(3, stats.totalTrades)
        assertEquals(66.666, stats.winRate, 1.0) // ~66.7%
    }

    @Test
    fun `computeStats ignores open trades`() {
        val entries = listOf(
            entry(pnl = 500.0),
            entry(pnl = null),  // open — should not count
        )
        val stats = engine.computeStats(entries)
        assertEquals(1, stats.totalTrades)
    }

    @Test
    fun `computeStats profit factor`() {
        val entries = listOf(
            entry(pnl = 600.0),
            entry(pnl = -200.0),
        )
        val stats = engine.computeStats(entries)
        assertEquals(3.0, stats.profitFactor, 0.01) // 600/200
    }

    @Test
    fun `closeTrade computes PnL and R-multiple for bullish trade`() {
        val open = entry(pnl = null)
        val closed = engine.closeTrade(open, exitPrice = 1.10500)

        assertTrue(closed.pnl!! > 0)
        assertTrue(closed.rMultiple!! > 0)
        assertEquals(1.10500, closed.exitPrice!!, 0.00001)
    }

    @Test
    fun `closeTrade computes negative PnL for losing bullish trade`() {
        val open = entry(pnl = null)
        val closed = engine.closeTrade(open, exitPrice = 1.09000)

        assertTrue(closed.pnl!! < 0)
        assertTrue(closed.rMultiple!! < 0)
    }
}
