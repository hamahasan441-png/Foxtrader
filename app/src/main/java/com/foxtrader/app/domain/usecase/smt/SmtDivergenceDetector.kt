package com.foxtrader.app.domain.usecase.smt

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SmtConfig
import com.foxtrader.app.domain.usecase.signalintel.SignalSeriesIntegrity
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * SMT (Smart Money Technique) divergence detector.
 *
 * Non-repaint contract:
 * - validates OHLC/timestamp integrity and rejects stale/short peers;
 * - aligns feeds by timestamp with a bounded skew;
 * - compares synchronized, confirmed swing sequences;
 * - freezes correlation and confidence at the first bar where both swings are
 *   confirmed, so later candles cannot retroactively re-score an old event;
 * - retains every recent confirmed divergence instead of replacing an older
 *   event simply because a newer swing appeared;
 * - rejects stale divergences and weak normalized separation.
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

    private data class SwingPair(
        val p0: Int,
        val p1: Int,
        val q0: Int,
        val q1: Int,
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
                period = cfg.period,
                swingLookback = cfg.swingLookback,
                minCorrelation = cfg.minCorrelation,
                config = cfg,
                maxTimestampSkewMillis = skewLimit,
            )
        }
            .filter { primaryCandles.lastIndex - it.confirmationIndex <= cfg.maxSignalAgeBars }
            .filter { it.confidence >= cfg.minConfidence }
            .distinctBy {
                listOf(
                    it.primarySymbol,
                    it.peerSymbol,
                    it.direction.name,
                    it.type.name,
                    it.primaryIndex,
                    it.confirmationIndex,
                )
            }
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
        // Keep enough history to preserve all events that are still eligible for
        // the public maxSignalAgeBars window. Using only the latest two swings
        // would make a still-valid historical marker disappear when a new pivot
        // confirms, which is a visual/forensic form of repainting.
        val historyBars = period + config.maxSignalAgeBars + swingLookback * 2 + 4
        val aligned = align(primaryCandles, peerCandles, maxTimestampSkewMillis).takeLast(historyBars)
        if (aligned.size < MIN_BARS) return emptyList()

        val ap = aligned.map { it.primary }
        val aq = aligned.map { it.peer }
        val primaryHighs = findSwings(ap, swingLookback, true)
        val primaryLows = findSwings(ap, swingLookback, false)
        val peerHighs = findSwings(aq, swingLookback, true)
        val peerLows = findSwings(aq, swingLookback, false)
        val result = mutableListOf<SmtDivergence>()

        for (pair in synchronizedPairs(primaryLows, peerLows, config.maxSwingSyncBars)) {
            val p0 = pair.p0
            val p1 = pair.p1
            val q0 = pair.q0
            val q1 = pair.q1
            val confirmationAligned = maxOf(p1, q1) + swingLookback
            if (confirmationAligned > aligned.lastIndex) continue
            val eventCorrelation = eventCorrelation(ap, aq, confirmationAligned, period, minCorrelation) ?: continue

            val primarySwept = ap[p1].low < ap[p0].low
            val peerHeld = aq[q1].low >= aq[q0].low
            val peerSwept = aq[q1].low < aq[q0].low
            val primaryHeld = ap[p1].low >= ap[p0].low

            if (primarySwept && peerHeld) {
                build(
                    primarySymbol,
                    peerSymbol,
                    Direction.BULLISH,
                    SmtType.PRIMARY_SWEEP_PEER_FAIL,
                    aligned,
                    p0,
                    p1,
                    q0,
                    q1,
                    confirmationAligned,
                    eventCorrelation,
                    lowSide = true,
                    config = config,
                    detail = "$primarySymbol swept sell-side while $peerSymbol held its prior low",
                )?.let(result::add)
            }
            if (peerSwept && primaryHeld) {
                build(
                    primarySymbol,
                    peerSymbol,
                    Direction.BULLISH,
                    SmtType.PEER_SWEEP_PRIMARY_FAIL,
                    aligned,
                    p0,
                    p1,
                    q0,
                    q1,
                    confirmationAligned,
                    eventCorrelation,
                    lowSide = true,
                    config = config,
                    detail = "$peerSymbol swept sell-side while $primarySymbol held its prior low",
                )?.let(result::add)
            }
        }

        for (pair in synchronizedPairs(primaryHighs, peerHighs, config.maxSwingSyncBars)) {
            val p0 = pair.p0
            val p1 = pair.p1
            val q0 = pair.q0
            val q1 = pair.q1
            val confirmationAligned = maxOf(p1, q1) + swingLookback
            if (confirmationAligned > aligned.lastIndex) continue
            val eventCorrelation = eventCorrelation(ap, aq, confirmationAligned, period, minCorrelation) ?: continue

            val primarySwept = ap[p1].high > ap[p0].high
            val peerHeld = aq[q1].high <= aq[q0].high
            val peerSwept = aq[q1].high > aq[q0].high
            val primaryHeld = ap[p1].high <= ap[p0].high

            if (primarySwept && peerHeld) {
                build(
                    primarySymbol,
                    peerSymbol,
                    Direction.BEARISH,
                    SmtType.PRIMARY_SWEEP_PEER_FAIL,
                    aligned,
                    p0,
                    p1,
                    q0,
                    q1,
                    confirmationAligned,
                    eventCorrelation,
                    lowSide = false,
                    config = config,
                    detail = "$primarySymbol swept buy-side while $peerSymbol failed to confirm the high",
                )?.let(result::add)
            }
            if (peerSwept && primaryHeld) {
                build(
                    primarySymbol,
                    peerSymbol,
                    Direction.BEARISH,
                    SmtType.PEER_SWEEP_PRIMARY_FAIL,
                    aligned,
                    p0,
                    p1,
                    q0,
                    q1,
                    confirmationAligned,
                    eventCorrelation,
                    lowSide = false,
                    config = config,
                    detail = "$peerSymbol swept buy-side while $primarySymbol failed to confirm the high",
                )?.let(result::add)
            }
        }

        return result
    }

    /**
     * Freeze correlation at the event confirmation boundary. The exact same
     * historical divergence therefore receives the exact same correlation and
     * confidence after arbitrary future candles are appended.
     */
    private fun eventCorrelation(
        primary: List<Candle>,
        peer: List<Candle>,
        confirmationIndex: Int,
        period: Int,
        minCorrelation: Double,
    ): Double? {
        if (confirmationIndex !in primary.indices || confirmationIndex !in peer.indices) return null
        val start = (confirmationIndex - period + 1).coerceAtLeast(0)
        val endExclusive = confirmationIndex + 1
        val corr = correlation(
            primary.subList(start, endExclusive),
            peer.subList(start, endExclusive),
        )
        return corr.takeIf { it.isFinite() && it >= minCorrelation }
    }

    /**
     * Match adjacent primary swing pairs with the nearest adjacent peer pair.
     * We preserve all confirmed pairs in the retained history rather than only
     * `takeLast(2)`, so a later pivot cannot erase a still-recent divergence.
     */
    private fun synchronizedPairs(
        primarySwings: List<Int>,
        peerSwings: List<Int>,
        maxSyncBars: Int,
    ): List<SwingPair> {
        if (primarySwings.size < 2 || peerSwings.size < 2) return emptyList()
        val result = mutableListOf<SwingPair>()

        for (pi in 1 until primarySwings.size) {
            val p0 = primarySwings[pi - 1]
            val p1 = primarySwings[pi]
            var best: SwingPair? = null
            var bestDistance = Int.MAX_VALUE

            for (qi in 1 until peerSwings.size) {
                val q0 = peerSwings[qi - 1]
                val q1 = peerSwings[qi]
                val firstDistance = abs(p0 - q0)
                val secondDistance = abs(p1 - q1)
                if (firstDistance > maxSyncBars || secondDistance > maxSyncBars) continue
                val totalDistance = firstDistance + secondDistance
                if (totalDistance < bestDistance ||
                    (totalDistance == bestDistance && (best == null || q1 < best.q1))
                ) {
                    best = SwingPair(p0, p1, q0, q1)
                    bestDistance = totalDistance
                }
            }

            if (best != null) result += best
        }

        return result.distinctBy { listOf(it.p0, it.p1, it.q0, it.q1) }
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
        val corrScore = ((correlation - config.minCorrelation) /
            (1.0 - config.minCorrelation).coerceAtLeast(1e-6) * 100.0)
            .coerceIn(0.0, 100.0)
        val divergenceScore = (separation / DIVERGENCE_FULL_STRENGTH * 100.0).coerceIn(0.0, 100.0)
        val confidence = (62.0 + corrScore * 0.11 + divergenceScore * 0.08 + syncScore * 0.05)
            .coerceIn(MIN_CONFIDENCE, MAX_CONFIDENCE)
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

    private fun findSwings(candles: List<Candle>, lookback: Int, isHigh: Boolean): List<Int> {
        val swings = mutableListOf<Int>()
        for (i in lookback until candles.size - lookback) {
            val confirmed = if (isHigh) {
                // Strict on the left, tolerant on the right: an equal-high
                // plateau is represented exactly once (its first peak).
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
        return candles.subList(start, index + 1)
            .map { it.range }
            .filter { it > 0.0 }
            .average()
            .takeIf { it.isFinite() && it > 0.0 } ?: 1e-9
    }

    private fun medianInterval(candles: List<Candle>): Long? {
        if (candles.size < 2) return null
        val diffs = candles.zipWithNext { a, b -> b.timestamp - a.timestamp }
            .filter { it > 0L }
            .sorted()
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
        const val RANGE_WINDOW = 20
        const val DIVERGENCE_FULL_STRENGTH = 1.0
        const val MIN_CONFIDENCE = 62.0
        const val MAX_CONFIDENCE = 86.0
    }
}
