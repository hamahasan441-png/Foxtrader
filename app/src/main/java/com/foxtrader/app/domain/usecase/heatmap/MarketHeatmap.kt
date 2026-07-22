package com.foxtrader.app.domain.usecase.heatmap

import com.foxtrader.app.domain.model.AssetClass
import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject
import kotlin.math.abs

/**
 * Market Heatmap Engine — visualizes relative performance across symbols.
 *
 * Produces a heatmap grid showing:
 * - Color intensity = magnitude of move (green positive, red negative)
 * - Size/weight = volume or market cap
 * - Grouping by asset class or correlation cluster
 *
 * Used for the Scanner/Heatmap screen to quickly identify:
 * - Strongest movers
 * - Sector rotation
 * - Risk-on / risk-off environments
 * - Divergences between correlated instruments
 */
class MarketHeatmap @Inject constructor() {

    data class HeatmapCell(
        val symbol: String,
        val assetClass: AssetClass,
        val changePercent: Double,
        val volume: Double,
        val relativeStrength: Double,  // Relative to group average
        val color: HeatmapColor,
        val intensity: Float,          // 0.0 to 1.0 (how strong the move is)
    )

    enum class HeatmapColor {
        STRONG_BULLISH,   // > +2%
        BULLISH,          // +0.5% to +2%
        NEUTRAL,          // -0.5% to +0.5%
        BEARISH,          // -2% to -0.5%
        STRONG_BEARISH,   // < -2%
    }

    data class HeatmapResult(
        val cells: List<HeatmapCell>,
        val bestPerformer: HeatmapCell?,
        val worstPerformer: HeatmapCell?,
        val averageChange: Double,
        val marketSentiment: MarketSentiment,
        val timestamp: Long = System.currentTimeMillis(),
    )

    enum class MarketSentiment {
        EXTREME_GREED,   // > 75% bullish
        GREED,           // 60-75% bullish
        NEUTRAL,         // 40-60%
        FEAR,            // 25-40% bullish
        EXTREME_FEAR,    // < 25% bullish
    }

    /**
     * Compute the market heatmap from candle data.
     *
     * @param dataMap Symbol → candles (minimum 20 bars per symbol)
     * @param period Number of bars to calculate change over
     */
    fun computeHeatmap(
        dataMap: Map<String, Pair<AssetClass, List<Candle>>>,
        period: Int = 20,
    ): HeatmapResult {
        val cells = mutableListOf<HeatmapCell>()

        for ((symbol, pair) in dataMap) {
            val (assetClass, candles) = pair
            if (candles.size < period + 1) continue

            val current = candles.last().close
            val previous = candles[candles.size - 1 - period].close
            val changePercent = if (previous > 0) ((current - previous) / previous) * 100.0 else 0.0
            val volume = candles.takeLast(period).sumOf { it.volume }

            cells.add(
                HeatmapCell(
                    symbol = symbol,
                    assetClass = assetClass,
                    changePercent = changePercent,
                    volume = volume,
                    relativeStrength = 0.0, // Calculated below
                    color = classifyColor(changePercent),
                    intensity = (abs(changePercent) / 5.0).coerceIn(0.0, 1.0).toFloat(),
                )
            )
        }

        // Calculate relative strength (vs group average)
        val groupAverages = cells.groupBy { it.assetClass }
            .mapValues { (_, group) -> group.map { it.changePercent }.average() }

        val enrichedCells = cells.map { cell ->
            val groupAvg = groupAverages[cell.assetClass] ?: 0.0
            cell.copy(relativeStrength = cell.changePercent - groupAvg)
        }

        val avgChange = cells.map { it.changePercent }.average()
        val bullishPct = cells.count { it.changePercent > 0 }.toDouble() / cells.size.coerceAtLeast(1)

        val sentiment = when {
            bullishPct > 0.75 -> MarketSentiment.EXTREME_GREED
            bullishPct > 0.60 -> MarketSentiment.GREED
            bullishPct > 0.40 -> MarketSentiment.NEUTRAL
            bullishPct > 0.25 -> MarketSentiment.FEAR
            else -> MarketSentiment.EXTREME_FEAR
        }

        return HeatmapResult(
            cells = enrichedCells.sortedByDescending { it.changePercent },
            bestPerformer = enrichedCells.maxByOrNull { it.changePercent },
            worstPerformer = enrichedCells.minByOrNull { it.changePercent },
            averageChange = avgChange,
            marketSentiment = sentiment,
        )
    }

    /**
     * Get cells grouped by asset class for sectored heatmap display.
     */
    fun groupByAssetClass(result: HeatmapResult): Map<AssetClass, List<HeatmapCell>> =
        result.cells.groupBy { it.assetClass }

    private fun classifyColor(change: Double): HeatmapColor = when {
        change > 2.0 -> HeatmapColor.STRONG_BULLISH
        change > 0.5 -> HeatmapColor.BULLISH
        change > -0.5 -> HeatmapColor.NEUTRAL
        change > -2.0 -> HeatmapColor.BEARISH
        else -> HeatmapColor.STRONG_BEARISH
    }
}
