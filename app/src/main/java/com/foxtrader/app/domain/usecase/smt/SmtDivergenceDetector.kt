package com.foxtrader.app.domain.usecase.smt

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * SMT (Smart Money Technique) divergence detector.
 *
 * SMT confirms correlated-instrument disagreement at liquidity extremes. For a
 * positively correlated peer set:
 * - Bullish SMT: one symbol sweeps / prints a lower low while the peer holds a
 *   higher low (sell-side liquidity was taken without broad confirmation).
 * - Bearish SMT: one symbol sweeps / prints a higher high while the peer holds
 *   a lower high (buy-side liquidity was taken without broad confirmation).
 *
 * Non-repainting & Real Time Synchronized:
 * - Aligns primary and peer series by exact candle timestamp (inner time join).
 * - Only confirmed swing points are compared (last `swingLookback` bars excluded).
 * - Gaps and market holidays in either instrument are handled cleanly.
 */
class SmtDivergenceDetector @Inject constructor() {

    data class SmtDivergence(
        val primarySymbol: String,
        val peerSymbol: String,
        val direction: Direction,
        val type: SmtType,
        val primaryIndex: Int,
        val peerIndex: Int,
        val primaryPrice: Double,
        val peerPrice: Double,
        val correlation: Double,
        val confidence: Double,
        val detail: String,
        /** Primary-series bar where both swings became confirmed/actionable. */
        val confirmationIndex: Int = primaryIndex,
    )

    enum class SmtType {
        PRIMARY_SWEEP_PEER_FAIL,
        PEER_SWEEP_PRIMARY_FAIL,
    }

    fun detect(
        primarySymbol: String,
        primaryCandles: List<Candle>,
        correlatedCandles: Map<String, List<Candle>>,
        period: Int = DEFAULT_PERIOD,
        swingLookback: Int = DEFAULT_SWING_LOOKBACK,
        minCorrelation: Double = MIN_POSITIVE_CORRELATION,
    ): List<SmtDivergence> {
        if (
            primaryCandles.size < MIN_BARS ||
            correlatedCandles.isEmpty() ||
            period < MIN_BARS ||
            swingLookback < 1 ||
            !minCorrelation.isFinite() ||
            minCorrelation !in 0.0..1.0
        ) return emptyList()

        return correlatedCandles.flatMap { (peerSymbol, peerCandles) ->
            detectPair(
                primarySymbol = primarySymbol,
                primaryCandles = primaryCandles,
                peerSymbol = peerSymbol,
                peerCandles = peerCandles,
                period = period,
                swingLookback = swingLookback,
                minCorrelation = minCorrelation,
            )
        }.sortedBy { it.primaryIndex }
    }

    private fun detectPair(
        primarySymbol: String,
        primaryCandles: List<Candle>,
        peerSymbol: String,
        peerCandles: List<Candle>,
        period: Int,
        swingLookback: Int,
        minCorrelation: Double,
    ): List<SmtDivergence> {
        if (primaryCandles.isEmpty() || peerCandles.isEmpty()) return emptyList()

        // 1. Map timestamps to (originalIndex, Candle)
        val primaryMap = HashMap<Long, Pair<Int, Candle>>(primaryCandles.size)
        for (i in primaryCandles.indices) {
            primaryMap[primaryCandles[i].timestamp] = i to primaryCandles[i]
        }

        val peerMap = HashMap<Long, Pair<Int, Candle>>(peerCandles.size)
        for (i in peerCandles.indices) {
            peerMap[peerCandles[i].timestamp] = i to peerCandles[i]
        }

        // 2. Intersect timestamps chronologically ascending
        val commonTimestamps = primaryCandles.map { it.timestamp }
            .filter { peerMap.containsKey(it) }
            .takeLast(period)

        if (commonTimestamps.size < MIN_BARS) return emptyList()

        val alignedPrimaryWithIdx = commonTimestamps.map { primaryMap.getValue(it) }
        val alignedPeerWithIdx = commonTimestamps.map { peerMap.getValue(it) }

        val alignedPrimaryCandles = alignedPrimaryWithIdx.map { it.second }
        val alignedPeerCandles = alignedPeerWithIdx.map { it.second }

        val correlation = correlation(alignedPrimaryCandles, alignedPeerCandles)
        if (correlation < minCorrelation) return emptyList()

        val primaryHighs = findSwings(alignedPrimaryCandles, swingLookback, isHigh = true).takeLast(2)
        val primaryLows = findSwings(alignedPrimaryCandles, swingLookback, isHigh = false).takeLast(2)
        val peerHighs = findSwings(alignedPeerCandles, swingLookback, isHigh = true).takeLast(2)
        val peerLows = findSwings(alignedPeerCandles, swingLookback, isHigh = false).takeLast(2)

        val result = mutableListOf<SmtDivergence>()

        if (primaryLows.size == 2 && peerLows.size == 2) {
            val p0 = primaryLows[0]
            val p1 = primaryLows[1]
            val q0 = peerLows[0]
            val q1 = peerLows[1]
            val primarySweptLow = alignedPrimaryCandles[p1].low < alignedPrimaryCandles[p0].low
            val peerHeldLow = alignedPeerCandles[q1].low >= alignedPeerCandles[q0].low
            val peerSweptLow = alignedPeerCandles[q1].low < alignedPeerCandles[q0].low
            val primaryHeldLow = alignedPrimaryCandles[p1].low >= alignedPrimaryCandles[p0].low

            val originalPrimaryIdx = alignedPrimaryWithIdx[p1].first
            val originalPeerIdx = alignedPeerWithIdx[q1].first
            val confirmationIndex = alignedPrimaryWithIdx[
                (maxOf(p1, q1) + swingLookback).coerceAtMost(alignedPrimaryWithIdx.lastIndex)
            ].first

            if (primarySweptLow && peerHeldLow) {
                result += buildDivergence(
                    primarySymbol, peerSymbol, Direction.BULLISH,
                    SmtType.PRIMARY_SWEEP_PEER_FAIL, originalPrimaryIdx, originalPeerIdx,
                    confirmationIndex,
                    alignedPrimaryCandles[p1].low, alignedPeerCandles[q1].low, correlation,
                    "$primarySymbol swept sell-side while $peerSymbol held its prior low",
                )
            }
            if (peerSweptLow && primaryHeldLow) {
                result += buildDivergence(
                    primarySymbol, peerSymbol, Direction.BULLISH,
                    SmtType.PEER_SWEEP_PRIMARY_FAIL, originalPrimaryIdx, originalPeerIdx,
                    confirmationIndex,
                    alignedPrimaryCandles[p1].low, alignedPeerCandles[q1].low, correlation,
                    "$peerSymbol swept sell-side while $primarySymbol held its prior low",
                )
            }
        }

        if (primaryHighs.size == 2 && peerHighs.size == 2) {
            val p0 = primaryHighs[0]
            val p1 = primaryHighs[1]
            val q0 = peerHighs[0]
            val q1 = peerHighs[1]
            val primarySweptHigh = alignedPrimaryCandles[p1].high > alignedPrimaryCandles[p0].high
            val peerHeldHigh = alignedPeerCandles[q1].high <= alignedPeerCandles[q0].high
            val peerSweptHigh = alignedPeerCandles[q1].high > alignedPeerCandles[q0].high
            val primaryHeldHigh = alignedPrimaryCandles[p1].high <= alignedPrimaryCandles[p0].high

            val originalPrimaryIdx = alignedPrimaryWithIdx[p1].first
            val originalPeerIdx = alignedPeerWithIdx[q1].first
            val confirmationIndex = alignedPrimaryWithIdx[
                (maxOf(p1, q1) + swingLookback).coerceAtMost(alignedPrimaryWithIdx.lastIndex)
            ].first

            if (primarySweptHigh && peerHeldHigh) {
                result += buildDivergence(
                    primarySymbol, peerSymbol, Direction.BEARISH,
                    SmtType.PRIMARY_SWEEP_PEER_FAIL, originalPrimaryIdx, originalPeerIdx,
                    confirmationIndex,
                    alignedPrimaryCandles[p1].high, alignedPeerCandles[q1].high, correlation,
                    "$primarySymbol swept buy-side while $peerSymbol failed to confirm the high",
                )
            }
            if (peerSweptHigh && primaryHeldHigh) {
                result += buildDivergence(
                    primarySymbol, peerSymbol, Direction.BEARISH,
                    SmtType.PEER_SWEEP_PRIMARY_FAIL, originalPrimaryIdx, originalPeerIdx,
                    confirmationIndex,
                    alignedPrimaryCandles[p1].high, alignedPeerCandles[q1].high, correlation,
                    "$peerSymbol swept buy-side while $primarySymbol failed to confirm the high",
                )
            }
        }

        return result
    }

    private fun buildDivergence(
        primarySymbol: String,
        peerSymbol: String,
        direction: Direction,
        type: SmtType,
        primaryIndex: Int,
        peerIndex: Int,
        confirmationIndex: Int,
        primaryPrice: Double,
        peerPrice: Double,
        correlation: Double,
        detail: String,
    ): SmtDivergence {
        val confidence = (62.0 + ((correlation - MIN_POSITIVE_CORRELATION) * 35.0)).coerceIn(62.0, 86.0)
        return SmtDivergence(
            primarySymbol = primarySymbol,
            peerSymbol = peerSymbol,
            direction = direction,
            type = type,
            primaryIndex = primaryIndex,
            peerIndex = peerIndex,
            confirmationIndex = confirmationIndex,
            primaryPrice = primaryPrice,
            peerPrice = peerPrice,
            correlation = correlation,
            confidence = confidence,
            detail = detail,
        )
    }

    private fun findSwings(candles: List<Candle>, lookback: Int, isHigh: Boolean): List<Int> {
        val swings = mutableListOf<Int>()
        for (i in lookback until candles.size - lookback) {
            val confirmed = if (isHigh) {
                (i - lookback until i).all { candles[it].high <= candles[i].high } &&
                    (i + 1..i + lookback).all { candles[it].high <= candles[i].high }
            } else {
                (i - lookback until i).all { candles[it].low >= candles[i].low } &&
                    (i + 1..i + lookback).all { candles[it].low >= candles[i].low }
            }
            if (confirmed) swings += i
        }
        return swings
    }

    private fun correlation(a: List<Candle>, b: List<Candle>): Double {
        val n = minOf(a.size, b.size) - 1
        if (n < 20) return 0.0
        val x = DoubleArray(n) { i -> returnValue(a[i].close, a[i + 1].close) }
        val y = DoubleArray(n) { i -> returnValue(b[i].close, b[i + 1].close) }
        return pearson(x, y)
    }

    private fun returnValue(previous: Double, next: Double): Double =
        if (previous > 0.0) (next - previous) / previous else 0.0

    private fun pearson(x: DoubleArray, y: DoubleArray): Double {
        val n = minOf(x.size, y.size)
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0
        var sumY2 = 0.0
        for (i in 0 until n) {
            sumX += x[i]
            sumY += y[i]
            sumXY += x[i] * y[i]
            sumX2 += x[i] * x[i]
            sumY2 += y[i] * y[i]
        }
        val numerator = n * sumXY - sumX * sumY
        val denominator = sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY))
        return if (denominator > 0.0) (numerator / denominator).coerceIn(-1.0, 1.0) else 0.0
    }

    private companion object {
        const val MIN_BARS = 40
        const val DEFAULT_PERIOD = 160
        const val DEFAULT_SWING_LOOKBACK = 3
        const val MIN_POSITIVE_CORRELATION = 0.45
    }
}
