package com.foxtrader.app.domain.usecase.virginwick

import com.foxtrader.app.domain.model.KillZone
import com.foxtrader.app.domain.model.Timeframe

/**
 * Configuration for the Virgin Wick engine.
 *
 * Every rule the methodology leaves to the trader is a named parameter, so the
 * model can be aligned to a specific rule set without editing engine code.
 */
data class VirginWickConfig(
    // --- Timeframe ladder ---
    /**
     * Execution timeframe -> the timeframe virgin wicks are read from.
     *
     * The methodology reads wicks on 1H and executes on 1M. The ladder keeps
     * that ratio wherever it can, because a point of interest only means
     * something if it is meaningfully above the timeframe entering against it.
     */
    val ladder: Map<Timeframe, Timeframe> = DEFAULT_LADDER,

    // --- Step 1: what counts as virgin ---
    /** How much of a wick price must re-enter before it stops being virgin. */
    val testMode: WickTestMode = WickTestMode.ANY_TOUCH,
    /** A wick shorter than this fraction of the bar's range is noise, not a level. */
    val minWickFractionOfRange: Double = 0.20,
    /** A wick shorter than this fraction of price is below the noise floor. */
    val minWickFractionOfPrice: Double = 0.00015,
    /** Wicks older than this many context bars are retired. */
    val maxWickAgeBars: Int = 120,

    // --- Step 2: activation into a point of interest ---
    /**
     * Context bars that must close beyond the wick before it becomes a POI.
     *
     * One close is the methodology's own rule. More than one is available for
     * research; it trades responsiveness for confidence that the market really
     * did leave the wick behind.
     */
    val closesBeyondToActivate: Int = 1,
    /** How far beyond the wick a close must be, as a fraction of the wick's height. */
    val activationMarginFraction: Double = 0.0,
    /** Execution bars a POI stays tradeable after it activates. */
    val maxPoiAgeBars: Int = 2_000,
    /** Most recent POIs kept live per side. */
    val maxActivePoisPerSide: Int = 4,

    // --- Step 3: the return and the confirmation ---
    val entryMode: EntryMode = EntryMode.IFVG,
    /** Execution bars allowed between the POI being reached and the entry. */
    val confirmationWindowBars: Int = 60,
    /**
     * How deep into the POI price must trade before a confirmation counts.
     *
     * Zero means touching the near edge is enough; one means price must reach
     * the wick's extreme.
     */
    val poiEntryDepthFraction: Double = 0.0,
    /** An inversion older than this many execution bars is stale. */
    val maxIfvgAgeBars: Int = 20,

    // --- Step 4: sessions ---
    /** Kill zones entries are allowed in. Empty means no session filter. */
    val sessions: Set<KillZone> = emptySet(),

    // --- Step 5: risk ---
    /** Extra room beyond the stop, as a fraction of price. */
    val stopBufferFraction: Double = 0.0001,
    /** The reward multiple used when no draw on liquidity is usable. */
    val defaultRewardMultiple: Double = 2.0,
    /**
     * Beyond this multiple a draw on liquidity is too far to aim at, and the
     * fixed multiple is used instead.
     */
    val maxDolRewardMultiple: Double = 4.0,
    /** A setup that cannot reach this multiple is rejected rather than taken. */
    val minRewardMultiple: Double = 1.5,

    // --- Publication ---
    val historicalSignals: Boolean = true,
    val liveWindowBars: Int = 500,

    /** Explicit warmup, overriding [warmupBars]. Production leaves this null. */
    val warmupBarsOverride: Int? = null,
) {
    init {
        require(minWickFractionOfRange in 0.0..1.0) { "minWickFractionOfRange must be within 0..1" }
        require(minWickFractionOfPrice >= 0.0) { "minWickFractionOfPrice must be >= 0" }
        require(maxWickAgeBars >= 1) { "maxWickAgeBars must be >= 1" }
        require(closesBeyondToActivate >= 1) { "closesBeyondToActivate must be >= 1" }
        require(activationMarginFraction >= 0.0) { "activationMarginFraction must be >= 0" }
        require(maxPoiAgeBars >= 1) { "maxPoiAgeBars must be >= 1" }
        require(maxActivePoisPerSide >= 1) { "maxActivePoisPerSide must be >= 1" }
        require(confirmationWindowBars >= 1) { "confirmationWindowBars must be >= 1" }
        require(poiEntryDepthFraction in 0.0..1.0) { "poiEntryDepthFraction must be within 0..1" }
        require(maxIfvgAgeBars >= 1) { "maxIfvgAgeBars must be >= 1" }
        require(stopBufferFraction >= 0.0) { "stopBufferFraction must be >= 0" }
        require(defaultRewardMultiple > 0.0) { "defaultRewardMultiple must be > 0" }
        require(maxDolRewardMultiple > 0.0) { "maxDolRewardMultiple must be > 0" }
        require(minRewardMultiple > 0.0) { "minRewardMultiple must be > 0" }
        require(liveWindowBars >= 1) { "liveWindowBars must be >= 1" }
        require(warmupBarsOverride == null || warmupBarsOverride >= 0) { "warmupBarsOverride must be >= 0" }
    }

    /**
     * Execution bars before a setup may be published.
     *
     * The context timeframe needs enough closed bars to have left wicks behind
     * and then move away from them; below that the engine is correctly silent.
     */
    val warmupBars: Int get() = warmupBarsOverride ?: DEFAULT_WARMUP_BARS

    fun contextTimeframeFor(execution: Timeframe): Timeframe? = ladder[execution]

    companion object {
        const val DEFAULT_WARMUP_BARS = 200

        /**
         * The methodology's own 1H context / 1M execution, generalised.
         *
         * Each execution timeframe maps far enough up that a context wick is a
         * level rather than a neighbour.
         */
        val DEFAULT_LADDER: Map<Timeframe, Timeframe> = mapOf(
            Timeframe.M1 to Timeframe.H1,
            Timeframe.M5 to Timeframe.H4,
            Timeframe.M15 to Timeframe.H4,
            Timeframe.M30 to Timeframe.D1,
            Timeframe.H1 to Timeframe.D1,
            Timeframe.H4 to Timeframe.W1,
            Timeframe.D1 to Timeframe.MN,
        )
    }
}

/** How much of a wick price must re-enter before it stops being virgin. */
enum class WickTestMode {
    /** Any trade back into the wick region tests it. The strictest reading. */
    ANY_TOUCH,

    /** The wick survives until price reaches its midpoint. */
    MIDPOINT,

    /** The wick survives until price reaches its extreme. */
    EXTREME,
}

/** What must confirm the return to the point of interest. */
enum class EntryMode {
    /** Price trading into the POI is enough. Earliest and loosest. */
    POI_TOUCH,

    /** An inverted fair value gap must confirm inside the window. */
    IFVG,

    /** The inversion must also form inside the point of interest itself. */
    IFVG_IN_POI,
}
