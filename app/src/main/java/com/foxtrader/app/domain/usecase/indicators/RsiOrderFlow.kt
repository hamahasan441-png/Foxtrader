package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import kotlin.math.abs
import kotlin.math.max

/**
 * RSI + OHLCV order-flow proxy study.
 *
 * This is deliberately NOT labelled as exchange bid/ask footprint data. It
 * estimates directional pressure from candle body/location weighted by the
 * provider's bar volume (or unit weight when volume is unavailable), then
 * combines that with Wilder RSI for dual-confirmed divergence detection.
 *
 * Non-repaint contract:
 * - pivots require [Config.pivotRight] bars to the right before confirmation;
 * - divergence availability is [Divergence.confirmedIndex], never the pivot bar;
 * - bars after confirmedIndex are irrelevant to an already-confirmed event.
 */
object RsiOrderFlow {

    data class Config(
        val rsiPeriod: Int = 14,
        val flowPeriod: Int = 14,
        val flowSmoothing: Int = 5,
        val pivotLeft: Int = 3,
        val pivotRight: Int = 3,
        val minPivotSeparation: Int = 5,
        val maxPivotSeparation: Int = 80,
        val minRsiDifference: Double = 2.0,
        val minFlowDifference: Double = 3.0,
        val minPriceChangeFraction: Double = 0.00005,
        val includeHidden: Boolean = false,
    ) {
        init {
            require(rsiPeriod >= 2)
            require(flowPeriod >= 2)
            require(flowSmoothing >= 1)
            require(pivotLeft >= 1)
            require(pivotRight >= 1)
            require(minPivotSeparation >= 1)
            require(maxPivotSeparation >= minPivotSeparation)
            require(minRsiDifference >= 0.0)
            require(minFlowDifference >= 0.0)
            require(minPriceChangeFraction >= 0.0)
        }
    }

    enum class DivergenceType {
        REGULAR_BULLISH,
        REGULAR_BEARISH,
        HIDDEN_BULLISH,
        HIDDEN_BEARISH,
    }

    data class Divergence(
        val type: DivergenceType,
        val startIndex: Int,
        val endIndex: Int,
        val confirmedIndex: Int,
        val startRsi: Double,
        val endRsi: Double,
        val startFlow: Double,
        val endFlow: Double,
        /** Setup-quality score only; never a win-probability claim. */
        val strength: Int,
    ) {
        val bullish: Boolean
            get() = type == DivergenceType.REGULAR_BULLISH || type == DivergenceType.HIDDEN_BULLISH
    }

    data class Result(
        val rsi: DoubleArray,
        /** Smoothed 0..100 directional-flow oscillator. */
        val flow: DoubleArray,
        /** Estimated aggressive buy minus sell pressure per bar. */
        val delta: DoubleArray,
        /** Running cumulative estimated delta, useful for diagnostics. */
        val cumulativeDelta: DoubleArray,
        val divergences: List<Divergence>,
        val positiveVolumeCoverage: Double,
    )

    fun calculate(
        candles: List<Candle>,
        config: Config = Config(),
    ): Result {
        if (candles.isEmpty()) {
            return Result(
                rsi = DoubleArray(0),
                flow = DoubleArray(0),
                delta = DoubleArray(0),
                cumulativeDelta = DoubleArray(0),
                divergences = emptyList(),
                positiveVolumeCoverage = 0.0,
            )
        }

        require(candles.all(::isWellFormed)) { "RSI OrderFlow requires finite, valid OHLCV candles." }

        val rsi = TechnicalIndicators.calculateRSI(candles, config.rsiPeriod)
        val delta = DoubleArray(candles.size)
        val cumulativeDelta = DoubleArray(candles.size)
        val rawFlow = DoubleArray(candles.size) { 50.0 }
        val flow = DoubleArray(candles.size) { 50.0 }
        val effectiveVolume = DoubleArray(candles.size)

        var positiveVolumeBars = 0
        var rollingDelta = 0.0
        var rollingVolume = 0.0

        for (i in candles.indices) {
            val candle = candles[i]
            val weight = if (candle.volume > 0.0) {
                positiveVolumeBars++
                candle.volume
            } else {
                // Some FX/CFD feeds omit volume. Keep the study defined without
                // pretending this unit weight is exchange order-flow volume.
                1.0
            }
            effectiveVolume[i] = weight

            val d = estimatedDelta(candle, weight)
            delta[i] = d
            cumulativeDelta[i] = (if (i > 0) cumulativeDelta[i - 1] else 0.0) + d

            rollingDelta += d
            rollingVolume += weight
            if (i >= config.flowPeriod) {
                rollingDelta -= delta[i - config.flowPeriod]
                rollingVolume -= effectiveVolume[i - config.flowPeriod]
            }

            val normalized = if (rollingVolume > EPSILON) rollingDelta / rollingVolume else 0.0
            rawFlow[i] = (50.0 + normalized.coerceIn(-1.0, 1.0) * 50.0).coerceIn(0.0, 100.0)
        }

        val alpha = 2.0 / (config.flowSmoothing + 1.0)
        flow[0] = rawFlow[0]
        for (i in 1 until flow.size) {
            flow[i] = rawFlow[i] * alpha + flow[i - 1] * (1.0 - alpha)
        }

        val divergences = detectDivergences(
            candles = candles,
            rsi = rsi,
            flow = flow,
            effectiveVolume = effectiveVolume,
            config = config,
        )

        return Result(
            rsi = rsi,
            flow = flow,
            delta = delta,
            cumulativeDelta = cumulativeDelta,
            divergences = divergences,
            positiveVolumeCoverage = positiveVolumeBars.toDouble() / candles.size.toDouble(),
        )
    }

    private fun estimatedDelta(candle: Candle, volumeWeight: Double): Double {
        val range = max(candle.high - candle.low, max(abs(candle.close) * 1e-9, EPSILON))
        val bodyPressure = ((candle.close - candle.open) / range).coerceIn(-1.0, 1.0)
        val closeLocation = (
            ((candle.close - candle.low) - (candle.high - candle.close)) / range
        ).coerceIn(-1.0, 1.0)

        // Body direction answers "who moved the bar" while close location
        // rewards/rejects moves that were absorbed before the close.
        val pressure = (bodyPressure * 0.55 + closeLocation * 0.45).coerceIn(-1.0, 1.0)
        return volumeWeight * pressure
    }

    private fun detectDivergences(
        candles: List<Candle>,
        rsi: DoubleArray,
        flow: DoubleArray,
        effectiveVolume: DoubleArray,
        config: Config,
    ): List<Divergence> {
        val warmup = max(config.rsiPeriod, config.flowPeriod) + config.pivotLeft
        if (candles.size <= warmup + config.pivotRight) return emptyList()

        val highs = mutableListOf<Int>()
        val lows = mutableListOf<Int>()
        for (i in warmup until candles.size - config.pivotRight) {
            if (isConfirmedHigh(candles, i, config.pivotLeft, config.pivotRight)) highs += i
            if (isConfirmedLow(candles, i, config.pivotLeft, config.pivotRight)) lows += i
        }

        val out = mutableListOf<Divergence>()
        comparePivotPairs(
            pivots = lows,
            candles = candles,
            rsi = rsi,
            flow = flow,
            effectiveVolume = effectiveVolume,
            config = config,
            isHigh = false,
            out = out,
        )
        comparePivotPairs(
            pivots = highs,
            candles = candles,
            rsi = rsi,
            flow = flow,
            effectiveVolume = effectiveVolume,
            config = config,
            isHigh = true,
            out = out,
        )
        return out.sortedBy { it.confirmedIndex }
    }

    private fun comparePivotPairs(
        pivots: List<Int>,
        candles: List<Candle>,
        rsi: DoubleArray,
        flow: DoubleArray,
        effectiveVolume: DoubleArray,
        config: Config,
        isHigh: Boolean,
        out: MutableList<Divergence>,
    ) {
        for (k in 1 until pivots.size) {
            val a = pivots[k - 1]
            val b = pivots[k]
            val separation = b - a
            if (separation !in config.minPivotSeparation..config.maxPivotSeparation) continue

            val aPrice = if (isHigh) candles[a].high else candles[a].low
            val bPrice = if (isHigh) candles[b].high else candles[b].low
            val priceEpsilon = max(abs(aPrice), abs(bPrice)) * config.minPriceChangeFraction
            val rsiDelta = rsi[b] - rsi[a]
            val flowDelta = flow[b] - flow[a]

            val type = if (isHigh) {
                when {
                    bPrice > aPrice + priceEpsilon &&
                        rsiDelta <= -config.minRsiDifference &&
                        flowDelta <= -config.minFlowDifference -> DivergenceType.REGULAR_BEARISH

                    config.includeHidden &&
                        bPrice < aPrice - priceEpsilon &&
                        rsiDelta >= config.minRsiDifference &&
                        flowDelta >= config.minFlowDifference -> DivergenceType.HIDDEN_BEARISH

                    else -> null
                }
            } else {
                when {
                    bPrice < aPrice - priceEpsilon &&
                        rsiDelta >= config.minRsiDifference &&
                        flowDelta >= config.minFlowDifference -> DivergenceType.REGULAR_BULLISH

                    config.includeHidden &&
                        bPrice > aPrice + priceEpsilon &&
                        rsiDelta <= -config.minRsiDifference &&
                        flowDelta <= -config.minFlowDifference -> DivergenceType.HIDDEN_BULLISH

                    else -> null
                }
            } ?: continue

            out += Divergence(
                type = type,
                startIndex = a,
                endIndex = b,
                confirmedIndex = b + config.pivotRight,
                startRsi = rsi[a],
                endRsi = rsi[b],
                startFlow = flow[a],
                endFlow = flow[b],
                strength = strengthScore(
                    candles = candles,
                    pivotIndex = b,
                    rsiDifference = abs(rsiDelta),
                    flowDifference = abs(flowDelta),
                    effectiveVolume = effectiveVolume,
                    bullish = !isHigh,
                ),
            )
        }
    }

    private fun isConfirmedLow(candles: List<Candle>, index: Int, left: Int, right: Int): Boolean {
        val value = candles[index].low
        for (i in index - left until index) if (candles[i].low < value) return false
        // Strict right side resolves equal-low plateaus to the last eligible bar.
        for (i in index + 1..index + right) if (candles[i].low <= value) return false
        return true
    }

    private fun isConfirmedHigh(candles: List<Candle>, index: Int, left: Int, right: Int): Boolean {
        val value = candles[index].high
        for (i in index - left until index) if (candles[i].high > value) return false
        for (i in index + 1..index + right) if (candles[i].high >= value) return false
        return true
    }

    private fun strengthScore(
        candles: List<Candle>,
        pivotIndex: Int,
        rsiDifference: Double,
        flowDifference: Double,
        effectiveVolume: DoubleArray,
        bullish: Boolean,
    ): Int {
        val candle = candles[pivotIndex]
        val range = max(candle.high - candle.low, EPSILON)
        val bodyHigh = max(candle.open, candle.close)
        val bodyLow = minOf(candle.open, candle.close)
        val rejection = if (bullish) {
            (bodyLow - candle.low).coerceAtLeast(0.0) / range
        } else {
            (candle.high - bodyHigh).coerceAtLeast(0.0) / range
        }

        val volumeRank = percentileRank(
            values = effectiveVolume,
            index = pivotIndex,
            lookback = 50,
        )

        val rsiComponent = (rsiDifference / 12.0).coerceIn(0.0, 1.0) * 30.0
        val flowComponent = (flowDifference / 18.0).coerceIn(0.0, 1.0) * 35.0
        val rejectionComponent = rejection.coerceIn(0.0, 1.0) * 20.0
        val participationComponent = volumeRank * 15.0
        return (rsiComponent + flowComponent + rejectionComponent + participationComponent)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun percentileRank(values: DoubleArray, index: Int, lookback: Int): Double {
        val start = (index - lookback + 1).coerceAtLeast(0)
        val current = values[index]
        var count = 0
        var lessOrEqual = 0
        for (i in start..index) {
            if (!values[i].isFinite()) continue
            count++
            if (values[i] <= current) lessOrEqual++
        }
        return if (count == 0) 0.5 else lessOrEqual.toDouble() / count.toDouble()
    }

    private fun isWellFormed(c: Candle): Boolean =
        c.open.isFinite() && c.high.isFinite() && c.low.isFinite() &&
            c.close.isFinite() && c.volume.isFinite() &&
            c.open > 0.0 && c.high > 0.0 && c.low > 0.0 && c.close > 0.0 &&
            c.high >= c.low && c.high >= max(c.open, c.close) &&
            c.low <= minOf(c.open, c.close) && c.volume >= 0.0

    private const val EPSILON = 1e-12
}
