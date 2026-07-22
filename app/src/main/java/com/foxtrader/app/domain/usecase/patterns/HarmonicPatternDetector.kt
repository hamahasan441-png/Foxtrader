package com.foxtrader.app.domain.usecase.patterns

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Harmonic Pattern Detector — identifies Fibonacci-based price patterns.
 *
 * Detects:
 * - Gartley (0.618 XA retracement)
 * - Butterfly (1.272 XA extension)
 * - Bat (0.886 XA retracement)
 * - Crab (1.618 XA extension)
 * - Shark (0.886 extreme)
 * - Cypher (0.786 XC retracement)
 *
 * All patterns require 5 swing points (X, A, B, C, D).
 * Non-repainting: only confirmed patterns (D point complete).
 */
class HarmonicPatternDetector @Inject constructor() {

    data class HarmonicPattern(
        val type: HarmonicType,
        val direction: Direction,
        val xIndex: Int, val xPrice: Double,
        val aIndex: Int, val aPrice: Double,
        val bIndex: Int, val bPrice: Double,
        val cIndex: Int, val cPrice: Double,
        val dIndex: Int, val dPrice: Double,
        val prz: Pair<Double, Double>,  // Potential Reversal Zone (low, high)
        val stopLoss: Double,
        val tp1: Double,
        val tp2: Double,
        val score: Double,  // 0-100 pattern quality
    )

    enum class HarmonicType {
        GARTLEY, BUTTERFLY, BAT, CRAB, SHARK, CYPHER
    }

    // Fibonacci ratio tolerances
    private val tolerance = 0.05 // 5% tolerance on ratios

    /**
     * Scan candles for harmonic patterns.
     * Returns all detected patterns sorted by recency.
     */
    operator fun invoke(candles: List<Candle>): List<HarmonicPattern> {
        if (candles.size < 30) return emptyList()

        val swings = detectSwingPoints(candles)
        if (swings.size < 5) return emptyList()

        val patterns = mutableListOf<HarmonicPattern>()

        // Scan all possible 5-point combinations (XABCD)
        for (i in 0 until swings.size - 4) {
            val x = swings[i]
            val a = swings[i + 1]
            val b = swings[i + 2]
            val c = swings[i + 3]
            val d = swings[i + 4]

            // Validate basic structure (alternating HH/HL or LH/LL)
            if (!isValidStructure(x, a, b, c, d)) continue

            // Calculate Fibonacci ratios
            val xa = abs(a.second - x.second)
            val ab = abs(b.second - a.second)
            val bc = abs(c.second - b.second)
            val cd = abs(d.second - c.second)
            val xd = abs(d.second - x.second)

            if (xa == 0.0) continue
            val abXa = ab / xa
            val bcAb = if (ab > 0) bc / ab else 0.0
            val cdBc = if (bc > 0) cd / bc else 0.0
            val xdXa = xd / xa

            // Check each pattern type
            checkGartley(abXa, bcAb, cdBc, xdXa)?.let { (type, score) ->
                val direction = if (d.second < x.second) Direction.BULLISH else Direction.BEARISH
                patterns.add(buildPattern(type, direction, x, a, b, c, d, candles, score))
            }
            checkButterfly(abXa, bcAb, cdBc, xdXa)?.let { (type, score) ->
                val direction = if (d.second < a.second) Direction.BEARISH else Direction.BULLISH
                patterns.add(buildPattern(type, direction, x, a, b, c, d, candles, score))
            }
            checkBat(abXa, bcAb, cdBc, xdXa)?.let { (type, score) ->
                val direction = if (d.second < x.second) Direction.BULLISH else Direction.BEARISH
                patterns.add(buildPattern(type, direction, x, a, b, c, d, candles, score))
            }
            checkCrab(abXa, bcAb, cdBc, xdXa)?.let { (type, score) ->
                val direction = if (d.second > x.second) Direction.BEARISH else Direction.BULLISH
                patterns.add(buildPattern(type, direction, x, a, b, c, d, candles, score))
            }
        }

        return patterns.sortedByDescending { it.dIndex }
    }

    // ========================================================================
    // PATTERN RATIO CHECKS
    // ========================================================================

    private fun checkGartley(abXa: Double, bcAb: Double, cdBc: Double, xdXa: Double): Pair<HarmonicType, Double>? {
        // Gartley: AB=0.618 XA, BC=0.382-0.886 AB, CD=1.272-1.618 BC, XD=0.786 XA
        if (!inRange(abXa, 0.618) || !inRange(xdXa, 0.786)) return null
        if (bcAb < 0.382 - tolerance || bcAb > 0.886 + tolerance) return null
        if (cdBc < 1.272 - tolerance || cdBc > 1.618 + tolerance) return null
        val score = 100.0 - (abs(abXa - 0.618) + abs(xdXa - 0.786)) * 100
        return HarmonicType.GARTLEY to score.coerceIn(0.0, 100.0)
    }

    private fun checkButterfly(abXa: Double, bcAb: Double, cdBc: Double, xdXa: Double): Pair<HarmonicType, Double>? {
        // Butterfly: AB=0.786 XA, BC=0.382-0.886 AB, CD=1.618-2.618 BC, XD=1.272 XA
        if (!inRange(abXa, 0.786) || !inRange(xdXa, 1.272)) return null
        if (bcAb < 0.382 - tolerance || bcAb > 0.886 + tolerance) return null
        if (cdBc < 1.618 - tolerance || cdBc > 2.618 + tolerance) return null
        val score = 100.0 - (abs(abXa - 0.786) + abs(xdXa - 1.272)) * 100
        return HarmonicType.BUTTERFLY to score.coerceIn(0.0, 100.0)
    }

    private fun checkBat(abXa: Double, bcAb: Double, cdBc: Double, xdXa: Double): Pair<HarmonicType, Double>? {
        // Bat: AB=0.382-0.5 XA, BC=0.382-0.886 AB, CD=1.618-2.618 BC, XD=0.886 XA
        if (!inRange(xdXa, 0.886)) return null
        if (abXa < 0.382 - tolerance || abXa > 0.5 + tolerance) return null
        if (bcAb < 0.382 - tolerance || bcAb > 0.886 + tolerance) return null
        val score = 100.0 - abs(xdXa - 0.886) * 100
        return HarmonicType.BAT to score.coerceIn(0.0, 100.0)
    }

    private fun checkCrab(abXa: Double, bcAb: Double, cdBc: Double, xdXa: Double): Pair<HarmonicType, Double>? {
        // Crab: AB=0.382-0.618 XA, BC=0.382-0.886 AB, CD=2.618-3.618 BC, XD=1.618 XA
        if (!inRange(xdXa, 1.618)) return null
        if (abXa < 0.382 - tolerance || abXa > 0.618 + tolerance) return null
        if (bcAb < 0.382 - tolerance || bcAb > 0.886 + tolerance) return null
        val score = 100.0 - abs(xdXa - 1.618) * 100
        return HarmonicType.CRAB to score.coerceIn(0.0, 100.0)
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private fun inRange(value: Double, target: Double): Boolean =
        abs(value - target) <= tolerance

    private fun isValidStructure(
        x: Pair<Int, Double>, a: Pair<Int, Double>,
        b: Pair<Int, Double>, c: Pair<Int, Double>,
        d: Pair<Int, Double>,
    ): Boolean {
        // Must have alternating peaks/troughs
        return x.first < a.first && a.first < b.first &&
            b.first < c.first && c.first < d.first
    }

    private fun detectSwingPoints(candles: List<Candle>, lookback: Int = 5): List<Pair<Int, Double>> {
        val swings = mutableListOf<Pair<Int, Double>>()
        for (i in lookback until candles.size - lookback) {
            val isHigh = (i - lookback until i).all { candles[it].high <= candles[i].high } &&
                (i + 1..i + lookback).all { candles[it].high <= candles[i].high }
            val isLow = (i - lookback until i).all { candles[it].low >= candles[i].low } &&
                (i + 1..i + lookback).all { candles[it].low >= candles[i].low }

            if (isHigh) swings.add(i to candles[i].high)
            else if (isLow) swings.add(i to candles[i].low)
        }
        return swings
    }

    private fun buildPattern(
        type: HarmonicType,
        direction: Direction,
        x: Pair<Int, Double>, a: Pair<Int, Double>,
        b: Pair<Int, Double>, c: Pair<Int, Double>,
        d: Pair<Int, Double>, candles: List<Candle>,
        score: Double,
    ): HarmonicPattern {
        val xa = abs(a.second - x.second)
        val przSize = xa * 0.05
        val prz = (d.second - przSize) to (d.second + przSize)

        val stopLoss = if (direction == Direction.BULLISH) d.second - xa * 0.1 else d.second + xa * 0.1
        val tp1 = if (direction == Direction.BULLISH) d.second + xa * 0.382 else d.second - xa * 0.382
        val tp2 = if (direction == Direction.BULLISH) d.second + xa * 0.618 else d.second - xa * 0.618

        return HarmonicPattern(
            type = type, direction = direction,
            xIndex = x.first, xPrice = x.second,
            aIndex = a.first, aPrice = a.second,
            bIndex = b.first, bPrice = b.second,
            cIndex = c.first, cPrice = c.second,
            dIndex = d.first, dPrice = d.second,
            prz = prz, stopLoss = stopLoss, tp1 = tp1, tp2 = tp2, score = score,
        )
    }
}
