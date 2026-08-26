package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.usecase.signalintel.ValueAreaLiquidityRejectionEngine

fun ValueAreaLiquidityRejectionStudySettings.toEngineConfig(): ValueAreaLiquidityRejectionEngine.Config {
    val safe = sanitized()
    return ValueAreaLiquidityRejectionEngine.Config(
        mode = when (safe.mode) {
            ValueAreaLiquidityRejectionMode.FAST -> ValueAreaLiquidityRejectionEngine.Mode.FAST
            ValueAreaLiquidityRejectionMode.PRECISION -> ValueAreaLiquidityRejectionEngine.Mode.PRECISION
            ValueAreaLiquidityRejectionMode.POWER -> ValueAreaLiquidityRejectionEngine.Mode.POWER
        },
        profileBins = safe.profileBins,
        valueAreaPercent = safe.valueAreaPercent,
        minPreviousSessionBars = safe.minPreviousSessionBars,
        atrPeriod = safe.atrPeriod,
        swingLeft = safe.swingLeft,
        swingRight = safe.swingRight,
        liquidityLookback = safe.liquidityLookback,
        poolToleranceAtr = safe.poolToleranceAtr,
        minSweepAtr = safe.minSweepAtr,
        minWickFraction = safe.minWickFraction,
        minCloseLocation = safe.minCloseLocation,
        volumeLookback = safe.volumeLookback,
        volumeSpikeMultiple = safe.volumeSpikeMultiple,
        structureLookback = safe.structureLookback,
        maxConfirmBars = safe.maxConfirmBars,
        displacementAtrMultiple = safe.displacementAtrMultiple,
        stopBufferAtr = safe.stopBufferAtr,
        minPocRewardRisk = safe.minPocRewardRisk,
        minScore = safe.minScore,
        cooldownBars = safe.cooldownBars,
        sessionOffsetMinutes = safe.sessionOffsetMinutes,
        maxSignals = safe.maxSignals,
    )
}
