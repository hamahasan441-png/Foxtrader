package com.foxtrader.app.domain.usecase.journal

import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.JournalStats
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

/**
 * Trade Journal Engine — logs, stores, and analyzes trading activity.
 *
 * Features:
 * - Trade entry logging (manual or auto-linked to backtest/replay)
 * - Performance statistics (win rate, R-multiple, profit factor, streaks)
 * - Emotion pattern tracking
 * - Setup type breakdown
 * - Filtering and search
 *
 * Pure domain logic — persistence handled by repository layer.
 */
@Singleton
class JournalEngine @Inject constructor() {

    private val entries = mutableListOf<JournalEntry>()

    // ========================================================================
    // ENTRY MANAGEMENT
    // ========================================================================

    /**
     * Add a new trade entry to the journal.
     */
    fun addEntry(entry: JournalEntry): JournalEntry {
        val withId = if (entry.id.isBlank()) entry.copy(id = UUID.randomUUID().toString()) else entry
        entries.add(withId)
        return withId
    }

    /**
     * Close an open trade (set exit price/time/pnl).
     */
    fun closeEntry(
        id: String,
        exitPrice: Double,
        exitTime: Long = System.currentTimeMillis(),
    ): JournalEntry? {
        val idx = entries.indexOfFirst { it.id == id }
        if (idx < 0) return null

        val entry = entries[idx]
        val priceDiff = if (entry.direction == com.foxtrader.app.domain.model.Direction.BULLISH) {
            exitPrice - entry.entryPrice
        } else {
            entry.entryPrice - exitPrice
        }
        val pnl = priceDiff * entry.volume * 100_000 // Standard lot conversion
        val risk = abs(entry.entryPrice - entry.stopLoss)
        val rMultiple = if (risk > 0) priceDiff / risk else 0.0

        val closed = entry.copy(
            exitPrice = exitPrice,
            exitTime = exitTime,
            pnl = pnl,
            rMultiple = rMultiple,
        )
        entries[idx] = closed
        return closed
    }

    /**
     * Update notes/rating/emotion on an existing entry.
     */
    fun updateEntry(
        id: String,
        notes: String? = null,
        rating: Int? = null,
        emotionTag: EmotionTag? = null,
        tags: List<String>? = null,
    ): JournalEntry? {
        val idx = entries.indexOfFirst { it.id == id }
        if (idx < 0) return null

        val entry = entries[idx]
        val updated = entry.copy(
            notes = notes ?: entry.notes,
            rating = rating ?: entry.rating,
            emotionTag = emotionTag ?: entry.emotionTag,
            tags = tags ?: entry.tags,
        )
        entries[idx] = updated
        return updated
    }

    /**
     * Delete a journal entry.
     */
    fun deleteEntry(id: String): Boolean = entries.removeAll { it.id == id }

    // ========================================================================
    // QUERIES
    // ========================================================================

    /** Get all entries (newest first). */
    fun getAllEntries(): List<JournalEntry> = entries.sortedByDescending { it.entryTime }

    /** Get entries for a specific symbol. */
    fun getBySymbol(symbol: String): List<JournalEntry> =
        entries.filter { it.symbol == symbol }.sortedByDescending { it.entryTime }

    /** Get only open trades. */
    fun getOpenTrades(): List<JournalEntry> = entries.filter { it.isOpen }

    /** Get entries within a time range. */
    fun getByDateRange(from: Long, to: Long): List<JournalEntry> =
        entries.filter { it.entryTime in from..to }.sortedByDescending { it.entryTime }

    /** Get entries by setup type. */
    fun getBySetup(setupType: String): List<JournalEntry> =
        entries.filter { it.setupType == setupType }.sortedByDescending { it.entryTime }

    // ========================================================================
    // STATISTICS
    // ========================================================================

    /**
     * Compute aggregate statistics from all closed trades.
     */
    fun getStats(): JournalStats {
        val closed = entries.filter { !it.isOpen && it.pnl != null }
        if (closed.isEmpty()) return JournalStats()

        val wins = closed.filter { it.isWin }
        val losses = closed.filter { !it.isWin }

        val totalPnl = closed.sumOf { it.pnl ?: 0.0 }
        val grossProfit = wins.sumOf { it.pnl ?: 0.0 }
        val grossLoss = abs(losses.sumOf { it.pnl ?: 0.0 })

        val winRate = if (closed.isNotEmpty()) (wins.size.toDouble() / closed.size) * 100.0 else 0.0
        val avgR = closed.mapNotNull { it.rMultiple }.average().takeIf { !it.isNaN() } ?: 0.0
        val profitFactor = if (grossLoss > 0) grossProfit / grossLoss else if (grossProfit > 0) Double.MAX_VALUE else 0.0

        // Streaks
        var maxWins = 0; var maxLosses = 0; var curWins = 0; var curLosses = 0
        for (entry in closed.sortedBy { it.entryTime }) {
            if (entry.isWin) { curWins++; curLosses = 0; maxWins = max(maxWins, curWins) }
            else { curLosses++; curWins = 0; maxLosses = max(maxLosses, curLosses) }
        }

        // Setup breakdown
        val tradesBySetup = closed.groupBy { it.setupType }.mapValues { it.value.size }
        val tradesByEmotion = closed.groupBy { it.emotionTag }.mapValues { it.value.size }

        val avgHoldingTime = closed.mapNotNull { it.holdingTimeMs }.average().toLong()
        val avgRating = closed.map { it.rating }.average()

        return JournalStats(
            totalTrades = closed.size,
            winRate = winRate,
            averageRMultiple = avgR,
            totalPnl = totalPnl,
            bestTrade = closed.maxOf { it.pnl ?: 0.0 },
            worstTrade = closed.minOf { it.pnl ?: 0.0 },
            averageHoldingTimeMs = avgHoldingTime,
            profitFactor = profitFactor,
            consecutiveWins = maxWins,
            consecutiveLosses = maxLosses,
            averageRating = avgRating,
            tradesBySetup = tradesBySetup,
            tradesByEmotion = tradesByEmotion,
        )
    }

    /**
     * Get stats for a specific time period.
     */
    fun getStatsForPeriod(from: Long, to: Long): JournalStats {
        val filtered = entries.filter { it.entryTime in from..to && !it.isOpen && it.pnl != null }
        // Reuse same logic with filtered set... simplified
        val totalPnl = filtered.sumOf { it.pnl ?: 0.0 }
        val wins = filtered.filter { it.isWin }
        val winRate = if (filtered.isNotEmpty()) (wins.size.toDouble() / filtered.size) * 100 else 0.0
        return JournalStats(
            totalTrades = filtered.size,
            winRate = winRate,
            totalPnl = totalPnl,
        )
    }

    /** Clear all journal entries. */
    fun clearAll() { entries.clear() }
}
