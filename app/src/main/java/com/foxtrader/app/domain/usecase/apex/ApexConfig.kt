package com.foxtrader.app.domain.usecase.apex

/**
 * Configuration for the Apex consensus engine.
 *
 * Apex does not invent a new way to read price. It runs the methodologies this
 * app already implements, publishes only where enough of them independently
 * agree, and then gates that on the engine's own **measured** recent hit rate
 * rather than on a claim about it.
 */
data class ApexConfig(
    val preset: ApexPreset = ApexPreset.INTRADAY,

    // --- Consensus ---
    /** Member methodologies allowed to vote. */
    val members: Set<ApexMember> = ApexMember.entries.toSet(),
    /** Distinct members that must agree before a candidate forms. */
    val minAgreeingMembers: Int = 2,
    /**
     * Bars within which votes count as the same event.
     *
     * Members confirm at different points of one move — a sweep engine on the
     * reclaim, a divergence engine several bars later at its own confirmation.
     * The window has to span that dispersion or agreement is impossible for
     * reasons that have nothing to do with whether they agree.
     */
    val agreementWindowBars: Int = 36,

    // --- The measured precision gate ---
    /**
     * Hit rate the engine's own recent record must show before it will publish.
     *
     * This is enforced against measured outcomes, never asserted. Raising it
     * does not make the method better; it makes the engine quieter, and it goes
     * silent entirely when its recent record cannot support the number.
     */
    val minHitRate: Double = 0.80,
    /**
     * Resolved trades required before a hit rate means anything.
     *
     * Deliberately not small. An 80% threshold judged on a handful of trades is
     * a coin-flip wearing a percentage sign, and the confidence bound below
     * will refuse it anyway; this simply refuses it sooner and more clearly.
     */
    val minResolvedSample: Int = 30,
    /** How many recent resolved trades the rate is measured over. */
    val precisionWindow: Int = 60,
    /**
     * Gate on the 95% Wilson lower bound rather than the raw hit rate.
     *
     * On by default. With it off, a short lucky run clears the threshold; with
     * it on, the record has to be long enough that the rate could not plausibly
     * be luck. This is the difference between a measured claim and a flattering
     * one, so turning it off should be a research choice, not a default.
     */
    val useConfidenceBound: Boolean = true,
    /**
     * What to do before enough trades have resolved to measure anything.
     *
     * Withholding is the default because publishing under a hit-rate threshold
     * that has not yet been measured is exactly the claim this engine exists to
     * avoid making.
     */
    val warmupPolicy: WarmupPolicy = WarmupPolicy.WITHHOLD,

    // --- Trade management ---
    /** Bars after which an unresolved trade is abandoned as expired. */
    val maxHoldBars: Int = 240,
    val targetMode: TargetMode = TargetMode.NEAREST_MEMBER,
    /** Reward multiple used by [TargetMode.FIXED_R]. */
    val rewardMultiple: Double = 1.5,
    /** A candidate whose geometry cannot reach this multiple is dropped. */
    val minRewardMultiple: Double = 0.8,

    // --- Publication ---
    val historicalSignals: Boolean = true,
    val liveWindowBars: Int = 500,
) {
    init {
        require(minAgreeingMembers >= 1) { "minAgreeingMembers must be >= 1" }
        require(agreementWindowBars >= 1) { "agreementWindowBars must be >= 1" }
        require(minHitRate in 0.0..1.0) { "minHitRate must be within 0..1" }
        require(minResolvedSample >= 1) { "minResolvedSample must be >= 1" }
        require(precisionWindow >= 1) { "precisionWindow must be >= 1" }
        require(maxHoldBars >= 1) { "maxHoldBars must be >= 1" }
        require(rewardMultiple > 0.0) { "rewardMultiple must be > 0" }
        require(minRewardMultiple > 0.0) { "minRewardMultiple must be > 0" }
        require(liveWindowBars >= 1) { "liveWindowBars must be >= 1" }
    }

    companion object {
        /**
         * Scalping: fast members only, a tight agreement window, a modest
         * target and a short hold.
         *
         * The reward multiple is deliberately low. A high hit rate and a high
         * reward multiple are not independent choices — asking for both is
         * asking the market for something it does not offer, and the honest way
         * to reach for a high hit rate is to accept a smaller target for it.
         */
        fun scalping(): ApexConfig = ApexConfig(
            preset = ApexPreset.SCALPING,
            members = setOf(
                ApexMember.LIQUIDITY_SWEEP,
                ApexMember.VIRGIN_WICK,
                ApexMember.RSI_ORDERFLOW,
                ApexMember.AMD,
            ),
            minAgreeingMembers = 2,
            agreementWindowBars = 18,
            maxHoldBars = 60,
            rewardMultiple = 1.0,
            minRewardMultiple = 0.5,
        )

        /** Intraday: every member, a wider window, a longer hold. */
        fun intraday(): ApexConfig = ApexConfig(
            preset = ApexPreset.INTRADAY,
            members = ApexMember.entries.toSet(),
            minAgreeingMembers = 2,
            agreementWindowBars = 36,
            maxHoldBars = 240,
            rewardMultiple = 1.5,
        )

        /** Swing: strongest agreement, longest hold, largest target. */
        fun swing(): ApexConfig = ApexConfig(
            preset = ApexPreset.SWING,
            members = ApexMember.entries.toSet(),
            minAgreeingMembers = 3,
            agreementWindowBars = 72,
            maxHoldBars = 720,
            rewardMultiple = 2.0,
        )

        fun forPreset(preset: ApexPreset): ApexConfig = when (preset) {
            ApexPreset.SCALPING -> scalping()
            ApexPreset.INTRADAY -> intraday()
            ApexPreset.SWING -> swing()
        }
    }
}

/** Trading style the defaults are shaped for. */
enum class ApexPreset(val label: String) {
    SCALPING("Scalping"),
    INTRADAY("Intraday"),
    SWING("Swing"),
}

/** The methodologies allowed to vote. */
enum class ApexMember(val label: String) {
    LIQUIDITY_SWEEP("Liquidity Sweep"),
    VIRGIN_WICK("Virgin Wick"),
    RSI_ORDERFLOW("RSI Orderflow"),
    PIVOT_SWEEP_DIVERGENCE("Pivot Sweep Divergence"),
    VALUE_AREA_REJECTION("Value Area Rejection"),
    AMD("AMD"),
}

/** What to do before the record can support a hit-rate claim. */
enum class WarmupPolicy {
    /** Publish nothing until the sample exists. */
    WITHHOLD,

    /** Publish, marked as unmeasured. */
    PUBLISH_UNMEASURED,
}

/** Where the target comes from. */
enum class TargetMode {
    /** The nearest target among the agreeing members. */
    NEAREST_MEMBER,

    /** A fixed multiple of the consensus risk. */
    FIXED_R,
}
