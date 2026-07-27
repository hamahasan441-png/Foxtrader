package com.foxtrader.app.domain.usecase.journal

import com.foxtrader.app.domain.model.BacktestTrade
import com.foxtrader.app.domain.model.DecisionResult
import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.RequiredConfluence
import com.foxtrader.app.domain.model.SignalGrade
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.ai.TradeExplanationEngine
import java.util.UUID

/**
 * Converts [BacktestTrade]s into [JournalEntry]s for automatic journaling.
 *
 * Each backtest trade becomes a closed journal entry with:
 * - entryPrice/exitPrice/stopLoss/TP from the trade
 * - PnL and R-multiple from the trade
 * - setupType = trade.setupType (or "Backtest" fallback)
 * - emotionTag = NEUTRAL (backtested, not live)
 * - AI explanation annotated in the notes (if available)
 *
 * Pure function — no state, no platform dependencies.
 */
object BacktestJournalMapper {

    fun mapTrades(
        trades: List<BacktestTrade>,
        symbol: String,
        timeframe: Timeframe,
        explanationEngine: TradeExplanationEngine = TradeExplanationEngine(),
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
            notes = buildNotes(trade, symbol, timeframe, explanationEngine),
            rating = 0,
            emotionTag = EmotionTag.NEUTRAL,
            tags = buildTags(trade),
        )
    }

    private fun buildNotes(
        trade: BacktestTrade,
        symbol: String,
        timeframe: Timeframe,
        explanationEngine: TradeExplanationEngine,
    ): String = buildString {
        append("Auto-journaled from backtest.")
        append(" Exit: ${trade.exitReason}.")
        val decision = trade.syntheticDecisionOrNull()
        if (decision != null) {
            append(' ')
            append(explanationEngine.compact(decision, symbol))
        } else if (trade.aiGrade != null) {
            append(" AI: ${trade.aiGrade} (${trade.aiConfidence?.toInt()}%).")
        }
        if (trade.aiApproved == true) append(" AI-approved.")
        else if (trade.aiApproved == false) append(" AI-rejected.")
        if (trade.aiConfluenceCount != null) append(" Confluences: ${trade.aiConfluenceCount}/9.")
        append(" TF: ${timeframe.label}.")
    }

    private fun BacktestTrade.syntheticDecisionOrNull(): DecisionResult? {
        val gradeName = aiGrade ?: return null
        val grade = runCatching { SignalGrade.valueOf(gradeName) }.getOrDefault(SignalGrade.NO_SIGNAL)
        val count = (aiConfluenceCount ?: 0).coerceIn(0, RequiredConfluence.entries.size)
        val present = RequiredConfluence.entries.take(count)
        val missing = RequiredConfluence.entries.drop(count)
        val approved = aiApproved == true
        return DecisionResult(
            approved = approved,
            direction = if (approved) direction else null,
            confidence = aiConfidence ?: 0.0,
            grade = grade,
            confluencePresent = present,
            confluenceMissing = missing,
            blockReasons = if (approved) emptyList() else listOf("AI gate rejected this backtest entry"),
            vetoedBy = null,
            explanation = if (approved) "AI-approved backtest entry" else "AI-rejected backtest entry",
            timestamp = entryTime,
        )
    }

    private fun buildTags(trade: BacktestTrade): List<String> = buildList {
        add("backtest")
        add(trade.exitReason.name.lowercase())
        if (trade.aiApproved == true) add("ai-approved")
        if (trade.aiApproved == false) add("ai-rejected")
        trade.aiGrade?.lowercase()?.let { add("ai-$it") }
        trade.setupType?.let { add(it.lowercase().replace(" ", "-")) }
    }
}
