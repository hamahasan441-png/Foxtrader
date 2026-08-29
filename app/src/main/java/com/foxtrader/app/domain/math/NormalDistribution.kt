package com.foxtrader.app.domain.math

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The standard normal CDF and its inverse.
 *
 * Shared rather than duplicated. More than one engine here computes confidence
 * bounds, and two copies of a numerical approximation are two things that can
 * drift apart — at which point the same evidence yields two different bounds
 * depending on which engine asked, and neither is obviously wrong.
 */
object NormalDistribution {

    /** P(Z <= [z]) for a standard normal Z. */
    fun cdf(z: Double): Double {
        if (!z.isFinite()) return if (z > 0) 1.0 else 0.0
        // Abramowitz & Stegun 7.1.26 applied to the error function.
        val sign = if (z < 0) -1.0 else 1.0
        val x = abs(z) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val y = 1.0 - (
            ((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t +
                0.254829592
            ) * t * exp(-x * x)
        return 0.5 * (1.0 + sign * y)
    }

    /** The z for which P(Z <= z) = [p]. Acklam's rational approximation. */
    fun quantile(p: Double): Double {
        if (p <= 0.0) return Double.NEGATIVE_INFINITY
        if (p >= 1.0) return Double.POSITIVE_INFINITY
        val a = doubleArrayOf(
            -3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
            1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00,
        )
        val b = doubleArrayOf(
            -5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
            6.680131188771972e+01, -1.328068155288572e+01,
        )
        val c = doubleArrayOf(
            -7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
            -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00,
        )
        val d = doubleArrayOf(
            7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
            3.754408661907416e+00,
        )
        val low = 0.02425
        return when {
            p < low -> {
                val q = sqrt(-2.0 * ln(p))
                (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
            }
            p > 1.0 - low -> -quantile(1.0 - p)
            else -> {
                val q = p - 0.5
                val r = q * q
                (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                    (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0)
            }
        }
    }
}
