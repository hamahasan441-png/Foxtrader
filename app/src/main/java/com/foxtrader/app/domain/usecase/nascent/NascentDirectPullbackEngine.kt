package com.foxtrader.app.domain.usecase.nascent

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.nascent.model.DirectPullbackState
import com.foxtrader.app.domain.usecase.nascent.model.StructurePoint
import com.foxtrader.app.domain.usecase.nascent.model.StructurePointType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Direct Pullback into the 50% of a delivery range.
 *
 * Nascent lists "Direct Pullback + 50% of the range" as an entry-confirmation
 * path, which makes this one of the better-supported components here. The
 * *structural* reading is what is implemented: a pullback is a retracement leg
 * that reaches the range equilibrium without first destroying the leg that
 * created it — not an equality test against a midpoint price.
 *
 * The equilibrium is treated as a **zone**, not a float. An exact touch of
 * `low + (high - low) / 2` is a measure-zero event on real data, so the zone is
 * widened by an ATR-scaled tolerance from
 * [NascentConfig.equilibriumToleranceAtrMultiple].
 *
 * "Direct" is enforced two ways: the retracement must reach equilibrium inside
 * [NascentConfig.maxDirectPullbackBars], and it must not close beyond the leg's
 * protected extreme on the way (which would make it a reversal, not a pullback).
 */
@Singleton
class NascentDirectPullbackEngine @Inject constructor() {

    fun evaluate(
        candles: List<Candle>,
        alternatingPivots: List<StructurePoint>,
        direction: Direction,
        atIndex: Int,
        atr: Double,
        config: NascentConfig,
    ): DirectPullbackState? {
        if (atIndex !in candles.indices) return null
        val pivots = alternatingPivots.filter { it.confirmationBarIndex <= atIndex }
        if (pivots.size < 2) return null
        val legStart = pivots[pivots.size - 2]
        val legEnd = pivots[pivots.size - 1]

        val legDirection = when {
            legEnd.type == StructurePointType.HIGH && legStart.type == StructurePointType.LOW ->
                Direction.BULLISH
            legEnd.type == StructurePointType.LOW && legStart.type == StructurePointType.HIGH ->
                Direction.BEARISH
            else -> return null
        }
        if (legDirection != direction) return null

        val from = legStart.pivotBarIndex
        val to = legEnd.pivotBarIndex
        if (from >= to || to > candles.lastIndex) return null

        val window = candles.subList(from, to + 1)
        val rangeHigh = window.maxOf { it.high }
        val rangeLow = window.minOf { it.low }
        if (!rangeHigh.isFinite() || !rangeLow.isFinite() || rangeHigh <= rangeLow) return null
        if (atr.isFinite() && atr > 0.0 && (rangeHigh - rangeLow) < atr * config.minLegAtrMultiple) {
            return null
        }

        val equilibrium = rangeLow + (rangeHigh - rangeLow) * 0.5
        val tolerance = if (atr.isFinite() && atr > 0.0) {
            atr * config.equilibriumToleranceAtrMultiple
        } else {
            (rangeHigh - rangeLow) * FALLBACK_EQ_TOLERANCE_FRACTION
        }
        val protectedExtreme = if (direction == Direction.BULLISH) rangeLow else rangeHigh

        val pullbackStart = to + 1
        val deadline = minOf(atIndex, to + config.maxDirectPullbackBars)
        if (pullbackStart > atIndex) {
            return DirectPullbackState(
                direction = direction,
                sourceLegStart = from,
                sourceLegEnd = to,
                rangeHigh = rangeHigh,
                rangeLow = rangeLow,
                equilibrium50 = equilibrium,
                pullbackStart = null,
                pullbackExtreme = null,
                touchedEqZone = false,
                invalidated = false,
                confirmed = false,
                confirmationIndex = null,
            )
        }

        var extreme: Double? = null
        var touchedIndex: Int? = null
        var invalidated = false
        for (index in pullbackStart..deadline) {
            val candle = candles[index]
            val closedBeyond = if (direction == Direction.BULLISH) {
                candle.close < protectedExtreme
            } else {
                candle.close > protectedExtreme
            }
            if (closedBeyond) {
                invalidated = true
                break
            }
            extreme = when (direction) {
                Direction.BULLISH -> minOf(extreme ?: candle.low, candle.low)
                Direction.BEARISH -> maxOf(extreme ?: candle.high, candle.high)
            }
            val reachedZone = when (direction) {
                Direction.BULLISH -> candle.low <= equilibrium + tolerance
                Direction.BEARISH -> candle.high >= equilibrium - tolerance
            }
            if (reachedZone && touchedIndex == null) touchedIndex = index
        }

        return DirectPullbackState(
            direction = direction,
            sourceLegStart = from,
            sourceLegEnd = to,
            rangeHigh = rangeHigh,
            rangeLow = rangeLow,
            equilibrium50 = equilibrium,
            pullbackStart = pullbackStart,
            pullbackExtreme = extreme,
            touchedEqZone = touchedIndex != null,
            invalidated = invalidated,
            confirmed = touchedIndex != null && !invalidated,
            confirmationIndex = touchedIndex,
        )
    }

    private companion object {
        /** Used only when ATR is unavailable (very short series). */
        const val FALLBACK_EQ_TOLERANCE_FRACTION = 0.05
    }
}
