package com.foxtrader.app.domain.model.tradepro

/**
 * A pairwise correlation matrix of return series across a set of symbols. Correlation is the
 * blind spot of naive position sizing: three "different" longs that are 0.9 correlated are really one
 * position at 3x size. This surfaces that hidden concentration so it can be managed.
 *
 * [values] is a dense row-major matrix indexed by [symbols]; `values[i][j]` is the Pearson correlation
 * of symbol i vs symbol j over the shared return window (diagonal is 1.0).
 */
data class SymbolCorrelationMatrix(
    val symbols: List<String>,
    val values: List<List<Double>>,
    val windowBars: Int,
    /** Clusters of mutually highly-correlated symbols (|r| >= the cluster threshold). */
    val clusters: List<SymbolCorrelationCluster>,
    /** The most strongly correlated (or anti-correlated) pairs, strongest first. */
    val notablePairs: List<SymbolCorrelationPair>,
    val narrative: String,
) {
    fun correlation(a: String, b: String): Double {
        val i = symbols.indexOf(a)
        val j = symbols.indexOf(b)
        if (i < 0 || j < 0) return 0.0
        return values[i][j]
    }

    companion object {
        fun empty(reason: String): SymbolCorrelationMatrix = SymbolCorrelationMatrix(
            symbols = emptyList(),
            values = emptyList(),
            windowBars = 0,
            clusters = emptyList(),
            notablePairs = emptyList(),
            narrative = reason,
        )
    }
}

/**
 * A single correlated pair with its coefficient. [strength] is |correlation| for easy ranking.
 */
data class SymbolCorrelationPair(
    val symbolA: String,
    val symbolB: String,
    val correlation: Double,
) {
    val strength: Double get() = kotlin.math.abs(correlation)
    val isPositive: Boolean get() = correlation >= 0.0
}

/**
 * A group of symbols that all move together (pairwise |r| >= the cluster threshold). Holding positions
 * across a cluster stacks correlated risk.
 */
data class SymbolCorrelationCluster(
    val symbols: List<String>,
    val averageCorrelation: Double,
) {
    val size: Int get() = symbols.size
}
