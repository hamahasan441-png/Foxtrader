package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.usecase.signalintel.AccumulationManipulationDistributionEngine

fun AmdStudySettings.toEngineConfig(): AccumulationManipulationDistributionEngine.Config {
    val s = sanitized()
    return AccumulationManipulationDistributionEngine.Config(
        mode = when (s.mode) {
            AmdMode.FAST -> AccumulationManipulationDistributionEngine.Mode.FAST
            AmdMode.PRECISION -> AccumulationManipulationDistributionEngine.Mode.PRECISION
            AmdMode.POWER -> AccumulationManipulationDistributionEngine.Mode.POWER
        },
        atrPeriod = s.atrPeriod,
        minAccumulationBars = s.minAccumulationBars,
        maxAccumulationBars = s.maxAccumulationBars,
        accumulationRangeAtrMultiple = s.accumulationRangeAtrMultiple,
        minSweepAtr = s.minSweepAtr,
        minRejectionWickFraction = s.minRejectionWickFraction,
        minCloseLocation = s.minCloseLocation,
        maxReclaimBars = s.maxReclaimBars,
        maxConfirmBars = s.maxConfirmBars,
        displacementAtrMultiple = s.displacementAtrMultiple,
        stopBufferAtr = s.stopBufferAtr,
        rewardRisk = s.rewardRisk,
        minScore = s.minScore,
        cooldownBars = s.cooldownBars,
        sessionOffsetMinutes = s.sessionOffsetMinutes,
        maxSignals = s.maxSignals,
    )
}
