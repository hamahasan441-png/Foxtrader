package com.foxtrader.app.domain.usecase.crucible

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.compass.CompassFeatures
import com.foxtrader.app.domain.usecase.compass.CompassLabeler
import com.foxtrader.app.domain.usecase.crucible.model.CrucibleObservation

/**
 * Turns a candle series into the observations a rule search reads.
 *
 * Two parts of this are load-bearing far beyond their size.
 *
 * **Discretisation is by quantile, not by fixed value.** A rule saying "ATR
 * above 0.0012" describes one instrument in one year. A rule saying "in the
 * top decile of its own recent volatility" describes a market state, and is
 * the only kind that can be checked on data it was not built from.
 *
 * **Every observation carries its uniqueness.** Outcomes span a horizon, so
 * neighbouring observations mostly describe the same stretch of market and are
 * not independent facts. Counting them as if they were is the single easiest
 * way to make a rule look far better supported than it is, and it inflates
 * every confidence bound computed downstream.
 */
object CrucibleObservations {

    /** Features are Compass's, reused deliberately rather than reinvented. */
    val FEATURE_NAMES: List<String> = CompassFeatures.NAMES.drop(1)

    fun build(
        candles: List<Candle>,
        config: CrucibleConfig,
    ): List<CrucibleObservation> {
        val stride = 1
        val raw = ArrayList<Raw>()

        for (index in WARMUP until candles.size - 1) {
            if (index % stride != 0) continue
            val atr = CompassLabeler.atrAt(candles, index, config.atrPeriod)
            if (atr <= 0.0) continue
            val barrier = atr * config.effectiveBarrierMultiple

            val (resolvedDirection, decidedIndex) = resolve(candles, index, barrier, config.horizonBars)
                ?: continue

            // Features are read at the observation's own bar and expressed
            // relative to a long, so a rule's meaning does not flip with the
            // side being tested.
            val features = CompassFeatures.extract(candles, index, Direction.BULLISH).drop(1).toDoubleArray()
            if (features.any { !it.isFinite() }) continue

            raw += Raw(index, candles[index].timestamp, candles[index].close, features, resolvedDirection, decidedIndex)
        }
        if (raw.isEmpty()) return emptyList()

        val buckets = discretise(raw.map { it.features }, config.cutPoints)
        val uniqueness = uniquenessOf(raw.map { it.index to it.decidedIndex })

        return raw.mapIndexed { i, item ->
            CrucibleObservation(
                index = item.index,
                timestamp = item.timestamp,
                price = item.price,
                buckets = buckets[i],
                // Set by the caller per target; direction rules override it.
                hit = item.resolvedDirection != null,
                resolvedDirection = item.resolvedDirection,
                decidedIndex = item.decidedIndex,
                uniqueness = uniqueness[i],
            )
        }
    }

    /**
     * Which side of the symmetric barrier came first.
     *
     * Null when the observation never resolved inside the horizon; a bar
     * containing both sides is treated as unresolved rather than guessed at,
     * because bar data cannot say which came first and guessing is how a
     * measured number becomes an advertised one.
     */
    private fun resolve(
        candles: List<Candle>,
        index: Int,
        barrier: Double,
        horizonBars: Int,
    ): Pair<Direction?, Int>? {
        val reference = candles[index].close
        if (!reference.isFinite() || reference <= 0.0) return null
        val upper = reference + barrier
        val lower = reference - barrier
        val last = minOf(candles.lastIndex, index + horizonBars)
        if (last <= index) return null

        for (i in (index + 1)..last) {
            val bar = candles[i]
            val touchedUpper = bar.high >= upper
            val touchedLower = bar.low <= lower
            when {
                touchedUpper && touchedLower -> return null to i
                touchedUpper -> return Direction.BULLISH to i
                touchedLower -> return Direction.BEARISH to i
            }
        }
        // Neither barrier reached: a real outcome for a movement rule, and the
        // absence of one for a direction rule.
        return if (last >= index + horizonBars) null to last else null
    }

    /**
     * Quantile buckets per feature.
     *
     * Computed over the whole series on purpose. Bucket edges are a description
     * of the feature's own distribution rather than of the outcome, so they
     * leak nothing about which way price went — and recomputing them per fold
     * would make a rule mean different things in different folds, which is the
     * larger error.
     */
    fun discretise(rows: List<DoubleArray>, cutPoints: List<Double>): List<IntArray> {
        if (rows.isEmpty()) return emptyList()
        val featureCount = rows[0].size
        val edges = Array(featureCount) { feature ->
            val sorted = rows.map { it[feature] }.sorted()
            cutPoints.map { q -> sorted[(q * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)] }
        }
        return rows.map { row ->
            IntArray(featureCount) { feature ->
                edges[feature].count { row[feature] > it }
            }
        }
    }

    /**
     * Average uniqueness per observation.
     *
     * At each bar, count how many observation horizons are live. An
     * observation's uniqueness is the mean of one-over-that-count across its
     * own horizon: one when it overlapped nothing, near zero when everything it
     * says was already said by its neighbours.
     */
    fun uniquenessOf(spans: List<Pair<Int, Int>>): DoubleArray {
        if (spans.isEmpty()) return DoubleArray(0)
        val last = spans.maxOf { it.second }
        val first = spans.minOf { it.first }
        val concurrency = IntArray(last - first + 2)

        for ((from, to) in spans) {
            concurrency[from - first]++
            concurrency[to - first + 1]--
        }
        var running = 0
        for (i in concurrency.indices) {
            running += concurrency[i]
            concurrency[i] = running
        }

        return DoubleArray(spans.size) { i ->
            val (from, to) = spans[i]
            var sum = 0.0
            var count = 0
            for (bar in (from - first)..(to - first)) {
                val live = concurrency.getOrElse(bar) { 1 }.coerceAtLeast(1)
                sum += 1.0 / live
                count++
            }
            if (count == 0) 1.0 else (sum / count).coerceIn(0.0, 1.0)
        }
    }

    private data class Raw(
        val index: Int,
        val timestamp: Long,
        val price: Double,
        val features: DoubleArray,
        val resolvedDirection: Direction?,
        val decidedIndex: Int,
    )

    /** Bars needed before any feature is meaningful. */
    const val WARMUP = 80
}
