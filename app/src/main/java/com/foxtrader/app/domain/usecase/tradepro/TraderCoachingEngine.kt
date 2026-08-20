package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.EmotionTag
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.tradepro.CategoryPerformance
import com.foxtrader.app.domain.model.tradepro.CoachingInsight
import com.foxtrader.app.domain.model.tradepro.EmotionPerformance
import com.foxtrader.app.domain.model.tradepro.InsightSeverity
import com.foxtrader.app.domain.model.tradepro.RatingCalibration
import com.foxtrader.app.domain.model.tradepro.SessionPerformance
import com.foxtrader.app.domain.model.tradepro.TraderArchetype
import com.foxtrader.app.domain.model.tradepro.TraderProfile
import com.foxtrader.app.domain.model.tradepro.TradingSession
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Mines the trade journal for behavioural patterns and turns them into a coaching profile:
 * an inferred archetype, discipline/consistency scores, ranked edges & leaks, and performance
 * breakdowns by setup, emotion, session, day-of-week, hold-time and self-rating calibration.
 *
 * Pure and deterministic — no I/O, no framework. Operates only on *closed* entries (an open trade
 * has no outcome to learn from). This makes the whole engine trivially unit-testable.
 */
class TraderCoachingEngine @Inject constructor() {

    fun buildProfile(entries: List<JournalEntry>): TraderProfile {
        val closed = entries.filter { !it.isOpen && it.pnl != null }
        if (closed.size < MIN_TRADES) {
            return TraderProfile.empty(
                "Log at least $MIN_TRADES closed trades to unlock your coaching profile " +
                    "(${closed.size} so far).",
            )
        }

        val setupPerf = aggregateBy(closed) { it.setupType.ifBlank { "Untagged" } }
        val emotionPerf = aggregateEmotions(closed)
        val sessionPerf = aggregateSessions(closed)
        val dayPerf = aggregateBy(closed) { dayOfWeekLabel(it.entryTime) }
        val holdPerf = aggregateBy(closed) { holdTimeBucket(it) }
        val calibration = calibrateRatings(closed)

        val disciplineScore = disciplineScore(closed, emotionPerf)
        val consistencyScore = consistencyScore(closed)
        val archetype = inferArchetype(closed, emotionPerf)

        val edges = detectEdges(setupPerf, sessionPerf, emotionPerf, calibration)
        val leaks = detectLeaks(closed, setupPerf, sessionPerf, emotionPerf, disciplineScore)

        val netPnl = closed.sumOf { it.pnl ?: 0.0 }
        val winRate = closed.count { it.isWin }.toDouble() / closed.size
        val headline = buildString {
            append("${archetype.label}: ${closed.size} closed trades, ")
            append("${(winRate * 100).toInt()}% win rate, ")
            append("net ${fmtMoney(netPnl)}. ")
            append("Discipline $disciplineScore/100, consistency $consistencyScore/100.")
        }

        return TraderProfile(
            totalClosedTrades = closed.size,
            archetype = archetype,
            disciplineScore = disciplineScore,
            consistencyScore = consistencyScore,
            edges = edges,
            leaks = leaks,
            setupPerformance = setupPerf.sortedByDescending { it.netPnl },
            emotionPerformance = emotionPerf.sortedByDescending { it.netPnl },
            sessionPerformance = sessionPerf,
            dayOfWeekPerformance = dayPerf,
            holdTimeBuckets = holdPerf,
            ratingCalibration = calibration,
            headline = headline,
        )
    }

    // --- Aggregations ---

    private fun aggregateBy(
        entries: List<JournalEntry>,
        keySelector: (JournalEntry) -> String,
    ): List<CategoryPerformance> = entries.groupBy(keySelector).map { (category, group) ->
        CategoryPerformance(
            category = category,
            trades = group.size,
            winRate = group.count { it.isWin }.toDouble() / group.size,
            netPnl = group.sumOf { it.pnl ?: 0.0 },
            avgR = group.mapNotNull { it.rMultiple }.averageOrZero(),
            expectancy = group.sumOf { it.pnl ?: 0.0 } / group.size,
        )
    }

    private fun aggregateEmotions(entries: List<JournalEntry>): List<EmotionPerformance> =
        entries.groupBy { it.emotionTag }.map { (emotion, group) ->
            EmotionPerformance(
                emotion = emotion,
                trades = group.size,
                winRate = group.count { it.isWin }.toDouble() / group.size,
                netPnl = group.sumOf { it.pnl ?: 0.0 },
                avgR = group.mapNotNull { it.rMultiple }.averageOrZero(),
            )
        }

    private fun aggregateSessions(entries: List<JournalEntry>): List<SessionPerformance> =
        entries.groupBy { TradingSession.fromHourUtc(hourOfDayUtc(it.entryTime)) }
            .map { (session, group) ->
                SessionPerformance(
                    session = session,
                    trades = group.size,
                    winRate = group.count { it.isWin }.toDouble() / group.size,
                    netPnl = group.sumOf { it.pnl ?: 0.0 },
                    avgR = group.mapNotNull { it.rMultiple }.averageOrZero(),
                )
            }
            .sortedBy { it.session.ordinal }

    private fun calibrateRatings(entries: List<JournalEntry>): RatingCalibration {
        val rated = entries.filter { it.rating in 1..5 }
        val wins = rated.filter { it.isWin }
        val losses = rated.filter { !it.isWin }
        val avgOnWins = wins.map { it.rating.toDouble() }.averageOrZero()
        val avgOnLosses = losses.map { it.rating.toDouble() }.averageOrZero()
        val correlation = pearson(
            rated.map { it.rating.toDouble() },
            rated.map { if (it.isWin) 1.0 else 0.0 },
        )
        val byRating = (1..5).mapNotNull { rating ->
            val group = rated.filter { it.rating == rating }
            if (group.isEmpty()) {
                null
            } else {
                CategoryPerformance(
                    category = "$rating\u2605",
                    trades = group.size,
                    winRate = group.count { it.isWin }.toDouble() / group.size,
                    netPnl = group.sumOf { it.pnl ?: 0.0 },
                    avgR = group.mapNotNull { it.rMultiple }.averageOrZero(),
                    expectancy = group.sumOf { it.pnl ?: 0.0 } / group.size,
                )
            }
        }
        return RatingCalibration(avgOnWins, avgOnLosses, correlation, byRating)
    }

    // --- Scores ---

    private fun disciplineScore(entries: List<JournalEntry>, emotions: List<EmotionPerformance>): Int {
        val total = entries.size.toDouble()
        val emotional = emotions
            .filter { it.emotion in EMOTIONAL_LEAK_TAGS }
            .sumOf { it.trades }
        val emotionalRatio = emotional / total
        // Fraction of trades that respected a stop (had a non-zero stop distance defined).
        val withStop = entries.count { abs(it.entryPrice - it.stopLoss) > 0.0 } / total
        val avgRating = entries.filter { it.rating in 1..5 }.map { it.rating.toDouble() }.averageOrZero()
        val ratingComponent = (avgRating / 5.0) * RATING_WEIGHT
        val stopComponent = withStop * STOP_WEIGHT
        val calmComponent = (1.0 - emotionalRatio) * CALM_WEIGHT
        return ((ratingComponent + stopComponent + calmComponent) * 100).toInt().coerceIn(0, 100)
    }

    private fun consistencyScore(entries: List<JournalEntry>): Int {
        val rs = entries.mapNotNull { it.rMultiple }
        if (rs.size < 2) return 50
        val mean = rs.average()
        val variance = rs.sumOf { (it - mean) * (it - mean) } / (rs.size - 1)
        val stdev = sqrt(variance)
        if (stdev <= 0.0) return if (mean > 0) 100 else 0
        // Reward positive mean R and low dispersion (a smooth, repeatable process).
        val sharpeLike = mean / stdev
        return ((sharpeLike + 1.0) / 2.0 * 100).toInt().coerceIn(0, 100)
    }

    private fun inferArchetype(
        entries: List<JournalEntry>,
        emotions: List<EmotionPerformance>,
    ): TraderArchetype {
        val total = entries.size.toDouble()
        val revengeShare = emotions.filter { it.emotion == EmotionTag.REVENGE }.sumOf { it.trades } / total
        val fomoGreedShare = emotions
            .filter { it.emotion == EmotionTag.FOMO || it.emotion == EmotionTag.GREEDY }
            .sumOf { it.trades } / total
        val patientShare = emotions.filter { it.emotion == EmotionTag.PATIENT }.sumOf { it.trades } / total
        val avgHoldMs = entries.mapNotNull { it.holdingTimeMs?.toDouble() }.averageOrZero()
        val winRate = entries.count { it.isWin }.toDouble() / entries.size

        return when {
            revengeShare >= REVENGE_ARCHETYPE_THRESHOLD -> TraderArchetype.REVENGE_TRADER
            fomoGreedShare >= FOMO_ARCHETYPE_THRESHOLD -> TraderArchetype.MOMENTUM_CHASER
            patientShare >= PATIENT_ARCHETYPE_THRESHOLD && winRate >= 0.55 -> TraderArchetype.DISCIPLINED
            avgHoldMs >= SWING_HOLD_MS -> TraderArchetype.SWING_TRADER
            avgHoldMs > 0.0 && avgHoldMs < SCALP_HOLD_MS -> TraderArchetype.SCALPER
            winRate >= 0.6 -> TraderArchetype.SNIPER
            else -> TraderArchetype.DISCIPLINED
        }
    }

    // --- Insight detection ---

    private fun detectEdges(
        setups: List<CategoryPerformance>,
        sessions: List<SessionPerformance>,
        emotions: List<EmotionPerformance>,
        calibration: RatingCalibration,
    ): List<CoachingInsight> {
        val out = mutableListOf<CoachingInsight>()

        setups.filter { it.trades >= MIN_CATEGORY_TRADES && it.winRate >= 0.6 && it.netPnl > 0 }
            .sortedByDescending { it.netPnl }
            .take(2)
            .forEach {
                out += CoachingInsight(
                    severity = InsightSeverity.POSITIVE,
                    title = "\"${it.category}\" is a strength",
                    detail = "This setup wins ${(it.winRate * 100).toInt()}% of the time and is net " +
                        "positive. Lean into it — trade it more, size it with confidence.",
                    metric = "${it.trades} trades \u00B7 ${fmtMoney(it.netPnl)}",
                )
            }

        sessions.filter { it.trades >= MIN_CATEGORY_TRADES && it.netPnl > 0 && it.winRate >= 0.55 }
            .maxByOrNull { it.netPnl }
            ?.let {
                out += CoachingInsight(
                    severity = InsightSeverity.POSITIVE,
                    title = "You trade the ${it.session.label} session well",
                    detail = "Your edge concentrates here (${(it.winRate * 100).toInt()}% win rate). " +
                        "Consider focusing your screen time on this window.",
                    metric = "${it.trades} trades \u00B7 ${fmtMoney(it.netPnl)}",
                )
            }

        emotions.filter { it.emotion == EmotionTag.PATIENT || it.emotion == EmotionTag.CONFIDENT }
            .filter { it.trades >= MIN_CATEGORY_TRADES && it.netPnl > 0 }
            .maxByOrNull { it.netPnl }
            ?.let {
                out += CoachingInsight(
                    severity = InsightSeverity.POSITIVE,
                    title = "Calm entries pay you",
                    detail = "Trades tagged ${it.emotion.name.lowercase(Locale.US)} are net positive. " +
                        "This is your A-game state — protect it.",
                    metric = "${it.trades} trades \u00B7 ${fmtMoney(it.netPnl)}",
                )
            }

        if (calibration.isWellCalibrated) {
            out += CoachingInsight(
                severity = InsightSeverity.POSITIVE,
                title = "Your self-ratings are honest",
                detail = "Higher-rated trades really do perform better — your read on trade quality " +
                    "is reliable. Trust your gut on A+ setups.",
                metric = "correlation ${fmt(calibration.correlation)}",
            )
        }

        return out
    }

    private fun detectLeaks(
        entries: List<JournalEntry>,
        setups: List<CategoryPerformance>,
        sessions: List<SessionPerformance>,
        emotions: List<EmotionPerformance>,
        disciplineScore: Int,
    ): List<CoachingInsight> {
        val out = mutableListOf<CoachingInsight>()
        val total = entries.size.toDouble()

        emotions.filter { it.emotion in EMOTIONAL_LEAK_TAGS && it.trades >= MIN_LEAK_TRADES && it.netPnl < 0 }
            .sortedBy { it.netPnl }
            .take(2)
            .forEach {
                out += CoachingInsight(
                    severity = if (it.emotion == EmotionTag.REVENGE) InsightSeverity.CRITICAL else InsightSeverity.WARNING,
                    title = "${it.emotion.name.lowercase(Locale.US).replaceFirstChar { c -> c.uppercase() }} trades bleed money",
                    detail = "These emotional entries lose ${fmtMoney(-it.netPnl)} net at a " +
                        "${(it.winRate * 100).toInt()}% win rate. When you feel this, step away from the screen.",
                    metric = "${it.trades} trades \u00B7 ${fmtMoney(it.netPnl)}",
                )
            }

        setups.filter { it.trades >= MIN_LEAK_TRADES && it.netPnl < 0 }
            .sortedBy { it.netPnl }
            .take(2)
            .forEach {
                out += CoachingInsight(
                    severity = InsightSeverity.WARNING,
                    title = "\"${it.category}\" is a leak",
                    detail = "This setup is net negative (${(it.winRate * 100).toInt()}% win rate). " +
                        "Either refine your criteria for it or cut it from your playbook.",
                    metric = "${it.trades} trades \u00B7 ${fmtMoney(it.netPnl)}",
                )
            }

        val noStop = entries.count { abs(it.entryPrice - it.stopLoss) <= 0.0 }
        if (noStop.toDouble() / total >= NO_STOP_THRESHOLD) {
            out += CoachingInsight(
                severity = InsightSeverity.CRITICAL,
                title = "Trades without a defined stop",
                detail = "$noStop of ${entries.size} entries have no stop distance. A trade without a " +
                    "predefined risk is a gamble — always define your out before you're in.",
                metric = "${(noStop * 100.0 / total).toInt()}% of trades",
            )
        }

        val losingSession = sessions.filter { it.trades >= MIN_LEAK_TRADES && it.netPnl < 0 }
            .minByOrNull { it.netPnl }
        if (losingSession != null) {
            out += CoachingInsight(
                severity = InsightSeverity.INFO,
                title = "The ${losingSession.session.label} session hurts you",
                detail = "You're net negative here (${(losingSession.winRate * 100).toInt()}% win rate). " +
                    "Consider sitting out this window until your process there improves.",
                metric = "${losingSession.trades} trades \u00B7 ${fmtMoney(losingSession.netPnl)}",
            )
        }

        if (disciplineScore < LOW_DISCIPLINE_THRESHOLD) {
            out += CoachingInsight(
                severity = InsightSeverity.WARNING,
                title = "Discipline needs work",
                detail = "Emotional entries and/or missing stops are dragging your process score down. " +
                    "Rebuild the basics: plan, stop, size, execute, review.",
                metric = "discipline $disciplineScore/100",
            )
        }

        return out
    }

    // --- Time helpers ---

    private fun hourOfDayUtc(epochMs: Long): Int = utcCalendar(epochMs).get(Calendar.HOUR_OF_DAY)

    private fun dayOfWeekLabel(epochMs: Long): String =
        when (utcCalendar(epochMs).get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "Monday"
            Calendar.TUESDAY -> "Tuesday"
            Calendar.WEDNESDAY -> "Wednesday"
            Calendar.THURSDAY -> "Thursday"
            Calendar.FRIDAY -> "Friday"
            Calendar.SATURDAY -> "Saturday"
            else -> "Sunday"
        }

    private fun utcCalendar(epochMs: Long): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = epochMs }

    private fun holdTimeBucket(entry: JournalEntry): String {
        val ms = entry.holdingTimeMs ?: return "Unknown"
        val minutes = ms / 60_000L
        return when {
            minutes < 15 -> "< 15m"
            minutes < 60 -> "15m - 1h"
            minutes < 240 -> "1h - 4h"
            minutes < 1440 -> "4h - 1d"
            else -> "> 1d"
        }
    }

    // --- Math helpers ---

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private fun pearson(xs: List<Double>, ys: List<Double>): Double {
        if (xs.size < 2 || xs.size != ys.size) return 0.0
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in xs.indices) {
            val a = xs[i] - mx
            val b = ys[i] - my
            num += a * b
            dx += a * a
            dy += b * b
        }
        val denom = sqrt(dx * dy)
        return if (denom > 0.0) (num / denom).coerceIn(-1.0, 1.0) else 0.0
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun fmtMoney(v: Double): String = String.format(Locale.US, "%+.2f", v)

    companion object {
        const val MIN_TRADES = 5
        private const val MIN_CATEGORY_TRADES = 3
        private const val MIN_LEAK_TRADES = 3
        private val EMOTIONAL_LEAK_TAGS = setOf(
            EmotionTag.FOMO, EmotionTag.REVENGE, EmotionTag.FEARFUL, EmotionTag.GREEDY,
        )
        private const val RATING_WEIGHT = 0.3
        private const val STOP_WEIGHT = 0.4
        private const val CALM_WEIGHT = 0.3
        private const val REVENGE_ARCHETYPE_THRESHOLD = 0.2
        private const val FOMO_ARCHETYPE_THRESHOLD = 0.35
        private const val PATIENT_ARCHETYPE_THRESHOLD = 0.3
        private const val SWING_HOLD_MS = 14_400_000L // 4h
        private const val SCALP_HOLD_MS = 900_000L // 15m
        private const val NO_STOP_THRESHOLD = 0.15
        private const val LOW_DISCIPLINE_THRESHOLD = 50
    }
}
