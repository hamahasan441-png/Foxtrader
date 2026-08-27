package com.foxtrader.app.domain.usecase.compass

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.compass.model.CompassVerdict
import kotlin.math.abs
import kotlin.math.max

/**
 * Decides whether a direction call was right.
 *
 * This is the most important file in the engine, because an accuracy figure is
 * only worth as much as the definition behind it, and most published accuracy
 * numbers fail here rather than in the model.
 *
 * The rule: from the call's bar, price must reach a barrier **equally far on
 * both sides**. Reaching the called side first is right; the opposite side
 * first is wrong; neither inside the horizon is undecided and counts as
 * neither.
 *
 * Two ways of defining it were rejected on purpose.
 *
 * *"Did price ever move my way?"* — one-sided, and true of nearly every call,
 * because price wanders both ways given time. It produces accuracy above 90%
 * for a coin flip.
 *
 * *"Did it hit a near target before a far stop?"* — this is the reward-ratio
 * question wearing an accuracy label. It can be driven arbitrarily close to
 * 100% by moving the target closer, while the strategy loses more money with
 * every step. Symmetric barriers remove that lever entirely: widening the side
 * you want widens the side you do not, so what is left is direction.
 */
object CompassLabeler {

    /**
     * Judge a call.
     *
     * When one bar contains both barriers the verdict is **wrong**. Bar data
     * cannot say which came first, and resolving the ambiguity in the call's
     * favour is how a measured accuracy quietly becomes an advertised one.
     */
    fun judge(
        candles: List<Candle>,
        index: Int,
        direction: Direction,
        barrier: Double,
        horizonBars: Int,
    ): Pair<CompassVerdict, Int?> {
        if (index !in candles.indices) return CompassVerdict.PENDING to null
        if (!barrier.isFinite() || barrier <= 0.0) return CompassVerdict.UNDECIDED to index

        val reference = candles[index].close
        if (!reference.isFinite() || reference <= 0.0) return CompassVerdict.UNDECIDED to index

        val bullish = direction == Direction.BULLISH
        val upper = reference + barrier
        val lower = reference - barrier
        val last = minOf(candles.lastIndex, index + horizonBars)

        for (i in (index + 1)..last) {
            val bar = candles[i]
            val touchedUpper = bar.high >= upper
            val touchedLower = bar.low <= lower
            if (!touchedUpper && !touchedLower) continue

            val correctSideTouched = if (bullish) touchedUpper else touchedLower
            val wrongSideTouched = if (bullish) touchedLower else touchedUpper
            // Both in one bar: unknowable order, so it counts against the call.
            return if (wrongSideTouched) {
                CompassVerdict.WRONG to i
            } else if (correctSideTouched) {
                CompassVerdict.RIGHT to i
            } else {
                continue
            }
        }

        // Out of horizon and out of series are different states: one is a real
        // verdict of "no move", the other simply is not known yet.
        return if (last >= index + horizonBars) {
            CompassVerdict.UNDECIDED to last
        } else {
            CompassVerdict.PENDING to null
        }
    }

    /**
     * Average true range at [index], computed from closed bars only.
     *
     * The barrier is scaled by volatility so that "the same distance" means the
     * same thing in a quiet market and a violent one. A fixed price distance
     * would make the engine look accurate purely because volatility fell.
     */
    fun atrAt(candles: List<Candle>, index: Int, period: Int): Double {
        if (index !in candles.indices || index < 1) return 0.0
        val from = max(1, index - period + 1)
        var sum = 0.0
        var count = 0
        for (i in from..index) {
            val previousClose = candles[i - 1].close
            val bar = candles[i]
            val range = maxOf(
                bar.high - bar.low,
                abs(bar.high - previousClose),
                abs(bar.low - previousClose),
            )
            if (range.isFinite() && range >= 0.0) {
                sum += range
                count++
            }
        }
        return if (count == 0) 0.0 else sum / count
    }
}
