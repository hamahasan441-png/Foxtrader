package com.foxtrader.app.domain.usecase.rsireversal

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.rsireversal.model.PatternPoint
import com.foxtrader.app.domain.usecase.rsireversal.model.RsiCandle
import com.foxtrader.app.domain.usecase.rsireversal.model.RsiReversalPivot
import com.foxtrader.app.domain.usecase.rsireversal.model.RsiReversalSetup
import com.foxtrader.app.domain.usecase.rsireversal.model.RsiReversalState
import com.foxtrader.app.domain.usecase.rsireversal.model.RsiStructureBreak
import kotlin.math.abs

/**
 * Higher-timeframe master pattern state machine (§7–§14).
 *
 * Implements the specification's single governing rule: price makes a new
 * extreme, RSI decides whether that extreme is confirmed continuation or
 * momentum failure. If RSI confirms, the reference moves forward and we wait
 * for the next extreme; if RSI fails to confirm, the setup arms.
 *
 * BUY and SELL are not written twice. Every directional comparison goes through
 * [Ops], so the SELL pattern is the BUY pattern with the comparison operators
 * and the chosen extremes mirrored — which is what §12 requires and what the
 * mirror test asserts.
 *
 * Everything here is a pure function of the closed-bar prefix. Pivots are
 * consumed on their [RsiReversalPivot.confirmedIndex], never on the bar they
 * formed on, so no state transition can depend on a bar that had not closed
 * when it happened.
 */
class RsiReversalHtfEngine {

    /** Scan a series and return every setup that reached ARMED, in order. */
    fun scan(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        rsiCandles: List<RsiCandle>,
        pricePivots: List<RsiReversalPivot>,
        rsiPivots: List<RsiReversalPivot>,
        config: RsiReversalConfig,
    ): List<RsiReversalSetup> {
        if (candles.isEmpty() || rsiCandles.size != candles.size) return emptyList()

        val bullish = runPass(
            ops = Ops(bullish = true),
            symbol = symbol,
            timeframe = timeframe,
            candles = candles,
            rsiCandles = rsiCandles,
            pricePivots = pricePivots,
            rsiPivots = rsiPivots,
            config = config,
        )
        val bearish = runPass(
            ops = Ops(bullish = false),
            symbol = symbol,
            timeframe = timeframe,
            candles = candles,
            rsiCandles = rsiCandles,
            pricePivots = pricePivots,
            rsiPivots = rsiPivots,
            config = config,
        )
        return (bullish + bearish).sortedWith(compareBy({ it.armedIndex }, { it.key }))
    }

    // ------------------------------------------------------------------
    // One directional pass
    // ------------------------------------------------------------------

    private fun runPass(
        ops: Ops,
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        rsiCandles: List<RsiCandle>,
        pricePivots: List<RsiReversalPivot>,
        rsiPivots: List<RsiReversalPivot>,
        config: RsiReversalConfig,
    ): List<RsiReversalSetup> {
        // Only the pivots this direction reacts to. A BUY pattern is built from
        // price swing lows and broken by RSI swing highs; SELL is the mirror.
        val pricePivotsByConfirmation = pricePivots
            .filter { it.isHigh != ops.bullish }
            .groupBy { it.confirmedIndex }
        val protectedRsiPivots = rsiPivots.filter { it.isHigh == ops.bullish }

        val out = ArrayList<RsiReversalSetup>()
        var state = RsiReversalState.IDLE

        var p1: PatternPoint? = null
        var p2: PatternPoint? = null
        var p3: RsiStructureBreak? = null
        var reference: PatternPoint? = null
        // Resolved once when P2 is established rather than rescanned on every
        // bar: the protected swing is a property of the P1..P2 leg, and
        // recomputing it per bar makes the scan quadratic on long histories.
        var protectedLevel: Double? = null
        val recursive = ArrayList<PatternPoint>()

        fun reset() {
            state = RsiReversalState.IDLE
            p1 = null
            p2 = null
            p3 = null
            reference = null
            protectedLevel = null
            recursive.clear()
        }

        /** Restart pattern discovery with [point] as the running extreme reference. */
        fun restartFrom(point: PatternPoint) {
            reset()
            p1 = point.copy(ordinal = 1)
            state = RsiReversalState.FOUND_P1
        }

        for (bar in candles.indices) {
            // --- 1. Expiry checks, before consuming anything new (§27) ---
            when (state) {
                RsiReversalState.FOUND_P1 -> {
                    val anchor = p1
                    if (anchor != null && bar - anchor.index > config.maxBarsP1ToP2) reset()
                }

                RsiReversalState.WAIT_RSI_STRUCTURE_BREAK -> {
                    val anchor = p2
                    if (anchor != null && bar - anchor.index > config.maxBarsP2ToP3) reset()
                }

                RsiReversalState.WAIT_FINAL_PRICE_EXTREME,
                RsiReversalState.WAIT_RECURSIVE_EXTREME,
                -> {
                    val anchor = p3
                    if (anchor != null && bar - anchor.index > config.maxBarsP3ToFinal) reset()
                }

                else -> Unit
            }

            // --- 2. RSI structure break, P3 (§8) ---
            if (state == RsiReversalState.WAIT_RSI_STRUCTURE_BREAK) {
                val level = protectedLevel
                val secondPoint = p2
                if (level != null && secondPoint != null && ops.breaksRsi(rsiCandles[bar], level, config)) {
                    p3 = RsiStructureBreak(
                        index = bar,
                        timestamp = candles[bar].timestamp,
                        brokenLevel = level,
                        rsiValue = ops.rsiBreakValue(rsiCandles[bar], config),
                    )
                    reference = secondPoint
                    state = RsiReversalState.WAIT_FINAL_PRICE_EXTREME
                }
            }

            // --- 3. Newly confirmed price pivots drive everything else ---
            for (pivot in pricePivotsByConfirmation[bar].orEmpty()) {
                val point = PatternPoint(
                    ordinal = 0,
                    index = pivot.index,
                    timestamp = pivot.timestamp,
                    price = pivot.value,
                    rsi = ops.rsiExtreme(rsiCandles[pivot.index]),
                )

                when (state) {
                    RsiReversalState.IDLE -> {
                        p1 = point.copy(ordinal = 1)
                        state = RsiReversalState.FOUND_P1
                    }

                    RsiReversalState.FOUND_P1 -> {
                        val anchor = p1 ?: continue
                        if (!ops.isNewExtreme(point.price, anchor.price, config)) continue
                        if (!separated(anchor.index, point.index, config)) continue

                        if (ops.rsiFailedToConfirm(point.rsi, anchor.rsi, config)) {
                            // Price made a new extreme, RSI did not: P2 (§7.2).
                            p2 = point.copy(ordinal = 2)
                            protectedLevel = protectedRsiLevel(
                                ops = ops,
                                pivots = protectedRsiPivots,
                                p1Index = anchor.index,
                                p2Index = point.index,
                                availableAt = bar,
                                config = config,
                            )
                            state = RsiReversalState.WAIT_RSI_STRUCTURE_BREAK
                        } else {
                            // RSI confirmed the extreme, so it is continuation.
                            // Move the reference forward and keep waiting.
                            restartFrom(point)
                        }
                    }

                    RsiReversalState.WAIT_RSI_STRUCTURE_BREAK -> {
                        // Price extended beyond P2 before RSI broke structure.
                        // The specification does not name this case; it is
                        // resolved by the same governing rule as everywhere
                        // else — RSI decides whether the deeper extreme is
                        // continuation or another failure.
                        val anchor = p2 ?: continue
                        val first = p1 ?: continue
                        if (!ops.isNewExtreme(point.price, anchor.price, config)) continue

                        if (ops.rsiFailedToConfirm(point.rsi, first.rsi, config)) {
                            p2 = point.copy(ordinal = 2)
                            protectedLevel = protectedRsiLevel(
                                ops = ops,
                                pivots = protectedRsiPivots,
                                p1Index = first.index,
                                p2Index = point.index,
                                availableAt = bar,
                                config = config,
                            )
                        } else {
                            restartFrom(point)
                        }
                    }

                    RsiReversalState.WAIT_FINAL_PRICE_EXTREME,
                    RsiReversalState.WAIT_RECURSIVE_EXTREME,
                    -> {
                        val anchor = reference ?: continue
                        val first = p1 ?: continue
                        val second = p2 ?: continue
                        val breakEvent = p3 ?: continue
                        // The extreme must come after the RSI break; a pivot
                        // that merely confirmed late does not qualify.
                        if (point.index <= breakEvent.index) continue
                        if (!ops.isNewExtreme(point.price, anchor.price, config)) continue

                        if (ops.rsiFailedToConfirm(point.rsi, anchor.rsi, config)) {
                            // §10.1 / §11: RSI refused the new extreme — arm.
                            val ordinal = 4 + recursive.size
                            val finalPoint = point.copy(ordinal = ordinal)
                            out += RsiReversalSetup(
                                direction = ops.direction,
                                symbol = symbol,
                                contextTimeframe = timeframe,
                                p1 = first,
                                p2 = second,
                                p3 = breakEvent,
                                finalExtreme = finalPoint,
                                recursiveExtremes = ArrayList(recursive),
                                armedIndex = pivot.confirmedIndex,
                                armedTimestamp = candles[pivot.confirmedIndex].timestamp,
                            )
                            restartFrom(finalPoint)
                        } else {
                            // §10.2 / §11: RSI confirmed — move the reference
                            // and wait for the next extreme, without limit.
                            if (recursive.size >= config.maxRecursiveExtremes) {
                                restartFrom(point)
                            } else {
                                val ordinal = 4 + recursive.size
                                val moved = point.copy(ordinal = ordinal)
                                recursive += moved
                                reference = moved
                                state = RsiReversalState.WAIT_RECURSIVE_EXTREME
                            }
                        }
                    }

                    else -> Unit
                }
            }
        }

        return out.filter { it.armedIndex >= config.warmupBars }
    }

    // ------------------------------------------------------------------
    // Protected RSI swing selection (§8)
    // ------------------------------------------------------------------

    /**
     * The RSI level P3 must break.
     *
     * Preference is the RSI swing formed between P1 and P2, which is the swing
     * the divergence leg actually left behind. When the two price extremes are
     * close enough together that no RSI swing confirmed between them, the most
     * recent RSI swing before P2 is used instead; without that fallback the
     * setup could never define P3 and would always expire.
     *
     * Only pivots already confirmed at [availableAt] are eligible.
     */
    private fun protectedRsiLevel(
        ops: Ops,
        pivots: List<RsiReversalPivot>,
        p1Index: Int,
        p2Index: Int,
        availableAt: Int,
        config: RsiReversalConfig,
    ): Double? {
        // Pivots arrive index-ordered, so the P1..P2 leg is a contiguous slice.
        // Walking backwards from P2 keeps this proportional to the leg rather
        // than to the whole history, which matters on 100k-bar series where
        // this runs once per setup.
        val upperBound = upperBound(pivots, p2Index)

        var best: RsiReversalPivot? = null
        var fallback: RsiReversalPivot? = null
        for (i in upperBound - 1 downTo 0) {
            val pivot = pivots[i]
            if (pivot.confirmedIndex > availableAt) continue
            if (pivot.index < p1Index) {
                if (fallback == null && pivot.index < p2Index) fallback = pivot
                break
            }
            when (config.protectedRsiMode) {
                ProtectedRsiMode.HIGHEST ->
                    if (best == null || ops.rsiSelector(pivot.value) > ops.rsiSelector(best.value)) best = pivot

                ProtectedRsiMode.MOST_RECENT ->
                    if (best == null) best = pivot
            }
        }
        return (best ?: fallback)?.value
    }

    /** First position whose pivot index exceeds [index], over an index-sorted list. */
    private fun upperBound(pivots: List<RsiReversalPivot>, index: Int): Int {
        var low = 0
        var high = pivots.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (pivots[mid].index <= index) low = mid + 1 else high = mid
        }
        return low
    }

    private fun separated(a: Int, b: Int, config: RsiReversalConfig): Boolean =
        abs(b - a) >= config.minBarsBetweenPivots

    // ------------------------------------------------------------------
    // Directional operations — the single place BUY/SELL differ
    // ------------------------------------------------------------------

    private class Ops(val bullish: Boolean) {

        val direction: Direction get() = if (bullish) Direction.BULLISH else Direction.BEARISH

        /** The RSI extreme the pattern tracks: RSI low for BUY, RSI high for SELL. */
        fun rsiExtreme(candle: RsiCandle): Double = if (bullish) candle.low else candle.high

        /** Order [value] so "more extreme" is always "larger" for selection. */
        fun rsiSelector(value: Double): Double = if (bullish) value else -value

        /**
         * Whether [candidate] is a new price extreme beyond [anchor]: strictly
         * lower for BUY, strictly higher for SELL, outside the price epsilon.
         *
         * A wick is enough — these are pivot extremes (low/high), never closes,
         * which is what makes a liquidity sweep qualify (§9).
         */
        fun isNewExtreme(
            candidate: Double,
            anchor: Double,
            config: RsiReversalConfig,
        ): Boolean {
            val eps = priceEpsilon(anchor, config)
            val minDistance = abs(anchor) * config.minPriceExtremeDistanceFraction
            val required = maxOf(eps, minDistance)
            return if (bullish) candidate < anchor - required else candidate > anchor + required
        }

        /**
         * Whether RSI refused to confirm the new price extreme (§7.2, §10).
         *
         * For BUY: price made a lower low, so RSI failing to confirm means the
         * RSI low did **not** go lower. Equality within the epsilon is governed
         * by [RsiReversalConfig.equalRsiCountsAsFailure], because §7.2 writes
         * `>=` while calling `>` the preferred strong form.
         */
        fun rsiFailedToConfirm(
            candidate: Double,
            anchor: Double,
            config: RsiReversalConfig,
        ): Boolean {
            val diff = if (bullish) candidate - anchor else anchor - candidate
            if (abs(diff) <= config.rsiEpsilon) return config.equalRsiCountsAsFailure
            return diff >= config.minRsiDivergenceDistance && diff > 0.0
        }

        /** Whether this bar's RSI candle breaks [level] in the pattern's direction (§8). */
        fun breaksRsi(candle: RsiCandle, level: Double, config: RsiReversalConfig): Boolean {
            val value = rsiBreakValue(candle, config)
            return if (bullish) value > level + config.rsiEpsilon else value < level - config.rsiEpsilon
        }

        /** The RSI value the configured break mode tests against. */
        fun rsiBreakValue(candle: RsiCandle, config: RsiReversalConfig): Double =
            when (config.rsiBreakMode) {
                BreakMode.CLOSE_BREAK -> candle.close
                BreakMode.WICK_BREAK, BreakMode.TOUCH -> if (bullish) candle.high else candle.low
            }

        private fun priceEpsilon(anchor: Double, config: RsiReversalConfig): Double =
            abs(anchor) * config.priceEpsilonFraction
    }
}
