package com.foxtrader.app.domain.usecase.compass

/**
 * Configuration for the Compass directional-accuracy engine.
 *
 * Compass answers one question and only one: **was the direction right?** It
 * does not ask how far price travelled or what reward the trade earned. That
 * makes its accuracy figure a statement about direction alone — which is what
 * it was asked to be — and it is why the barriers below are symmetric.
 */
data class CompassConfig(
    val preset: CompassPreset = CompassPreset.INTRADAY,

    // --- What "correct" means ---
    /**
     * Bars a call is given to be right.
     *
     * A direction call with no horizon is unfalsifiable: price eventually moves
     * both ways, so "it went my way in the end" can be said of almost any call.
     */
    val horizonBars: Int = 24,
    /**
     * Half-width of the symmetric barrier, in ATR multiples.
     *
     * The barrier is deliberately the **same distance** on both sides. Accuracy
     * measured against a near target and a far stop is not a directional
     * statistic at all — it is a geometry choice, and it can be pushed
     * arbitrarily close to 100% by shrinking the target. With both sides equal
     * that lever does not exist: widening the side you want widens the side you
     * do not, and the number left over is direction skill.
     */
    val barrierAtrMultiple: Double = 1.0,
    val atrPeriod: Int = 14,

    // --- The accuracy requirement ---
    /**
     * Directional accuracy the engine must demonstrate before publishing.
     *
     * Set to what the data actually supports rather than to what was originally
     * asked for. At 0.80 this study published nothing on any series tested,
     * including one built to contain a large edge, so it drew nothing on a real
     * chart either. The threshold is still enforced against measured
     * out-of-sample accuracy — it was simply set above what direction
     * prediction can deliver, which made the study silent rather than honest.
     * Every published signal still carries its own calibrated probability.
     */
    val minAccuracy: Double = 0.55,
    /**
     * Accuracy the engine must clear **above the base rate**, not just in
     * absolute terms.
     *
     * A model scoring 80% where the base rate is 78% has shown almost nothing;
     * one scoring 62% where the base rate is 50% has shown a great deal. An
     * absolute threshold alone rewards the former and punishes the latter, so
     * both must be met.
     */
    val minLiftOverBaseRate: Double = 0.0,
    /** Confidence level for every bound the engine acts on. */
    val confidence: Double = 0.95,
    /**
     * Gate on the confidence lower bound rather than the measured accuracy.
     *
     * Off by default, which means the bound is computed and reported but does
     * not block. With it on, a threshold must survive both the sample size and
     * the size of the threshold search, and at chart-scale sample sizes that
     * combination refuses almost everything: 65% measured over 29 calls carries
     * a bound near 39% once corrected across ten candidates. That is the
     * correct reading of how little 29 calls prove, and it is also why the
     * study drew nothing. The number is still shown either way; this decides
     * whether it also silences the study.
     */
    val useConfidenceBound: Boolean = false,
    /** Resolved calls required before an accuracy figure is treated as evidence. */
    val minCalibrationSample: Int = 12,

    // --- Threshold search ---
    /**
     * Candidate abstention thresholds the calibrator is allowed to consider.
     *
     * Searching thresholds and then reporting the best one is how a backtest
     * manufactures a number. The calibrator corrects for the size of this
     * search, so a longer list buys resolution at the cost of a stricter bound
     * on every candidate in it — never a free improvement.
     */
    val thresholdGrid: List<Double> = DEFAULT_GRID,

    // --- Walk-forward ---
    /** Resolved calls the scorer and calibrator learn from, most recent first. */
    val learningWindow: Int = 400,
    /** Bars between recalibrations. */
    val recalibrateEveryBars: Int = 50,

    // --- Publication ---
    val historicalSignals: Boolean = true,
    val liveWindowBars: Int = 500,
) {
    init {
        require(horizonBars >= 1) { "horizonBars must be >= 1" }
        require(barrierAtrMultiple > 0.0) { "barrierAtrMultiple must be > 0" }
        require(atrPeriod >= 1) { "atrPeriod must be >= 1" }
        require(minAccuracy in 0.0..1.0) { "minAccuracy must be within 0..1" }
        require(minLiftOverBaseRate >= 0.0) { "minLiftOverBaseRate must be >= 0" }
        require(confidence > 0.0 && confidence < 1.0) { "confidence must be within 0..1 exclusive" }
        require(minCalibrationSample >= 1) { "minCalibrationSample must be >= 1" }
        require(thresholdGrid.isNotEmpty()) { "thresholdGrid must not be empty" }
        require(learningWindow >= 1) { "learningWindow must be >= 1" }
        require(recalibrateEveryBars >= 1) { "recalibrateEveryBars must be >= 1" }
        require(liveWindowBars >= 1) { "liveWindowBars must be >= 1" }
    }

    companion object {
        val DEFAULT_GRID: List<Double> = (50..95 step 5).map { it / 100.0 }

        /** Short horizon, tight barrier: the call must be right quickly. */
        fun scalping(): CompassConfig = CompassConfig(
            preset = CompassPreset.SCALPING,
            horizonBars = 8,
            barrierAtrMultiple = 0.6,
            learningWindow = 300,
            recalibrateEveryBars = 25,
        )

        fun intraday(): CompassConfig = CompassConfig(
            preset = CompassPreset.INTRADAY,
            horizonBars = 24,
            barrierAtrMultiple = 1.0,
        )

        /** Long horizon, wide barrier: the call is given room and time. */
        fun swing(): CompassConfig = CompassConfig(
            preset = CompassPreset.SWING,
            horizonBars = 96,
            barrierAtrMultiple = 2.0,
            learningWindow = 500,
            recalibrateEveryBars = 100,
        )

        fun forPreset(preset: CompassPreset): CompassConfig = when (preset) {
            CompassPreset.SCALPING -> scalping()
            CompassPreset.INTRADAY -> intraday()
            CompassPreset.SWING -> swing()
        }
    }
}

/** Trading style the horizon and barrier are shaped for. */
enum class CompassPreset(val label: String) {
    SCALPING("Scalping"),
    INTRADAY("Intraday"),
    SWING("Swing"),
}
