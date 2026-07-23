package com.foxtrader.app.domain.usecase.smc

import com.foxtrader.app.domain.model.AmdPattern
import com.foxtrader.app.domain.model.AmdPhase
import com.foxtrader.app.domain.model.BalancedPriceRange
import com.foxtrader.app.domain.model.BreakerBlock
import com.foxtrader.app.domain.model.BreakerType
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.FairValueGap
import com.foxtrader.app.domain.model.FvgType
import com.foxtrader.app.domain.model.IfvgType
import com.foxtrader.app.domain.model.InversionFVG
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
 * - Breaker Blocks (failed/flipped order blocks)
 * - Inversion FVGs (filled FVGs acting as opposite support/resistance)
 * - Balanced Price Ranges (BPR — overlap of bullish + bearish FVG)
 * - AMD patterns (Accumulation → Manipulation → Distribution / Power of Three)
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

    // ========================================================================
    // BREAKER BLOCKS
    // ========================================================================

    /**
     * Detect Breaker Blocks — order blocks that have been violated (mitigated)
     * and now act as institutional levels in the opposite direction.
     *
     * A bullish OB that price closes below → bearish breaker (resistance).
     * A bearish OB that price closes above → bullish breaker (support).
     *
     * Non-repainting: detection only uses candles up to the current bar.
     */
    fun detectBreakers(candles: List<Candle>): List<BreakerBlock> {
        if (candles.size < 5) return emptyList()
        val orderBlocks = detectOrderBlocks(candles)
        val breakers = mutableListOf<BreakerBlock>()

        for (ob in orderBlocks) {
            val violationIndex = findOBViolation(candles, ob) ?: continue
            val type = if (ob.type == OrderBlockType.BULLISH) BreakerType.BEARISH
                       else BreakerType.BULLISH
            breakers.add(
                BreakerBlock(
                    type = type,
                    highPrice = ob.highPrice,
                    lowPrice = ob.lowPrice,
                    originIndex = ob.startIndex,
                    breakerIndex = violationIndex,
                    strength = ob.strength,
                )
            )
        }
        return breakers
    }

    /**
     * Returns the bar index at which price fully closes beyond an OB zone,
     * confirming a breaker (violation of the OB). Returns null if not violated.
     */
    private fun findOBViolation(candles: List<Candle>, ob: OrderBlock): Int? {
        val searchStart = ob.startIndex + 1
        if (searchStart >= candles.size) return null
        for (i in searchStart until candles.size) {
            val c = candles[i]
            when (ob.type) {
                OrderBlockType.BULLISH -> if (c.close < ob.lowPrice) return i
                OrderBlockType.BEARISH -> if (c.close > ob.highPrice) return i
            }
        }
        return null
    }

    // ========================================================================
    // INVERSION FAIR VALUE GAPS (IFVG)
    // ========================================================================

    /**
     * Detect Inversion Fair Value Gaps (IFVG) — FVGs that have been fully
     * filled and now act as support/resistance from the opposite direction.
     *
     * A bullish FVG filled ≥ 100% → becomes a bearish IFVG (resistance zone).
     * A bearish FVG filled ≥ 100% → becomes a bullish IFVG (support zone).
     *
     * Non-repainting: each IFVG is confirmed only after full fill is observed.
     */
    fun detectIFVG(candles: List<Candle>): List<InversionFVG> {
        if (candles.size < 3) return emptyList()
        val fvgs = detectFairValueGaps(candles)
        val ifvgs = mutableListOf<InversionFVG>()

        for (fvg in fvgs) {
            // Only process FVGs that are fully filled.
            val fillIdx = findFullFill(candles, fvg) ?: continue
            val type = if (fvg.type == FvgType.BULLISH) IfvgType.BEARISH
                       else IfvgType.BULLISH
            ifvgs.add(
                InversionFVG(
                    type = type,
                    highPrice = fvg.highPrice,
                    lowPrice = fvg.lowPrice,
                    originIndex = fvg.index,
                    inversionIndex = fillIdx,
                )
            )
        }
        return ifvgs
    }

    /**
     * Returns the first bar index at which the FVG is fully filled
     * (price body closes beyond the far edge of the gap), or null.
     */
    private fun findFullFill(candles: List<Candle>, fvg: FairValueGap): Int? {
        val start = fvg.index + 1
        if (start >= candles.size) return null
        for (i in start until candles.size) {
            val c = candles[i]
            when (fvg.type) {
                FvgType.BULLISH -> if (c.close <= fvg.lowPrice) return i
                FvgType.BEARISH -> if (c.close >= fvg.highPrice) return i
            }
        }
        return null
    }

    // ========================================================================
    // BALANCED PRICE RANGE (BPR)
    // ========================================================================

    /**
     * Detect Balanced Price Ranges (BPR) — the overlapping zone between a
     * bullish FVG and a bearish FVG. This overlap represents an equilibrium
     * zone where price has traded efficiently in both directions, and often
     * acts as a powerful reaction level.
     *
     * Non-repainting: only confirmed FVGs (both already formed) are considered.
     */
    fun detectBPR(candles: List<Candle>): List<BalancedPriceRange> {
        if (candles.size < 3) return emptyList()
        val fvgs = detectFairValueGaps(candles)
        val bullish = fvgs.filter { it.type == FvgType.BULLISH }
        val bearish = fvgs.filter { it.type == FvgType.BEARISH }
        val bprs = mutableListOf<BalancedPriceRange>()

        for (bull in bullish) {
            for (bear in bearish) {
                // The overlap of [bull.lowPrice, bull.highPrice] ∩ [bear.lowPrice, bear.highPrice]
                val overlapLow = max(bull.lowPrice, bear.lowPrice)
                val overlapHigh = min(bull.highPrice, bear.highPrice)
                if (overlapHigh > overlapLow) {
                    bprs.add(
                        BalancedPriceRange(
                            highPrice = overlapHigh,
                            lowPrice = overlapLow,
                            bullishFvgIndex = bull.index,
                            bearishFvgIndex = bear.index,
                        )
                    )
                }
            }
        }
        return bprs
    }

    // ========================================================================
    // AMD / POWER OF THREE
    // ========================================================================

    /**
     * Detect AMD (Accumulation → Manipulation → Distribution) patterns.
     *
     * Algorithm:
     * 1. Accumulation: identify a range-bound period (low ATR relative to recent average).
     * 2. Manipulation: a spike beyond the accumulation range that sweeps liquidity.
     * 3. Distribution: a sustained move in the opposite direction of the manipulation spike.
     *
     * Non-repainting: the pattern is confirmed only after the distribution move begins.
     * Port of reference/modules/ict-concepts — AMD/Power-of-Three variant.
     *
     * @param accumulationBars Minimum bars for an accumulation range (default 5).
     * @param atrMultiplier Threshold for classifying a spike as manipulation (default 1.8).
     */
    fun detectAMD(
        candles: List<Candle>,
        accumulationBars: Int = 5,
        atrMultiplier: Double = 1.8,
    ): List<AmdPattern> {
        if (candles.size < accumulationBars + 3) return emptyList()
        val patterns = mutableListOf<AmdPattern>()
        val atr = computeSimpleATR(candles)

        var i = accumulationBars
        while (i < candles.size - 2) {
            // 1. Check for accumulation: a range-bound window ending at i
            val accSlice = candles.subList(i - accumulationBars, i)
            val accHigh = accSlice.maxOf { it.high }
            val accLow = accSlice.minOf { it.low }
            val accRange = accHigh - accLow
            val localAtr = atr.getOrElse(i) { accRange }
            if (accRange > localAtr * 1.5 || accRange < 1e-9) { i++; continue }

            // 2. Check for manipulation spike in bar i
            val spike = candles[i]
            val spikeRange = spike.high - spike.low
            if (spikeRange < localAtr * atrMultiplier) { i++; continue }

            // Determine spike direction: bearish spike → bullish distribution expected
            val spikeIsBearish = spike.close < spike.open
            val manipDir: Direction
            val manipPrice: Double
            if (spikeIsBearish && spike.low < accLow) {
                // Sell-side sweep → distribution up
                manipDir = Direction.BULLISH
                manipPrice = spike.low
            } else if (!spikeIsBearish && spike.high > accHigh) {
                // Buy-side sweep → distribution down
                manipDir = Direction.BEARISH
                manipPrice = spike.high
            } else {
                i++; continue
            }

            // 3. Confirm distribution: next bar must close back inside or beyond accum range
            val distBar = candles.getOrNull(i + 1) ?: break
            val confirmed = when (manipDir) {
                Direction.BULLISH -> distBar.close > accLow // closes back up
                Direction.BEARISH -> distBar.close < accHigh // closes back down
                else -> false
            }
            if (!confirmed) { i++; continue }

            val target = when (manipDir) {
                Direction.BULLISH -> accHigh + accRange // project up
                Direction.BEARISH -> accLow - accRange  // project down
                else -> accHigh
            }

            patterns.add(
                AmdPattern(
                    phase = AmdPhase.DISTRIBUTION,
                    direction = manipDir,
                    accumulationHigh = accHigh,
                    accumulationLow = accLow,
                    accumulationStart = i - accumulationBars,
                    accumulationEnd = i - 1,
                    manipulationPrice = manipPrice,
                    manipulationIndex = i,
                    distributionTarget = target,
                    confirmIndex = i + 1,
                )
            )
            i += 2 // skip the confirmed bars to avoid overlapping patterns
        }
        return patterns
    }

    /** Simple per-bar ATR (high-low range EMA approximation). */
    private fun computeSimpleATR(candles: List<Candle>, period: Int = 14): DoubleArray {
        val n = candles.size
        val atr = DoubleArray(n)
        if (n == 0) return atr
        atr[0] = candles[0].high - candles[0].low
        val k = 2.0 / (period + 1)
        for (i in 1 until n) {
            val tr = candles[i].high - candles[i].low
            atr[i] = atr[i - 1] * (1 - k) + tr * k
        }
        return atr
    }
}
