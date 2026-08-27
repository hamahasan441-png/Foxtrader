package com.foxtrader.app.domain.usecase.crucible

import com.foxtrader.app.domain.usecase.crucible.model.CrucibleObservation
import com.foxtrader.app.domain.usecase.crucible.model.CrucibleOverfitReport

/**
 * Splitting, purging and the overfitting measurement.
 *
 * The two things here are what separate a search that can be believed from one
 * that cannot.
 */
object CrucibleValidation {

    /**
     * Split into contiguous folds, then purge and embargo around the test fold.
     *
     * An ordinary split leaks. Outcomes span a horizon, so a training
     * observation starting shortly before the test fold finishes *inside* it,
     * and its label was written by the very bars the rule is about to be tested
     * on. Purging removes any training observation whose outcome window touches
     * the test fold; the embargo removes those close enough after it to have
     * been driven by the same move.
     */
    fun split(
        observations: List<CrucibleObservation>,
        folds: Int,
        embargoBars: Int,
    ): List<Fold> {
        if (observations.size < folds * 2) return emptyList()
        val ordered = observations.sortedBy { it.index }
        val size = ordered.size / folds
        val out = ArrayList<Fold>()

        for (fold in 0 until folds) {
            val from = fold * size
            val to = if (fold == folds - 1) ordered.size else (fold + 1) * size
            val test = ordered.subList(from, to)
            if (test.isEmpty()) continue

            val testFrom = test.first().index
            val testTo = test.last().decidedIndex

            val train = ordered.filterIndexed { i, _ -> i < from || i >= to }.filter {
                // Purge: its outcome window must not touch the test span.
                val overlaps = it.index <= testTo && it.decidedIndex >= testFrom
                // Embargo: nor may it start inside the shadow just after it.
                val embargoed = it.index > testTo && it.index <= testTo + embargoBars
                !overlaps && !embargoed
            }
            if (train.isEmpty()) continue
            out += Fold(index = fold, train = train, test = test)
        }
        return out
    }

    data class Fold(
        val index: Int,
        val train: List<CrucibleObservation>,
        val test: List<CrucibleObservation>,
    )

    /**
     * Probability of backtest overfitting, by combinatorially symmetric
     * cross-validation.
     *
     * The idea is simple and merciless. Split the observations into an even
     * number of blocks. For every way of choosing half the blocks as "in
     * sample", find the rule that scores best there, then see where that same
     * rule ranks among all rules on the other half. If the in-sample winner
     * routinely lands in the bottom half out of sample, the search is selecting
     * noise.
     *
     * This is a property of the **search**, not of any rule, and it is the
     * check a rule-mining engine most needs: as the number of rules grows, this
     * probability tends to one whether or not anything real is present. An
     * engine that mines without measuring it is reporting the width of its own
     * search and calling it an edge.
     *
     * @param matched per rule, per block: how many observations the rule caught.
     * @param hits per rule, per block: how many of those it got right.
     *
     * Tallies are taken per block rather than recomputed per split because a
     * split's score is just the sum of its blocks. Rescoring every rule against
     * every split instead turns a fast check into an intractable one, and a
     * check nobody can afford to run is a check that does not happen.
     */
    fun overfittingProbability(
        matched: Array<IntArray>,
        hits: Array<IntArray>,
        blocks: Int,
        minSupport: Int = 20,
    ): CrucibleOverfitReport {
        val rulesTested = matched.size
        if (rulesTested < 2 || blocks < 4 || blocks % 2 != 0) {
            return CrucibleOverfitReport(
                probability = null,
                trials = 0,
                rulesTested = rulesTested,
                verdict = "Not enough data to measure overfitting.",
            )
        }

        var belowMedian = 0
        var trials = 0

        for (combination in combinations(blocks, blocks / 2)) {
            val inSample = combination.toSet()
            val trainScores = DoubleArray(rulesTested)
            val testScores = DoubleArray(rulesTested)

            for (rule in 0 until rulesTested) {
                var trainMatched = 0
                var trainHits = 0
                var testMatched = 0
                var testHits = 0
                for (block in 0 until blocks) {
                    if (block in inSample) {
                        trainMatched += matched[rule][block]
                        trainHits += hits[rule][block]
                    } else {
                        testMatched += matched[rule][block]
                        testHits += hits[rule][block]
                    }
                }
                // A rule with almost no support must not win a split on one
                // lucky observation.
                trainScores[rule] = if (trainMatched < minSupport) 0.0 else trainHits.toDouble() / trainMatched
                testScores[rule] = if (testMatched < minSupport) 0.0 else testHits.toDouble() / testMatched
            }

            val best = trainScores.indices.maxByOrNull { trainScores[it] } ?: continue
            if (trainScores[best] <= 0.0) continue

            val bestOutOfSample = testScores[best]
            val worse = testScores.count { it < bestOutOfSample }
            if (worse.toDouble() / testScores.size < 0.5) belowMedian++
            trials++
        }

        if (trials == 0) {
            return CrucibleOverfitReport(null, 0, rulesTested, "Overfitting could not be measured.")
        }
        val probability = belowMedian.toDouble() / trials
        return CrucibleOverfitReport(
            probability = probability,
            trials = trials,
            rulesTested = rulesTested,
            verdict = when {
                probability >= 0.5 ->
                    "Search is overfitting: the in-sample best ranks below median out of sample in " +
                        "${(probability * 100).toInt()}% of splits"
                probability >= 0.25 ->
                    "Search is fragile: ${(probability * 100).toInt()}% of splits put the winner below median"
                else ->
                    "Search holds up: the winner stays above median in " +
                        "${((1 - probability) * 100).toInt()}% of splits"
            },
        )
    }

    /**
     * Benjamini-Hochberg: which p-values survive at a false discovery rate.
     *
     * Testing thousands of rules at 95% each guarantees false findings in
     * proportion to how many were tried. Controlling the false discovery rate
     * instead bounds the share of published findings expected to be spurious,
     * which is the quantity a trader actually cares about.
     *
     * @return the largest p-value that may be published, or null if none may.
     */
    fun benjaminiHochbergThreshold(pValues: List<Double>, falseDiscoveryRate: Double): Double? {
        val sorted = pValues.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return null
        val total = sorted.size
        var threshold: Double? = null
        for ((rank, p) in sorted.withIndex()) {
            if (p <= falseDiscoveryRate * (rank + 1) / total) threshold = p
        }
        return threshold
    }

    /** Index combinations, in a fixed order so a run is reproducible. */
    private fun combinations(n: Int, k: Int): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        val current = IntArray(k)
        fun build(start: Int, depth: Int) {
            if (depth == k) {
                out += current.toList()
                return
            }
            for (i in start until n) {
                current[depth] = i
                build(i + 1, depth + 1)
            }
        }
        build(0, 0)
        return out
    }
}
