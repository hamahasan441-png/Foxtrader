package com.foxtrader.app.domain.usecase.rsireversal

import com.foxtrader.app.domain.model.Timeframe

/**
 * Configuration for the RSI Orderflow Reversal system.
 *
 * Every threshold the strategy specification leaves open is a named parameter
 * here rather than a literal buried in a detector, so a rule can be researched
 * without editing engine code. Defaults reproduce the specification's own
 * default preset (§44).
 */
data class RsiReversalConfig(
    // --- RSI Orderflow candles (§3.2) ---
    val rsiLength: Int = 14,

    // --- Structure (§5.1, §6) ---
    val pricePivotLeft: Int = 2,
    val pricePivotRight: Int = 2,
    val rsiPivotLeft: Int = 2,
    val rsiPivotRight: Int = 2,

    // --- Break semantics (§24) ---
    /** RSI P3 structure break. Specification default is CLOSE_BREAK. */
    val rsiBreakMode: BreakMode = BreakMode.CLOSE_BREAK,
    /** Final price extreme P4/P5/... A wick is enough (§9). */
    val priceExtremeMode: BreakMode = BreakMode.WICK_BREAK,
    /** LTF CHOCH/BOS confirmation. Specification default is CLOSE_BREAK. */
    val ltfBreakMode: BreakMode = BreakMode.CLOSE_BREAK,

    // --- Tolerance (§25) ---
    /** RSI points below which two RSI values are treated as equal. */
    val rsiEpsilon: Double = 0.05,
    /** Price comparison tolerance as a fraction of price (tick-size aware). */
    val priceEpsilonFraction: Double = 0.00002,

    // --- Ambiguities isolated behind flags (§48.4) ---
    /**
     * §7.2 writes `P2.rsiLow >= P1.rsiLow` but calls `>` the "preferred strong
     * form". When true, an RSI low equal to P1's (within [rsiEpsilon]) still
     * counts as RSI failing to confirm the new price low.
     */
    val equalRsiCountsAsFailure: Boolean = true,
    /** §8 "the relevant protected RSI high" — which swing that means. */
    val protectedRsiMode: ProtectedRsiMode = ProtectedRsiMode.HIGHEST,

    // --- Setup expiry (§27) ---
    val maxBarsP1ToP2: Int = 120,
    val maxBarsP2ToP3: Int = 60,
    val maxBarsP3ToFinal: Int = 120,
    /** Recursion is unbounded in principle (§11); this only bounds runaway state. */
    val maxRecursiveExtremes: Int = 24,

    // --- Lower timeframe (§15, §18, §29) ---
    val ltfMapping: Map<Timeframe, Timeframe> = DEFAULT_LTF_MAPPING,
    val entryMode: EntryMode = EntryMode.BALANCED,
    val ltfConfirmationWindowBars: Int = 12,
    val ltfPivotLeft: Int = 2,
    val ltfPivotRight: Int = 2,
    /** Displacement body must exceed this multiple of the recent average body. */
    val displacementBodyMultiple: Double = 1.5,
    val displacementLookback: Int = 20,

    // --- Risk (§19, §20) ---
    val riskReward: Double = 4.0,
    /** Stop buffer as a fraction of price, added beyond the swept extreme. */
    val stopBufferFraction: Double = 0.0,

    // --- Optional noise filters, disabled by default (§26) ---
    val minBarsBetweenPivots: Int = 0,
    val minRsiDivergenceDistance: Double = 0.0,
    val minPriceExtremeDistanceFraction: Double = 0.0,

    /**
     * Explicit warmup, overriding the [warmupBars] default.
     *
     * Exists so component tests can exercise pattern logic on short synthetic
     * series. Production paths leave this null; the repaint protection the
     * default provides is described on [warmupBars].
     */
    val warmupBarsOverride: Int? = null,
) {
    init {
        require(rsiLength >= 2) { "rsiLength must be >= 2" }
        require(pricePivotLeft >= 1 && pricePivotRight >= 1) { "price pivot strength must be >= 1" }
        require(rsiPivotLeft >= 1 && rsiPivotRight >= 1) { "rsi pivot strength must be >= 1" }
        require(ltfPivotLeft >= 1 && ltfPivotRight >= 1) { "ltf pivot strength must be >= 1" }
        require(rsiEpsilon >= 0.0) { "rsiEpsilon must be >= 0" }
        require(priceEpsilonFraction >= 0.0) { "priceEpsilonFraction must be >= 0" }
        require(maxBarsP1ToP2 >= 1) { "maxBarsP1ToP2 must be >= 1" }
        require(maxBarsP2ToP3 >= 1) { "maxBarsP2ToP3 must be >= 1" }
        require(maxBarsP3ToFinal >= 1) { "maxBarsP3ToFinal must be >= 1" }
        require(maxRecursiveExtremes >= 1) { "maxRecursiveExtremes must be >= 1" }
        require(ltfConfirmationWindowBars >= 1) { "ltfConfirmationWindowBars must be >= 1" }
        require(displacementBodyMultiple > 0.0) { "displacementBodyMultiple must be > 0" }
        require(displacementLookback >= 1) { "displacementLookback must be >= 1" }
        require(riskReward > 0.0) { "riskReward must be > 0" }
        require(stopBufferFraction >= 0.0) { "stopBufferFraction must be >= 0" }
        require(minBarsBetweenPivots >= 0) { "minBarsBetweenPivots must be >= 0" }
        require(minRsiDivergenceDistance >= 0.0) { "minRsiDivergenceDistance must be >= 0" }
        require(minPriceExtremeDistanceFraction >= 0.0) { "minPriceExtremeDistanceFraction must be >= 0" }
        require(warmupBarsOverride == null || warmupBarsOverride >= 0) { "warmupBarsOverride must be >= 0" }
    }

    /**
     * Bars that must elapse before a signal may be emitted.
     *
     * Wilder RSI is seeded from the first bar of whatever series it is given.
     * The chart prepends older history at runtime, which perturbs early RSI
     * values; excluding this warmup keeps that perturbation far below
     * [rsiEpsilon] so a confirmed historical arrow cannot repaint after a
     * scroll-back. See RSI_REVERSAL_RULES.md.
     */
    val warmupBars: Int get() = warmupBarsOverride ?: maxOf(rsiLength * 10, MIN_WARMUP_BARS)

    /** Resolve the entry timeframe for a context timeframe (§15). */
    fun entryTimeframe(context: Timeframe): Timeframe? = ltfMapping[context]

    companion object {
        const val MIN_WARMUP_BARS = 200

        /**
         * Specification §15 mapping.
         *
         * The `3m -> 1m` row is absent because [Timeframe] has no M3; adding it
         * reaches providers, Room entities and stored preferences and is
         * deliberately out of scope here rather than faked with a near value.
         */
        val DEFAULT_LTF_MAPPING: Map<Timeframe, Timeframe> = mapOf(
            Timeframe.D1 to Timeframe.H4,
            Timeframe.H4 to Timeframe.H1,
            Timeframe.H1 to Timeframe.M15,
            Timeframe.M30 to Timeframe.M5,
            Timeframe.M15 to Timeframe.M5,
            Timeframe.M5 to Timeframe.M1,
        )
    }
}

/** Intrabar event classes tracked separately (§24). */
enum class BreakMode {
    /** Level reached but not exceeded. */
    TOUCH,

    /** Extreme exceeded the level; the close need not. */
    WICK_BREAK,

    /** The bar closed beyond the level. */
    CLOSE_BREAK,
}

/** Which RSI swing the P3 break must clear (§8). */
enum class ProtectedRsiMode {
    /** The most extreme RSI swing between P1 and P2. */
    HIGHEST,

    /** The last RSI swing formed before P2. */
    MOST_RECENT,
}

/** Lower-timeframe confirmation strictness (§18). */
enum class EntryMode {
    /** Sweep + CHOCH. */
    AGGRESSIVE,

    /** Sweep + CHOCH + displacement. */
    BALANCED,

    /** Sweep + CHOCH + BOS + retest. */
    STRICT,
}
