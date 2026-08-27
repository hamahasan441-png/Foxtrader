package com.foxtrader.app.domain.usecase.apex.model

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.apex.ApexMember

/** One member methodology's vote, normalised to a common shape. */
data class ApexVote(
    val member: ApexMember,
    val direction: Direction,
    /** Execution bar the member confirmed on. */
    val index: Int,
    val timestamp: Long,
    val entry: Double,
    val stop: Double,
    val target: Double,
)

/** How a trade ended. */
enum class ApexOutcome { WIN, LOSS, EXPIRED, OPEN }

/**
 * A candidate the members agreed on, plus how it actually turned out.
 *
 * Every candidate is tracked, published or not: the engine's measured record is
 * the record of its method, and withholding a signal does not make the trade it
 * would have taken disappear from the evidence.
 */
data class ApexCandidate(
    val direction: Direction,
    /** Bar the last agreeing vote landed on; the candidate is knowable here. */
    val index: Int,
    val timestamp: Long,
    val entry: Double,
    val stop: Double,
    val target: Double,
    val votes: List<ApexVote>,
    val outcome: ApexOutcome,
    /** Bar the trade resolved on, or null while it is still open. */
    val resolvedIndex: Int?,
    /** Realised R: the reward multiple on a win, -1 on a loss. */
    val realisedR: Double?,
) {
    val members: Set<ApexMember> get() = votes.map { it.member }.toSet()

    val risk: Double get() = kotlin.math.abs(entry - stop)

    val rewardMultiple: Double
        get() = if (risk <= 0.0) 0.0 else kotlin.math.abs(target - entry) / risk
}

/**
 * The engine's measured record over a window of resolved trades.
 *
 * Win rate alone decides nothing. A high rate bought with a small target can
 * still lose money, so expectancy travels with it everywhere it is shown.
 */
data class ApexPrecision(
    val resolved: Int,
    val wins: Int,
    val losses: Int,
    /** Wins over resolved, or null when nothing has resolved yet. */
    val hitRate: Double?,
    /** Average R per resolved trade. Negative means the rate is not enough. */
    val expectancyR: Double?,
    /** Gross win R over gross loss R, or null when either side is empty. */
    val profitFactor: Double?,
    /**
     * Wilson lower bound on the hit rate at 95% confidence.
     *
     * The raw rate is what a small sample flatters. Four wins out of five is
     * "80%" and means nothing; its lower bound is about 38%, which is the
     * honest reading. Gating on this instead of the raw rate is what stops a
     * lucky handful of trades unlocking a threshold the record cannot support.
     */
    val hitRateLowerBound: Double?,
) {
    /**
     * Whether the record is long enough and good enough to publish under.
     *
     * @param useLowerBound gate on the confidence bound rather than the raw
     *   rate. On by default; turning it off makes the threshold much easier to
     *   clear and much less meaningful.
     */
    fun meets(minHitRate: Double, minSample: Int, useLowerBound: Boolean = true): Boolean {
        if (resolved < minSample) return false
        val measure = if (useLowerBound) hitRateLowerBound else hitRate
        return (measure ?: 0.0) >= minHitRate
    }

    companion object {
        val EMPTY = ApexPrecision(0, 0, 0, null, null, null, null)

        /** 95% two-sided normal quantile. */
        private const val Z = 1.959963985

        /**
         * Wilson score interval, lower bound.
         *
         * Chosen over the textbook normal approximation because that one is
         * badly behaved exactly where this gate lives — small samples and
         * proportions near 1, where it can even produce bounds above 1.
         */
        fun wilsonLowerBound(wins: Int, total: Int): Double? {
            if (total <= 0) return null
            val p = wins.toDouble() / total
            val z2 = Z * Z
            val denominator = 1.0 + z2 / total
            val centre = p + z2 / (2.0 * total)
            val margin = Z * kotlin.math.sqrt(p * (1.0 - p) / total + z2 / (4.0 * total * total))
            return ((centre - margin) / denominator).coerceIn(0.0, 1.0)
        }

        fun of(outcomes: List<Pair<ApexOutcome, Double>>): ApexPrecision {
            val resolved = outcomes.filter { it.first == ApexOutcome.WIN || it.first == ApexOutcome.LOSS }
            if (resolved.isEmpty()) return EMPTY

            val wins = resolved.count { it.first == ApexOutcome.WIN }
            val losses = resolved.size - wins
            val grossWin = resolved.filter { it.first == ApexOutcome.WIN }.sumOf { it.second }
            val grossLoss = resolved.filter { it.first == ApexOutcome.LOSS }.sumOf { -it.second }

            return ApexPrecision(
                resolved = resolved.size,
                wins = wins,
                losses = losses,
                hitRate = wins.toDouble() / resolved.size,
                expectancyR = resolved.sumOf { it.second } / resolved.size,
                profitFactor = if (grossLoss <= 0.0) null else grossWin / grossLoss,
                hitRateLowerBound = wilsonLowerBound(wins, resolved.size),
            )
        }
    }
}

/** A published signal: a candidate the measured record was good enough to allow. */
data class ApexSignal(
    val symbol: String,
    val timeframe: Timeframe,
    val candidate: ApexCandidate,
    /**
     * The record as it stood when this was published — measured on trades that
     * had already resolved by then, never on this trade or any later one.
     */
    val precisionAtPublication: ApexPrecision,
    val reasons: List<String>,
) {
    val direction: Direction get() = candidate.direction

    val index: Int get() = candidate.index

    val timestamp: Long get() = candidate.timestamp

    val entry: Double get() = candidate.entry

    val stop: Double get() = candidate.stop

    val target: Double get() = candidate.target

    val key: String
        get() = "$symbol|${timeframe.label}|${direction.name}|$index"
}

/** Everything the engine produced for one series. */
data class ApexAnalysis(
    val votes: List<ApexVote>,
    val candidates: List<ApexCandidate>,
    val signals: List<ApexSignal>,
    /** The record over every candidate the method produced. */
    val methodPrecision: ApexPrecision,
    /** The record over the signals it actually published. */
    val publishedPrecision: ApexPrecision,
    val statusText: String,
) {
    companion object {
        fun empty(reason: String) = ApexAnalysis(
            votes = emptyList(),
            candidates = emptyList(),
            signals = emptyList(),
            methodPrecision = ApexPrecision.EMPTY,
            publishedPrecision = ApexPrecision.EMPTY,
            statusText = reason,
        )
    }
}
