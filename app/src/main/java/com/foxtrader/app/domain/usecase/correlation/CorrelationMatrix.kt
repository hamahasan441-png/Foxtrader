package com.foxtrader.app.domain.usecase.correlation

import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * Correlation Matrix Engine — calculates statistical correlation between instruments.
 *
 * Produces a correlation matrix showing how price movements of different
 * symbols relate to each other. Essential for:
 * - Portfolio diversification (avoid correlated positions)
 * - Pair trading (find divergence in correlated pairs)
 * - Risk management (correlated exposure amplifies risk)
 *
 * Uses Pearson correlation coefficient on log returns.
 */
class CorrelationMatrix @Inject constructor() {

    /**
     * A single correlation pair result.
     */
    data class CorrelationPair(
        val symbolA: String,
        val symbolB: String,
        val correlation: Double,  // -1.0 to +1.0
        val strength: CorrelationStrength,
        val dataPoints: Int,
    )

    enum class CorrelationStrength {
        STRONG_POSITIVE,   // > 0.7
        MODERATE_POSITIVE, // 0.4 to 0.7
        WEAK,              // -0.4 to 0.4
        MODERATE_NEGATIVE, // -0.7 to -0.4
        STRONG_NEGATIVE,   // < -0.7
    }

    /**
     * Full correlation matrix result.
     */
    data class MatrixResult(
        val symbols: List<String>,
        val matrix: Array<DoubleArray>,   // [i][j] = correlation between symbols[i] and symbols[j]
        val pairs: List<CorrelationPair>, // Sorted by absolute correlation descending
        val period: Int,
    )

    /**
     * Compute the full correlation matrix for a set of symbols.
     *
     * @param dataMap Map of symbol → candle list (must have same length for alignment)
     * @param period Number of bars to use for correlation (from the end)
     */
    fun computeMatrix(
        dataMap: Map<String, List<Candle>>,
        period: Int = 100,
    ): MatrixResult {
        val symbols = dataMap.keys.toList()
        val n = symbols.size
        val matrix = Array(n) { DoubleArray(n) { 0.0 } }
        val pairs = mutableListOf<CorrelationPair>()

        // Calculate returns for each symbol
        val returnsMap = symbols.associateWith { symbol ->
            calculateReturns(dataMap[symbol] ?: emptyList(), period)
        }

        for (i in 0 until n) {
            matrix[i][i] = 1.0 // Self-correlation is always 1
            for (j in i + 1 until n) {
                val returnsA = returnsMap[symbols[i]] ?: continue
                val returnsB = returnsMap[symbols[j]] ?: continue

                val corr = pearsonCorrelation(returnsA, returnsB)
                matrix[i][j] = corr
                matrix[j][i] = corr

                pairs.add(
                    CorrelationPair(
                        symbolA = symbols[i],
                        symbolB = symbols[j],
                        correlation = corr,
                        strength = classifyStrength(corr),
                        dataPoints = minOf(returnsA.size, returnsB.size),
                    )
                )
            }
        }

        return MatrixResult(
            symbols = symbols,
            matrix = matrix,
            pairs = pairs.sortedByDescending { kotlin.math.abs(it.correlation) },
            period = period,
        )
    }

    /**
     * Get the most correlated pair (positive or negative).
     */
    fun getMostCorrelated(result: MatrixResult): CorrelationPair? =
        result.pairs.maxByOrNull { kotlin.math.abs(it.correlation) }

    /**
     * Get pairs that are strongly negatively correlated (hedging candidates).
     */
    fun getHedgingPairs(result: MatrixResult): List<CorrelationPair> =
        result.pairs.filter { it.strength == CorrelationStrength.STRONG_NEGATIVE }

    /**
     * Get diversified pairs (weakly correlated).
     */
    fun getDiversifiedPairs(result: MatrixResult): List<CorrelationPair> =
        result.pairs.filter { it.strength == CorrelationStrength.WEAK }

    // ========================================================================
    // PRIVATE
    // ========================================================================

    private fun calculateReturns(candles: List<Candle>, period: Int): DoubleArray {
        val data = candles.takeLast(period + 1)
        if (data.size < 2) return doubleArrayOf()
        return DoubleArray(data.size - 1) { i ->
            if (data[i].close > 0) {
                kotlin.math.ln(data[i + 1].close / data[i].close)
            } else 0.0
        }
    }

    private fun pearsonCorrelation(x: DoubleArray, y: DoubleArray): Double {
        val n = minOf(x.size, y.size)
        if (n < 5) return 0.0

        var sumX = 0.0; var sumY = 0.0
        var sumXY = 0.0; var sumX2 = 0.0; var sumY2 = 0.0

        for (i in 0 until n) {
            sumX += x[i]
            sumY += y[i]
            sumXY += x[i] * y[i]
            sumX2 += x[i] * x[i]
            sumY2 += y[i] * y[i]
        }

        val numerator = n * sumXY - sumX * sumY
        val denominator = sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY))

        return if (denominator > 0) (numerator / denominator).coerceIn(-1.0, 1.0) else 0.0
    }

    private fun classifyStrength(correlation: Double): CorrelationStrength = when {
        correlation > 0.7 -> CorrelationStrength.STRONG_POSITIVE
        correlation > 0.4 -> CorrelationStrength.MODERATE_POSITIVE
        correlation > -0.4 -> CorrelationStrength.WEAK
        correlation > -0.7 -> CorrelationStrength.MODERATE_NEGATIVE
        else -> CorrelationStrength.STRONG_NEGATIVE
    }
}
