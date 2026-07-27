package com.foxtrader.app.domain.usecase.journal

import com.foxtrader.app.domain.model.BacktestTrade
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.ExitReason
import com.foxtrader.app.domain.model.Timeframe
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestJournalMapperTest {

    @Test
    fun `mapTrades adds deterministic AI explanation notes and tags`() {
        val entries = BacktestJournalMapper.mapTrades(
            trades = listOf(trade()),
            symbol = "EURUSD",
            timeframe = Timeframe.H1,
        )

        val entry = entries.first()
        assertTrue(entry.notes.contains("EURUSD Bullish"))
        assertTrue(entry.notes.contains("Confluences: 6/9"))
        assertTrue(entry.notes.contains("AI-approved"))
        assertTrue(entry.tags.contains("ai-approved"))
        assertTrue(entry.tags.contains("ai-strong"))
    }

    private fun trade(): BacktestTrade = BacktestTrade(
        id = 1,
        direction = Direction.BULLISH,
        entryIndex = 10,
        entryTime = 1_000L,
        entryPrice = 1.1000,
        exitIndex = 15,
        exitTime = 2_000L,
        exitPrice = 1.1150,
        volume = 0.1,
        grossPnL = 150.0,
        commission = 0.0,
        netPnL = 150.0,
        rMultiple = 3.0,
        exitReason = ExitReason.TP,
        balanceAfter = 100_150.0,
        setupType = "FVG Continuation",
        holdingBars = 5,
        aiApproved = true,
        aiGrade = "STRONG",
        aiConfidence = 78.0,
        aiConfluenceCount = 6,
    )
}
