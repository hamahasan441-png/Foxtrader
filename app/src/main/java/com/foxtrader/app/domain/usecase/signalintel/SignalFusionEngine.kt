package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitAnalysis
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.SignalFusionComponent
import com.foxtrader.app.domain.model.SignalFusionResult
import com.foxtrader.app.domain.model.SmsAnalysis
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Phase 13 fusion layer. It does not invent a trade by voting: TradePro may only
 * remain executable if its own setup was already EXECUTE.
 *
 * Raw LiTX, LiT, SMS, SMT and TradePro components remain visible for diagnostics,
 * but correlated engines are reduced to evidence families before weighting.
 * LiTX/LiT/SMS share structure/liquidity primitives and therefore cannot count
 * as three independent confirmations merely because they have different names.
 */
class SignalFusionEngine @Inject constructor(
    private val evidenceReducer: SignalEvidenceReducer = SignalEvidenceReducer(),
) {

    data class Output(
        val tradePro: TradeProAnalysis?,
        val fusion: SignalFusionResult,
    )

    fun fuse(
        tradePro: TradeProAnalysis?,
        litX: LitXAnalysis?,
        lit: LitAnalysis?,
        sms: SmsAnalysis?,
        smt: List<SmtDivergenceDetector.SmtDivergence>,
        latestConfirmedIndex: Int,
    ): Output {
        val latestSmt = smt
            .filter { it.confirmationIndex <= latestConfirmedIndex }
            .maxByOrNull { it.confirmationIndex }
            ?.takeIf { latestConfirmedIndex - it.confirmationIndex <= SMT_MAX_AGE_BARS }

        val components = buildList {
            tradePro?.setup?.let {
                add(SignalFusionComponent("TradePro", it.direction, it.confidence.coerceIn(0, 100), true, it.stage.name))
            }
            litX?.signal?.let {
                add(SignalFusionComponent("LiTX", it.direction, it.confidence.score, true, it.confidence.grade.name))
            }
            lit?.signal?.let {
                add(SignalFusionComponent("LiT", it.direction, it.confidence, true, it.confirmations.joinToString("+")))
            }
            sms?.signal?.let {
                add(SignalFusionComponent("SMS", it.direction, it.confidence, true, it.type.name))
            }
            latestSmt?.let {
                add(SignalFusionComponent("SMT", it.direction, it.confidence.roundToInt().coerceIn(0, 100), true, it.peerSymbol))
            }
        }

        if (components.isEmpty()) {
            return Output(
                tradePro = tradePro,
                fusion = SignalFusionResult(null, 0, false, false, emptyList(), emptyList(), "No Phase 13 signal evidence."),
            )
        }

        // Keep all raw components in the returned audit object, but use only one
        // representative per correlated evidence-family/direction for math.
        val evidence = evidenceReducer.reduce(components)
        if (evidence.isEmpty()) {
            return Output(
                tradePro = tradePro,
                fusion = SignalFusionResult(null, 0, false, false, components, emptyList(), "No active signal evidence."),
            )
        }

        val targetDirection = tradePro?.setup?.direction ?: weightedDirection(evidence)
        if (targetDirection == null) {
            return Output(
                tradePro = tradePro,
                fusion = SignalFusionResult(
                    null,
                    50,
                    false,
                    true,
                    components,
                    emptyList(),
                    "Phase 13 evidence is directionally balanced after correlation reduction.",
                ),
            )
        }

        val supportive = evidence.filter { it.direction == targetDirection }
        val opposing = evidence.filter { it.direction != null && it.direction != targetDirection }
        val supportWeight = supportive.sumOf { weight(it.name) }
        val oppositionWeight = opposing.sumOf { weight(it.name) }
        val supportQuality = if (supportWeight > 0.0) {
            supportive.sumOf { it.score * weight(it.name) } / supportWeight
        } else 0.0
        val totalWeight = supportWeight + oppositionWeight
        val oppositionRatio = if (totalWeight > 0.0) oppositionWeight / totalWeight else 0.0
        val supportiveFamilies = evidenceReducer.distinctFamilyCount(supportive)
        val diversityBoost = ((supportiveFamilies - 1).coerceAtLeast(0) * 3.0).coerceAtMost(9.0)
        val fusedScore = (supportQuality + diversityBoost - oppositionRatio * 42.0).roundToInt().coerceIn(0, 100)
        val hardConflict = opposing.any { it.score >= HARD_CONFLICT_SCORE } || oppositionRatio >= HARD_CONFLICT_RATIO
        val strong = !hardConflict && fusedScore >= STRONG_SCORE && supportiveFamilies >= 2

        val confirmations = supportive
            .sortedByDescending { it.score }
            .map { "${evidenceReducer.family(it.name).name}:${it.name}:${it.score}" }
        val narrative = buildString {
            append(targetDirection.name)
            append(" fusion ")
            append(fusedScore)
            append("/100")
            if (hardConflict) append(" — BLOCKED by opposing evidence family")
            else if (strong) append(" — multi-family confirmation")
            else append(" — partial/correlated confirmation")
        }
        val fusion = SignalFusionResult(
            direction = targetDirection,
            score = fusedScore,
            strong = strong,
            conflict = hardConflict,
            components = components,
            confirmations = confirmations,
            narrative = narrative,
        )

        val setup = tradePro?.setup
        val fusedTradePro = if (tradePro == null || setup == null) {
            tradePro
        } else {
            val shouldBlock = setup.stage == SetupStage.EXECUTE && (hardConflict || fusedScore < MIN_EXECUTE_FUSION_SCORE)
            val newStage = if (shouldBlock) SetupStage.CONFIRMATION else setup.stage
            val adjustedConfidence = when {
                shouldBlock -> minOf(setup.confidence, fusedScore)
                setup.stage == SetupStage.EXECUTE -> ((setup.confidence * 0.55) + (fusedScore * 0.45)).roundToInt().coerceIn(0, 100)
                else -> setup.confidence
            }
            val tags = setup.confluences + confirmations.map { confirmation ->
                "PH13_${confirmation.substringAfter(':').substringBefore(':').uppercase()}"
            }
            val noteSuffix = if (shouldBlock) {
                " Phase 13 gate: ${fusion.narrative}. Re-review after fresh confirmation."
            } else {
                " Phase 13: ${fusion.narrative}."
            }
            tradePro.copy(
                setup = setup.copy(
                    stage = newStage,
                    confidence = adjustedConfidence,
                    confluences = tags.distinct(),
                    note = setup.note + noteSuffix,
                ),
                stage = newStage,
                narrative = tradePro.narrative + noteSuffix,
            )
        }
        return Output(fusedTradePro, fusion)
    }

    private fun weightedDirection(components: List<SignalFusionComponent>): Direction? {
        val bull = components.filter { it.direction == Direction.BULLISH }.sumOf { it.score * weight(it.name) }
        val bear = components.filter { it.direction == Direction.BEARISH }.sumOf { it.score * weight(it.name) }
        if (bull <= 0.0 && bear <= 0.0) return null
        val max = maxOf(bull, bear)
        val min = minOf(bull, bear)
        if (max > 0.0 && min / max > DIRECTION_BALANCE_RATIO) return null
        return if (bull > bear) Direction.BULLISH else Direction.BEARISH
    }

    private fun weight(name: String): Double = when (name) {
        "LiTX" -> 1.35
        "LiT" -> 1.20
        "SMS" -> 1.20
        "SMT" -> 1.10
        "TradePro" -> 1.30
        else -> 1.0
    }

    private companion object {
        const val SMT_MAX_AGE_BARS = 6
        const val HARD_CONFLICT_SCORE = 82
        const val HARD_CONFLICT_RATIO = 0.38
        const val DIRECTION_BALANCE_RATIO = 0.82
        const val STRONG_SCORE = 80
        const val MIN_EXECUTE_FUSION_SCORE = 68
    }
}
