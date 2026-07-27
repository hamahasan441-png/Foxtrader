package com.foxtrader.app.domain.usecase.portfolio

import com.foxtrader.app.domain.usecase.correlation.CorrelationMatrix
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Groups held symbols into correlation clusters — sets of positions that move
 * together strongly enough to behave as a single risk unit.
 *
 * `PortfolioEngine` already reports a single `correlatedExposurePercent` (the
 * worst cluster's total). That is the right input for a risk gate, but it is
 * not explainable: a trader seeing "correlated exposure 240%" cannot tell
 * *which* positions are the problem. This builds the full grouping so the UI
 * can name them.
 *
 * Implemented as union-find over the correlation pairs: two symbols join the
 * same cluster when |correlation| meets the threshold, and connectivity is
 * transitive (A~B and B~C puts A, B and C in one cluster even if A~C is weak).
 * Transitivity is the conservative choice — a chain of strongly linked
 * positions can still unwind together.
 */
@Singleton
class CorrelationClusterBuilder @Inject constructor() {

    data class Cluster(
        val symbols: List<String>,
        val peakCorrelation: Double,
        val combinedExposurePercent: Double,
        val strength: CorrelationMatrix.CorrelationStrength,
    ) {
        /**
         * A negative peak means the members move *against* each other, so the
         * cluster partly self-hedges rather than compounding exposure. This is
         * domain semantics (the sign of a correlation), not presentation, so it
         * lives here rather than in the UI model.
         */
        val isHedge: Boolean get() = peakCorrelation < 0.0
    }

    /**
     * @param exposureBySymbol uppercase symbol → exposure percent of equity.
     * @param matrix correlation result covering (at least) the held symbols.
     * @param threshold minimum |correlation| for two symbols to be linked.
     * @return clusters of 2+ symbols, largest combined exposure first.
     *   Single symbols are omitted: one position is not a cluster.
     */
    fun build(
        exposureBySymbol: Map<String, Double>,
        matrix: CorrelationMatrix.MatrixResult?,
        threshold: Double = DEFAULT_THRESHOLD,
    ): List<Cluster> {
        if (matrix == null || exposureBySymbol.size < 2) return emptyList()

        val held = exposureBySymbol.keys
        val parent = held.associateWith { it }.toMutableMap()

        fun find(x: String): String {
            var root = x
            while (parent[root] != root) root = parent[root] ?: root
            // Path compression keeps repeated lookups near-constant.
            var cur = x
            while (parent[cur] != root) {
                val next = parent[cur] ?: break
                parent[cur] = root
                cur = next
            }
            return root
        }

        fun union(a: String, b: String) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        // Only pairs where BOTH symbols are actually held can form a cluster.
        val relevant = matrix.pairs.filter { pair ->
            val a = pair.symbolA.uppercase()
            val b = pair.symbolB.uppercase()
            a in held && b in held && abs(pair.correlation) >= threshold
        }
        relevant.forEach { union(it.symbolA.uppercase(), it.symbolB.uppercase()) }

        return held
            .groupBy { find(it) }
            .values
            .filter { it.size >= 2 }
            .map { members ->
                val memberSet = members.toSet()
                val inCluster = relevant.filter {
                    it.symbolA.uppercase() in memberSet && it.symbolB.uppercase() in memberSet
                }
                // Peak by magnitude, but keep the SIGN: a -0.9 cluster hedges,
                // a +0.9 cluster compounds. Reporting 0.9 for both would invert
                // the risk reading.
                val peak = inCluster.maxByOrNull { abs(it.correlation) }
                Cluster(
                    symbols = members.sorted(),
                    peakCorrelation = peak?.correlation ?: 0.0,
                    combinedExposurePercent = members.sumOf { exposureBySymbol[it] ?: 0.0 },
                    strength = peak?.strength ?: CorrelationMatrix.CorrelationStrength.WEAK,
                )
            }
            .sortedByDescending { it.combinedExposurePercent }
    }

    private companion object {
        /** Matches PortfolioEngine's correlated-exposure threshold. */
        const val DEFAULT_THRESHOLD = 0.7
    }
}
