package com.foxtrader.app.domain.usecase.ai

import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.JournalStats
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// Data models
// ─────────────────────────────────────────────────────────────────────────────

/** Types of detectable behavior patterns in trading history. */
enum class BehaviorPatternType {
    REVENGE_TRADING,
    OVERTRADING,
    FOMO_PATTERN,
    CONSISTENT_WINNER,
    IMPROVING_TREND,
    DECLINING_TREND,
}

/** A detected behavior pattern with confidence and actionable advice. */
data class BehaviorPattern(
    val type: BehaviorPatternType,
    val confidence: Int,
    val evidence: String,
    val suggestion: String,
)

/** Emotion-based performance analysis. */
data class EmotionAnalysis(
    val emotionWinRates: Map<EmotionTag, Double>,
    val worstEmotion: EmotionTag?,
    val bestEmotion: EmotionTag?,
    val emotionPnlCorrelation: Map<EmotionTag, Double>,
)

/** Performance ranking for a single setup type. */
data class SetupRanking(
    val setupType: String,
    val tradeCount: Int,
    val winRate: Double,
    val avgR: Double,
    val totalPnl: Double,
    val grade: String,
)

/** Win/loss streak analysis. */
data class StreakAnalysis(
    val currentStreak: Int,
    val isWinStreak: Boolean,
    val longestWinStreak: Int,
    val longestLossStreak: Int,
    val streakImpactOnBehavior: String,
)

/** Verdict for an individual trade review. */
enum class TradeReviewVerdict {
    EXCELLENT,
    GOOD,
    ACCEPTABLE,
    POOR,
    TERRIBLE,
}

/** AI review of a single trade. */
data class TradeReview(
    val entryId: String,
    val verdict: TradeReviewVerdict,
    val reasons: List<String>,
    val improvementTips: List<String>,
)

/** Complete journal AI report combining all analyses. */
data class JournalAiReport(
    val stats: JournalStats,
    val behaviorPatterns: List<BehaviorPattern>,
    val emotionAnalysis: EmotionAnalysis,
    val setupRankings: List<SetupRanking>,
    val streakAnalysis: StreakAnalysis,
    val recommendations: List<String>,
    val tradeReviews: List<TradeReview>,
)

// ─────────────────────────────────────────────────────────────────────────────
// Engine
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Algorithmic trade review and analytics engine.
 *
 * Analyzes [JournalEntry] history to produce actionable insights about trading
 * behavior patterns, emotional correlations, and personalized improvement
 * recommendations. Pure rule-based computation with no external dependencies.
 */
@Singleton
class TradeJournalAi @Inject constructor() {

    companion object {
        private const val REVENGE_WINDOW_MS = 30L * 60 * 1000 // 30 minutes
        private const val OVERTRADING_THRESHOLD = 5
        private const val MS_PER_DAY = 86_400_000L
    }

    /**
     * Analyze a list of journal entries and produce a comprehensive report.
     */
    fun analyze(entries: List<JournalEntry>): JournalAiReport {
        val closedEntries = entries.filter { it.exitPrice != null }
        val stats = computeStats(closedEntries)
        val behaviorPatterns = detectBehaviorPatterns(entries, closedEntries, stats)
        val emotionAnalysis = analyzeEmotions(closedEntries)
        val setupRankings = rankSetups(closedEntries)
        val streakAnalysis = analyzeStreaks(closedEntries)
        val tradeReviews = reviewTrades(entries)
        val recommendations = generateRecommendations(behaviorPatterns, emotionAnalysis, setupRankings, stats)

        return JournalAiReport(
            stats = stats,
            behaviorPatterns = behaviorPatterns,
            emotionAnalysis = emotionAnalysis,
            setupRankings = setupRankings,
            streakAnalysis = streakAnalysis,
            recommendations = recommendations,
            tradeReviews = tradeReviews,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stats computation
    // ─────────────────────────────────────────────────────────────────────────

    private fun computeStats(closed: List<JournalEntry>): JournalStats {
        if (closed.isEmpty()) return JournalStats()

        val wins = closed.filter { it.isWin }
        val losses = closed.filter { !it.isWin }
        val pnls = closed.mapNotNull { it.pnl }
        val rMultiples = closed.mapNotNull { it.rMultiple }

        val winRate = if (closed.isNotEmpty()) wins.size.toDouble() / closed.size else 0.0
        val averageWin = wins.mapNotNull { it.pnl }.average().takeIf { !it.isNaN() } ?: 0.0
        val averageLoss = losses.mapNotNull { it.pnl }.average().takeIf { !it.isNaN() } ?: 0.0
        val payoffRatio = if (averageLoss != 0.0) kotlin.math.abs(averageWin / averageLoss) else 0.0
        val expectancy = (winRate * averageWin) + ((1 - winRate) * averageLoss)

        val totalWins = wins.mapNotNull { it.pnl }.sum()
        val totalLosses = kotlin.math.abs(losses.mapNotNull { it.pnl }.sum())
        val profitFactor = if (totalLosses != 0.0) totalWins / totalLosses else 0.0

        val holdingTimes = closed.mapNotNull { it.holdingTimeMs }
        val avgHoldingTime = if (holdingTimes.isNotEmpty()) holdingTimes.average().toLong() else 0L

        // Max drawdown (peak-to-trough of cumulative PnL)
        var peak = 0.0
        var maxDrawdown = 0.0
        var cumulative = 0.0
        for (entry in closed) {
            cumulative += (entry.pnl ?: 0.0)
            if (cumulative > peak) peak = cumulative
            val dd = peak - cumulative
            if (dd > maxDrawdown) maxDrawdown = dd
        }

        // Consecutive wins/losses
        val (consWins, consLosses) = computeConsecutive(closed)

        // Trades by setup
        val tradesBySetup = closed.groupBy { it.setupType }.mapValues { it.value.size }
        val tradesByEmotion = closed.groupBy { it.emotionTag }.mapValues { it.value.size }

        // Best setup by average R
        val setupAvgR = closed.groupBy { it.setupType }.mapValues { (_, trades) ->
            trades.mapNotNull { it.rMultiple }.average().takeIf { !it.isNaN() } ?: 0.0
        }
        val bestSetup = setupAvgR.maxByOrNull { it.value }?.key

        // Weakest emotion by win rate
        val emotionWinRates = closed.groupBy { it.emotionTag }.mapValues { (_, trades) ->
            trades.count { it.isWin }.toDouble() / trades.size
        }
        val weakestEmotion = emotionWinRates.minByOrNull { it.value }?.key

        val avgRating = closed.map { it.rating }.average().takeIf { !it.isNaN() } ?: 0.0

        return JournalStats(
            totalTrades = closed.size,
            winRate = winRate,
            averageRMultiple = rMultiples.average().takeIf { !it.isNaN() } ?: 0.0,
            totalPnl = pnls.sum(),
            averageWin = averageWin,
            averageLoss = averageLoss,
            expectancy = expectancy,
            payoffRatio = payoffRatio,
            bestTrade = pnls.maxOrNull() ?: 0.0,
            worstTrade = pnls.minOrNull() ?: 0.0,
            maxDrawdown = maxDrawdown,
            averageHoldingTimeMs = avgHoldingTime,
            profitFactor = profitFactor,
            consecutiveWins = consWins,
            consecutiveLosses = consLosses,
            averageRating = avgRating,
            tradesBySetup = tradesBySetup,
            tradesByEmotion = tradesByEmotion,
            bestSetupByAverageR = bestSetup,
            weakestEmotionByWinRate = weakestEmotion,
        )
    }

    private fun computeConsecutive(closed: List<JournalEntry>): Pair<Int, Int> {
        var maxWins = 0
        var maxLosses = 0
        var currentWins = 0
        var currentLosses = 0
        for (entry in closed) {
            if (entry.isWin) {
                currentWins++
                currentLosses = 0
                if (currentWins > maxWins) maxWins = currentWins
            } else {
                currentLosses++
                currentWins = 0
                if (currentLosses > maxLosses) maxLosses = currentLosses
            }
        }
        return Pair(maxWins, maxLosses)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Behavior pattern detection
    // ─────────────────────────────────────────────────────────────────────────

    private fun detectBehaviorPatterns(
        all: List<JournalEntry>,
        closed: List<JournalEntry>,
        stats: JournalStats,
    ): List<BehaviorPattern> {
        val patterns = mutableListOf<BehaviorPattern>()

        detectRevengeTrading(all)?.let { patterns.add(it) }
        detectOvertrading(all)?.let { patterns.add(it) }
        detectFomoPattern(closed, stats)?.let { patterns.add(it) }
        detectImprovingTrend(closed)?.let { patterns.add(it) }
        detectDecliningTrend(closed)?.let { patterns.add(it) }
        detectConsistentWinner(closed)?.let { patterns.add(it) }

        return patterns
    }

    private fun detectRevengeTrading(entries: List<JournalEntry>): BehaviorPattern? {
        val sorted = entries.sortedBy { it.entryTime }
        var occurrences = 0

        for (i in 0 until sorted.size - 1) {
            val current = sorted[i]
            val next = sorted[i + 1]
            // Loss followed by quick entry with REVENGE or FOMO emotion
            if (!current.isWin && (next.entryTime - current.entryTime) <= REVENGE_WINDOW_MS) {
                if (next.emotionTag == EmotionTag.REVENGE || next.emotionTag == EmotionTag.FOMO) {
                    occurrences++
                }
            }
        }

        if (occurrences == 0) return null
        val confidence = ((occurrences.toDouble() / entries.size) * 100).toInt().coerceIn(0, 100)

        return BehaviorPattern(
            type = BehaviorPatternType.REVENGE_TRADING,
            confidence = confidence,
            evidence = "Detected $occurrences instances of entering trades within 30 minutes of a loss with emotional tags.",
            suggestion = "Implement a mandatory break after consecutive losses to avoid emotional entries.",
        )
    }

    private fun detectOvertrading(entries: List<JournalEntry>): BehaviorPattern? {
        // Group trades by day
        val byDay = entries.groupBy { it.entryTime / MS_PER_DAY }
        var overtradingDays = 0

        for ((_, dayTrades) in byDay) {
            if (dayTrades.size > OVERTRADING_THRESHOLD) {
                // Check for declining average R on that day
                val rValues = dayTrades.mapNotNull { it.rMultiple }
                if (rValues.size >= 2) {
                    val firstHalf = rValues.take(rValues.size / 2).average()
                    val secondHalf = rValues.drop(rValues.size / 2).average()
                    if (secondHalf < firstHalf) {
                        overtradingDays++
                    }
                } else {
                    // More than 5 trades but no R data - still flag
                    overtradingDays++
                }
            }
        }

        if (overtradingDays == 0) return null
        val confidence = ((overtradingDays.toDouble() / byDay.size) * 100).toInt().coerceIn(0, 100)

        return BehaviorPattern(
            type = BehaviorPatternType.OVERTRADING,
            confidence = confidence,
            evidence = "Found $overtradingDays days with more than $OVERTRADING_THRESHOLD trades and declining performance.",
            suggestion = "Limit yourself to 3-4 high-quality setups per session.",
        )
    }

    private fun detectFomoPattern(closed: List<JournalEntry>, stats: JournalStats): BehaviorPattern? {
        val fomoTrades = closed.filter { it.emotionTag == EmotionTag.FOMO }
        if (fomoTrades.isEmpty()) return null

        val fomoWinRate = fomoTrades.count { it.isWin }.toDouble() / fomoTrades.size
        if (fomoWinRate >= stats.winRate) return null

        val diff = ((stats.winRate - fomoWinRate) * 100).toInt()
        val confidence = ((fomoTrades.size.toDouble() / closed.size) * 100).toInt().coerceIn(0, 100)

        return BehaviorPattern(
            type = BehaviorPatternType.FOMO_PATTERN,
            confidence = confidence,
            evidence = "FOMO trades have ${diff}% lower win rate than your overall average.",
            suggestion = "Consider a mandatory cooling period before entries when feeling FOMO.",
        )
    }

    private fun detectImprovingTrend(closed: List<JournalEntry>): BehaviorPattern? {
        if (closed.size < 20) return null
        val sorted = closed.sortedBy { it.entryTime }
        val recent10 = sorted.takeLast(10).mapNotNull { it.rMultiple }
        val previous10 = sorted.dropLast(10).takeLast(10).mapNotNull { it.rMultiple }

        if (recent10.isEmpty() || previous10.isEmpty()) return null
        val recentAvg = recent10.average()
        val previousAvg = previous10.average()

        if (recentAvg <= previousAvg) return null
        val improvement = ((recentAvg - previousAvg) / kotlin.math.abs(previousAvg).coerceAtLeast(0.01) * 100).toInt()
        val confidence = improvement.coerceIn(0, 100)

        return BehaviorPattern(
            type = BehaviorPatternType.IMPROVING_TREND,
            confidence = confidence,
            evidence = "Last 10 trades average R (%.2f) is higher than previous 10 (%.2f).".format(recentAvg, previousAvg),
            suggestion = "Keep doing what you are doing. Your recent performance shows improvement.",
        )
    }

    private fun detectDecliningTrend(closed: List<JournalEntry>): BehaviorPattern? {
        if (closed.size < 20) return null
        val sorted = closed.sortedBy { it.entryTime }
        val recent10 = sorted.takeLast(10).mapNotNull { it.rMultiple }
        val previous10 = sorted.dropLast(10).takeLast(10).mapNotNull { it.rMultiple }

        if (recent10.isEmpty() || previous10.isEmpty()) return null
        val recentAvg = recent10.average()
        val previousAvg = previous10.average()

        if (recentAvg >= previousAvg) return null
        val decline = ((previousAvg - recentAvg) / kotlin.math.abs(previousAvg).coerceAtLeast(0.01) * 100).toInt()
        val confidence = decline.coerceIn(0, 100)

        return BehaviorPattern(
            type = BehaviorPatternType.DECLINING_TREND,
            confidence = confidence,
            evidence = "Last 10 trades average R (%.2f) is lower than previous 10 (%.2f).".format(recentAvg, previousAvg),
            suggestion = "Review your recent trades for changes in discipline or market conditions.",
        )
    }

    private fun detectConsistentWinner(closed: List<JournalEntry>): BehaviorPattern? {
        val last20 = closed.sortedBy { it.entryTime }.takeLast(20)
        if (last20.size < 20) return null

        val winRate = last20.count { it.isWin }.toDouble() / last20.size
        val wins = last20.filter { it.isWin }.mapNotNull { it.pnl }
        val losses = last20.filter { !it.isWin }.mapNotNull { it.pnl }
        val totalWins = wins.sum()
        val totalLosses = kotlin.math.abs(losses.sum())
        val profitFactor = if (totalLosses != 0.0) totalWins / totalLosses else 0.0

        if (winRate <= 0.60 || profitFactor <= 1.5) return null
        val confidence = ((winRate * 100).toInt()).coerceIn(0, 100)

        return BehaviorPattern(
            type = BehaviorPatternType.CONSISTENT_WINNER,
            confidence = confidence,
            evidence = "Last 20 trades: %.0f%% win rate and %.1f profit factor.".format(winRate * 100, profitFactor),
            suggestion = "You are performing consistently. Consider gradually increasing position size.",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Emotion analysis
    // ─────────────────────────────────────────────────────────────────────────

    private fun analyzeEmotions(closed: List<JournalEntry>): EmotionAnalysis {
        if (closed.isEmpty()) {
            return EmotionAnalysis(
                emotionWinRates = emptyMap(),
                worstEmotion = null,
                bestEmotion = null,
                emotionPnlCorrelation = emptyMap(),
            )
        }

        val byEmotion = closed.groupBy { it.emotionTag }

        val winRates = byEmotion.mapValues { (_, trades) ->
            trades.count { it.isWin }.toDouble() / trades.size
        }

        val pnlCorrelation = byEmotion.mapValues { (_, trades) ->
            trades.mapNotNull { it.pnl }.average().takeIf { !it.isNaN() } ?: 0.0
        }

        val worstEmotion = winRates.minByOrNull { it.value }?.key
        val bestEmotion = winRates.maxByOrNull { it.value }?.key

        return EmotionAnalysis(
            emotionWinRates = winRates,
            worstEmotion = worstEmotion,
            bestEmotion = bestEmotion,
            emotionPnlCorrelation = pnlCorrelation,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup rankings
    // ─────────────────────────────────────────────────────────────────────────

    private fun rankSetups(closed: List<JournalEntry>): List<SetupRanking> {
        if (closed.isEmpty()) return emptyList()

        return closed.groupBy { it.setupType }.map { (setup, trades) ->
            val winRate = trades.count { it.isWin }.toDouble() / trades.size
            val avgR = trades.mapNotNull { it.rMultiple }.average().takeIf { !it.isNaN() } ?: 0.0
            val totalPnl = trades.mapNotNull { it.pnl }.sum()
            val grade = when {
                avgR >= 2.0 && winRate >= 0.60 -> "A"
                avgR >= 1.0 && winRate >= 0.50 -> "B"
                avgR >= 0 -> "C"
                else -> "D"
            }
            SetupRanking(
                setupType = setup,
                tradeCount = trades.size,
                winRate = winRate,
                avgR = avgR,
                totalPnl = totalPnl,
                grade = grade,
            )
        }.sortedByDescending { it.avgR }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Streak analysis
    // ─────────────────────────────────────────────────────────────────────────

    private fun analyzeStreaks(closed: List<JournalEntry>): StreakAnalysis {
        if (closed.isEmpty()) {
            return StreakAnalysis(
                currentStreak = 0,
                isWinStreak = false,
                longestWinStreak = 0,
                longestLossStreak = 0,
                streakImpactOnBehavior = "Not enough data to analyze streak impact.",
            )
        }

        val sorted = closed.sortedBy { it.entryTime }

        // Current streak
        var currentStreak = 1
        val lastIsWin = sorted.last().isWin
        for (i in sorted.size - 2 downTo 0) {
            if (sorted[i].isWin == lastIsWin) currentStreak++ else break
        }

        // Longest streaks
        var longestWin = 0
        var longestLoss = 0
        var winStreak = 0
        var lossStreak = 0
        for (entry in sorted) {
            if (entry.isWin) {
                winStreak++
                lossStreak = 0
                if (winStreak > longestWin) longestWin = winStreak
            } else {
                lossStreak++
                winStreak = 0
                if (lossStreak > longestLoss) longestLoss = lossStreak
            }
        }

        val impact = when {
            !lastIsWin && currentStreak >= 3 ->
                "Currently on a $currentStreak-trade losing streak. Risk of revenge trading is elevated."
            lastIsWin && currentStreak >= 3 ->
                "Currently on a $currentStreak-trade winning streak. Watch for overconfidence."
            else -> "Streaks are within normal parameters."
        }

        return StreakAnalysis(
            currentStreak = currentStreak,
            isWinStreak = lastIsWin,
            longestWinStreak = longestWin,
            longestLossStreak = longestLoss,
            streakImpactOnBehavior = impact,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Trade reviews
    // ─────────────────────────────────────────────────────────────────────────

    private fun reviewTrades(entries: List<JournalEntry>): List<TradeReview> {
        return entries.mapNotNull { entry ->
            // Skip open trades (no rMultiple)
            val r = entry.rMultiple ?: return@mapNotNull null
            val verdict = gradeTradeReview(r, entry.emotionTag)
            val reasons = buildReviewReasons(r, entry)
            val tips = buildImprovementTips(verdict, entry)
            TradeReview(
                entryId = entry.id,
                verdict = verdict,
                reasons = reasons,
                improvementTips = tips,
            )
        }
    }

    private fun gradeTradeReview(rMultiple: Double, emotion: EmotionTag): TradeReviewVerdict {
        return when {
            rMultiple >= 3.0 && (emotion == EmotionTag.PATIENT || emotion == EmotionTag.CONFIDENT) ->
                TradeReviewVerdict.EXCELLENT
            rMultiple >= 1.5 -> TradeReviewVerdict.GOOD
            rMultiple >= 0.0 -> TradeReviewVerdict.ACCEPTABLE
            rMultiple < 0.0 && emotion in listOf(EmotionTag.REVENGE, EmotionTag.FOMO, EmotionTag.GREEDY) ->
                TradeReviewVerdict.TERRIBLE
            rMultiple < 0.0 && emotion == EmotionTag.NEUTRAL -> TradeReviewVerdict.POOR
            else -> TradeReviewVerdict.POOR // default for negative R with other emotions
        }
    }

    private fun buildReviewReasons(r: Double, entry: JournalEntry): List<String> {
        val reasons = mutableListOf<String>()
        when {
            r >= 3.0 -> reasons.add("Exceptional risk-reward achieved (${String.format("%.1f", r)}R).")
            r >= 1.5 -> reasons.add("Solid reward captured (${String.format("%.1f", r)}R).")
            r >= 0 -> reasons.add("Breakeven or small profit.")
            else -> reasons.add("Loss of ${String.format("%.1f", kotlin.math.abs(r))}R.")
        }
        if (entry.emotionTag in listOf(EmotionTag.REVENGE, EmotionTag.FOMO, EmotionTag.GREEDY)) {
            reasons.add("Trade entered under emotional pressure (${entry.emotionTag.name}).")
        }
        if (entry.emotionTag == EmotionTag.PATIENT || entry.emotionTag == EmotionTag.CONFIDENT) {
            reasons.add("Calm and disciplined entry.")
        }
        return reasons
    }

    private fun buildImprovementTips(verdict: TradeReviewVerdict, entry: JournalEntry): List<String> {
        val tips = mutableListOf<String>()
        when (verdict) {
            TradeReviewVerdict.EXCELLENT -> tips.add("Continue this disciplined approach.")
            TradeReviewVerdict.GOOD -> tips.add("Look for opportunities to let winners run further.")
            TradeReviewVerdict.ACCEPTABLE -> tips.add("Review if trade management could have improved the outcome.")
            TradeReviewVerdict.POOR -> tips.add("Consider tighter stop management or earlier exit signals.")
            TradeReviewVerdict.TERRIBLE -> {
                tips.add("This trade shows emotional decision-making.")
                tips.add("Implement a pre-trade checklist to filter impulsive entries.")
            }
        }
        return tips
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recommendations
    // ─────────────────────────────────────────────────────────────────────────

    private fun generateRecommendations(
        patterns: List<BehaviorPattern>,
        emotionAnalysis: EmotionAnalysis,
        setupRankings: List<SetupRanking>,
        stats: JournalStats,
    ): List<String> {
        val recommendations = mutableListOf<String>()

        // FOMO recommendation
        val fomoPattern = patterns.find { it.type == BehaviorPatternType.FOMO_PATTERN }
        if (fomoPattern != null) {
            val fomoWinRate = emotionAnalysis.emotionWinRates[EmotionTag.FOMO]
            val diff = if (fomoWinRate != null) {
                ((stats.winRate - fomoWinRate) * 100).toInt()
            } else 0
            recommendations.add(
                "Your FOMO trades have ${diff}% lower win rate than average. Consider a mandatory cooling period before entries."
            )
        }

        // Revenge recommendation
        val revengePattern = patterns.find { it.type == BehaviorPatternType.REVENGE_TRADING }
        if (revengePattern != null) {
            recommendations.add(
                "Revenge trading detected after losses. Implement a mandatory break after consecutive losses."
            )
        }

        // Overtrading recommendation
        val overtradingPattern = patterns.find { it.type == BehaviorPatternType.OVERTRADING }
        if (overtradingPattern != null) {
            recommendations.add(
                "You took more than 5 trades in a single session on days with declining performance. Limit yourself to 3-4 high-quality setups."
            )
        }

        // Best setup recommendation
        val bestSetup = setupRankings.firstOrNull()
        if (bestSetup != null && bestSetup.avgR > 0) {
            recommendations.add(
                "Your best setup is '${bestSetup.setupType}' with ${(bestSetup.winRate * 100).toInt()}% win rate and ${String.format("%.1f", bestSetup.avgR)} average R. Focus on this pattern type."
            )
        }

        // Worst emotion recommendation
        val worstEmotion = emotionAnalysis.worstEmotion
        if (worstEmotion != null) {
            val rate = emotionAnalysis.emotionWinRates[worstEmotion]
            if (rate != null) {
                recommendations.add(
                    "Trades tagged '${worstEmotion.name}' have the lowest win rate (${(rate * 100).toInt()}%). Build awareness of this emotional state."
                )
            }
        }

        return recommendations
    }
}
