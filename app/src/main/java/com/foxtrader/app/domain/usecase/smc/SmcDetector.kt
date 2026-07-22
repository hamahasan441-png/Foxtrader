package com.foxtrader.app.domain.usecase.smc

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.FairValueGap
import com.foxtrader.app.domain.model.FvgType
import com.foxtrader.app.domain.model.LiquidityPool
import com.foxtrader.app.domain.model.LiquidityType
import com.foxtrader.app.domain.model.OrderBlock
import com.foxtrader.app.domain.model.OrderBlockType
import com.foxtrader.app.domain.model.VolumeProfile
import com.foxtrader.app.domain.model.VolumeProfileLevel
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Smart Money Concepts Detector — institutional price action analysis.
 *
 * Detects:
 * - Order Blocks (supply/demand zones)
 * - Fair Value Gaps (price imbalances)
 * - Liquidity pools (equal highs/lows)
 * - Volume Profile (per visible range)
 *
 * All detections are non-repainting: at bar index `i`, only data [0..i] is used.
 * Thread-safe: all functions are pure transforms (no mutable state).
 */
class SmcDetector @Inject constructor() {

    // ========================================================================
    // ORDER BLOCKS
    // ========================================================================

    /**
     * Detect order blocks — the last opposing candle before an impulsive move.
     *
     * Bullish OB: last bearish candle before a strong bullish impulse
     * Bearish OB: last bullish candle before a strong bearish impulse
     *
     * @param impulseMultiplier How many ATR-equivalents define "impulsive" (default 1.5)
     */
    fun detectOrderBlocks(
        candles: List<Candle>,
        impulseMultiplier: Double = 1.5,
    ): List<OrderBlock> {
        if (candles.size < 5) return emptyList()
        val blocks = mutableListOf<OrderBlock>()
        val avgRange = candles.takeLast(20).map { it.range }.average()

        for (i in 2 until candles.size) {
            val current = candles[i]
            val prev = candles[i - 1]
            val bodySize = current.bodySize
            val isImpulsive = bodySize > avgRange * impulseMultiplier

            if (!isImpulsive) continue

            // Bullish impulse: look for preceding bearish candle
            if (current.isBullish && !prev.isBullish) {
                blocks.add(
                    OrderBlock(
                        type = OrderBlockType.BULLISH,
                        highPrice = prev.high,
                        lowPrice = prev.low,
                        startIndex = i - 1,
                        endIndex = min(i + 20, candles.size - 1),
                        mitigated = isPriceMitigated(candles, i, prev.low, isBullish = true),
                        strength = (bodySize / avgRange).coerceIn(0.0, 1.0),
                    )
                )
            }
            // Bearish impulse: look for preceding bullish candle
            else if (!current.isBullish && prev.isBullish) {
                blocks.add(
                    OrderBlock(
                        type = OrderBlockType.BEARISH,
                        highPrice = prev.high,
                        lowPrice = prev.low,
                        startIndex = i - 1,
                        endIndex = min(i + 20, candles.size - 1),
                        mitigated = isPriceMitigated(candles, i, prev.high, isBullish = false),
                        strength = (bodySize / avgRange).coerceIn(0.0, 1.0),
                    )
                )
            }
        }
        return blocks
    }

    private fun isPriceMitigated(
        candles: List<Candle>,
        fromIndex: Int,
        level: Double,
        isBullish: Boolean,
    ): Boolean {
        for (i in fromIndex + 1 until candles.size) {
            if (isBullish && candles[i].low <= level) return true
            if (!isBullish && candles[i].high >= level) return true
        }
        return false
    }

    // ========================================================================
    // FAIR VALUE GAPS
    // ========================================================================

    /**
     * Detect Fair Value Gaps (FVGs) — three-candle imbalance patterns.
     *
     * Bullish FVG: candle[i-2].high < candle[i].low (gap between c1 high and c3 low)
     * Bearish FVG: candle[i-2].low > candle[i].high (gap between c1 low and c3 high)
     */
    fun detectFairValueGaps(candles: List<Candle>): List<FairValueGap> {
        if (candles.size < 3) return emptyList()
        val gaps = mutableListOf<FairValueGap>()

        for (i in 2 until candles.size) {
            val c1 = candles[i - 2]
            val c3 = candles[i]

            // Bullish FVG: gap up (c1 high < c3 low)
            if (c1.high < c3.low) {
                val gapHigh = c3.low
                val gapLow = c1.high
                val filled = isFvgFilled(candles, i, gapHigh, gapLow, isBullish = true)
                gaps.add(
                    FairValueGap(
                        type = FvgType.BULLISH,
                        highPrice = gapHigh,
                        lowPrice = gapLow,
                        index = i - 1,
                        filled = filled.first,
                        fillPercent = filled.second,
                    )
                )
            }
            // Bearish FVG: gap down (c1 low > c3 high)
            else if (c1.low > c3.high) {
                val gapHigh = c1.low
                val gapLow = c3.high
                val filled = isFvgFilled(candles, i, gapHigh, gapLow, isBullish = false)
                gaps.add(
                    FairValueGap(
                        type = FvgType.BEARISH,
                        highPrice = gapHigh,
                        lowPrice = gapLow,
                        index = i - 1,
                        filled = filled.first,
                        fillPercent = filled.second,
                    )
                )
            }
        }
        return gaps
    }

    private fun isFvgFilled(
        candles: List<Candle>,
        fromIndex: Int,
        gapHigh: Double,
        gapLow: Double,
        isBullish: Boolean,
    ): Pair<Boolean, Double> {
        val gapSize = gapHigh - gapLow
        if (gapSize <= 0) return true to 1.0

        var maxPenetration = 0.0
        for (i in fromIndex + 1 until candles.size) {
            val penetration = if (isBullish) {
                // Bearish move into bullish gap
                (gapHigh - candles[i].low).coerceAtLeast(0.0)
            } else {
                // Bullish move into bearish gap
                (candles[i].high - gapLow).coerceAtLeast(0.0)
            }
            maxPenetration = max(maxPenetration, penetration)
            if (maxPenetration >= gapSize) return true to 1.0
        }
        return (maxPenetration >= gapSize * 0.5) to (maxPenetration / gapSize).coerceIn(0.0, 1.0)
    }

    // ========================================================================
    // LIQUIDITY POOLS
    // ========================================================================

    /**
     * Detect liquidity pools — clusters of equal highs or equal lows.
     * These represent stop-loss clusters that smart money targets.
     *
     * @param tolerance Price tolerance for "equal" (as fraction of ATR)
     * @param minTouches Minimum number of touches to form a pool
     */
    fun detectLiquidity(
        candles: List<Candle>,
        tolerance: Double = 0.3,
        minTouches: Int = 2,
    ): List<LiquidityPool> {
        if (candles.size < 10) return emptyList()
        val pools = mutableListOf<LiquidityPool>()
        val avgRange = candles.takeLast(20).map { it.range }.average()
        val tol = avgRange * tolerance

        // Detect equal highs (buy-side liquidity)
        val highClusters = findPriceClusters(candles.mapIndexed { i, c -> i to c.high }, tol, minTouches)
        for ((price, indices) in highClusters) {
            val swept = (indices.last() + 1 until candles.size).any { candles[it].high > price + tol }
            pools.add(
                LiquidityPool(
                    type = LiquidityType.BUY_SIDE,
                    price = price,
                    startIndex = indices.first(),
                    endIndex = indices.last(),
                    swept = swept,
                    sweepIndex = if (swept) {
                        (indices.last() + 1 until candles.size).firstOrNull { candles[it].high > price + tol }
                    } else null,
                )
            )
        }

        // Detect equal lows (sell-side liquidity)
        val lowClusters = findPriceClusters(candles.mapIndexed { i, c -> i to c.low }, tol, minTouches)
        for ((price, indices) in lowClusters) {
            val swept = (indices.last() + 1 until candles.size).any { candles[it].low < price - tol }
            pools.add(
                LiquidityPool(
                    type = LiquidityType.SELL_SIDE,
                    price = price,
                    startIndex = indices.first(),
                    endIndex = indices.last(),
                    swept = swept,
                    sweepIndex = if (swept) {
                        (indices.last() + 1 until candles.size).firstOrNull { candles[it].low < price - tol }
                    } else null,
                )
            )
        }

        return pools
    }

    private fun findPriceClusters(
        indexedPrices: List<Pair<Int, Double>>,
        tolerance: Double,
        minTouches: Int,
    ): List<Pair<Double, List<Int>>> {
        val clusters = mutableListOf<Pair<Double, MutableList<Int>>>()

        for ((index, price) in indexedPrices) {
            val existing = clusters.firstOrNull { abs(it.first - price) <= tolerance }
            if (existing != null) {
                existing.second.add(index)
            } else {
                clusters.add(price to mutableListOf(index))
            }
        }

        return clusters
            .filter { it.second.size >= minTouches }
            .map { (price, indices) -> price to indices.toList() }
    }

    // ========================================================================
    // VOLUME PROFILE
    // ========================================================================

    /**
     * Compute volume profile for a range of candles.
     * Distributes each bar's volume across its price range into buckets.
     *
     * @param buckets Number of price levels to divide the range into
     */
    fun computeVolumeProfile(
        candles: List<Candle>,
        buckets: Int = 50,
    ): VolumeProfile {
        if (candles.isEmpty()) return VolumeProfile(emptyList(), 0.0, 0.0, 0.0, 0.0)

        val high = candles.maxOf { it.high }
        val low = candles.minOf { it.low }
        val range = (high - low).coerceAtLeast(1e-9)
        val bucketSize = range / buckets

        val buyVolumes = DoubleArray(buckets)
        val sellVolumes = DoubleArray(buckets)

        for (c in candles) {
            val barRange = c.range.coerceAtLeast(1e-9)
            val volumePerUnit = c.volume / barRange
            val startBucket = ((c.low - low) / bucketSize).toInt().coerceIn(0, buckets - 1)
            val endBucket = ((c.high - low) / bucketSize).toInt().coerceIn(0, buckets - 1)

            for (b in startBucket..endBucket) {
                val overlap = min(low + (b + 1) * bucketSize, c.high) - max(low + b * bucketSize, c.low)
                val vol = volumePerUnit * overlap.coerceAtLeast(0.0)
                if (c.isBullish) buyVolumes[b] += vol else sellVolumes[b] += vol
            }
        }

        val levels = (0 until buckets).map { b ->
            VolumeProfileLevel(
                priceLevel = low + (b + 0.5) * bucketSize,
                volume = buyVolumes[b] + sellVolumes[b],
                buyVolume = buyVolumes[b],
                sellVolume = sellVolumes[b],
            )
        }

        val totalVolume = levels.sumOf { it.volume }
        val pocLevel = levels.maxByOrNull { it.volume } ?: levels.first()
        val pocPrice = pocLevel.priceLevel

        // Value Area (70% of total volume, centered on POC)
        val valueAreaTarget = totalVolume * 0.7
        val sortedByDistance = levels.sortedBy { abs(it.priceLevel - pocPrice) }
        var accumulated = 0.0
        var vahPrice = pocPrice
        var valPrice = pocPrice
        for (level in sortedByDistance) {
            accumulated += level.volume
            if (level.priceLevel > vahPrice) vahPrice = level.priceLevel
            if (level.priceLevel < valPrice) valPrice = level.priceLevel
            if (accumulated >= valueAreaTarget) break
        }

        return VolumeProfile(
            levels = levels,
            pocPrice = pocPrice,
            vahPrice = vahPrice,
            valPrice = valPrice,
            totalVolume = totalVolume,
        )
    }
}
