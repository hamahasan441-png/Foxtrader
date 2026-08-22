package com.foxtrader.app.domain.usecase.journal

import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.JournalStats
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Deterministic analytics for the professional journal. */
@Singleton
class JournalAnalytics @Inject constructor() {
    fun calculate(entries: List<JournalEntry>): JournalStats {
        val closed = entries
            .filter { !it.isOpen && it.pnl?.isFinite() == true }
            .sortedBy { it.exitTime ?: it.entryTime }
        if (closed.isEmpty()) return JournalStats()

        val pnls = closed.mapNotNull { it.pnl }
        val wins = pnls.filter { it > 0.0 }
        val losses = pnls.filter { it < 0.0 }
        val rated = closed.filter { it.rating in 1..5 }
        val rs = closed.mapNotNull { it.rMultiple?.takeIf(Double::isFinite) }
        val totalPnl = pnls.sum()
        val avgWin = wins.averageOrZero()
        val avgLoss = losses.averageOrZero()
        val grossProfit = wins.sum()
        val grossLoss = abs(losses.sum())

        var equity = 0.0
        var peak = 0.0
        var maxDrawdown = 0.0
        var consecutiveWins = 0
        var consecutiveLosses = 0
        var maxWins = 0
        var maxLosses = 0
        closed.forEach { trade ->
            val pnl = trade.pnl ?: 0.0
            equity += pnl
            peak = maxOf(peak, equity)
            maxDrawdown = maxOf(maxDrawdown, peak - equity)
            if (pnl > 0.0) {
                consecutiveWins++
                consecutiveLosses = 0
                maxWins = maxOf(maxWins, consecutiveWins)
            } else if (pnl < 0.0) {
                consecutiveLosses++
                consecutiveWins = 0
                maxLosses = maxOf(maxLosses, consecutiveLosses)
            }
        }

        val setupGroups = closed.groupBy { it.setupType.ifBlank { "Unspecified" } }
        val bestSetup = setupGroups.mapNotNull { (setup, group) ->
            val values = group.mapNotNull { it.rMultiple?.takeIf(Double::isFinite) }
            if (values.isEmpty()) null else setup to values.average()
        }.maxByOrNull { it.second }?.first

        val emotionGroups = closed.groupBy { it.emotionTag }
        val weakestEmotion = emotionGroups
            .filterValues { it.isNotEmpty() }
            .map { (emotion, group) ->
                val winRate = group.count { (it.pnl ?: 0.0) > 0.0 }.toDouble() / group.size.toDouble()
                emotion to winRate
            }
            .minByOrNull { it.second }
            ?.first

        val holding = closed.mapNotNull { it.holdingTimeMs?.takeIf { ms -> ms >= 0L } }
        return JournalStats(
            totalTrades = closed.size,
            winRate = wins.size * 100.0 / closed.size,
            averageRMultiple = rs.averageOrZero(),
            totalPnl = totalPnl,
            averageWin = avgWin,
            averageLoss = avgLoss,
            expectancy = totalPnl / closed.size,
            payoffRatio = if (avgLoss < 0.0) avgWin / abs(avgLoss) else if (avgWin > 0.0) Double.POSITIVE_INFINITY else 0.0,
            bestTrade = pnls.maxOrNull() ?: 0.0,
            worstTrade = pnls.minOrNull() ?: 0.0,
            maxDrawdown = maxDrawdown,
            averageHoldingTimeMs = holding.averageLongOrZero(),
            profitFactor = if (grossLoss > 0.0) grossProfit / grossLoss else if (grossProfit > 0.0) Double.POSITIVE_INFINITY else 0.0,
            consecutiveWins = maxWins,
            consecutiveLosses = maxLosses,
            averageRating = rated.map { it.rating.toDouble() }.averageOrZero(),
            tradesBySetup = setupGroups.mapValues { it.value.size },
            tradesByEmotion = EmotionTag.entries.associateWith { emotion -> emotionGroups[emotion]?.size ?: 0 },
            bestSetupByAverageR = bestSetup,
            weakestEmotionByWinRate = weakestEmotion,
        )
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
    private fun List<Long>.averageLongOrZero(): Long = if (isEmpty()) 0L else (sumOf { it.toDouble() } / size).toLong()
}
