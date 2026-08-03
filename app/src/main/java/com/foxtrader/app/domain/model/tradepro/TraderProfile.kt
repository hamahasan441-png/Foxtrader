package com.foxtrader.app.domain.model.tradepro

import com.foxtrader.app.domain.model.EmotionTag

/**
 * A data-driven behavioural profile of the trader, mined from their journal. Surfaces edges
 * (what's working) and leaks (what's costing money) so practice can be targeted rather than random.
 *
 * All metrics are computed from *closed* journal entries only — open trades have no outcome to learn
 * from. Point/PnL figures use the journal's PnL where available and fall back to R multiples.
 */
data class TraderProfile(
    val totalClosedTrades: Int,
    val archetype: TraderArchetype,
    val disciplineScore: Int,
    val consistencyScore: Int,
    val edges: List<CoachingInsight>,
    val leaks: List<CoachingInsight>,
    val setupPerformance: List<CategoryPerformance>,
    val emotionPerformance: List<EmotionPerformance>,
    val sessionPerformance: List<SessionPerformance>,
    val dayOfWeekPerformance: List<CategoryPerformance>,
    val holdTimeBuckets: List<CategoryPerformance>,
    val ratingCalibration: RatingCalibration,
    val headline: String,
) {
    companion object {
        fun empty(reason: String): TraderProfile = TraderProfile(
            totalClosedTrades = 0,
            archetype = TraderArchetype.UNKNOWN,
            disciplineScore = 0,
            consistencyScore = 0,
            edges = emptyList(),
            leaks = emptyList(),
            setupPerformance = emptyList(),
            emotionPerformance = emptyList(),
            sessionPerformance = emptyList(),
            dayOfWeekPerformance = emptyList(),
            holdTimeBuckets = emptyList(),
            ratingCalibration = RatingCalibration(0.0, 0.0, 0.0, emptyList()),
            headline = reason,
        )
    }
}

/**
 * A behavioural archetype inferred from trade frequency, hold time, win-rate/payoff shape, and the
 * mix of emotional tags. Purely descriptive — a mirror, not a judgement.
 */
enum class TraderArchetype(val label: String, val description: String) {
    UNKNOWN("Unknown", "Not enough closed trades to profile yet."),
    SNIPER("Sniper", "Selective, patient, high win-rate on few high-quality setups."),
    SCALPER("Scalper", "High frequency, short holds, small edges compounded."),
    SWING_TRADER("Swing Trader", "Longer holds, larger targets, fewer trades."),
    MOMENTUM_CHASER("Momentum Chaser", "Frequent FOMO/greed entries chasing extended moves."),
    REVENGE_TRADER("Revenge Trader", "Losses trigger clustered, emotionally-driven re-entries."),
    DISCIPLINED("Disciplined Operator", "Consistent process, tight emotional control, positive expectancy."),
}

/**
 * One actionable coaching insight — either a strength to lean into or a leak to plug.
 */
data class CoachingInsight(
    val severity: InsightSeverity,
    val title: String,
    val detail: String,
    val metric: String,
)

enum class InsightSeverity { POSITIVE, INFO, WARNING, CRITICAL }

/**
 * Performance aggregated over a category (setup type, day of week, hold-time bucket).
 */
data class CategoryPerformance(
    val category: String,
    val trades: Int,
    val winRate: Double,
    val netPnl: Double,
    val avgR: Double,
    val expectancy: Double,
) {
    val isProfitable: Boolean get() = netPnl > 0.0
}

/**
 * Performance aggregated per emotional tag — the clearest window into behavioural leaks.
 */
data class EmotionPerformance(
    val emotion: EmotionTag,
    val trades: Int,
    val winRate: Double,
    val netPnl: Double,
    val avgR: Double,
)

/**
 * Trading-session performance (Asia / London / New York / Off-hours), by entry time-of-day (UTC).
 */
data class SessionPerformance(
    val session: TradingSession,
    val trades: Int,
    val winRate: Double,
    val netPnl: Double,
    val avgR: Double,
)

enum class TradingSession(val label: String, val startHourUtc: Int, val endHourUtc: Int) {
    ASIA("Asia", 0, 8),
    LONDON("London", 7, 13),
    NEW_YORK("New York", 13, 21),
    OFF_HOURS("Off-Hours", 21, 24),
    ;

    companion object {
        /** Map a UTC hour (0-23) to its dominant session. London/NY overlap resolves to NY. */
        fun fromHourUtc(hour: Int): TradingSession = when (hour) {
            in 0 until 7 -> ASIA
            in 7 until 13 -> LONDON
            in 13 until 21 -> NEW_YORK
            else -> OFF_HOURS
        }
    }
}

/**
 * How well the trader's 1-5 self-rating predicts actual outcome — measures self-awareness.
 * A positive [correlation] means higher self-ratings really do produce better results.
 */
data class RatingCalibration(
    val avgRatingOnWins: Double,
    val avgRatingOnLosses: Double,
    val correlation: Double,
    val byRating: List<CategoryPerformance>,
) {
    val isWellCalibrated: Boolean get() = correlation >= 0.3
}
