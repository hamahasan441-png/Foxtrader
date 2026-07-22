package com.foxtrader.app.domain.usecase.analysis

import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject
import kotlin.math.abs

/**
 * Support/Resistance Detector — auto-identifies horizontal S/R zones.
 *
 * Method: finds swing points, then clusters nearby swings into zones.
 * A zone touched more times = stronger. Recent touches weighted higher.
 *
 * Non-repainting: only uses confirmed swings.
 */
class SupportResistanceDetector @Inject constructor() {

    data class SRZone(
        val price: Double,        // Center of the zone
        val upperBound: Double,
        val lowerBound: Double,
        val touches: Int,
        val strength: Double,     // 0-100
        val isSupport: Boolean,   // true = mostly held as support
        val lastTouchIndex: Int,
    )

    fun detect(
        candles: List<Candle>,
        swingLookback: Int = 5,
        maxZones: Int = 8,
    ): List<SRZone> {
        if (candles.size < swingLookback * 2 + 1) return emptyList()

        val avgRange = candles.takeLast(50).map { it.high - it.low }.average()
        val tolerance = avgRange * 0.5

        // Collect swing highs and lows as (index, price, isHigh)
        val swings = mutableListOf<Triple<Int, Double, Boolean>>()
        for (i in swingLookback until candles.size - swingLookback) {
            val isHigh = (i - swingLookback until i).all { candles[it].high <= candles[i].high } &&
                (i + 1..i + swingLookback).all { candles[it].high <= candles[i].high }
            val isLow = (i - swingLookback until i).all { candles[it].low >= candles[i].low } &&
                (i + 1..i + swingLookback).all { candles[it].low >= candles[i].low }
            if (isHigh) swings.add(Triple(i, candles[i].high, true))
            else if (isLow) swings.add(Triple(i, candles[i].low, false))
        }

        // Cluster swings by price proximity
        val clusters = mutableListOf<MutableList<Triple<Int, Double, Boolean>>>()
        for (swing in swings) {
            val existing = clusters.firstOrNull { cluster ->
                abs(cluster.map { it.second }.average() - swing.second) <= tolerance
            }
            if (existing != null) existing.add(swing) else clusters.add(mutableListOf(swing))
        }

        // Build zones (only clusters with >= 2 touches)
        val zones = clusters
            .filter { it.size >= 2 }
            .map { cluster ->
                val prices = cluster.map { it.second }
                val center = prices.average()
                val highCount = cluster.count { it.third }
                val lastTouch = cluster.maxOf { it.first }
                val recencyBonus = (lastTouch.toDouble() / candles.size) * 20
                SRZone(
                    price = center,
                    upperBound = prices.max(),
                    lowerBound = prices.min(),
                    touches = cluster.size,
                    strength = (cluster.size * 15.0 + recencyBonus).coerceAtMost(100.0),
                    isSupport = highCount < cluster.size / 2.0,
                    lastTouchIndex = lastTouch,
                )
            }
            .sortedByDescending { it.strength }
            .take(maxZones)

        return zones
    }
}
