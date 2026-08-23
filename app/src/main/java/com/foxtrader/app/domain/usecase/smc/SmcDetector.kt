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
import kotlin.math.roundToLong

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
    // SMC ANALYSIS RESULT
    // ========================================================================

    /**
     * Aggregated result of all SMC detections. Produced by [analyzeAll] to
     * avoid redundant recomputation of order blocks and fair value gaps that
     * are shared across dependent detections (breakers, IFVGs, BPRs). AMD is
     * also included here so institutional consumers never need a second SMC
     * pass outside the canonical package.
     */
    data class SmcAnalysisResult(
        val orderBlocks: List<OrderBlock>,
        val fairValueGaps: List<FairValueGap>,
        val liquidityPools: List<LiquidityPool>,
        val breakerBlocks: List<BreakerBlock>,
        val inversionFVGs: List<InversionFVG>,
        val balancedPriceRanges: List<BalancedPriceRange>,
        val amdPatterns: List<AmdPattern>,
    )

    // ========================================================================
    // ANALYZE ALL (compute-reuse entry point)
    // ========================================================================

    /**
     * Compute all SMC detections in a single package, reusing intermediate
     * results where detectors depend on the same zones.
     *
     * [detectBreakers] depends on order blocks, while [detectIFVG] and [detectBPR]
     * depend on fair value gaps. This method computes OBs and FVGs once and
     * passes them to dependent detections. AMD is computed once from the same
     * causal candle prefix and carried in the returned snapshot.
     */
    fun analyzeAll(candles: List<Candle>): SmcAnalysisResult {
        val orderBlocks = detectOrderBlocks(candles)
        val fairValueGaps = detectFairValueGaps(candles)
        val liquidityPools = detectLiquidity(candles)
        val breakerBlocks = detectBreakers(candles, precomputedOBs = orderBlocks)
        val inversionFVGs = detectIFVG(candles, precomputedFVGs = fairValueGaps)
        val balancedPriceRanges = detectBPR(candles, precomputedFVGs = fairValueGaps)
        val amdPatterns = detectAMD(candles)

        return SmcAnalysisResult(
            orderBlocks = orderBlocks,
            fairValueGaps = fairValueGaps,
            liquidityPools = liquidityPools,
            breakerBlocks = breakerBlocks,
            inversionFVGs = inversionFVGs,
            balancedPriceRanges = balancedPriceRanges,
            amdPatterns = amdPatterns,
        )
    }

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
            if (current.isBullish && prev.isBearish) {
                val strength = min(1.0, bodySize / (avgRange * impulseMultiplier * 2))
                blocks.add(
                    OrderBlock(
                        type = OrderBlockType.BULLISH,
                        highPrice = prev.high,
                        lowPrice = prev.low,
                        index = i - 1,
                        strength = strength,
                        mitigated = isMitigated(candles, i, prev.low, prev.high),
                    )
                )
            }

            // Bearish impulse: look for preceding bullish candle
            if (current.isBearish && prev.isBullish) {
                val strength = min(1.0, bodySize / (avgRange * impulseMultiplier * 2))
                blocks.add(
                    OrderBlock(
                        type = OrderBlockType.BEARISH,
                        highPrice = prev.high,
                        lowPrice = prev.low,
                        index = i - 1,
                        strength = strength,
                        mitigated = isMitigated(candles, i, prev.low, prev.high),
                    )
                )
            }
        }
        return blocks
    }

    private fun isMitigated(
        candles: List<Candle>,
        startIndex: Int,
        low: Double,
        high: Double,
    ): Boolean {
        for (i in startIndex until candles.size) {
            val c = candles[i]
            if (c.low <= high && c.high >= low) return true
        }
        return false
    }

    // ========================================================================
    // FAIR VALUE GAPS
    // ========================================================================

    /**
     * Detect Fair Value Gaps — 3-candle imbalances.
     *
     * Bullish FVG: candle[i].low > candle[i-2].high
     * Bearish FVG: candle[i].high < candle[i-2].low
     */
    fun detectFairValueGaps(candles: List<Candle>): List<FairValueGap> {
        if (candles.size < 3) return emptyList()
        val gaps = mutableListOf<FairValueGap>()

        for (i in 2 until candles.size) {
            val c0 = candles[i - 2]
            val c2 = candles[i]

            if (c2.low > c0.high) {
                val low = c0.high
                val high = c2.low
                val fill = calculateFvgFill(candles, i + 1, low, high, FvgType.BULLISH)
                gaps.add(
                    FairValueGap(
                        type = FvgType.BULLISH,
                        highPrice = high,
                        lowPrice = low,
                        index = i,
                        filled = fill >= 1.0,
                        fillPercent = fill,
                    )
                )
            }

            if (c2.high < c0.low) {
                val low = c2.high
                val high = c0.low
                val fill = calculateFvgFill(candles, i + 1, low, high, FvgType.BEARISH)
                gaps.add(
                    FairValueGap(
                        type = FvgType.BEARISH,
                        highPrice = high,
                        lowPrice = low,
                        index = i,
                        filled = fill >= 1.0,
                        fillPercent = fill,
                    )
                )
            }
        }
        return gaps
    }

    private fun calculateFvgFill(
        candles: List<Candle>,
        startIndex: Int,
        low: Double,
        high: Double,
        type: FvgType,
    ): Double {
        if (startIndex >= candles.size) return 0.0
        val size = high - low
        if (size <= 0) return 0.0
        var deepest = 0.0

        for (i in startIndex until candles.size) {
            val c = candles[i]
            val fill = when (type) {
                FvgType.BULLISH -> ((high - c.low) / size).coerceIn(0.0, 1.0)
                FvgType.BEARISH -> ((c.high - low) / size).coerceIn(0.0, 1.0)
            }
            deepest = max(deepest, fill)
        }
        return deepest
    }

    // ========================================================================
    // LIQUIDITY POOLS
    // ========================================================================

    /**
     * Detect equal-high / equal-low liquidity pools and whether they were swept.
     */
    fun detectLiquidity(
        candles: List<Candle>,
        tolerancePercent: Double = 0.05,
        lookback: Int = 50,
    ): List<LiquidityPool> {
        if (candles.size < 5) return emptyList()
        val pools = mutableListOf<LiquidityPool>()
        val start = (candles.size - lookback).coerceAtLeast(2)

        for (i in start until candles.size - 1) {
            val c = candles[i]
            val tolerance = abs(c.close) * (tolerancePercent / 100.0)

            // Equal highs / buy-side liquidity
            val highMatches = mutableListOf(i)
            for (j in i + 1 until candles.size) {
                if (abs(candles[j].high - c.high) <= tolerance) highMatches.add(j)
            }
            if (highMatches.size >= 2) {
                val allIndices = highMatches.distinct()
                val latestEqual = allIndices.maxOrNull() ?: i
                var swept = false
                var sweepIndex: Int? = null
                for (j in latestEqual + 1 until candles.size) {
                    if (candles[j].high > c.high + tolerance) {
                        swept = true
                        sweepIndex = j
                        break
                    }
                }
                pools.add(
                    LiquidityPool(
                        type = LiquidityType.BUY_SIDE,
                        price = c.high,
                        indices = allIndices,
                        swept = swept,
                        sweepIndex = sweepIndex,
                    )
                )
            }

            // Equal lows / sell-side liquidity
            val lowMatches = mutableListOf(i)
            for (j in i + 1 until candles.size) {
                if (abs(candles[j].low - c.low) <= tolerance) lowMatches.add(j)
            }
            if (lowMatches.size >= 2) {
                val allIndices = lowMatches.distinct()
                val latestEqual = allIndices.maxOrNull() ?: i
                var swept = false
                var sweepIndex: Int? = null
                for (j in latestEqual + 1 until candles.size) {
                    if (candles[j].low < c.low - tolerance) {
                        swept = true
                        sweepIndex = j
                        break
                    }
                }
                pools.add(
                    LiquidityPool(
                        type = LiquidityType.SELL_SIDE,
                        price = c.low,
                        indices = allIndices,
                        swept = swept,
                        sweepIndex = sweepIndex,
                    )
                )
            }
        }
        return pools.distinctBy { Triple(it.type, it.price, it.indices) }
    }

    // ========================================================================
    // VOLUME PROFILE
    // ========================================================================

    /** Build a basic price-bucket volume profile for the supplied range. */
    fun buildVolumeProfile(candles: List<Candle>, bins: Int = 24): VolumeProfile {
        if (candles.isEmpty() || bins <= 0) {
            return VolumeProfile(emptyList(), 0.0, 0.0, 0.0, 0.0)
        }
        val low = candles.minOf { it.low }
        val high = candles.maxOf { it.high }
        val range = high - low
        if (range <= 0.0) {
            val total = candles.sumOf { it.volume }
            return VolumeProfile(
                listOf(VolumeProfileLevel(low, total, total / 2, total / 2)),
                low,
                low,
                low,
                total,
            )
        }
        val safeBins = bins.coerceAtLeast(1)
        val step = range / safeBins
        val buy = DoubleArray(safeBins)
        val sell = DoubleArray(safeBins)

        candles.forEach { candle ->
            val typical = (candle.high + candle.low + candle.close) / 3.0
            val bucket = ((typical - low) / step).toInt().coerceIn(0, safeBins - 1)
            if (candle.isBullish) buy[bucket] += candle.volume else sell[bucket] += candle.volume
        }

        val levels = (0 until safeBins).map { i ->
            VolumeProfileLevel(
                priceLevel = low + (i + 0.5) * step,
                volume = buy[i] + sell[i],
                buyVolume = buy[i],
                sellVolume = sell[i],
            )
        }
        val poc = levels.maxByOrNull { it.totalVolume }?.priceLevel ?: 0.0
        val totalVolume = levels.sumOf { it.totalVolume }
        if (totalVolume <= 0.0) return VolumeProfile(levels, poc, high, low, 0.0)

        // Expand value area outward from POC until 70% of total volume is covered.
        val pocIndex = levels.indices.maxByOrNull { levels[it].totalVolume } ?: 0
        var left = pocIndex
        var right = pocIndex
        var included = levels[pocIndex].totalVolume
        val target = totalVolume * 0.70
        while (included < target && (left > 0 || right < levels.lastIndex)) {
            val leftVol = if (left > 0) levels[left - 1].totalVolume else -1.0
            val rightVol = if (right < levels.lastIndex) levels[right + 1].totalVolume else -1.0
            if (rightVol > leftVol) {
                right++
                included += levels[right].totalVolume
            } else if (left > 0) {
                left--
                included += levels[left].totalVolume
            } else {
                right++
                included += levels[right].totalVolume
            }
        }
        return VolumeProfile(
            levels = levels,
            pocPrice = poc,
            vahPrice = levels[right].priceLevel,
            valPrice = levels[left].priceLevel,
            totalVolume = totalVolume,
        )
    }

    // ========================================================================
    // BREAKER BLOCKS
    // ========================================================================

    fun detectBreakers(
        candles: List<Candle>,
        precomputedOBs: List<OrderBlock>? = null,
    ): List<BreakerBlock> {
        val obs = precomputedOBs ?: detectOrderBlocks(candles)
        if (obs.isEmpty()) return emptyList()
        val breakers = mutableListOf<BreakerBlock>()

        for (ob in obs) {
            val origin = ob.index
            for (i in origin + 1 until candles.size) {
                val c = candles[i]
                val broken = when (ob.type) {
                    OrderBlockType.BULLISH -> c.close < ob.lowPrice
                    OrderBlockType.BEARISH -> c.close > ob.highPrice
                }
                if (!broken) continue

                // After violation, wait for price to return into the old OB zone.
                for (j in i + 1 until candles.size) {
                    val r = candles[j]
                    if (r.low <= ob.highPrice && r.high >= ob.lowPrice) {
                        breakers.add(
                            BreakerBlock(
                                type = if (ob.type == OrderBlockType.BULLISH) BreakerType.BEARISH else BreakerType.BULLISH,
                                highPrice = ob.highPrice,
                                lowPrice = ob.lowPrice,
                                originIndex = origin,
                                breakerIndex = j,
                                strength = ob.strength,
                            )
                        )
                        break
                    }
                }
                break
            }
        }
        return breakers
    }

    // ========================================================================
    // INVERSION FAIR VALUE GAPS (IFVG)
    // ========================================================================

    fun detectIFVG(
        candles: List<Candle>,
        precomputedFVGs: List<FairValueGap>? = null,
    ): List<InversionFVG> {
        if (candles.size < 3) return emptyList()
        val fvgs = precomputedFVGs ?: detectFairValueGaps(candles)
        val ifvgs = mutableListOf<InversionFVG>()

        for (fvg in fvgs) {
            val fillIdx = findFullFill(candles, fvg) ?: continue
            val type = if (fvg.type == FvgType.BULLISH) IfvgType.BEARISH else IfvgType.BULLISH
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

    fun detectBPR(
        candles: List<Candle>,
        precomputedFVGs: List<FairValueGap>? = null,
    ): List<BalancedPriceRange> {
        if (candles.size < 3) return emptyList()
        val fvgs = precomputedFVGs ?: detectFairValueGaps(candles)
        val bullish = fvgs.filter { it.type == FvgType.BULLISH }
        val bearish = fvgs.filter { it.type == FvgType.BEARISH }
        val bprs = mutableListOf<BalancedPriceRange>()

        for (bull in bullish) {
            for (bear in bearish) {
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
     * Non-repainting: the pattern is confirmed only after the distribution move begins.
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
            val accSlice = candles.subList(i - accumulationBars, i)
            val accHigh = accSlice.maxOf { it.high }
            val accLow = accSlice.minOf { it.low }
            val accRange = accHigh - accLow
            val localAtr = atr.getOrElse(i) { accRange }
            if (accRange > localAtr * 1.5 || accRange < 1e-9) { i++; continue }

            val spike = candles[i]
            val spikeRange = spike.high - spike.low
            if (spikeRange < localAtr * atrMultiplier) { i++; continue }

            val spikeIsBearish = spike.close < spike.open
            val manipDir: Direction
            val manipPrice: Double
            if (spikeIsBearish && spike.low < accLow) {
                manipDir = Direction.BULLISH
                manipPrice = spike.low
            } else if (!spikeIsBearish && spike.high > accHigh) {
                manipDir = Direction.BEARISH
                manipPrice = spike.high
            } else {
                i++; continue
            }

            val distBar = candles.getOrNull(i + 1) ?: break
            val confirmed = when (manipDir) {
                Direction.BULLISH -> distBar.close > accLow
                Direction.BEARISH -> distBar.close < accHigh
            }
            if (!confirmed) { i++; continue }

            val target = when (manipDir) {
                Direction.BULLISH -> accHigh + accRange
                Direction.BEARISH -> accLow - accRange
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
            i += 2
        }
        return patterns
    }

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
