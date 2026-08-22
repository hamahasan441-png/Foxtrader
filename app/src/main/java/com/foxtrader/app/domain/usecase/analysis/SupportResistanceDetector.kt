package com.foxtrader.app.domain.usecase.analysis

import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject
import kotlin.math.abs

/**
 * Support/Resistance detector with parameter and numeric containment.
 * Non-repainting: only confirmed swings are used.
 */
class SupportResistanceDetector @Inject constructor() {

    data class SRZone(
        val price: Double,
        val upperBound: Double,
        val lowerBound: Double,
        val touches: Int,
        val strength: Double,
        val isSupport: Boolean,
        val lastTouchIndex: Int,
    )

    fun detect(
        candles: List<Candle>,
        swingLookback: Int = 5,
        maxZones: Int = 8,
    ): List<SRZone> {
        val lookback = swingLookback.coerceAtLeast(1)
        val zoneLimit = maxZones.coerceAtLeast(0)
        if (zoneLimit == 0 || candles.size < lookback * 2 + 1) return emptyList()
        if (candles.any { !it.isWellFormed() }) return emptyList()

        val ranges = candles.takeLast(50).map { it.high - it.low }.filter { it.isFinite() && it >= 0.0 }
        if (ranges.isEmpty()) return emptyList()
        val avgRange = ranges.average()
        if (!avgRange.isFinite() || avgRange <= 0.0) return emptyList()
        val tolerance = avgRange * 0.5
        if (!tolerance.isFinite() || tolerance <= 0.0) return emptyList()

        val swings = mutableListOf<Triple<Int, Double, Boolean>>()
        for (i in lookback until candles.size - lookback) {
            val isHigh = (i - lookback until i).all { candles[it].high <= candles[i].high } &&
                (i + 1..i + lookback).all { candles[it].high <= candles[i].high }
            val isLow = (i - lookback until i).all { candles[it].low >= candles[i].low } &&
                (i + 1..i + lookback).all { candles[it].low >= candles[i].low }
            if (isHigh) swings.add(Triple(i, candles[i].high, true))
            else if (isLow) swings.add(Triple(i, candles[i].low, false))
        }

        val clusters = mutableListOf<MutableList<Triple<Int, Double, Boolean>>>()
        for (swing in swings) {
            val existing = clusters.firstOrNull { cluster ->
                val mean = cluster.map { it.second }.average()
                mean.isFinite() && abs(mean - swing.second) <= tolerance
            }
            if (existing != null) existing.add(swing) else clusters.add(mutableListOf(swing))
        }

        return clusters
            .filter { it.size >= 2 }
            .mapNotNull { cluster ->
                val prices = cluster.map { it.second }.filter { it.isFinite() && it > 0.0 }
                if (prices.size != cluster.size) return@mapNotNull null
                val center = prices.average()
                val upper = prices.maxOrNull() ?: return@mapNotNull null
                val lower = prices.minOrNull() ?: return@mapNotNull null
                if (!center.isFinite() || upper < lower) return@mapNotNull null
                val highCount = cluster.count { it.third }
                val lastTouch = cluster.maxOf { it.first }
                val recencyBonus = (lastTouch.toDouble() / candles.size.toDouble()) * 20.0
                SRZone(
                    price = center,
                    upperBound = upper,
                    lowerBound = lower,
                    touches = cluster.size,
                    strength = (cluster.size * 15.0 + recencyBonus).coerceIn(0.0, 100.0),
                    isSupport = highCount < cluster.size / 2.0,
                    lastTouchIndex = lastTouch,
                )
            }
            .sortedByDescending { it.strength }
            .take(zoneLimit)
    }

    private fun Candle.isWellFormed(): Boolean =
        open.isFinite() && high.isFinite() && low.isFinite() && close.isFinite() &&
            open > 0.0 && high > 0.0 && low > 0.0 && close > 0.0 && high >= low
}
