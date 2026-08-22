package com.foxtrader.app.domain.usecase.smt

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SmtConfig
import com.foxtrader.app.domain.usecase.signalintel.SignalSeriesIntegrity
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * SMT (Smart Money Technique) divergence detector.
 *
 * Phase 13 hardening:
 * - validates OHLC/timestamp integrity and rejects stale/short peers;
 * - aligns feeds by exact timestamp first and by tightly-bounded nearest bar when
 *   providers differ slightly in open timestamps;
 * - compares synchronized swing sequences rather than unrelated latest swings;
 * - uses only confirmed swings (right-side bars already exist);
 * - rejects stale divergences and weak price separation;
 * - confidence combines correlation, divergence magnitude and synchronization.
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
        /** Absolute timestamp skew between the paired second swings. */
        val timestampSkewMs: Long = 0L,
        /** Normalized divergence separation, in average-range units. */
        val divergenceStrength: Double = 0.0,
    )

    enum class SmtType {
        PRIMARY_SWEEP_PEER_FAIL,
        PEER_SWEEP_PRIMARY_FAIL,
    }

    private data class AlignedBar(
        val primaryIndex: Int,
        val peerIndex: Int,
        val primary: Candle,
        val peer: Candle,
    )

    fun detect(
        primarySymbol: String,
        primaryCandles: List<Candle>,
        correlatedCandles: Map<String, List<Candle>>,
        period: Int = DEFAULT_PERIOD,
        swingLookback: Int = DEFAULT_SWING_LOOKBACK,
        minCorrelation: Double = MIN_POSITIVE_CORRELATION,
        maxTimestampSkewMillis: Long? = null,
        config: SmtConfig? = null,
    ): List<SmtDivergence> {
        val cfg = (config ?: SmtConfig(
            period = period,
            swingLookback = swingLookback,
            minCorrelation = minCorrelation,
        )).sanitized()
        val effectivePeriod = cfg.period
        val effectiveSwingLookback = cfg.swingLookback
        val effectiveMinCorrelation = cfg.minCorrelation
        if (correlatedCandles.isEmpty()) return emptyList()
        if (!SignalSeriesIntegrity.validate(primaryCandles, MIN_BARS).valid) return emptyList()

        val inferredInterval = medianInterval(primaryCandles) ?: return emptyList()
        val skewLimit = (maxTimestampSkewMillis ?: (inferredInterval * cfg.maxTimestampSkewFraction).toLong())
            .coerceIn(0L, inferredInterval / 2)

        return correlatedCandles.flatMap { (peerSymbol, peerCandles) ->
            if (!SignalSeriesIntegrity.validate(peerCandles, MIN_BARS).valid) return@flatMap emptyList()
            detectPair(
                primarySymbol = primarySymbol,
                primaryCandles = primaryCandles,
                peerSymbol = peerSymbol,
                peerCandles = peerCandles,
                period = effectivePeriod,
                swingLookback = effectiveSwingLookback,
                minCorrelation = effectiveMinCorrelation,
                config = cfg,
                maxTimestampSkewMillis = skewLimit,
            )
        }
            .filter { primaryCandles.lastIndex - it.confirmationIndex <= cfg.maxSignalAgeBars }
            .filter { it.confidence >= cfg.minConfidence }
            .distinctBy { listOf(it.primarySymbol, it.peerSymbol, it.direction.name, it.type.name, it.primaryIndex, it.confirmationIndex) }
            .sortedWith(compareBy<SmtDivergence> { it.confirmationIndex }.thenBy { it.primaryIndex })
    }

    private fun detectPair(
        primarySymbol: String,
        primaryCandles: List<Candle>,
        peerSymbol: String,
        peerCandles: List<Candle>,
        period: Int,
        swingLookback: Int,
        minCorrelation: Double,
        maxTimestampSkewMillis: Long,
        config: SmtConfig,
    ): List<SmtDivergence> {
        val aligned = align(primaryCandles, peerCandles, maxTimestampSkewMillis).takeLast(period)
        if (aligned.size < MIN_BARS) return emptyList()

        val ap = aligned.map { it.primary }
        val aq = aligned.map { it.peer }
        val correlation = correlation(ap, aq)
        if (correlation < minCorrelation) return emptyList()

        val primaryHighs = findSwings(ap, swingLookback, true).takeLast(2)
        val primaryLows = findSwings(ap, swingLookback, false).takeLast(2)
        val peerHighs = findSwings(aq, swingLookback, true).takeLast(2)
        val peerLows = findSwings(aq, swingLookback, false).takeLast(2)
        val result = mutableListOf<SmtDivergence>()

        if (synchronized(primaryLows, peerLows, config.maxSwingSyncBars)) {
            val p0 = primaryLows[0]; val p1 = primaryLows[1]
            val q0 = peerLows[0]; val q1 = peerLows[1]
            val primarySwept = ap[p1].low < ap[p0].low
            val peerHeld = aq[q1].low >= aq[q0].low
            val peerSwept = aq[q1].low < aq[q0].low
            val primaryHeld = ap[p1].low >= ap[p0].low
            val confirmationAligned = maxOf(p1, q1) + swingLookback
            if (confirmationAligned <= aligned.lastIndex) {
                if (primarySwept && peerHeld) {
                    build(
                        primarySymbol, peerSymbol, Direction.BULLISH, SmtType.PRIMARY_SWEEP_PEER_FAIL,
                        aligned, p0, p1, q0, q1, confirmationAligned, correlation, lowSide = true, config = config,
                        detail = "$primarySymbol swept sell-side while $peerSymbol held its prior low",
                    )?.let(result::add)
                }
                if (peerSwept && primaryHeld) {
                    build(
                        primarySymbol, peerSymbol, Direction.BULLISH, SmtType.PEER_SWEEP_PRIMARY_FAIL,
                        aligned, p0, p1, q0, q1, confirmationAligned, correlation, lowSide = true, config = config,
                        detail = "$peerSymbol swept sell-side while $primarySymbol held its prior low",
                    )?.let(result::add)
                }
            }
        }

        if (synchronized(primaryHighs, peerHighs, config.maxSwingSyncBars)) {
            val p0 = primaryHighs[0]; val p1 = primaryHighs[1]
            val q0 = peerHighs[0]; val q1 = peerHighs[1]
            val primarySwept = ap[p1].high > ap[p0].high
            val peerHeld = aq[q1].high <= aq[q0].high
            val peerSwept = aq[q1].high > aq[q0].high
            val primaryHeld = ap[p1].high <= ap[p0].high
            val confirmationAligned = maxOf(p1, q1) + swingLookback
            if (confirmationAligned <= aligned.lastIndex) {
                if (primarySwept && peerHeld) {
                    build(
                        primarySymbol, peerSymbol, Direction.BEARISH, SmtType.PRIMARY_SWEEP_PEER_FAIL,
                        aligned, p0, p1, q0, q1, confirmationAligned, correlation, lowSide = false, config = config,
                        detail = "$primarySymbol swept buy-side while $peerSymbol failed to confirm the high",
                    )?.let(result::add)
                }
                if (peerSwept && primaryHeld) {
                    build(
                        primarySymbol, peerSymbol, Direction.BEARISH, SmtType.PEER_SWEEP_PRIMARY_FAIL,
                        aligned, p0, p1, q0, q1, confirmationAligned, correlation, lowSide = false, config = config,
                        detail = "$peerSymbol swept buy-side while $primarySymbol failed to confirm the high",
                    )?.let(result::add)
                }
            }
        }
        return result
    }

    private fun build(
        primarySymbol: String,
        peerSymbol: String,
        direction: Direction,
        type: SmtType,
        aligned: List<AlignedBar>,
        p0: Int,
        p1: Int,
        q0: Int,
        q1: Int,
        confirmationAligned: Int,
        correlation: Double,
        lowSide: Boolean,
        config: SmtConfig,
        detail: String,
    ): SmtDivergence? {
        val pPrev = if (lowSide) aligned[p0].primary.low else aligned[p0].primary.high
        val pNow = if (lowSide) aligned[p1].primary.low else aligned[p1].primary.high
        val qPrev = if (lowSide) aligned[q0].peer.low else aligned[q0].peer.high
        val qNow = if (lowSide) aligned[q1].peer.low else aligned[q1].peer.high
        val pRange = localRange(aligned.map { it.primary }, p1)
        val qRange = localRange(aligned.map { it.peer }, q1)
        val primaryMove = abs(pNow - pPrev) / pRange
        val peerMove = abs(qNow - qPrev) / qRange
        val separation = abs(primaryMove - peerMove)
        if (!separation.isFinite() || separation < config.minDivergenceStrength) return null

        val pBar = aligned[p1]
        val qBar = aligned[q1]
        val skew = abs(pBar.primary.timestamp - qBar.peer.timestamp)
        val syncScore = (100.0 - (abs(p1 - q1) * 10.0)).coerceIn(60.0, 100.0)
        val corrScore = ((correlation - config.minCorrelation) / (1.0 - config.minCorrelation).coerceAtLeast(1e-6) * 100.0)
            .coerceIn(0.0, 100.0)
        val divergenceScore = (separation / DIVERGENCE_FULL_STRENGTH * 100.0).coerceIn(0.0, 100.0)
        val confidence = (62.0 + corrScore * 0.11 + divergenceScore * 0.08 + syncScore * 0.05)
            .coerceIn(MIN_CONFIDENCE, 98.0)
        val confirmationIndex = aligned[confirmationAligned].primaryIndex
        return SmtDivergence(
            primarySymbol = primarySymbol,
            peerSymbol = peerSymbol,
            direction = direction,
            type = type,
            primaryIndex = pBar.primaryIndex,
            peerIndex = qBar.peerIndex,
            confirmationIndex = confirmationIndex,
            primaryPrice = pNow,
            peerPrice = qNow,
            correlation = correlation,
            confidence = confidence,
            timestampSkewMs = skew,
            divergenceStrength = separation,
            detail = "$detail · corr ${"%.2f".format(correlation)} · strength ${"%.2f".format(separation)}",
        )
    }

    private fun align(primary: List<Candle>, peer: List<Candle>, maxSkew: Long): List<AlignedBar> {
        val result = ArrayList<AlignedBar>(minOf(primary.size, peer.size))
        var j = 0
        for (i in primary.indices) {
            val ts = primary[i].timestamp
            while (j + 1 < peer.size && peer[j + 1].timestamp <= ts) j++
            var best = j
            if (j + 1 < peer.size && abs(peer[j + 1].timestamp - ts) < abs(peer[best].timestamp - ts)) best = j + 1
            if (best > 0 && abs(peer[best - 1].timestamp - ts) < abs(peer[best].timestamp - ts)) best--
            val skew = abs(peer[best].timestamp - ts)
            if (skew <= maxSkew) {
                // Prevent one peer candle being matched to two primary candles.
                if (result.lastOrNull()?.peerIndex == best) continue
                result += AlignedBar(i, best, primary[i], peer[best])
            }
        }
        return result
    }

    private fun synchronized(primarySwings: List<Int>, peerSwings: List<Int>, maxSyncBars: Int): Boolean {
        if (primarySwings.size != 2 || peerSwings.size != 2) return false
        return abs(primarySwings[0] - peerSwings[0]) <= maxSyncBars &&
            abs(primarySwings[1] - peerSwings[1]) <= maxSyncBars &&
            primarySwings[0] < primarySwings[1] && peerSwings[0] < peerSwings[1]
    }

    private fun findSwings(candles: List<Candle>, lookback: Int, isHigh: Boolean): List<Int> {
        val swings = mutableListOf<Int>()
        for (i in lookback until candles.size - lookback) {
            val confirmed = if (isHigh) {
                // Strict on the left, tolerant on the right: an equal-high
                // plateau is represented exactly once (its first peak), rather
                // than manufacturing several adjacent SMT swing points.
                (i - lookback until i).all { candles[it].high < candles[i].high } &&
                    (i + 1..i + lookback).all { candles[it].high <= candles[i].high }
            } else {
                (i - lookback until i).all { candles[it].low > candles[i].low } &&
                    (i + 1..i + lookback).all { candles[it].low >= candles[i].low }
            }
            if (confirmed) swings += i
        }
        return swings
    }

    private fun localRange(candles: List<Candle>, index: Int): Double {
        val start = (index - RANGE_WINDOW + 1).coerceAtLeast(0)
        return candles.subList(start, index + 1).map { it.range }.filter { it > 0.0 }.average()
            .takeIf { it.isFinite() && it > 0.0 } ?: 1e-9
    }

    private fun medianInterval(candles: List<Candle>): Long? {
        if (candles.size < 2) return null
        val diffs = candles.zipWithNext { a, b -> b.timestamp - a.timestamp }.filter { it > 0L }.sorted()
        if (diffs.isEmpty()) return null
        return diffs[diffs.size / 2]
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
        var sumX = 0.0; var sumY = 0.0; var sumXY = 0.0; var sumX2 = 0.0; var sumY2 = 0.0
        for (i in 0 until n) {
            sumX += x[i]; sumY += y[i]; sumXY += x[i] * y[i]; sumX2 += x[i] * x[i]; sumY2 += y[i] * y[i]
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
        const val DEFAULT_SKEW_DIVISOR = 4L
        const val MAX_SWING_SYNC_BARS = 4
        const val MAX_SIGNAL_AGE_BARS = 24
        const val RANGE_WINDOW = 20
        const val MIN_DIVERGENCE_STRENGTH = 0.05
        const val DIVERGENCE_FULL_STRENGTH = 1.0
        const val MIN_CONFIDENCE = 62.0
        const val MAX_CONFIDENCE = 86.0
    }
}
