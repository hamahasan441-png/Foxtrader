package com.foxtrader.app.domain.usecase.crucible

/**
 * Configuration for Crucible — the rule-discovery engine.
 *
 * Crucible searches for market conditions under which an outcome is unusually
 * predictable. Searching is the easy part and the dangerous part: try enough
 * conditions and one of them always looks brilliant. Nearly every setting here
 * exists to make that search survivable rather than to make it wider.
 */
data class CrucibleConfig(
    /** What the discovered rules are asked to predict. */
    val target: CrucibleTarget = CrucibleTarget.DIRECTION,
    val preset: CruciblePreset = CruciblePreset.INTRADAY,

    // --- The prediction problem ---
    /** Bars a rule is given to be right. */
    val horizonBars: Int = 24,
    /** Barrier half-width in ATR multiples, symmetric for direction targets. */
    val barrierAtrMultiple: Double = 1.0,
    /**
     * Barrier used when the target is movement, in ATR multiples.
     *
     * Deliberately much wider than the direction barrier, because the movement
     * question is only a question at the right distance. At one ATR over a
     *24-bar horizon price reaches a barrier about 99% of the time, so "will it
     * move" is answered "yes" before anything is asked and no rule can beat a
     * base rate that high. Widening it until the base rate is genuinely
     * uncertain is what makes the answer worth measuring.
     */
    val movementBarrierAtrMultiple: Double = 2.5,
    val atrPeriod: Int = 14,

    // --- What survives ---
    /** Accuracy a rule must demonstrate out of sample. */
    val minAccuracy: Double = 0.80,
    /** Margin the accuracy must clear above the target's own base rate. */
    val minLiftOverBaseRate: Double = 0.05,
    /** Confidence level for every bound, before correction. */
    val confidence: Double = 0.95,
    /**
     * Effective observations a rule needs before its accuracy is evidence.
     *
     * Effective, not raw: overlapping outcomes are not independent, so a
     * hundred observations that share most of their horizon carry far less
     * information than a hundred separate ones.
     */
    val minEffectiveSample: Double = 25.0,
    /**
     * False discovery rate allowed across the whole search.
     *
     * The alternative — testing each rule at 95% and keeping whatever passes —
     * guarantees false findings in proportion to how many rules were tried.
     */
    val falseDiscoveryRate: Double = 0.05,
    /**
     * Probability of backtest overfitting above which the entire run is
     * declared untrustworthy and nothing is published.
     *
     * This is a check on the search itself rather than on any one rule. When it
     * is high, the best rule found is best because the search was wide, not
     * because the rule is good.
     */
    val maxOverfittingProbability: Double = 0.5,

    // --- Validation ---
    /** Folds the series is split into for out-of-sample evaluation. */
    val folds: Int = 8,
    /**
     * Bars held out either side of a test fold.
     *
     * Outcomes span [horizonBars], so a training observation that starts just
     * before a test fold finishes inside it and has already seen the answer.
     * Purging removes those; the embargo removes the ones close enough to be
     * driven by the same move.
     */
    val embargoBars: Int = 24,

    // --- Search scope ---
    /**
     * Quantile cut-points each feature is split at.
     *
     * Kept coarse deliberately. Finer buckets multiply the rule count, and
     * every rule tested is paid for twice — once in the false discovery
     * correction and once in the overfitting probability, which climbs towards
     * one as a search widens. Resolution here is never free.
     */
    val cutPoints: List<Double> = listOf(0.25, 0.5, 0.75),
    /** Maximum conditions combined into one rule. */
    val maxConditions: Int = 2,
    /** Findings reported, best first. */
    val maxFindings: Int = 12,

    // --- Publication ---
    val historicalSignals: Boolean = true,
    val liveWindowBars: Int = 500,
) {
    /** The barrier this run actually uses, which depends on the question. */
    val effectiveBarrierMultiple: Double
        get() = when (target) {
            CrucibleTarget.DIRECTION -> barrierAtrMultiple
            CrucibleTarget.MOVEMENT -> movementBarrierAtrMultiple
        }

    init {
        require(horizonBars >= 1) { "horizonBars must be >= 1" }
        require(barrierAtrMultiple > 0.0) { "barrierAtrMultiple must be > 0" }
        require(movementBarrierAtrMultiple > 0.0) { "movementBarrierAtrMultiple must be > 0" }
        require(atrPeriod >= 1) { "atrPeriod must be >= 1" }
        require(minAccuracy in 0.0..1.0) { "minAccuracy must be within 0..1" }
        require(minLiftOverBaseRate >= 0.0) { "minLiftOverBaseRate must be >= 0" }
        require(confidence > 0.0 && confidence < 1.0) { "confidence must be within 0..1 exclusive" }
        require(minEffectiveSample > 0.0) { "minEffectiveSample must be > 0" }
        require(falseDiscoveryRate > 0.0 && falseDiscoveryRate < 1.0) { "falseDiscoveryRate must be within 0..1" }
        require(maxOverfittingProbability in 0.0..1.0) { "maxOverfittingProbability must be within 0..1" }
        require(folds >= 4 && folds % 2 == 0) { "folds must be even and >= 4" }
        require(embargoBars >= 0) { "embargoBars must be >= 0" }
        require(cutPoints.isNotEmpty()) { "cutPoints must not be empty" }
        require(maxConditions in 1..3) { "maxConditions must be within 1..3" }
        require(maxFindings >= 1) { "maxFindings must be >= 1" }
        require(liveWindowBars >= 1) { "liveWindowBars must be >= 1" }
    }

    companion object {
        fun scalping(): CrucibleConfig = CrucibleConfig(
            preset = CruciblePreset.SCALPING,
            horizonBars = 8,
            barrierAtrMultiple = 0.6,
            embargoBars = 8,
        )

        fun intraday(): CrucibleConfig = CrucibleConfig(
            preset = CruciblePreset.INTRADAY,
            horizonBars = 24,
            barrierAtrMultiple = 1.0,
            embargoBars = 24,
        )

        fun swing(): CrucibleConfig = CrucibleConfig(
            preset = CruciblePreset.SWING,
            horizonBars = 96,
            barrierAtrMultiple = 2.0,
            embargoBars = 96,
        )

        fun forPreset(preset: CruciblePreset): CrucibleConfig = when (preset) {
            CruciblePreset.SCALPING -> scalping()
            CruciblePreset.INTRADAY -> intraday()
            CruciblePreset.SWING -> swing()
        }
    }
}

/**
 * What a rule is asked to predict.
 *
 * Both are offered because the difference between them is the most useful
 * thing this engine has to say. Direction is close to unpredictable; whether a
 * move of a given size happens at all is not, because volatility clusters.
 * Running the identical search against both makes that contrast a measurement
 * on the trader's own data rather than a claim.
 */
enum class CrucibleTarget(val label: String, val question: String) {
    /** Which side of a symmetric barrier price reaches first. */
    DIRECTION("Direction", "which way price resolves"),

    /** Whether either side of the barrier is reached inside the horizon. */
    MOVEMENT("Movement", "whether price moves at all"),
}

enum class CruciblePreset(val label: String) {
    SCALPING("Scalping"),
    INTRADAY("Intraday"),
    SWING("Swing"),
}
