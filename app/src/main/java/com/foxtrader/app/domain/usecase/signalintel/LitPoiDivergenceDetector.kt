package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import javax.inject.Inject
import kotlin.math.abs

/**
 * RSI divergence confirmation for a LiT POI retest.
 *
 * The structural sequence (IDM → BOS → CHOCH → POI) establishes that price has
 * arrived at a decision point. It does not establish that momentum is failing
 * there, and a POI price simply passes through looks identical to one that
 * reverses until something says otherwise. This is that something: at the
 * retest, the price extreme must be worse than an earlier one while RSI is
 * better — the classic regular divergence.
 *
 * Non-repaint contract:
 * - the retest bar itself is the newest bar considered, never a later one;
 * - the earlier pivot must be a *confirmed* swing, so it required only bars
 *   that had already closed at the retest;
 * - the result is a pure function of the prefix ending at the retest bar.
 *
 * Open so tests can drive [LitEngine]'s gate through a stubbed verdict: the
 * full LiT sequence is selective enough that synthetic series rarely reach a
 * POI retest, and the gate's two outcomes deserve testing regardless of how
 * often price happens to arrive there.
 */
open class LitPoiDivergenceDetector @Inject constructor() {

    /** A confirmed regular divergence supporting a directional entry. */
    data class Divergence(
        val direction: Direction,
        /** Index of the earlier confirmed swing the comparison is against. */
        val fromIndex: Int,
        /** Index of the extreme at or near the retest. */
        val toIndex: Int,
        val fromPrice: Double,
        val toPrice: Double,
        val fromRsi: Double,
        val toRsi: Double,
    ) {
        /** RSI points by which momentum refused to confirm the price extreme. */
        val rsiGap: Double get() = abs(toRsi - fromRsi)
    }

    /**
     * Look for a divergence supporting [direction] at [retestIndex].
     *
     * @param candles bars up to and including the retest; nothing after it is read.
     * @param lookback how far back the earlier pivot may sit.
     */
    open fun detect(
        candles: List<Candle>,
        retestIndex: Int,
        direction: Direction,
        lookback: Int,
        rsiPeriod: Int,
        minRsiGap: Double,
        pivotLeft: Int = DEFAULT_PIVOT,
        pivotRight: Int = DEFAULT_PIVOT,
    ): Divergence? {
        if (retestIndex !in candles.indices) return null
        if (lookback < MIN_LOOKBACK || pivotLeft < 1 || pivotRight < 1) return null

        val prefix = if (retestIndex == candles.lastIndex) candles else candles.subList(0, retestIndex + 1)
        if (prefix.size < rsiPeriod + pivotLeft + pivotRight + 2) return null

        val rsi = TechnicalIndicators.calculateRSI(prefix, rsiPeriod)
        if (rsi.size != prefix.size) return null

        val bullish = direction == Direction.BULLISH
        val last = prefix.lastIndex

        // The extreme the entry is being made at. The retest leg's own extreme
        // is used rather than the retest bar's close, because a POI mitigation
        // routinely wicks past the level and closes back inside it.
        val recentStart = (last - pivotLeft).coerceAtLeast(0)
        val toIndex = (recentStart..last).minByOrNull {
            if (bullish) prefix[it].low else -prefix[it].high
        } ?: return null
        val toPrice = if (bullish) prefix[toIndex].low else prefix[toIndex].high
        val toRsi = rsi[toIndex]
        if (!toRsi.isFinite()) return null

        // Earlier confirmed swings only: a pivot needs pivotRight bars after it,
        // all of which had closed before the retest.
        val earliest = (last - lookback).coerceAtLeast(rsiPeriod + pivotLeft)
        val latestEligible = last - pivotRight - pivotLeft
        if (latestEligible < earliest) return null

        var best: Divergence? = null
        for (i in earliest..latestEligible) {
            if (!isConfirmedExtreme(prefix, i, pivotLeft, pivotRight, bullish)) continue

            val fromPrice = if (bullish) prefix[i].low else prefix[i].high
            val fromRsi = rsi[i]
            if (!fromRsi.isFinite()) continue

            // Price made the worse extreme...
            val priceExtended = if (bullish) toPrice < fromPrice else toPrice > fromPrice
            if (!priceExtended) continue

            // ...and RSI refused to follow it there.
            val rsiGap = if (bullish) toRsi - fromRsi else fromRsi - toRsi
            if (rsiGap < minRsiGap) continue

            // The widest refusal is the strongest evidence, so it wins ties.
            if (best == null || rsiGap > best.rsiGap) {
                best = Divergence(
                    direction = direction,
                    fromIndex = i,
                    toIndex = toIndex,
                    fromPrice = fromPrice,
                    toPrice = toPrice,
                    fromRsi = fromRsi,
                    toRsi = toRsi,
                )
            }
        }
        return best
    }

    /**
     * A confirmed swing extreme.
     *
     * Loose on the left and strict on the right, so an equal-level plateau
     * resolves to exactly one pivot — its last bar — instead of emitting one
     * per bar of the plateau.
     */
    private fun isConfirmedExtreme(
        candles: List<Candle>,
        index: Int,
        left: Int,
        right: Int,
        bullish: Boolean,
    ): Boolean {
        if (index - left < 0 || index + right > candles.lastIndex) return false
        val value = if (bullish) candles[index].low else candles[index].high
        if (!value.isFinite()) return false

        for (i in index - left until index) {
            val other = if (bullish) candles[i].low else candles[i].high
            if (bullish && other < value) return false
            if (!bullish && other > value) return false
        }
        for (i in index + 1..index + right) {
            val other = if (bullish) candles[i].low else candles[i].high
            if (bullish && other <= value) return false
            if (!bullish && other >= value) return false
        }
        return true
    }

    private companion object {
        const val DEFAULT_PIVOT = 2
        const val MIN_LOOKBACK = 5
    }
}
