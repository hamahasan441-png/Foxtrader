package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.ManagedTrade
import com.foxtrader.app.domain.model.tradepro.ManagedTradeState
import com.foxtrader.app.domain.repository.JournalRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the Trade Management Dashboard to the Trade Journal.
 *
 * When a managed trade closes (stop, runner-target, or manual), this bridge
 * automatically logs a [JournalEntry] with full TRADEPRO context — setup type,
 * confluences, realized points, R-multiple, and the exit reason. The trader gets
 * an auto-populated journal record they can later annotate with emotion/rating/notes.
 *
 * This closes the TRADEPRO loop: Analyze → Execute → Manage → **Auto-Journal** → Review.
 */
@Singleton
class TradeProJournalBridge @Inject constructor(
    private val journalRepository: JournalRepository,
) {

    /**
     * Logs a closed [ManagedTrade] to the journal. Only logs if CLOSED.
     * Uses upsert (by "tp-{id}") so duplicate calls are idempotent.
     */
    suspend fun logClosedTrade(
        trade: ManagedTrade,
        timeframe: Timeframe = Timeframe.M15,
        confluences: List<String> = emptyList(),
    ) {
        if (trade.state != ManagedTradeState.CLOSED) return

        val initialStopDistance = when (trade.direction) {
            com.foxtrader.app.domain.model.Direction.BULLISH -> trade.entryPrice - trade.stopPrice
            com.foxtrader.app.domain.model.Direction.BEARISH -> trade.stopPrice - trade.entryPrice
        }
        val rMultiple = if (initialStopDistance > 0.0 && trade.contracts > 0) {
            trade.realizedPoints / (initialStopDistance * trade.contracts)
        } else {
            0.0
        }

        val entry = JournalEntry(
            id = "tp-${trade.id}",
            symbol = trade.symbol,
            direction = trade.direction,
            timeframe = timeframe,
            entryPrice = trade.entryPrice,
            exitPrice = trade.currentPrice,
            stopLoss = trade.stopPrice,
            takeProfit = trade.runnerTarget,
            volume = trade.contracts.toDouble(),
            entryTime = trade.entryTimestamp,
            exitTime = trade.closedAt,
            pnl = trade.realizedPoints,
            rMultiple = rMultiple,
            setupType = "TRADEPRO",
            notes = buildNotes(trade, confluences),
            tags = buildTags(trade, confluences),
        )

        journalRepository.upsert(entry)
    }

    private fun buildNotes(trade: ManagedTrade, confluences: List<String>): String = buildString {
        append("Auto-logged from Trade Management. ")
        append("Exit: ${trade.exitReason ?: "unknown"}. ")
        if (confluences.isNotEmpty()) {
            append("Confluences: ${confluences.joinToString(", ")}. ")
        }
        append("Realized: ${"%.1f".format(trade.realizedPoints)} pts across ${trade.contracts} contracts.")
    }

    private fun buildTags(trade: ManagedTrade, confluences: List<String>): List<String> = buildList {
        add("TRADEPRO")
        add("auto-recorded")
        trade.exitReason?.let { add(it.lowercase().replace(" ", "-")) }
        confluences.take(3).forEach { add(it) }
    }
}
