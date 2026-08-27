package com.foxtrader.app.domain.usecase.crucible

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.crucible.model.CrucibleCondition
import com.foxtrader.app.domain.usecase.crucible.model.CrucibleEvidence
import com.foxtrader.app.domain.usecase.crucible.model.CrucibleObservation
import com.foxtrader.app.domain.usecase.crucible.model.CrucibleRule

/**
 * Enumerates the rules to be tested, and scores one against a set of
 * observations.
 *
 * The rule space is kept deliberately small and interpretable. Every condition
 * is a contiguous band of quantile buckets on one feature, and at most a
 * configured handful are combined. This is not a limitation to apologise for:
 * the size of the search is the quantity every correction downstream is paid
 * in, so an unnecessarily wide search makes every genuine finding harder to
 * establish. A search that cannot be counted cannot be corrected for.
 */
object CrucibleSearch {

    /** Every candidate rule, in a fixed order so runs are reproducible. */
    fun enumerate(
        featureCount: Int,
        bucketCount: Int,
        target: CrucibleTarget,
        maxConditions: Int,
    ): List<CrucibleRule> {
        val singles = ArrayList<CrucibleCondition>()
        for (feature in 0 until featureCount) {
            val name = CrucibleObservations.FEATURE_NAMES.getOrElse(feature) { "feature $feature" }
            // Contiguous bands only. An arbitrary subset of buckets would let
            // the search carve the data into whatever shape fits it, which is
            // how a rule stops describing anything.
            for (from in 0 until bucketCount) {
                for (to in from until bucketCount) {
                    if (from == 0 && to == bucketCount - 1) continue // matches everything
                    singles += CrucibleCondition(
                        feature = feature,
                        featureName = name,
                        fromBucket = from,
                        toBucket = to,
                        description = describe(name, from, to, bucketCount),
                    )
                }
            }
        }

        val sides: List<Direction?> = when (target) {
            CrucibleTarget.DIRECTION -> listOf(Direction.BULLISH, Direction.BEARISH)
            CrucibleTarget.MOVEMENT -> listOf(null)
        }

        val out = ArrayList<CrucibleRule>()
        for (side in sides) {
            singles.forEach { out += CrucibleRule(listOf(it), side) }
            if (maxConditions >= 2) {
                for (i in singles.indices) {
                    for (j in (i + 1) until singles.size) {
                        // One condition per feature: two bands on the same
                        // feature are either redundant or contradictory.
                        if (singles[i].feature == singles[j].feature) continue
                        out += CrucibleRule(listOf(singles[i], singles[j]), side)
                    }
                }
            }
        }
        return out
    }

    /** Did this observation do what the rule predicted? */
    fun hit(observation: CrucibleObservation, rule: CrucibleRule, target: CrucibleTarget): Boolean =
        when (target) {
            // Movement asks only whether the barrier was reached at all, which
            // is exactly the question volatility clustering makes answerable.
            CrucibleTarget.MOVEMENT -> observation.resolvedDirection != null
            CrucibleTarget.DIRECTION -> observation.resolvedDirection == rule.side
        }

    /** Score a rule over the observations it matches. */
    fun evaluate(
        rule: CrucibleRule,
        observations: List<CrucibleObservation>,
        target: CrucibleTarget,
        baseRate: Double?,
        confidence: Double,
    ): CrucibleEvidence {
        val matched = observations.filter { rule.matches(it.buckets) }
        if (matched.isEmpty()) return CrucibleEvidence.EMPTY
        return CrucibleEvidence.of(
            hits = matched.map { hit(it, rule, target) },
            uniqueness = matched.map { it.uniqueness },
            baseRate = baseRate,
            confidence = confidence,
        )
    }

    /**
     * The rate a rule has to beat: what the target scores with no rule at all.
     *
     * For direction this is the better of the two constant sides, so a market
     * that simply rose cannot be sold back as skill.
     */
    fun baseRateOf(
        observations: List<CrucibleObservation>,
        target: CrucibleTarget,
        side: Direction?,
    ): Double? {
        if (observations.isEmpty()) return null
        return when (target) {
            CrucibleTarget.MOVEMENT ->
                observations.count { it.resolvedDirection != null }.toDouble() / observations.size

            CrucibleTarget.DIRECTION -> {
                val up = observations.count { it.resolvedDirection == Direction.BULLISH }
                val down = observations.count { it.resolvedDirection == Direction.BEARISH }
                // The constant rule for this side is what a rule taking this
                // side must beat; the better of the two is what the run as a
                // whole must beat.
                val forSide = when (side) {
                    Direction.BULLISH -> up
                    Direction.BEARISH -> down
                    null -> maxOf(up, down)
                }
                forSide.toDouble() / observations.size
            }
        }
    }

    private fun describe(name: String, from: Int, to: Int, bucketCount: Int): String {
        val top = bucketCount - 1
        return when {
            from == 0 && to < top -> "$name in the lowest ${to + 1} of ${bucketCount}"
            from > 0 && to == top -> "$name in the highest ${bucketCount - from} of ${bucketCount}"
            from == to -> "$name in band ${from + 1} of $bucketCount"
            else -> "$name in bands ${from + 1}-${to + 1} of $bucketCount"
        }
    }
}
