package com.foxtrader.app.domain.usecase.virginwick

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.usecase.mtf.MultiTimeframeSeries
import com.foxtrader.app.domain.usecase.virginwick.model.VirginWick
import com.foxtrader.app.domain.usecase.virginwick.model.WickPoi
import com.foxtrader.app.domain.usecase.virginwick.model.WickSide
import kotlin.math.abs

/**
 * Steps 1 and 2 — finding untested wicks and promoting them to points of interest.
 *
 * A wick is the region between a bar's body edge and its extreme. It is
 * **virgin** while no later bar has traded back into that region: price reached
 * there once, was rejected, and has not returned. That unfinished business is
 * the whole premise — the market tends to come back for it.
 *
 * The wick becomes a **point of interest** once the context timeframe closes
 * beyond it, which is what confirms the market genuinely left it behind rather
 * than still working around it.
 *
 * Both events are dated in execution time: a context wick is only knowable once
 * the context bar that formed it has closed, and the activation only once the
 * closing bar has. Nothing here can be acted on earlier than that.
 */
class VirginWickDetector {

    /**
     * Virgin wicks as they stand at [asOfContextIndex].
     *
     * Virginity is evaluated against the context bars up to that point only, so
     * asking the question at an earlier bar gives the answer that was true then
     * — a wick tested later is still virgin in the past.
     */
    fun virginWicks(
        context: MultiTimeframeSeries,
        asOfContextIndex: Int,
        config: VirginWickConfig,
    ): List<VirginWick> {
        val bars = context.candles
        if (asOfContextIndex < 1 || bars.isEmpty()) return emptyList()
        val last = minOf(asOfContextIndex, bars.lastIndex)

        val out = ArrayList<VirginWick>()
        val earliest = (last - config.maxWickAgeBars).coerceAtLeast(0)

        for (i in earliest until last) {
            val bar = bars[i]
            if (!isWellFormed(bar)) continue

            candidate(bar, WickSide.UPPER, i, context, config)
                ?.takeIf { !isTested(bars, it, i + 1, last, config) }
                ?.let { out += it }

            candidate(bar, WickSide.LOWER, i, context, config)
                ?.takeIf { !isTested(bars, it, i + 1, last, config) }
                ?.let { out += it }
        }
        return out
    }

    /**
     * Promote virgin wicks the context timeframe has closed away from.
     *
     * A lower wick needs closes above it and becomes demand; an upper wick
     * needs closes below it and becomes supply.
     */
    fun activate(
        wicks: List<VirginWick>,
        context: MultiTimeframeSeries,
        asOfContextIndex: Int,
        config: VirginWickConfig,
    ): List<WickPoi> {
        val bars = context.candles
        if (bars.isEmpty()) return emptyList()
        val last = minOf(asOfContextIndex, bars.lastIndex)

        val out = ArrayList<WickPoi>()
        for (wick in wicks) {
            val margin = wick.height * config.activationMarginFraction
            var closes = 0
            var activatedAt = -1

            for (i in (wick.contextIndex + 1)..last) {
                val close = bars[i].close
                val beyond = if (wick.side == WickSide.LOWER) {
                    close > wick.proximal + margin
                } else {
                    close < wick.proximal - margin
                }
                if (!beyond) continue
                closes++
                if (closes >= config.closesBeyondToActivate) {
                    activatedAt = context.knownFrom(i)
                    break
                }
            }
            if (activatedAt < 0) continue

            out += WickPoi(wick = wick, activatedAtIndex = activatedAt, activatingCloses = closes)
        }
        return out
    }

    /**
     * The points of interest that are live at [executionIndex].
     *
     * Bounded per side so an old zone far from price cannot crowd out the ones
     * price is actually working toward.
     */
    fun activeAt(
        pois: List<WickPoi>,
        executionIndex: Int,
        config: VirginWickConfig,
    ): List<WickPoi> {
        val eligible = pois.filter {
            it.activatedAtIndex <= executionIndex &&
                executionIndex - it.activatedAtIndex <= config.maxPoiAgeBars
        }
        val demand = eligible.filter { it.wick.side == WickSide.LOWER }
            .sortedByDescending { it.activatedAtIndex }
            .take(config.maxActivePoisPerSide)
        val supply = eligible.filter { it.wick.side == WickSide.UPPER }
            .sortedByDescending { it.activatedAtIndex }
            .take(config.maxActivePoisPerSide)
        return (demand + supply).sortedBy { it.activatedAtIndex }
    }

    // ------------------------------------------------------------------

    private fun candidate(
        bar: Candle,
        side: WickSide,
        contextIndex: Int,
        context: MultiTimeframeSeries,
        config: VirginWickConfig,
    ): VirginWick? {
        val proximal = if (side == WickSide.UPPER) maxOf(bar.open, bar.close) else minOf(bar.open, bar.close)
        val distal = if (side == WickSide.UPPER) bar.high else bar.low
        val height = abs(distal - proximal)
        if (height <= 0.0) return null

        // A wick that is a rounding error on the bar, or on the price, is noise
        // rather than a level someone is defending.
        val range = bar.high - bar.low
        if (range <= 0.0) return null
        if (height / range < config.minWickFractionOfRange) return null
        if (height / abs(bar.close) < config.minWickFractionOfPrice) return null

        return VirginWick(
            side = side,
            timeframe = context.timeframe,
            proximal = proximal,
            distal = distal,
            contextIndex = contextIndex,
            knownFromIndex = context.knownFrom(contextIndex),
            timestamp = bar.timestamp,
        )
    }

    /**
     * Whether any bar in `(from..to)` traded back into the wick far enough to
     * spend it, under the configured reading of "tested".
     */
    private fun isTested(
        bars: List<Candle>,
        wick: VirginWick,
        from: Int,
        to: Int,
        config: VirginWickConfig,
    ): Boolean {
        val threshold = when (config.testMode) {
            WickTestMode.ANY_TOUCH -> wick.proximal
            WickTestMode.MIDPOINT -> (wick.proximal + wick.distal) / 2.0
            WickTestMode.EXTREME -> wick.distal
        }
        for (i in from..to) {
            val reached = if (wick.side == WickSide.UPPER) {
                bars[i].high >= threshold
            } else {
                bars[i].low <= threshold
            }
            if (reached) return true
        }
        return false
    }

    private fun isWellFormed(c: Candle): Boolean =
        c.open.isFinite() && c.high.isFinite() && c.low.isFinite() && c.close.isFinite() &&
            c.high >= c.low && c.close > 0.0
}
