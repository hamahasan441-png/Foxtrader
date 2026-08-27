package com.foxtrader.app.domain.usecase.compass

import com.foxtrader.app.domain.usecase.compass.model.CompassVerdict
import kotlin.math.exp
import kotlin.math.ln

/**
 * Estimates the probability that a direction call is right.
 *
 * Logistic regression, fitted by gradient descent on resolved calls only. The
 * choice is deliberate on three counts.
 *
 * It is **calibrated by construction**: minimising log loss makes the output a
 * probability rather than a score, and the engine's entire decision rests on
 * that number meaning what it says. A model that ranked well but was
 * systematically overconfident would pass every ranking check and still break
 * the guarantee.
 *
 * It is **deterministic**: no random initialisation, no shuffling, fixed
 * iteration count. The same history always produces the same weights, which is
 * what lets a signal be reproducible and a backtest be believed.
 *
 * It is **small**: eight features and strong regularisation, fitted on a few
 * hundred observations. A larger model on this much data would fit the noise
 * and report wonderful accuracy on the data it had already seen, which is the
 * failure this whole engine is built to avoid.
 */
class CompassScorer private constructor(
    private val weights: DoubleArray,
    val trainedOn: Int,
) {

    /** Probability that a call with these features is right. */
    fun probability(features: DoubleArray): Double {
        if (features.size != weights.size) return 0.5
        var z = 0.0
        for (i in weights.indices) z += weights[i] * features[i]
        return sigmoid(z)
    }

    /** Weight per feature, for explaining a published call. */
    fun weightOf(featureIndex: Int): Double = weights.getOrElse(featureIndex) { 0.0 }

    companion object {
        /** A scorer that has learned nothing and says so. */
        val UNINFORMED = CompassScorer(DoubleArray(CompassFeatures.SIZE), 0)

        private const val ITERATIONS = 400
        private const val LEARNING_RATE = 0.08
        private const val L2 = 0.02

        /**
         * Fit on resolved calls, oldest first.
         *
         * Only RIGHT/WRONG observations are used. An undecided call carries no
         * information about direction — treating it as either outcome would be
         * inventing a label, and treating "no move" as a loss would teach the
         * model to avoid quiet markets rather than wrong ones.
         */
        fun fit(observations: List<Pair<DoubleArray, CompassVerdict>>): CompassScorer {
            val data = observations.filter {
                it.second == CompassVerdict.RIGHT || it.second == CompassVerdict.WRONG
            }.filter { it.first.size == CompassFeatures.SIZE }

            if (data.isEmpty()) return UNINFORMED

            val weights = DoubleArray(CompassFeatures.SIZE)
            // Start the intercept at the observed base rate rather than zero:
            // with few observations, that is already the best honest guess.
            val right = data.count { it.second == CompassVerdict.RIGHT }
            weights[0] = logit((right + 0.5) / (data.size + 1.0))

            val gradient = DoubleArray(CompassFeatures.SIZE)
            repeat(ITERATIONS) {
                java.util.Arrays.fill(gradient, 0.0)
                for ((features, verdict) in data) {
                    val target = if (verdict == CompassVerdict.RIGHT) 1.0 else 0.0
                    var z = 0.0
                    for (i in weights.indices) z += weights[i] * features[i]
                    val error = sigmoid(z) - target
                    for (i in weights.indices) gradient[i] += error * features[i]
                }
                for (i in weights.indices) {
                    // The intercept is not penalised: shrinking it would bias
                    // the base rate itself towards a coin flip.
                    val penalty = if (i == 0) 0.0 else L2 * weights[i]
                    weights[i] -= LEARNING_RATE * (gradient[i] / data.size + penalty)
                    if (!weights[i].isFinite()) weights[i] = 0.0
                }
            }

            return CompassScorer(weights, data.size)
        }

        private fun sigmoid(z: Double): Double = when {
            !z.isFinite() -> 0.5
            z >= 0.0 -> 1.0 / (1.0 + exp(-z.coerceAtMost(40.0)))
            else -> exp(z.coerceAtLeast(-40.0)).let { it / (1.0 + it) }
        }

        private fun logit(p: Double): Double {
            val clamped = p.coerceIn(1e-6, 1.0 - 1e-6)
            return ln(clamped / (1.0 - clamped))
        }
    }
}
