package com.foxtrader.app.domain.usecase.keystone

import com.foxtrader.app.domain.model.Timeframe

/**
 * Configuration for the Keystone liquidity-sweep engine.
 *
 * Keystone trades one sequence and refuses everything else: a sweep of known
 * resting liquidity, a divergence against a correlated market at that sweep, a
 * closed displacement candle breaking internal structure, and an entry on the
 * first retracement into what that displacement left behind.
 *
 * The parameters below are grouped by the step they belong to. Two of them
 * carry most of the engine's character:
 *
 * - [minRewardMultiple] is a floor on geometry, not an aspiration. A setup that
 *   cannot reach it is dropped rather than taken at worse terms, because the
 *   thing being optimised here is expectancy, and expectancy is the product of
 *   how often and how much — not of how often alone.
 * - [maxDailyLosses] stops the engine after a bad run rather than letting it
 *   keep paying for the same wrong read. It is the only rule here that looks at
 *   the account rather than the chart.
 */
data class KeystoneConfig(
    val preset: KeystonePreset = KeystonePreset.INTRADAY,

    // --- Step 1: bias ---
    /**
     * How the directional read is established.
     *
     * The specification asks for confirmed higher-timeframe structure *and*
     * session direction. Both are demanded by default; relaxing this is a
     * research setting, not a trading one.
     */
    val biasMode: KeystoneBiasMode = KeystoneBiasMode.HTF_AND_MTF_AGREE,
    /** Swing definition used on the two timeframes above the execution series. */
    val htfSwingLeft: Int = 3,
    val htfSwingRight: Int = 3,
    val mtfSwingLeft: Int = 2,
    val mtfSwingRight: Int = 2,
    /**
     * Require the sweep to point the same way as the session's own direction.
     *
     * The session direction is measured from where the session opened to where
     * price stands, so it is knowable at the bar it is used on.
     */
    val requireSessionAlignment: Boolean = true,

    // --- Step 2: liquidity ---
    /** Which pools of resting liquidity count as a valid location. */
    val liquiditySources: Set<KeystoneLiquiditySource> = KeystoneLiquiditySource.entries.toSet(),
    /** Swing definition for the major-swing pool. */
    val swingLeft: Int = 4,
    val swingRight: Int = 4,
    /** Bars a marked pool stays eligible before it is treated as stale. */
    val maxPoolAgeBars: Int = 480,
    /**
     * How far beyond the pool the wick must reach, in ATR multiples.
     *
     * Zero would make every touch a sweep. The point of the number is that
     * liquidity has to actually be taken — price must trade through the level,
     * not stop at it.
     */
    val minSweepPenetrationAtr: Double = 0.05,
    /** Pools within this fraction of price are treated as the same shelf. */
    val poolClusterFraction: Double = 0.0004,

    // --- Step 3: SMT ---
    /**
     * Require a divergence against a correlated market at the sweep.
     *
     * On by default: it is the filter that separates this model from an
     * ordinary sweep-and-reclaim, and it is the one that cannot be produced by
     * the primary series alone. When no trustworthy peer data is available the
     * engine reports that it stood down rather than quietly dropping the
     * requirement.
     */
    val requireSmt: Boolean = true,
    /** Bars either side of the sweep in which the divergence must confirm. */
    val smtWindowBars: Int = 12,
    /** Swing definition used on both legs of the divergence. */
    val smtSwingLookback: Int = 3,
    /** Correlation the peer must show over [smtCorrelationPeriod] bars. */
    val minPeerCorrelation: Double = 0.45,
    val smtCorrelationPeriod: Int = 160,
    /** Divergence separation required, in average-range units. */
    val minDivergenceStrength: Double = 0.05,
    /** Peer bars may differ from the primary bar by at most this fraction of the interval. */
    val maxTimestampSkewFraction: Double = 0.25,

    // --- Step 4: confirmation ---
    /** Body size a displacement candle must reach, in ATR multiples. */
    val displacementAtrMultiple: Double = 1.2,
    /** Share of the candle's range its body must occupy. */
    val displacementBodyRatio: Double = 0.55,
    /** Bars after the sweep in which displacement must appear. */
    val maxSweepToDisplacementBars: Int = 20,
    /**
     * Require the displacement to close beyond the last internal swing.
     *
     * A large candle on its own is volatility. A large candle that takes out
     * the structure standing against it is a break — and the specification asks
     * for the break, not the candle.
     */
    val requireInternalBreak: Boolean = true,

    // --- Step 5: entry ---
    val entryMode: KeystoneEntryMode = KeystoneEntryMode.FVG_THEN_EQUILIBRIUM,
    /** Retracement band used when no fair-value gap is available. */
    val retracementMin: Double = 0.50,
    val retracementMax: Double = 0.62,
    /** Bars the pending entry stays live before the setup is abandoned. */
    val maxEntryWaitBars: Int = 30,

    // --- Steps 6-7: stop and exit ---
    /** Buffer beyond the swept extreme, in ATR multiples. */
    val stopAtrBuffer: Double = 0.25,
    /** Reward the geometry must be able to reach or the setup is dropped. */
    val minRewardMultiple: Double = 1.5,
    /** Reward used when no opposing pool sits far enough away. */
    val defaultRewardMultiple: Double = 2.0,
    /** Prefer an opposing liquidity pool as the target when one qualifies. */
    val targetOpposingLiquidity: Boolean = true,
    /**
     * Move the stop to breakeven only after price has travelled this far.
     *
     * Breakeven is not free. Moved early it converts winners into scratches at
     * the exact noise level the stop was placed outside of, which is why this
     * asks for confirmed continuation rather than any favourable movement.
     */
    val breakevenAfterRMultiple: Double = 1.0,
    /** Bars after which an unresolved trade is abandoned. */
    val maxHoldBars: Int = 240,

    // --- Step 8: filters ---
    /** Sessions in which an entry may be taken. */
    val sessions: Set<KeystoneSession> = setOf(KeystoneSession.LONDON, KeystoneSession.NEW_YORK),
    /** Spread the engine assumes it pays, as a fraction of the entry price. */
    val assumedSpreadFraction: Double = 0.00002,
    /** Spread is unacceptable once it exceeds this share of the trade's risk. */
    val maxSpreadShareOfRisk: Double = 0.10,
    /** Minutes either side of a scheduled high-impact release in which nothing is taken. */
    val newsBlackoutMinutes: Int = 30,
    /** UTC hour:minute pairs treated as scheduled high-impact releases. */
    val newsWindowsUtc: List<KeystoneNewsWindow> = DEFAULT_NEWS_WINDOWS,
    /** ATR must reach this share of its own recent median or the market is too quiet. */
    val volatilityFloorFraction: Double = 0.6,
    val atrPeriod: Int = 14,
    val volatilityMedianWindow: Int = 200,
    /**
     * One signal per liquidity event.
     *
     * A swept shelf produces several qualifying retracements. Taking each of
     * them is not diversification — it is the same idea, sized several times
     * over, and it is how a model with a defensible edge acquires an
     * indefensible drawdown.
     */
    val oneSignalPerLiquidityEvent: Boolean = true,

    // --- Step 9: risk ---
    /** Account risked per trade, in percent. */
    val riskPercent: Double = 0.5,
    /** Losses after which the engine stands down for the rest of the day. */
    val maxDailyLosses: Int = 2,
    /** Trades taken per day before the engine stops looking. */
    val maxDailySignals: Int = 3,

    // --- Step 10: validation costs ---
    /** Commission per unit of notional, charged on entry and exit. */
    val commissionFraction: Double = 0.00002,
    /** Slippage paid against the trade on both fills, as a fraction of price. */
    val slippageFraction: Double = 0.00001,
    /** Bars between the decision and the fill. */
    val latencyBars: Int = 0,
    /** Folds used by the walk-forward and out-of-sample split. */
    val validationFolds: Int = 5,
    /** Monte Carlo reorderings used to bound the drawdown. */
    val monteCarloRuns: Int = 500,
    /** Deterministic seed: a validation whose answer moves per run is not a validation. */
    val monteCarloSeed: Long = 0x5EEDL,

    // --- Step 11: acceptance ---
    /** Profit factor the record must clear. */
    val minProfitFactor: Double = 1.3,
    /** Expectancy the record must clear, in R. */
    val minExpectancyR: Double = 0.0,
    /** Maximum drawdown tolerated, in R. */
    val maxDrawdownR: Double = 12.0,
    /** Trades before the acceptance verdict is treated as evidence rather than noise. */
    val minValidationTrades: Int = 300,
    /**
     * Withhold signals until the acceptance test passes.
     *
     * Off by default. The acceptance test needs 300-500 trades to mean
     * anything, and a chart holds a few thousand bars — so enforcing it on a
     * chart would silence the study for a reason that has nothing to do with
     * the setup in front of the trader. The verdict is computed and reported
     * either way; this decides whether it also blocks.
     */
    val enforceAcceptance: Boolean = false,

    // --- Publication ---
    val historicalSignals: Boolean = true,
    val liveWindowBars: Int = 500,
) {
    init {
        require(htfSwingLeft >= 1 && htfSwingRight >= 1) { "htf swing bars must be >= 1" }
        require(mtfSwingLeft >= 1 && mtfSwingRight >= 1) { "mtf swing bars must be >= 1" }
        require(swingLeft >= 1 && swingRight >= 1) { "swing bars must be >= 1" }
        require(maxPoolAgeBars >= 1) { "maxPoolAgeBars must be >= 1" }
        require(minSweepPenetrationAtr >= 0.0) { "minSweepPenetrationAtr must be >= 0" }
        require(poolClusterFraction >= 0.0) { "poolClusterFraction must be >= 0" }
        require(smtWindowBars >= 1) { "smtWindowBars must be >= 1" }
        require(smtSwingLookback >= 1) { "smtSwingLookback must be >= 1" }
        require(minPeerCorrelation in 0.0..1.0) { "minPeerCorrelation must be within 0..1" }
        require(smtCorrelationPeriod >= 20) { "smtCorrelationPeriod must be >= 20" }
        require(displacementAtrMultiple > 0.0) { "displacementAtrMultiple must be > 0" }
        require(displacementBodyRatio in 0.0..1.0) { "displacementBodyRatio must be within 0..1" }
        require(maxSweepToDisplacementBars >= 1) { "maxSweepToDisplacementBars must be >= 1" }
        require(retracementMin > 0.0 && retracementMax > retracementMin && retracementMax < 1.0) {
            "retracement band must satisfy 0 < min < max < 1"
        }
        require(maxEntryWaitBars >= 1) { "maxEntryWaitBars must be >= 1" }
        require(stopAtrBuffer >= 0.0) { "stopAtrBuffer must be >= 0" }
        require(minRewardMultiple > 0.0) { "minRewardMultiple must be > 0" }
        require(defaultRewardMultiple >= minRewardMultiple) {
            "defaultRewardMultiple must be >= minRewardMultiple"
        }
        require(breakevenAfterRMultiple > 0.0) { "breakevenAfterRMultiple must be > 0" }
        require(maxHoldBars >= 1) { "maxHoldBars must be >= 1" }
        require(assumedSpreadFraction >= 0.0) { "assumedSpreadFraction must be >= 0" }
        require(maxSpreadShareOfRisk > 0.0) { "maxSpreadShareOfRisk must be > 0" }
        require(newsBlackoutMinutes >= 0) { "newsBlackoutMinutes must be >= 0" }
        require(volatilityFloorFraction >= 0.0) { "volatilityFloorFraction must be >= 0" }
        require(atrPeriod >= 1) { "atrPeriod must be >= 1" }
        require(volatilityMedianWindow >= 1) { "volatilityMedianWindow must be >= 1" }
        require(riskPercent > 0.0) { "riskPercent must be > 0" }
        require(maxDailyLosses >= 1) { "maxDailyLosses must be >= 1" }
        require(maxDailySignals >= 1) { "maxDailySignals must be >= 1" }
        require(commissionFraction >= 0.0) { "commissionFraction must be >= 0" }
        require(slippageFraction >= 0.0) { "slippageFraction must be >= 0" }
        require(latencyBars >= 0) { "latencyBars must be >= 0" }
        require(validationFolds >= 2) { "validationFolds must be >= 2" }
        require(monteCarloRuns >= 0) { "monteCarloRuns must be >= 0" }
        require(minProfitFactor > 0.0) { "minProfitFactor must be > 0" }
        require(maxDrawdownR > 0.0) { "maxDrawdownR must be > 0" }
        require(minValidationTrades >= 1) { "minValidationTrades must be >= 1" }
        require(liveWindowBars >= 1) { "liveWindowBars must be >= 1" }
    }

    /** The two timeframes above [execution] that carry the bias. */
    fun timeframesFor(execution: Timeframe): KeystoneTimeframes? = LADDER[execution]

    companion object {
        /**
         * Scheduled high-impact windows, in UTC.
         *
         * This app carries no economic calendar feed, so the blackout is the
         * recurring release times rather than a list of actual events: the US
         * 12:30 block, the 14:00 follow-ups, and the 18:00/19:00 policy slots.
         * That is coarser than a calendar and it is stated as such — it will
         * stand a trade down on a quiet day at 12:30 and it will not know about
         * an unscheduled announcement at 09:15.
         */
        val DEFAULT_NEWS_WINDOWS: List<KeystoneNewsWindow> = listOf(
            KeystoneNewsWindow(12, 30),
            KeystoneNewsWindow(14, 0),
            KeystoneNewsWindow(18, 0),
            KeystoneNewsWindow(19, 0),
        )

        /**
         * Bias ladder: the mid timeframe one step above execution, the higher
         * two steps above.
         *
         * The specification names 1H and 15m, which is that relationship read
         * from a 1-5 minute execution chart. Expressing it as a ladder keeps
         * the same rule true on every chart instead of demanding two fixed
         * timeframes that would sit below the execution series on an H4 chart.
         */
        val LADDER: Map<Timeframe, KeystoneTimeframes> = mapOf(
            Timeframe.M1 to KeystoneTimeframes(Timeframe.M15, Timeframe.H1),
            Timeframe.M5 to KeystoneTimeframes(Timeframe.M15, Timeframe.H1),
            Timeframe.M15 to KeystoneTimeframes(Timeframe.H1, Timeframe.H4),
            Timeframe.M30 to KeystoneTimeframes(Timeframe.H1, Timeframe.H4),
            Timeframe.H1 to KeystoneTimeframes(Timeframe.H4, Timeframe.D1),
            Timeframe.H4 to KeystoneTimeframes(Timeframe.D1, Timeframe.W1),
            Timeframe.D1 to KeystoneTimeframes(Timeframe.W1, Timeframe.MN),
        )

        /**
         * Scalping: a tighter sequence and a smaller target.
         *
         * The reward floor drops to 1.5R rather than staying at 2R. A short
         * hold cannot reach a distant pool often enough to justify the wait,
         * and asking for both is asking the market for something it does not
         * offer at this frequency.
         */
        fun scalping(): KeystoneConfig = KeystoneConfig(
            preset = KeystonePreset.SCALPING,
            swingLeft = 3,
            swingRight = 3,
            maxPoolAgeBars = 240,
            smtWindowBars = 8,
            maxSweepToDisplacementBars = 12,
            maxEntryWaitBars = 18,
            minRewardMultiple = 1.5,
            defaultRewardMultiple = 1.5,
            maxHoldBars = 90,
            maxDailySignals = 5,
        )

        fun intraday(): KeystoneConfig = KeystoneConfig(preset = KeystonePreset.INTRADAY)

        /** Swing: older pools stay live, the sequence is given more room. */
        fun swing(): KeystoneConfig = KeystoneConfig(
            preset = KeystonePreset.SWING,
            swingLeft = 5,
            swingRight = 5,
            maxPoolAgeBars = 900,
            smtWindowBars = 18,
            maxSweepToDisplacementBars = 30,
            maxEntryWaitBars = 45,
            minRewardMultiple = 2.0,
            defaultRewardMultiple = 3.0,
            maxHoldBars = 600,
            maxDailySignals = 2,
            // A swing entry is not a session decision: the sequence spans them.
            sessions = KeystoneSession.entries.toSet(),
            requireSessionAlignment = false,
        )

        fun forPreset(preset: KeystonePreset): KeystoneConfig = when (preset) {
            KeystonePreset.SCALPING -> scalping()
            KeystonePreset.INTRADAY -> intraday()
            KeystonePreset.SWING -> swing()
        }
    }
}

/** The two timeframes above the execution series. */
data class KeystoneTimeframes(val mid: Timeframe, val higher: Timeframe)

/** A recurring scheduled release window, in UTC. */
data class KeystoneNewsWindow(val hourUtc: Int, val minuteUtc: Int) {
    init {
        require(hourUtc in 0..23) { "hourUtc must be within 0..23" }
        require(minuteUtc in 0..59) { "minuteUtc must be within 0..59" }
    }

    /** Minutes from midnight UTC. */
    val minuteOfDay: Int get() = hourUtc * 60 + minuteUtc
}

/** Trading style the defaults are shaped for. */
enum class KeystonePreset(val label: String) {
    SCALPING("Scalping"),
    INTRADAY("Intraday"),
    SWING("Swing"),
}

/** How the directional read of step 1 is established. */
enum class KeystoneBiasMode {
    /** Nothing is filtered; both directions are eligible. Research only. */
    NONE,

    /** The higher timeframe alone decides. */
    HTF_STRUCTURE,

    /** Both timeframes above the execution series must agree. */
    HTF_AND_MTF_AGREE,
}

/** Pools of resting liquidity a sweep may target. */
enum class KeystoneLiquiditySource(val label: String) {
    PREVIOUS_DAY("Previous day high/low"),
    ASIAN_RANGE("Asian session high/low"),
    MAJOR_SWING("Major swing high/low"),
}

/** Where the entry is taken once displacement has confirmed. */
enum class KeystoneEntryMode {
    /** The displacement's fair-value gap, falling back to the 50-62% band. */
    FVG_THEN_EQUILIBRIUM,

    /** The fair-value gap only; no gap means no trade. */
    FVG_ONLY,

    /** The 50-62% band of the impulse only. */
    EQUILIBRIUM_ONLY,
}

/** Sessions an entry may be taken in, by UTC hour. */
enum class KeystoneSession(val label: String, val startHourUtc: Int, val endHourUtc: Int) {
    ASIA("Asia", 0, 7),
    LONDON("London", 7, 12),
    NEW_YORK("New York", 12, 17),
    ;

    fun contains(hourUtc: Int): Boolean = if (startHourUtc < endHourUtc) {
        hourUtc in startHourUtc until endHourUtc
    } else {
        hourUtc >= startHourUtc || hourUtc < endHourUtc
    }
}
