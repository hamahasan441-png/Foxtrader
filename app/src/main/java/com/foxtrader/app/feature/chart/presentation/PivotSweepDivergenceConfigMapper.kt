package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.usecase.indicators.RsiOrderFlow
import com.foxtrader.app.domain.usecase.signalintel.PivotSweepDivergenceEngine

fun PivotSweepDivergenceStudySettings.toEngineConfig(): PivotSweepDivergenceEngine.Config {
    val s = sanitized()
    return PivotSweepDivergenceEngine.Config(
        mode = when (s.mode) {
            PivotSweepDivergenceMode.FAST -> PivotSweepDivergenceEngine.Mode.FAST
            PivotSweepDivergenceMode.PRECISION -> PivotSweepDivergenceEngine.Mode.PRECISION
            PivotSweepDivergenceMode.POWER -> PivotSweepDivergenceEngine.Mode.POWER
        },
        divergence = RsiOrderFlow.Config(
            rsiPeriod = s.rsiPeriod,
            flowPeriod = s.flowPeriod,
            flowSmoothing = s.flowSmoothing,
            pivotLeft = s.pivotLeft,
            pivotRight = s.pivotRight,
            minPivotSeparation = s.minPivotSeparation,
            maxPivotSeparation = s.maxPivotSeparation,
            minRsiDifference = s.minRsiDifference,
            minFlowDifference = s.minFlowDifference,
            includeHidden = false,
        ),
        atrPeriod = s.atrPeriod,
        minSweepAtr = s.minSweepAtr,
        minRejectionWickFraction = s.minRejectionWickFraction,
        minCloseLocation = s.minCloseLocation,
        structureLookback = s.structureLookback,
        maxConfirmBars = s.maxConfirmBars,
        displacementAtrMultiple = s.displacementAtrMultiple,
        stopBufferAtr = s.stopBufferAtr,
        rewardRisk = s.rewardRisk,
        minScore = s.minScore,
        cooldownBars = s.cooldownBars,
        sessionOffsetMinutes = s.sessionOffsetMinutes,
        maxSignals = s.maxSignals,
        sweepWindowBars = s.sweepWindowBars,
        maxReclaimBars = s.maxReclaimBars,
    )
}
