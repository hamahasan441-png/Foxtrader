package com.foxtrader.app.domain.usecase.scanner

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.ScannerRiskLevel
import com.foxtrader.app.domain.model.ScreenerResult
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import com.foxtrader.app.domain.usecase.strategies.StrategyExternalContextAnalyzer
import com.foxtrader.app.domain.usecase.strategies.StrategyMarketContext
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Maps canonical external strategy context onto scanner-specific fields.
 *
 * SMT and HTF detection happen exactly once in [StrategyExternalContextAnalyzer].
 * This class only applies scanner ranking, actionability and adaptive-risk policy
 * to that result; it contains no second indicator/SMT implementation.
 */
class ScannerContextEnricher @Inject constructor(
    private val externalAnalyzer: StrategyExternalContextAnalyzer = StrategyExternalContextAnalyzer(
        smtDetector = SmtDivergenceDetector(),
        analyzeStructure = AnalyzeMarketStructureUseCase(),
    ),
) {

    fun enrich(
        base: ScreenerResult,
        baseCandles: List<Candle>,
        timeframe: Timeframe,
        context: StrategyMarketContext,
    ): ScreenerResult = enrich(
        base = base,
        external = externalAnalyzer.analyze(
            primarySymbol = base.symbol,
            primaryTimeframe = timeframe,
            primaryCandles = baseCandles,
            context = context,
        ),
    )

    /** Pure mapping overload used by focused tests and precomputed callers. */
    fun enrich(
        base: ScreenerResult,
        external: StrategyExternalContextAnalyzer.Analysis,
    ): ScreenerResult {
        val mtf = evaluateMtf(base.direction, external.higherTimeframeBiases)
        val latestSmt = external.smtDivergences.maxByOrNull { it.confirmationIndex }
        val smtConfirmed = latestSmt?.direction == base.direction
        val smtOpposed = latestSmt != null && latestSmt.direction != base.direction
        val trustworthy = external.context.decisionEligible

        var score = base.score
        score += when {
            mtf.checked == 0 -> 0
            mtf.alignment >= 0.999 -> MTF_FULL_BONUS
            mtf.alignment >= 0.5 -> MTF_PARTIAL_BONUS
            else -> -MTF_CONFLICT_PENALTY
        }
        score += when {
            smtConfirmed -> SMT_CONFIRM_BONUS
            smtOpposed -> -SMT_CONFLICT_PENALTY
            else -> 0
        }
        if (base.riskLevel == ScannerRiskLevel.HIGH) score -= HIGH_RISK_PENALTY
        score = score.coerceIn(0, 100)

        val riskMultiplier = adaptiveRiskMultiplier(
            alignment = mtf.alignment,
            checkedHtf = mtf.checked,
            smtConfirmed = smtConfirmed,
            smtOpposed = smtOpposed,
            riskLevel = base.riskLevel,
            score = score,
        )
        val actionable = trustworthy &&
            score >= ACTIONABLE_SCORE &&
            mtf.checked > 0 &&
            mtf.alignment >= MIN_ACTIONABLE_MTF_ALIGNMENT &&
            !smtOpposed &&
            base.riskLevel != ScannerRiskLevel.HIGH

        val contextTags = buildList {
            if (mtf.checked == 0) {
                add("MTF unavailable")
            } else {
                add("MTF ${mtf.aligned}/${mtf.checked}")
                if (mtf.alignment < MIN_ACTIONABLE_MTF_ALIGNMENT) add("MTF conflict")
            }
            when {
                smtConfirmed -> add("SMT ${latestSmt?.peerSymbol ?: "confirmed"}")
                smtOpposed -> add("SMT conflict")
            }
            if (!trustworthy) add("P4 data blocked")
            if (actionable) add("P4 confirmed")
            if (riskMultiplier < 0.99) add("Risk x${formatMultiplier(riskMultiplier)}")
        }

        val contextText = buildString {
            append(" Context package: ")
            if (mtf.checked == 0) {
                append("HTF context unavailable")
            } else {
                append("MTF ${mtf.aligned}/${mtf.checked} aligned")
            }
            when {
                smtConfirmed -> append(", SMT confirmed${latestSmt?.peerSymbol?.let { " by $it" } ?: ""}")
                smtOpposed -> append(", SMT conflicts${latestSmt?.peerSymbol?.let { " via $it" } ?: ""}")
                else -> append(", no fresh SMT confirmation")
            }
            append(", adaptive risk ${formatMultiplier(riskMultiplier)}x")
            if (!trustworthy) append(", execution blocked by simulated/untrusted context")
            append(".")
        }

        return base.copy(
            score = score,
            tags = (base.tags + contextTags).distinct().take(MAX_TAGS),
            rationale = base.rationale + contextText,
            mtfAlignment = mtf.alignment,
            smtConfirmed = smtConfirmed,
            smtPeer = latestSmt?.peerSymbol,
            actionable = actionable,
            riskMultiplier = if (trustworthy) riskMultiplier else MIN_RISK_MULTIPLIER,
        )
    }

    private fun evaluateMtf(
        direction: Direction,
        biases: Map<Timeframe, Bias>,
    ): MtfEvaluation {
        if (biases.isEmpty()) return MtfEvaluation()
        val reads = biases.values.toList()
        val aligned = reads.count { bias ->
            when (direction) {
                Direction.BULLISH -> bias == Bias.BULLISH
                Direction.BEARISH -> bias == Bias.BEARISH
            }
        }
        return MtfEvaluation(
            checked = reads.size,
            aligned = aligned,
            alignment = aligned.toDouble() / reads.size,
        )
    }

    private fun adaptiveRiskMultiplier(
        alignment: Double,
        checkedHtf: Int,
        smtConfirmed: Boolean,
        smtOpposed: Boolean,
        riskLevel: ScannerRiskLevel,
        score: Int,
    ): Double {
        var multiplier = when {
            checkedHtf == 0 -> 0.60
            alignment >= 0.999 -> 1.00
            alignment >= 0.5 -> 0.75
            else -> 0.50
        }
        if (smtConfirmed) multiplier += 0.10
        if (smtOpposed) multiplier *= 0.65
        multiplier *= when (riskLevel) {
            ScannerRiskLevel.LOW -> 1.0
            ScannerRiskLevel.MODERATE -> 0.80
            ScannerRiskLevel.HIGH -> 0.50
        }
        if (score < 60) multiplier *= 0.60
        return multiplier.coerceIn(MIN_RISK_MULTIPLIER, 1.0)
    }

    private fun formatMultiplier(value: Double): String =
        ((value * 100.0).roundToInt() / 100.0).toString()

    private data class MtfEvaluation(
        val checked: Int = 0,
        val aligned: Int = 0,
        val alignment: Double = 0.0,
    )

    private companion object {
        const val MTF_FULL_BONUS = 12
        const val MTF_PARTIAL_BONUS = 5
        const val MTF_CONFLICT_PENALTY = 12
        const val SMT_CONFIRM_BONUS = 10
        const val SMT_CONFLICT_PENALTY = 10
        const val HIGH_RISK_PENALTY = 8
        const val ACTIONABLE_SCORE = 70
        const val MIN_ACTIONABLE_MTF_ALIGNMENT = 0.5
        const val MIN_RISK_MULTIPLIER = 0.25
        const val MAX_TAGS = 8
    }
}
