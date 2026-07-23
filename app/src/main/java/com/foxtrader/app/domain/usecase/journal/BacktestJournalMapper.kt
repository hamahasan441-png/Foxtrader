package com.foxtrader.app.domain.usecase.journal

import com.foxtrader.app.domain.model.BacktestTrade
import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.Timeframe
import java.util.UUID

/**
 * Converts [BacktestTrade]s into [JournalEntry]s for automatic journaling.
 *
 * Each backtest trade becomes a closed journal entry with:
 * - entryPrice/exitPrice/stopLoss/TP from the trade
 * - PnL and R-multiple from the trade
 * - setupType = trade.setupType (or "Backtest" fallback)
 * - emotionTag = NEUTRAL (backtested, not live)
 * - AI grade annotated in the notes (if available)
 *
 * Pure function — no state, no dependencies.
 */
object BacktestJournalMapper {

    fun mapTrades(
        trades: List<BacktestTrade>,
        symbol: String,
        timeframe: Timeframe,
    ): List<JournalEntry> = trades.map { trade ->
        JournalEntry(
            id = UUID.randomUUID().toString(),
            symbol = symbol,
            direction = trade.direction,
            timeframe = timeframe,
            entryPrice = trade.entryPrice,
            exitPrice = trade.exitPrice,
            stopLoss = trade.entryPrice, // reconstructed: SL info not in BacktestTrade directly
            takeProfit = trade.exitPrice, // simplified — the exit is what happened
            volume = trade.volume,
            entryTime = trade.entryTime,
            exitTime = trade.exitTime,
            pnl = trade.netPnL,
            rMultiple = trade.rMultiple,
            setupType = trade.setupType ?: "Backtest",
            notes = buildNotes(trade),
            rating = 0,
            emotionTag = EmotionTag.NEUTRAL,
            tags = buildTags(trade),
        )
    }

    private fun buildNotes(trade: BacktestTrade): String = buildString {
        append("Auto-journaled from backtest.")
        append(" Exit: ${trade.exitReason}.")
        if (trade.aiGrade != null) append(" AI: ${trade.aiGrade} (${trade.aiConfidence?.toInt()}%).")
        if (trade.aiApproved == true) append(" AI-approved.")
        else if (trade.aiApproved == false) append(" AI-rejected.")
    }

    private fun buildTags(trade: BacktestTrade): List<String> = buildList {
        add("backtest")
        add(trade.exitReason.name.lowercase())
        if (trade.aiApproved == true) add("ai-approved")
        if (trade.aiApproved == false) add("ai-rejected")
        trade.setupType?.let { add(it.lowercase().replace(" ", "-")) }
    }
}
