package com.foxtrader.app.domain.usecase.rsireversal

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.rsireversal.model.LtfConfirmationType
import com.foxtrader.app.domain.usecase.rsireversal.model.PivotSeries
import com.foxtrader.app.domain.usecase.rsireversal.model.RsiReversalPivot
import kotlin.math.abs

/**
 * Lower-timeframe entry confirmation (§16–§18).
 *
 * Only runs while a higher-timeframe setup is armed, and only over bars that
 * had not closed before the setup armed — a confirmation may never be found in
 * bars the armed state could not have seen (§28).
 *
 * The three specified sequences share one scan: a liquidity sweep first, then a
 * change of character, then whatever extra evidence the configured entry mode
 * demands. Direction is parameterised exactly as in the HTF engine so SELL is
 * the mirror of BUY rather than a second implementation.
 */
class RsiReversalLtfEngine {

    /** A confirmed lower-timeframe entry. */
    data class Confirmation(
        val type: LtfConfirmationType,
        val entryIndex: Int,
        val entryTimestamp: Long,
        val entry: Double,
        /** The swept extreme the stop sits behind (§19). */
        val sweptExtreme: Double,
        val reasons: List<String>,
    )

    /**
     * Search for a confirmation.
     *
     * @param startIndex first bar eligible to be examined; callers derive it
     *   from the armed timestamp so no future information is consulted.
     */
    fun confirm(
        direction: Direction,
        candles: List<Candle>,
        startIndex: Int,
        config: RsiReversalConfig,
    ): Confirmation? {
        if (candles.isEmpty()) return null
        if (startIndex !in candles.indices) return null

        val bullish = direction == Direction.BULLISH
        val windowEnd = minOf(candles.lastIndex, startIndex + config.ltfConfirmationWindowBars - 1)
        if (windowEnd < startIndex) return null

        val pivots = RsiReversalPivotEngine.detect(
            series = PivotSeries.PRICE,
            size = candles.size,
            left = config.ltfPivotLeft,
            right = config.ltfPivotRight,
            highAt = { candles[it].high },
            lowAt = { candles[it].low },
            timestampAt = { candles[it].timestamp },
        )

        for (sweepBar in startIndex..windowEnd) {
            val swept = sweepAt(bullish, candles, pivots, sweepBar, config) ?: continue
            val protectedLevel = protectedLevel(bullish, pivots, sweepBar) ?: continue

            for (chochBar in (sweepBar + 1)..windowEnd) {
                if (!breaks(bullish, candles[chochBar], protectedLevel, config)) continue

                val reasons = mutableListOf(
                    "LTF liquidity sweep @ ${format(swept)}",
                    "CHOCH ${if (bullish) "above" else "below"} ${format(protectedLevel)}",
                )

                when (config.entryMode) {
                    EntryMode.AGGRESSIVE -> return Confirmation(
                        type = LtfConfirmationType.SWEEP_CHOCH,
                        entryIndex = chochBar,
                        entryTimestamp = candles[chochBar].timestamp,
                        entry = candles[chochBar].close,
                        sweptExtreme = swept,
                        reasons = reasons,
                    )

                    EntryMode.BALANCED -> {
                        // Measured against the bars leading into the sweep, not
                        // against the window start: "impulsive" only means
                        // anything relative to what this market was doing just
                        // before the sweep.
                        val displacement = displacementBetween(
                            candles = candles,
                            from = sweepBar,
                            to = chochBar,
                            averageBody = averageBody(candles, sweepBar, config.displacementLookback),
                            config = config,
                            bullish = bullish,
                        ) ?: continue
                        reasons += "Displacement ${format(displacement)} >= ${config.displacementBodyMultiple}x avg body"
                        return Confirmation(
                            type = LtfConfirmationType.SWEEP_DISPLACEMENT_BOS,
                            entryIndex = chochBar,
                            entryTimestamp = candles[chochBar].timestamp,
                            entry = candles[chochBar].close,
                            sweptExtreme = swept,
                            reasons = reasons,
                        )
                    }

                    EntryMode.STRICT -> {
                        val strict = strictContinuation(
                            bullish = bullish,
                            candles = candles,
                            pivots = pivots,
                            chochBar = chochBar,
                            windowEnd = windowEnd,
                            config = config,
                        ) ?: continue
                        reasons += "BOS beyond ${format(strict.bosLevel)}"
                        reasons += "Retest held @ ${format(candles[strict.retestBar].close)}"
                        return Confirmation(
                            type = LtfConfirmationType.CHOCH_BOS_RETEST,
                            entryIndex = strict.retestBar,
                            entryTimestamp = candles[strict.retestBar].timestamp,
                            entry = candles[strict.retestBar].close,
                            sweptExtreme = swept,
                            reasons = reasons,
                        )
                    }
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------

    /**
     * A liquidity sweep: the bar traded through a prior confirmed swing extreme
     * and closed back on the original side of it.
     *
     * Requiring the close to reclaim the level is what separates a sweep from a
     * plain break — without it every continuation bar would qualify and the
     * "stop hunt" premise of §9 would be lost.
     *
     * Returns the extreme reached, which the stop sits behind.
     */
    private fun sweepAt(
        bullish: Boolean,
        candles: List<Candle>,
        pivots: List<RsiReversalPivot>,
        bar: Int,
        config: RsiReversalConfig,
    ): Double? {
        val candle = candles[bar]
        val level = pivots
            .filter { it.isHigh != bullish && it.confirmedIndex <= bar && it.index < bar }
            .maxByOrNull { it.index }
            ?.value ?: return null

        val eps = abs(level) * config.priceEpsilonFraction
        return if (bullish) {
            if (candle.low < level - eps && candle.close > level) candle.low else null
        } else {
            if (candle.high > level + eps && candle.close < level) candle.high else null
        }
    }

    /** The protected opposing swing a change of character must break. */
    private fun protectedLevel(
        bullish: Boolean,
        pivots: List<RsiReversalPivot>,
        sweepBar: Int,
    ): Double? = pivots
        .filter { it.isHigh == bullish && it.confirmedIndex <= sweepBar && it.index <= sweepBar }
        .maxByOrNull { it.index }
        ?.value

    private fun breaks(
        bullish: Boolean,
        candle: Candle,
        level: Double,
        config: RsiReversalConfig,
    ): Boolean {
        val eps = abs(level) * config.priceEpsilonFraction
        val value = when (config.ltfBreakMode) {
            BreakMode.CLOSE_BREAK -> candle.close
            BreakMode.WICK_BREAK, BreakMode.TOUCH -> if (bullish) candle.high else candle.low
        }
        return if (bullish) value > level + eps else value < level - eps
    }

    /**
     * The largest qualifying displacement body between the sweep and the break,
     * or null when no bar in that leg was impulsive enough.
     */
    private fun displacementBetween(
        candles: List<Candle>,
        from: Int,
        to: Int,
        averageBody: Double,
        config: RsiReversalConfig,
        bullish: Boolean,
    ): Double? {
        if (averageBody <= 0.0) return null
        val threshold = averageBody * config.displacementBodyMultiple
        var best: Double? = null
        // Strictly after the sweep: §16 orders the sequence sweep ->
        // displacement -> BOS. The sweep bar's own body is the rejection that
        // defines the sweep, and letting it double as the displacement would
        // collapse the Balanced preset back into Aggressive.
        for (i in (from + 1)..to) {
            val candle = candles[i]
            if (bullish && candle.close <= candle.open) continue
            if (!bullish && candle.close >= candle.open) continue
            val body = candle.bodySize
            if (body >= threshold && (best == null || body > best)) best = body
        }
        return best
    }

    private data class StrictLegs(val bosLevel: Double, val retestBar: Int)

    /**
     * §18 STRICT: after the change of character, price must break the next
     * opposing swing (BOS) and then return to that level and hold it.
     */
    private fun strictContinuation(
        bullish: Boolean,
        candles: List<Candle>,
        pivots: List<RsiReversalPivot>,
        chochBar: Int,
        windowEnd: Int,
        config: RsiReversalConfig,
    ): StrictLegs? {
        for (bosBar in (chochBar + 1)..windowEnd) {
            val level = pivots
                .filter { it.isHigh == bullish && it.confirmedIndex <= bosBar && it.index in (chochBar + 1) until bosBar }
                .maxByOrNull { it.index }
                ?.value ?: continue
            if (!breaks(bullish, candles[bosBar], level, config)) continue

            for (retestBar in (bosBar + 1)..windowEnd) {
                val candle = candles[retestBar]
                val held = if (bullish) {
                    candle.low <= level && candle.close > level
                } else {
                    candle.high >= level && candle.close < level
                }
                if (held) return StrictLegs(bosLevel = level, retestBar = retestBar)
            }
        }
        return null
    }

    private fun averageBody(candles: List<Candle>, endExclusive: Int, lookback: Int): Double {
        val start = (endExclusive - lookback).coerceAtLeast(0)
        if (endExclusive <= start) return 0.0
        var sum = 0.0
        for (i in start until endExclusive) sum += candles[i].bodySize
        return sum / (endExclusive - start)
    }

    private fun format(value: Double): String = String.format(java.util.Locale.US, "%.5f", value)
}
