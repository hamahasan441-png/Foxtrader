package com.foxtrader.app.domain.usecase.compass.model

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Timeframe
import kotlin.math.ln
import kotlin.math.sqrt

/** Whether a direction call turned out to be right. */
enum class CompassVerdict {
    /** Price reached the barrier on the called side first. */
    RIGHT,

    /** Price reached the barrier on the opposite side first. */
    WRONG,

    /** Neither barrier was reached inside the horizon. */
    UNDECIDED,

    /** Not enough series left to say. */
    PENDING,
}

/**
 * One primary call, its measured features, and how it turned out.
 *
 * Features are captured **at the call's own bar** from closed data only. A
 * feature computed from anything later would leak the answer into the input,
 * and the resulting accuracy would be a description of the future rather than
 * a prediction of it.
 */
data class CompassCall(
    val source: String,
    val direction: Direction,
    val index: Int,
    val timestamp: Long,
    val price: Double,
    val features: DoubleArray,
    val verdict: CompassVerdict,
    /** Bar the verdict became known, or null while it is still pending. */
    val decidedIndex: Int?,
) {
    val resolved: Boolean get() = verdict == CompassVerdict.RIGHT || verdict == CompassVerdict.WRONG

    override fun equals(other: Any?): Boolean =
        this === other || (other is CompassCall && index == other.index && source == other.source && direction == other.direction)

    override fun hashCode(): Int = 31 * (31 * index + source.hashCode()) + direction.hashCode()
}

/**
 * A directional accuracy figure, always reported next to what it must beat.
 *
 * Accuracy on its own is not interpretable. A model at 80% where the base rate
 * is 78% has demonstrated almost nothing; one at 62% where the base rate is 50%
 * has demonstrated a great deal. Every figure this engine reports therefore
 * carries its base rate and the lift over it.
 */
data class CompassAccuracy(
    val resolved: Int,
    val right: Int,
    /** Right over resolved, or null when nothing has resolved. */
    val accuracy: Double?,
    /**
     * The accuracy the best **constant-direction** rule would have reached on
     * exactly these bars — always-long, or always-short, whichever did better.
     *
     * This is the null that matters for a directional claim. In a market that
     * mostly rose, "always long" scores highly while reading nothing at all, so
     * an accuracy figure that does not clear this number is describing the
     * market's drift rather than any skill.
     */
    val baseRate: Double?,
    /** Lower bound on accuracy at the configured confidence. */
    val accuracyLowerBound: Double?,
) {
    /** How much of the accuracy is skill rather than the base rate. */
    val lift: Double? get() = if (accuracy == null || baseRate == null) null else accuracy - baseRate

    companion object {
        val EMPTY = CompassAccuracy(0, 0, null, null, null)

        /**
         * @param observations each call's direction and how it turned out.
         *
         * Direction is required, not incidental. Accuracy alone cannot be
         * compared to anything: it is the pairing of the call's direction with
         * its verdict that reveals which way the market actually went, and
         * therefore what a rule with no skill would have scored here.
         */
        fun of(
            observations: List<Pair<Direction, CompassVerdict>>,
            confidence: Double = 0.95,
        ): CompassAccuracy {
            val resolved = observations.filter {
                it.second == CompassVerdict.RIGHT || it.second == CompassVerdict.WRONG
            }
            if (resolved.isEmpty()) return EMPTY

            val right = resolved.count { it.second == CompassVerdict.RIGHT }

            // Recover which side moved first from the call and its verdict: a
            // long that was right and a short that was wrong both mean price
            // reached the upper barrier first.
            val upFirst = resolved.count { (direction, verdict) ->
                val bullish = direction == Direction.BULLISH
                val correct = verdict == CompassVerdict.RIGHT
                bullish == correct
            }
            val downFirst = resolved.size - upFirst

            return CompassAccuracy(
                resolved = resolved.size,
                right = right,
                accuracy = right.toDouble() / resolved.size,
                baseRate = maxOf(upFirst, downFirst).toDouble() / resolved.size,
                accuracyLowerBound = wilsonLowerBound(right, resolved.size, confidence),
            )
        }

        /**
         * Wilson score interval, lower bound.
         *
         * Preferred over the normal approximation because that one misbehaves
         * exactly here — small samples and proportions near 1, where it can
         * report bounds above 1.
         */
        fun wilsonLowerBound(right: Int, total: Int, confidence: Double = 0.95): Double? {
            if (total <= 0) return null
            val z = normalQuantile(1.0 - (1.0 - confidence) / 2.0)
            val p = right.toDouble() / total
            val z2 = z * z
            val denominator = 1.0 + z2 / total
            val centre = p + z2 / (2.0 * total)
            val margin = z * sqrt(p * (1.0 - p) / total + z2 / (4.0 * total * total))
            return ((centre - margin) / denominator).coerceIn(0.0, 1.0)
        }

        /**
         * Standard normal quantile (Acklam's rational approximation).
         *
         * Needed because the confidence level is configurable, so a hard-coded
         * 1.96 would silently ignore the setting.
         */
        fun normalQuantile(p: Double): Double {
            if (p <= 0.0) return Double.NEGATIVE_INFINITY
            if (p >= 1.0) return Double.POSITIVE_INFINITY

            val a = doubleArrayOf(-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00)
            val b = doubleArrayOf(-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01)
            val c = doubleArrayOf(-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00)
            val d = doubleArrayOf(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)
            val low = 0.02425

            return when {
                p < low -> {
                    val q = sqrt(-2.0 * ln(p))
                    (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                        ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
                }
                p > 1.0 - low -> -normalQuantile(1.0 - p)
                else -> {
                    val q = p - 0.5
                    val r = q * q
                    (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                        (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0)
                }
            }
        }
    }
}

/**
 * The outcome of a threshold search: which confidence cut-off was selected, and
 * what can honestly be claimed about it.
 */
data class CompassCalibration(
    /** Selected abstention threshold, or null when none could be justified. */
    val threshold: Double?,
    /** Calls at or above the threshold in the calibration window. */
    val selected: Int,
    /** Accuracy of those calls, with its base rate and bound. */
    val accuracy: CompassAccuracy,
    /** Candidate thresholds the search considered — the multiplicity corrected for. */
    val candidatesTested: Int,
    val reason: String,
) {
    val guaranteed: Boolean get() = threshold != null

    companion object {
        fun none(reason: String, candidatesTested: Int = 0) =
            CompassCalibration(null, 0, CompassAccuracy.EMPTY, candidatesTested, reason)
    }
}

/** A published call: one the calibrated threshold admitted. */
data class CompassSignal(
    val symbol: String,
    val timeframe: Timeframe,
    val call: CompassCall,
    /** The scorer's estimated probability that this direction is right. */
    val probability: Double,
    /**
     * Half-width of the barrier this call was judged against.
     *
     * Carried on the signal so the levels a trader is shown are the very ones
     * the accuracy figure was measured on. Reporting accuracy for one distance
     * and drawing another would make the number describe a trade nobody took.
     */
    val barrier: Double,
    /** The calibration in force when it was published. */
    val calibration: CompassCalibration,
    val reasons: List<String>,
) {
    val direction: Direction get() = call.direction

    val index: Int get() = call.index

    val timestamp: Long get() = call.timestamp

    val price: Double get() = call.price

    val key: String get() = "$symbol|${timeframe.label}|${direction.name}|$index"
}

/** Everything the engine produced for one series. */
data class CompassAnalysis(
    val calls: List<CompassCall>,
    val signals: List<CompassSignal>,
    /** Accuracy over every call the primary layer made, published or not. */
    val rawAccuracy: CompassAccuracy,
    /** Accuracy over the calls that were actually published. */
    val publishedAccuracy: CompassAccuracy,
    val calibration: CompassCalibration,
    val statusText: String,
) {
    companion object {
        fun empty(reason: String) = CompassAnalysis(
            calls = emptyList(),
            signals = emptyList(),
            rawAccuracy = CompassAccuracy.EMPTY,
            publishedAccuracy = CompassAccuracy.EMPTY,
            calibration = CompassCalibration.none(reason),
            statusText = reason,
        )
    }
}
