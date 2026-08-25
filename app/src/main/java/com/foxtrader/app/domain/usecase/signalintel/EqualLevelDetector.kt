package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Equal-high / equal-low cluster detection.
 *
 * The May Madness framework treats an **EQH/EQL cluster** as a distinct and
 * stronger form of inducement than a single swing point. The reasoning is about
 * where resting orders accumulate: one swing high leaves one shelf of stops
 * above it, but two or three highs printed at effectively the same price leave a
 * visibly flat ceiling that draws in breakout entries and stacks their stops in
 * one place. That is the pool price is actually reaching for.
 *
 * [LitProStructureDetector.findInducement] previously recognised only the
 * single-swing case — it swept the most recent qualifying swing and stopped
 * there. A flat two- or three-touch shelf slightly *above* that swing was
 * invisible to it, so the engine could anchor its inducement on the smaller pool
 * and treat the sweep of the real one as noise.
 *
 * This detector is deliberately separate from the structure detector: it is a
 * pure geometric primitive over candles, it has no opinion about trend or
 * sequence, and it is independently testable.
 *
 * ## Causality
 *
 * A cluster is reported with `confirmationIndex` set to the **last** touch in
 * it — the bar on which the cluster's existence first becomes knowable. Nothing
 * here reads a bar after that index. Callers must respect the confirmation
 * index rather than the origin index when deciding what was knowable when.
 */
// @Inject/@Singleton because LitProStructureDetector takes this in its
// @Inject constructor. Hilt resolves every constructor parameter from the
// graph and does not honour Kotlin default values, so a plain class here
// would fail at component build time even though the default keeps the
// hand-constructed call sites (LitEngine, tests) compiling.
@Singleton
class EqualLevelDetector @Inject constructor() {

    /**
     * @param touchIndices bar indices of every touch, ascending.
     * @param level representative price of the shelf (mean of the touches).
     * @param confirmationIndex last touch — first bar the cluster is knowable.
     */
    data class EqualLevelCluster(
        val direction: Direction,
        val level: Double,
        val touchIndices: List<Int>,
        val confirmationIndex: Int,
    ) {
        val touchCount: Int get() = touchIndices.size
        val firstIndex: Int get() = touchIndices.first()

        /** Bars spanned from first to last touch. */
        val spanBars: Int get() = touchIndices.last() - touchIndices.first()
    }

    /**
     * Find equal-high (BEARISH-side liquidity) or equal-low (BULLISH-side)
     * clusters among [pivots].
     *
     * @param pivots indices of confirmed swing pivots of the matching kind,
     *   ascending. Callers pass their own pivots so this detector never has to
     *   duplicate — or diverge from — the structure detector's swing rules.
     * @param tolerance maximum price distance between two touches for them to
     *   count as "equal". Callers should derive this from volatility (ATR),
     *   not from a fixed pip value, so it scales across instruments.
     * @param minTouches minimum touches to qualify as a shelf. Two is the
     *   meaningful floor: a single pivot is an ordinary swing, not an EQH/EQL.
     */
    fun detect(
        candles: List<Candle>,
        pivots: List<Int>,
        direction: Direction,
        tolerance: Double,
        minTouches: Int = DEFAULT_MIN_TOUCHES,
        maxSpanBars: Int = DEFAULT_MAX_SPAN_BARS,
    ): List<EqualLevelCluster> {
        if (tolerance <= 0.0 || minTouches < 2) return emptyList()
        val valid = pivots.filter { it in candles.indices }.sorted()
        if (valid.size < minTouches) return emptyList()

        fun priceAt(index: Int): Double = when (direction) {
            Direction.BEARISH -> candles[index].high
            Direction.BULLISH -> candles[index].low
        }

        val clusters = mutableListOf<EqualLevelCluster>()
        var start = 0
        while (start <= valid.size - minTouches) {
            val anchor = valid[start]
            val anchorPrice = priceAt(anchor)
            val members = mutableListOf(anchor)

            for (next in start + 1 until valid.size) {
                val index = valid[next]
                if (index - anchor > maxSpanBars) break
                // Compare against the anchor rather than the running mean so a
                // slow drift cannot walk the shelf away from where it started.
                if (abs(priceAt(index) - anchorPrice) <= tolerance) members += index
            }

            if (members.size >= minTouches) {
                clusters += EqualLevelCluster(
                    direction = direction,
                    level = members.map(::priceAt).average(),
                    touchIndices = members.toList(),
                    confirmationIndex = members.last(),
                )
                // Advance past this shelf so overlapping subsets of the same
                // touches are not reported as separate clusters.
                start = valid.indexOf(members.last()) + 1
            } else {
                start++
            }
        }
        return clusters
    }

    /**
     * The cluster most likely to be the operative inducement pool ahead of an
     * event at [beforeIndex]: the most recent one fully knowable by then,
     * breaking ties toward more touches.
     *
     * Returns null rather than falling back to a single swing — the caller owns
     * that fallback, so the absence of a shelf stays visible instead of being
     * silently papered over here.
     */
    fun mostRecentBefore(
        clusters: List<EqualLevelCluster>,
        beforeIndex: Int,
    ): EqualLevelCluster? = clusters
        .filter { it.confirmationIndex < beforeIndex }
        .maxWithOrNull(
            compareBy<EqualLevelCluster> { it.confirmationIndex }.thenBy { it.touchCount },
        )

    companion object {
        const val DEFAULT_MIN_TOUCHES = 2

        /**
         * Touches further apart than this are not one shelf. 60 bars is wide
         * enough to catch a session-scale range and narrow enough that two
         * unrelated highs a week apart are not fused into a fictional pool.
         */
        const val DEFAULT_MAX_SPAN_BARS = 60

        /**
         * Default tolerance as a fraction of ATR. A shelf is "equal" when the
         * touches sit within a fifth of a typical bar's range — tight enough to
         * look flat on the chart, loose enough to survive spread noise.
         */
        const val DEFAULT_TOLERANCE_ATR_FRACTION = 0.20
    }
}
