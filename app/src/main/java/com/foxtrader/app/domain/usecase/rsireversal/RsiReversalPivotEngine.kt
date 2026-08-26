package com.foxtrader.app.domain.usecase.rsireversal

import com.foxtrader.app.domain.usecase.rsireversal.model.PivotSeries
import com.foxtrader.app.domain.usecase.rsireversal.model.RsiReversalPivot

/**
 * Non-repainting swing detection (§5.1, §6).
 *
 * One engine serves both price structure and RSI structure. The specification
 * is explicit that RSI structure must be derived from RSI highs and lows rather
 * than reduced to RSI close (§6), which is exactly the same shape of problem as
 * price structure using high/low rather than close (§5) — so they share an
 * implementation instead of being written twice and drifting.
 *
 * Non-repaint contract:
 * - a pivot is emitted only once all [right] bars after it exist;
 * - [RsiReversalPivot.confirmedIndex] is the bar it became knowable on, and no
 *   caller may act on it earlier;
 * - once emitted, a pivot never moves or disappears as bars are appended.
 */
object RsiReversalPivotEngine {

    /**
     * Detect confirmed pivots over an indexed series.
     *
     * @param size number of bars
     * @param highAt extreme used for swing highs at a bar
     * @param lowAt extreme used for swing lows at a bar
     * @param timestampAt bar open time, carried onto the pivot
     */
    fun detect(
        series: PivotSeries,
        size: Int,
        left: Int,
        right: Int,
        highAt: (Int) -> Double,
        lowAt: (Int) -> Double,
        timestampAt: (Int) -> Long,
    ): List<RsiReversalPivot> {
        if (size <= left + right) return emptyList()

        val out = ArrayList<RsiReversalPivot>()
        for (i in left until size - right) {
            if (isSwingHigh(i, left, right, highAt)) {
                out += RsiReversalPivot(
                    series = series,
                    isHigh = true,
                    index = i,
                    confirmedIndex = i + right,
                    timestamp = timestampAt(i),
                    value = highAt(i),
                )
            }
            if (isSwingLow(i, left, right, lowAt)) {
                out += RsiReversalPivot(
                    series = series,
                    isHigh = false,
                    index = i,
                    confirmedIndex = i + right,
                    timestamp = timestampAt(i),
                    value = lowAt(i),
                )
            }
        }
        return out
    }

    /**
     * A swing high is strictly greater than the bars on its right and at least
     * as high as those on its left.
     *
     * The asymmetry is deliberate and is what makes an equal-level plateau
     * resolve deterministically to its last bar instead of emitting a pivot for
     * every bar of the plateau. Using strict comparison on both sides would
     * drop plateau pivots entirely; using loose comparison on both sides would
     * emit several pivots at one level, and downstream "is this a new extreme"
     * tests would then depend on which of them happened to be picked.
     */
    private fun isSwingHigh(index: Int, left: Int, right: Int, highAt: (Int) -> Double): Boolean {
        val value = highAt(index)
        if (!value.isFinite()) return false
        for (i in index - left until index) if (highAt(i) > value) return false
        for (i in index + 1..index + right) if (highAt(i) >= value) return false
        return true
    }

    /** Mirror of [isSwingHigh]. */
    private fun isSwingLow(index: Int, left: Int, right: Int, lowAt: (Int) -> Double): Boolean {
        val value = lowAt(index)
        if (!value.isFinite()) return false
        for (i in index - left until index) if (lowAt(i) < value) return false
        for (i in index + 1..index + right) if (lowAt(i) <= value) return false
        return true
    }
}
