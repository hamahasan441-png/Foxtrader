package com.foxtrader.app.domain.usecase.liquiditysweep

import com.foxtrader.app.domain.model.Timeframe

/**
 * Configuration for the Liquidity Sweep multi-timeframe scalping engine.
 *
 * Every rule the methodology leaves to the trader is a named parameter here
 * rather than a literal inside a detector, so the model can be aligned to a
 * specific rule set without editing engine code.
 */
data class LiquiditySweepConfig(
    // --- Timeframe ladder ---
    /**
     * Execution timeframe -> (mid timeframe, higher timeframe).
     *
     * The charted series is the execution timeframe; the two above it are
     * derived from it by resampling, which is the only direction that adds no
     * information the bars did not already carry.
     */
    val ladder: Map<Timeframe, TimeframePair> = DEFAULT_LADDER,

    // --- Step 1: bias ---
    val biasMode: BiasMode = BiasMode.HTF_STRUCTURE,
    val htfSwingLeft: Int = 2,
    val htfSwingRight: Int = 2,
    /** Reject an entry taken against the higher-timeframe premium/discount side. */
    val requireDiscountForLongs: Boolean = true,

    // --- Step 2: key liquidity levels ---
    val levelSources: Set<LevelSource> = setOf(
        LevelSource.MTF_SWING,
        LevelSource.HTF_SWING,
        LevelSource.EQUAL_LEVELS,
    ),
    val mtfSwingLeft: Int = 2,
    val mtfSwingRight: Int = 2,
    /** How many recent levels per side stay eligible to be swept. */
    val maxActiveLevelsPerSide: Int = 6,
    /** Levels within this fraction of price are treated as the same level. */
    val levelClusterFraction: Double = 0.0004,
    /** A level older than this many execution bars is retired. */
    val maxLevelAgeBars: Int = 500,

    // --- Step 3: the sweep ---
    /** The bar must trade beyond the level by at least this fraction of price. */
    val minSweepPenetrationFraction: Double = 0.00002,
    /** Bars allowed for price to close back on the original side of the level. */
    val maxReclaimBars: Int = 3,
    /** Require the reclaim close to clear the level, not merely touch it. */
    val requireCloseReclaim: Boolean = true,

    // --- Step 4: the retest entry ---
    val entryMode: EntryMode = EntryMode.RETEST,
    /** Execution bars the entry may form within, measured from the reclaim. */
    val entryWindowBars: Int = 12,
    /** How close price must come back to the reclaimed level, as a fraction of the sweep leg. */
    val retestDepthFraction: Double = 0.5,
    val ltfSwingLeft: Int = 2,
    val ltfSwingRight: Int = 2,

    // --- Step 5: risk ---
    val stopBufferFraction: Double = 0.0001,
    val targetMode: TargetMode = TargetMode.OPPOSING_LIQUIDITY,
    /** Reward multiple used by [TargetMode.FIXED_R], and as the fallback. */
    val riskReward: Double = 2.0,
    /** A setup whose reward is below this multiple of risk is rejected. */
    val minRiskReward: Double = 1.5,

    // --- Publication ---
    /** Keep previously confirmed arrows rather than only the live window. */
    val historicalSignals: Boolean = true,
    /** Trailing execution bars searched for new setups. */
    val liveWindowBars: Int = 200,

    /**
     * Explicit warmup, overriding [warmupBars].
     *
     * Exists so component tests can work on short synthetic series. Production
     * paths leave this null.
     */
    val warmupBarsOverride: Int? = null,
) {
    init {
        require(htfSwingLeft >= 1 && htfSwingRight >= 1) { "htf swing strength must be >= 1" }
        require(mtfSwingLeft >= 1 && mtfSwingRight >= 1) { "mtf swing strength must be >= 1" }
        require(ltfSwingLeft >= 1 && ltfSwingRight >= 1) { "ltf swing strength must be >= 1" }
        require(maxActiveLevelsPerSide >= 1) { "maxActiveLevelsPerSide must be >= 1" }
        require(levelClusterFraction >= 0.0) { "levelClusterFraction must be >= 0" }
        require(maxLevelAgeBars >= 1) { "maxLevelAgeBars must be >= 1" }
        require(minSweepPenetrationFraction >= 0.0) { "minSweepPenetrationFraction must be >= 0" }
        require(maxReclaimBars >= 1) { "maxReclaimBars must be >= 1" }
        require(entryWindowBars >= 1) { "entryWindowBars must be >= 1" }
        require(retestDepthFraction in 0.0..1.0) { "retestDepthFraction must be within 0..1" }
        require(stopBufferFraction >= 0.0) { "stopBufferFraction must be >= 0" }
        require(riskReward > 0.0) { "riskReward must be > 0" }
        require(minRiskReward > 0.0) { "minRiskReward must be > 0" }
        require(liveWindowBars >= 1) { "liveWindowBars must be >= 1" }
        require(warmupBarsOverride == null || warmupBarsOverride >= 0) { "warmupBarsOverride must be >= 0" }
    }

    /**
     * Execution bars that must elapse before a setup may be published.
     *
     * The higher timeframe needs enough closed buckets to carry structure of
     * its own; below that the engine is correctly silent rather than calling a
     * bias off two bars.
     */
    val warmupBars: Int get() = warmupBarsOverride ?: DEFAULT_WARMUP_BARS

    fun timeframesFor(execution: Timeframe): TimeframePair? = ladder[execution]

    companion object {
        const val DEFAULT_WARMUP_BARS = 120

        /**
         * Scalping-oriented ladder: two steps up from execution.
         *
         * A single step up is usually too close to the execution series to
         * carry an independent bias, and three steps leaves levels so far away
         * that a scalp never reaches them.
         */
        val DEFAULT_LADDER: Map<Timeframe, TimeframePair> = mapOf(
            Timeframe.M1 to TimeframePair(Timeframe.M5, Timeframe.M15),
            Timeframe.M5 to TimeframePair(Timeframe.M15, Timeframe.H1),
            Timeframe.M15 to TimeframePair(Timeframe.H1, Timeframe.H4),
            Timeframe.M30 to TimeframePair(Timeframe.H1, Timeframe.H4),
            Timeframe.H1 to TimeframePair(Timeframe.H4, Timeframe.D1),
            Timeframe.H4 to TimeframePair(Timeframe.D1, Timeframe.W1),
            Timeframe.D1 to TimeframePair(Timeframe.W1, Timeframe.MN),
        )
    }
}

/** The two timeframes above the execution series. */
data class TimeframePair(val mid: Timeframe, val higher: Timeframe)

/** How the directional bias of step 1 is established. */
enum class BiasMode {
    /** Higher-timeframe market structure decides, and nothing trades against it. */
    HTF_STRUCTURE,

    /** Both the higher and the mid timeframe must agree before anything trades. */
    HTF_AND_MTF_AGREE,

    /** No directional filter; both sides are eligible. Research only. */
    NONE,
}

/** Where the key liquidity levels of step 2 come from. */
enum class LevelSource {
    /** Confirmed swing highs and lows on the mid timeframe. */
    MTF_SWING,

    /** Confirmed swing highs and lows on the higher timeframe. */
    HTF_SWING,

    /** Clusters of near-equal highs or lows, where stops pile up. */
    EQUAL_LEVELS,

    /** The prior higher-timeframe bar's high and low. */
    PREVIOUS_HTF_RANGE,
}

/** What the engine waits for after the reclaim before entering. */
enum class EntryMode {
    /** Enter on the reclaim close itself. Earliest and loosest. */
    RECLAIM,

    /** Wait for price to come back toward the reclaimed level and hold. */
    RETEST,

    /** Require a change of character after the reclaim, then the retest. */
    CHOCH_RETEST,
}

/** How the target of step 5 is chosen. */
enum class TargetMode {
    /** The nearest untouched liquidity on the opposite side. */
    OPPOSING_LIQUIDITY,

    /** A fixed multiple of the risk. */
    FIXED_R,
}
