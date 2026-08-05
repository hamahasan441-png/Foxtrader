package com.foxtrader.app.domain.usecase.litx

import com.foxtrader.app.domain.model.LitXConfidence
import com.foxtrader.app.domain.model.LitXFactor
import com.foxtrader.app.domain.model.LitXGrade
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * LIT X confidence engine — turns the 11 institutional-quality factors into a
 * single 0-100 score and an A+/A/B/Reject grade.
 *
 * This is LIT X's OWN scorer, deliberately separate from `MasterDecisionEngine`
 * (which uses a different 9-confluence model). Each factor is a 0-100 quality
 * measured by [LitXEngine]; the score is their weighted average.
 */
class LitXConfidenceScorer @Inject constructor() {

    /** The 11 factor scores (each 0..100) produced by the pipeline. */
    data class Inputs(
        val trendAlignment: Int,
        val liquidityQuality: Int,
        val structureQuality: Int,
        val sweepStrength: Int,
        val displacementStrength: Int,
        val poiQuality: Int,
        val retestQuality: Int,
        val volumeConfirmation: Int,
        val volatilityCondition: Int,
        val sessionQuality: Int,
        val riskReward: Int,
    )

    fun score(inputs: Inputs): LitXConfidence {
        val factors = listOf(
            LitXFactor("Trend Alignment", inputs.trendAlignment.clamp(), 1.4),
            LitXFactor("Liquidity Quality", inputs.liquidityQuality.clamp(), 1.2),
            LitXFactor("Structure Quality", inputs.structureQuality.clamp(), 1.3),
            LitXFactor("Sweep Strength", inputs.sweepStrength.clamp(), 1.2),
            LitXFactor("Displacement Strength", inputs.displacementStrength.clamp(), 1.3),
            LitXFactor("POI Quality", inputs.poiQuality.clamp(), 1.2),
            LitXFactor("Retest Quality", inputs.retestQuality.clamp(), 1.0),
            LitXFactor("Volume Confirmation", inputs.volumeConfirmation.clamp(), 0.9),
            LitXFactor("Volatility Condition", inputs.volatilityCondition.clamp(), 0.7),
            LitXFactor("Session Quality", inputs.sessionQuality.clamp(), 0.8),
            LitXFactor("Risk : Reward", inputs.riskReward.clamp(), 1.0),
        )
        val totalWeight = factors.sumOf { it.weight }
        val weighted = factors.sumOf { (it.score / 100.0) * it.weight }
        val score = ((weighted / totalWeight) * 100.0).roundToInt().coerceIn(0, 100)
        return LitXConfidence(score = score, grade = gradeOf(score), factors = factors)
    }

    private fun gradeOf(score: Int): LitXGrade = when {
        score >= A_PLUS_MIN -> LitXGrade.A_PLUS
        score >= A_MIN -> LitXGrade.A
        score >= B_MIN -> LitXGrade.B
        else -> LitXGrade.REJECT
    }

    private fun Int.clamp() = coerceIn(0, 100)

    companion object {
        const val A_PLUS_MIN = 85
        const val A_MIN = 75
        const val B_MIN = 60

        /** True when [grade] is at least as strong as [minGrade] (for filtering). */
        fun meets(grade: LitXGrade, minGrade: LitXGrade): Boolean = grade.rank() >= minGrade.rank()

        private fun LitXGrade.rank(): Int = when (this) {
            LitXGrade.A_PLUS -> 3
            LitXGrade.A -> 2
            LitXGrade.B -> 1
            LitXGrade.REJECT -> 0
        }
    }
}
