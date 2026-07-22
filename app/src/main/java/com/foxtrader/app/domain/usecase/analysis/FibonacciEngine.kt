package com.foxtrader.app.domain.usecase.analysis

import com.foxtrader.app.domain.model.Direction
import javax.inject.Inject

/**
 * Fibonacci Engine — retracement, extension, and projection levels.
 *
 * - Retracements: pullback levels within a move (0.236 - 0.786)
 * - Extensions: profit targets beyond the move (1.272 - 4.236)
 * - Projections: ABC-based targets
 *
 * Pure math — no candle dependency.
 */
class FibonacciEngine @Inject constructor() {

    data class FibLevel(val ratio: Double, val price: Double, val label: String)

    private val retracementRatios = listOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0)
    private val extensionRatios = listOf(1.272, 1.414, 1.618, 2.0, 2.618, 3.618, 4.236)

    /**
     * Retracement levels between a swing high and low.
     * @param direction BULLISH = measuring an up-move (retrace down),
     *                   BEARISH = measuring a down-move (retrace up)
     */
    fun retracements(swingHigh: Double, swingLow: Double, direction: Direction): List<FibLevel> {
        val range = swingHigh - swingLow
        return retracementRatios.map { ratio ->
            val price = if (direction == Direction.BULLISH) {
                swingHigh - range * ratio
            } else {
                swingLow + range * ratio
            }
            FibLevel(ratio, price, "${(ratio * 100).toInt()}%")
        }
    }

    /**
     * Extension (target) levels projected beyond the move.
     */
    fun extensions(swingHigh: Double, swingLow: Double, direction: Direction): List<FibLevel> {
        val range = swingHigh - swingLow
        return extensionRatios.map { ratio ->
            val price = if (direction == Direction.BULLISH) {
                swingLow + range * ratio
            } else {
                swingHigh - range * ratio
            }
            FibLevel(ratio, price, "${(ratio * 1000).toInt() / 10.0}%")
        }
    }

    /**
     * ABC projection: from point C, project the AB leg by fib ratios.
     */
    fun projections(pointA: Double, pointB: Double, pointC: Double): List<FibLevel> {
        val abRange = pointB - pointA
        return listOf(0.618, 1.0, 1.272, 1.618, 2.0).map { ratio ->
            FibLevel(ratio, pointC + abRange * ratio, "${(ratio * 1000).toInt() / 10.0}%")
        }
    }
}
