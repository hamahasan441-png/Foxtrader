package com.foxtrader.app.domain.usecase.analysis

import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * Market Profile (TPO — Time Price Opportunity) engine.
 *
 * Builds a price distribution showing how much TIME price spent at each level
 * (as opposed to volume profile which uses volume). Reveals:
 * - Point of Control (POC): most-visited price
 * - Value Area (70% of time): the "fair value" range
 * - Single prints / poor highs & lows
 *
 * Complements the volume profile for a complete auction-market view.
 */
class MarketProfile @Inject constructor() {

    data class TpoLevel(
        val priceLevel: Double,
        val tpoCount: Int,       // Number of periods that traded here
    )

    data class ProfileResult(
        val levels: List<TpoLevel>,
        val poc: Double,          // Point of Control
        val valueAreaHigh: Double,
        val valueAreaLow: Double,
        val profileHigh: Double,
        val profileLow: Double,
    )

    fun compute(candles: List<Candle>, rowSize: Int = 50): ProfileResult {
        if (candles.isEmpty()) {
            return ProfileResult(emptyList(), 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        val high = candles.maxOf { it.high }
        val low = candles.minOf { it.low }
        val range = (high - low).coerceAtLeast(1e-9)
        val bucketSize = range / rowSize

        val counts = IntArray(rowSize)
        for (c in candles) {
            val startBucket = (((c.low - low) / bucketSize).toInt()).coerceIn(0, rowSize - 1)
            val endBucket = (((c.high - low) / bucketSize).toInt()).coerceIn(0, rowSize - 1)
            for (b in startBucket..endBucket) counts[b]++
        }

        val levels = (0 until rowSize).map { b ->
            TpoLevel(priceLevel = low + (b + 0.5) * bucketSize, tpoCount = counts[b])
        }

        val pocBucket = counts.indices.maxByOrNull { counts[it] } ?: 0
        val poc = low + (pocBucket + 0.5) * bucketSize
        val totalTpo = counts.sum()
        val valueAreaTarget = totalTpo * 0.7

        // Expand from POC outward until 70% captured
        var accumulated = counts[pocBucket]
        var upper = pocBucket
        var lower = pocBucket
        while (accumulated < valueAreaTarget && (upper < rowSize - 1 || lower > 0)) {
            val upNext = if (upper < rowSize - 1) counts[upper + 1] else -1
            val downNext = if (lower > 0) counts[lower - 1] else -1
            if (upNext >= downNext && upNext >= 0) {
                upper++; accumulated += counts[upper]
            } else if (downNext >= 0) {
                lower--; accumulated += counts[lower]
            } else break
        }

        return ProfileResult(
            levels = levels,
            poc = poc,
            valueAreaHigh = low + (upper + 1) * bucketSize,
            valueAreaLow = low + lower * bucketSize,
            profileHigh = high,
            profileLow = low,
        )
    }
}
