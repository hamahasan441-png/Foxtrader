package com.foxtrader.app.domain.usecase.crucible.model

import com.foxtrader.app.domain.math.NormalDistribution
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.crucible.CrucibleTarget
import kotlin.math.sqrt

/** One observation: a bar, what was true there, and what happened next. */
data class CrucibleObservation(
    val index: Int,
    val timestamp: Long,
    val price: Double,
    /** Discretised feature bucket per feature, parallel to the feature list. */
    val buckets: IntArray,
    /** True when the outcome the rule predicts occurred. */
    val hit: Boolean,
    /** Which way price actually resolved, or null when it did not. */
    val resolvedDirection: Direction?,
    /** Bar the outcome became known. */
    val decidedIndex: Int,
    /**
     * Share of this observation's horizon not shared with any other.
     *
     * One means it overlapped nothing; near zero means almost everything it
     * says was already said by its neighbours. This is what stops a thousand
     * overlapping observations being counted as a thousand facts.
     */
    val uniqueness: Double,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is CrucibleObservation && index == other.index)

    override fun hashCode(): Int = index
}

/** A single condition: one feature confined to a range of buckets. */
data class CrucibleCondition(
    val feature: Int,
    val featureName: String,
    val fromBucket: Int,
    val toBucket: Int,
    val description: String,
) {
    fun matches(buckets: IntArray): Boolean {
        val value = buckets.getOrNull(feature) ?: return false
        return value in fromBucket..toBucket
    }
}

/** A candidate rule: conditions that must all hold, and a side to take. */
data class CrucibleRule(
    val conditions: List<CrucibleCondition>,
    /** The side the rule calls, or null for a movement rule. */
    val side: Direction?,
) {
    fun matches(buckets: IntArray): Boolean = conditions.all { it.matches(buckets) }

    val description: String
        get() = conditions.joinToString(" and ") { it.description } +
            (side?.let { " → ${it.name.lowercase()}" } ?: "")

    val key: String
        get() = conditions.joinToString("|") { "${it.feature}:${it.fromBucket}-${it.toBucket}" } +
            "/${side?.name ?: "ANY"}"
}

/**
 * What a rule scored, with the honesty corrections applied.
 *
 * The distinction between [samples] and [effectiveSamples] carries most of the
 * meaning. Overlapping outcomes are not independent observations; treating them
 * as if they were is what lets a rule with almost no evidence behind it report
 * a confident-looking bound.
 */
data class CrucibleEvidence(
    val samples: Int,
    val hits: Int,
    /** Sum of uniqueness: how many genuinely independent observations this is. */
    val effectiveSamples: Double,
    val accuracy: Double?,
    /** What the same target scored over every observation, rule or no rule. */
    val baseRate: Double?,
    /** Lower bound on accuracy, computed on the effective sample. */
    val accuracyLowerBound: Double?,
    /** One-sided p-value against the base rate, on the effective sample. */
    val pValue: Double?,
) {
    val lift: Double? get() = if (accuracy == null || baseRate == null) null else accuracy - baseRate

    companion object {
        val EMPTY = CrucibleEvidence(0, 0, 0.0, null, null, null, null)

        /**
         * @param uniqueness per-observation uniqueness, parallel to [hits].
         */
        fun of(
            hits: List<Boolean>,
            uniqueness: List<Double>,
            baseRate: Double?,
            confidence: Double,
        ): CrucibleEvidence {
            if (hits.isEmpty()) return EMPTY

            val effective = uniqueness.sum().coerceAtLeast(0.0)
            val hitCount = hits.count { it }
            val accuracy = hitCount.toDouble() / hits.size
            // Scale the count down to independent-equivalent observations
            // before any bound is computed from it.
            val effectiveHits = accuracy * effective

            return CrucibleEvidence(
                samples = hits.size,
                hits = hitCount,
                effectiveSamples = effective,
                accuracy = accuracy,
                baseRate = baseRate,
                accuracyLowerBound = wilsonLowerBound(effectiveHits, effective, confidence),
                pValue = baseRate?.let { binomialTailAbove(effectiveHits, effective, it) },
            )
        }

        /** Wilson lower bound over a possibly fractional sample. */
        fun wilsonLowerBound(hits: Double, total: Double, confidence: Double): Double? {
            if (total <= 0.0) return null
            val z = normalQuantile(1.0 - (1.0 - confidence) / 2.0)
            val p = (hits / total).coerceIn(0.0, 1.0)
            val z2 = z * z
            val denominator = 1.0 + z2 / total
            val centre = p + z2 / (2.0 * total)
            val margin = z * sqrt(p * (1.0 - p) / total + z2 / (4.0 * total * total))
            return ((centre - margin) / denominator).coerceIn(0.0, 1.0)
        }

        /**
         * Probability of scoring at least this well by chance at [baseRate].
         *
         * Normal approximation over the effective sample: the effective sample
         * is fractional, so an exact binomial is not defined on it, and the
         * approximation is conservative enough at the sizes this gate allows.
         */
        fun binomialTailAbove(hits: Double, total: Double, baseRate: Double): Double? {
            if (total <= 0.0) return null
            val p = baseRate.coerceIn(1e-9, 1.0 - 1e-9)
            val sd = sqrt(p * (1.0 - p) * total)
            if (sd <= 0.0) return if (hits > p * total) 0.0 else 1.0
            // Continuity correction, so a single lucky observation cannot look
            // like overwhelming evidence.
            val z = (hits - p * total - 0.5) / sd
            return (1.0 - standardNormalCdf(z)).coerceIn(0.0, 1.0)
        }

        fun standardNormalCdf(z: Double): Double = NormalDistribution.cdf(z)

        fun normalQuantile(p: Double): Double = NormalDistribution.quantile(p)
    }
}

/** A rule that survived every test, with the evidence that carried it. */
data class CrucibleFinding(
    val rule: CrucibleRule,
    /** Out-of-sample evidence — the only evidence that counts. */
    val outOfSample: CrucibleEvidence,
    /** In-sample evidence, reported so the gap between them is visible. */
    val inSample: CrucibleEvidence,
    /** Benjamini-Hochberg threshold this rule's p-value had to clear. */
    val discoveryThreshold: Double,
    val reasons: List<String>,
)

/**
 * How likely the whole search is to have fooled itself.
 *
 * Measured by splitting the series into folds, selecting the best rule on
 * every possible half, and seeing where it lands on the other half. If the
 * winner routinely ranks below median out of sample, the search is selecting
 * noise — and the published number would be the width of the search rather
 * than any property of the market.
 */
data class CrucibleOverfitReport(
    /** Probability the in-sample best ranks below median out of sample. */
    val probability: Double?,
    val trials: Int,
    val rulesTested: Int,
    val verdict: String,
)

data class CrucibleSignal(
    val symbol: String,
    val timeframe: Timeframe,
    val finding: CrucibleFinding,
    val direction: Direction,
    val index: Int,
    val timestamp: Long,
    val price: Double,
    val barrier: Double,
) {
    val key: String get() = "$symbol|${timeframe.label}|${direction.name}|$index"
}

data class CrucibleAnalysis(
    val target: CrucibleTarget,
    val observations: Int,
    /** Independent-equivalent observations behind the whole run. */
    val effectiveObservations: Double,
    val baseRate: Double?,
    val rulesTested: Int,
    val findings: List<CrucibleFinding>,
    val signals: List<CrucibleSignal>,
    val overfitting: CrucibleOverfitReport,
    val statusText: String,
) {
    companion object {
        fun empty(target: CrucibleTarget, reason: String) = CrucibleAnalysis(
            target = target,
            observations = 0,
            effectiveObservations = 0.0,
            baseRate = null,
            rulesTested = 0,
            findings = emptyList(),
            signals = emptyList(),
            overfitting = CrucibleOverfitReport(null, 0, 0, reason),
            statusText = reason,
        )
    }
}
