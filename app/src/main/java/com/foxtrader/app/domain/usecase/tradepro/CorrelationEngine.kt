package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.tradepro.SymbolCorrelationCluster
import com.foxtrader.app.domain.model.tradepro.SymbolCorrelationMatrix
import com.foxtrader.app.domain.model.tradepro.SymbolCorrelationPair
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Computes a pairwise Pearson correlation matrix of percentage returns across a set of symbols, and
 * derives the practically useful bits: clusters of co-moving symbols and the most notable pairs.
 *
 * Correlation is measured on **returns**, not price, and over a **shared window** (the last N bars
 * common to every symbol), so mismatched history lengths don't skew the result. Pure and
 * deterministic — trivially unit-testable.
 */
class CorrelationEngine @Inject constructor() {

    fun compute(
        candlesBySymbol: Map<String, List<Candle>>,
        window: Int = DEFAULT_WINDOW,
        clusterThreshold: Double = DEFAULT_CLUSTER_THRESHOLD,
    ): SymbolCorrelationMatrix {
        // Keep only symbols with enough data, in a stable, sorted order.
        val symbols = candlesBySymbol
            .filterValues { it.size > MIN_BARS }
            .keys
            .sorted()
        if (symbols.size < 2) {
            return SymbolCorrelationMatrix.empty("Need at least 2 symbols with sufficient history to correlate.")
        }

        // Align to the shortest available return window across the selected symbols.
        val returnsBySymbol = LinkedHashMap<String, DoubleArray>(symbols.size)
        var shared = window
        for (symbol in symbols) {
            val closes = candlesBySymbol.getValue(symbol).map { it.close }
            val returns = pctReturns(closes)
            returnsBySymbol[symbol] = returns
            shared = minOf(shared, returns.size)
        }
        if (shared < MIN_RETURNS) {
            return SymbolCorrelationMatrix.empty("Not enough overlapping bars to compute a stable correlation.")
        }
        // Trim every series to the last [shared] returns so all are aligned.
        val aligned = returnsBySymbol.mapValues { (_, r) -> r.copyOfRange(r.size - shared, r.size) }

        val n = symbols.size
        val values = MutableList(n) { MutableList(n) { 0.0 } }
        val notable = ArrayList<SymbolCorrelationPair>()
        for (i in 0 until n) {
            values[i][i] = 1.0
            for (j in i + 1 until n) {
                val r = pearson(aligned.getValue(symbols[i]), aligned.getValue(symbols[j]))
                values[i][j] = r
                values[j][i] = r
                notable += SymbolCorrelationPair(symbols[i], symbols[j], r)
            }
        }

        notable.sortByDescending { it.strength }
        val clusters = buildClusters(symbols, values, clusterThreshold)

        return SymbolCorrelationMatrix(
            symbols = symbols,
            values = values.map { it.toList() },
            windowBars = shared,
            clusters = clusters,
            notablePairs = notable.take(MAX_NOTABLE),
            narrative = narrative(symbols.size, shared, clusters, notable.firstOrNull()),
        )
    }

    // --- Math ---

    private fun pctReturns(closes: List<Double>): DoubleArray {
        if (closes.size < 2) return DoubleArray(0)
        val out = DoubleArray(closes.size - 1)
        for (i in 1 until closes.size) {
            val prev = closes[i - 1]
            out[i - 1] = if (prev != 0.0) (closes[i] - prev) / prev else 0.0
        }
        return out
    }

    private fun pearson(xs: DoubleArray, ys: DoubleArray): Double {
        val n = minOf(xs.size, ys.size)
        if (n < 2) return 0.0
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until n) {
            sx += xs[i]
            sy += ys[i]
        }
        val mx = sx / n
        val my = sy / n
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in 0 until n) {
            val a = xs[i] - mx
            val b = ys[i] - my
            num += a * b
            dx += a * a
            dy += b * b
        }
        val denom = sqrt(dx * dy)
        return if (denom > 0.0) (num / denom).coerceIn(-1.0, 1.0) else 0.0
    }

    /**
     * Greedy clustering: seed a cluster from the strongest still-unclustered positive pair, then pull
     * in any symbol that is >= [threshold] correlated with every current member. Simple, deterministic,
     * and good enough to flag concentration.
     */
    private fun buildClusters(
        symbols: List<String>,
        values: List<List<Double>>,
        threshold: Double,
    ): List<SymbolCorrelationCluster> {
        val clustered = HashSet<String>()
        val clusters = ArrayList<SymbolCorrelationCluster>()

        for (i in symbols.indices) {
            if (symbols[i] in clustered) continue
            val members = ArrayList<String>()
            members += symbols[i]
            for (j in symbols.indices) {
                if (i == j || symbols[j] in clustered) continue
                val fitsAll = members.all { m ->
                    val mi = symbols.indexOf(m)
                    values[mi][j] >= threshold
                }
                if (fitsAll) members += symbols[j]
            }
            if (members.size >= 2) {
                clustered.addAll(members)
                clusters += SymbolCorrelationCluster(members, averagePairwise(members, symbols, values))
            }
        }
        return clusters.sortedByDescending { it.averageCorrelation }
    }

    private fun averagePairwise(members: List<String>, symbols: List<String>, values: List<List<Double>>): Double {
        var sum = 0.0
        var count = 0
        for (a in members.indices) {
            for (b in a + 1 until members.size) {
                val i = symbols.indexOf(members[a])
                val j = symbols.indexOf(members[b])
                sum += values[i][j]
                count++
            }
        }
        return if (count > 0) sum / count else 0.0
    }

    private fun narrative(
        symbolCount: Int,
        window: Int,
        clusters: List<SymbolCorrelationCluster>,
        topPair: SymbolCorrelationPair?,
    ): String = buildString {
        append("$symbolCount symbols correlated over $window bars. ")
        if (clusters.isNotEmpty()) {
            val biggest = clusters.maxByOrNull { it.size }
            if (biggest != null) {
                append("${clusters.size} correlated cluster(s); largest: ${biggest.symbols.joinToString(", ")} ")
                append("(avg r ${fmt(biggest.averageCorrelation)}). Avoid stacking these in one direction. ")
            }
        } else {
            append("No tight clusters \u2014 the set is well diversified. ")
        }
        if (topPair != null) {
            val kind = if (topPair.isPositive) "move together" else "move inversely"
            append("Strongest link: ${topPair.symbolA}/${topPair.symbolB} $kind (r ${fmt(topPair.correlation)}).")
        }
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)

    companion object {
        const val DEFAULT_WINDOW = 120
        const val DEFAULT_CLUSTER_THRESHOLD = 0.7
        private const val MIN_BARS = 10
        private const val MIN_RETURNS = 5
        private const val MAX_NOTABLE = 10
    }
}
