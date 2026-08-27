package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.usecase.liquiditysweep.BiasMode
import com.foxtrader.app.domain.usecase.liquiditysweep.EntryMode
import com.foxtrader.app.domain.usecase.liquiditysweep.LevelSource
import com.foxtrader.app.domain.usecase.liquiditysweep.LiquiditySweepConfig
import com.foxtrader.app.domain.usecase.liquiditysweep.TargetMode

/**
 * Maps the trader-facing Liquidity Sweep settings onto the engine config.
 *
 * Only the exposed choices are forwarded; everything else keeps the engine's
 * own defaults, so what ships is the methodology rather than a variant of it
 * that happened to be tuned through the UI. The warmup is never overridden —
 * it is what stops the engine calling a higher-timeframe bias off two bars.
 */
fun LiquiditySweepStudySettings.toEngineConfig(): LiquiditySweepConfig {
    val s = sanitized()
    return LiquiditySweepConfig(
        biasMode = when (s.biasMode) {
            LiquiditySweepBiasPreset.OFF -> BiasMode.NONE
            LiquiditySweepBiasPreset.HIGHER_TIMEFRAME -> BiasMode.HTF_STRUCTURE
            LiquiditySweepBiasPreset.BOTH_AGREE -> BiasMode.HTF_AND_MTF_AGREE
        },
        entryMode = when (s.entryMode) {
            LiquiditySweepEntryPreset.RECLAIM -> EntryMode.RECLAIM
            LiquiditySweepEntryPreset.RETEST -> EntryMode.RETEST
            LiquiditySweepEntryPreset.CHOCH_RETEST -> EntryMode.CHOCH_RETEST
        },
        levelSources = buildSet {
            add(LevelSource.MTF_SWING)
            add(LevelSource.HTF_SWING)
            if (s.useEqualLevels) add(LevelSource.EQUAL_LEVELS)
            if (s.usePreviousHtfRange) add(LevelSource.PREVIOUS_HTF_RANGE)
        },
        targetMode = if (s.targetOpposingLiquidity) TargetMode.OPPOSING_LIQUIDITY else TargetMode.FIXED_R,
        riskReward = s.riskReward,
        minRiskReward = s.minRiskReward,
        entryWindowBars = s.entryWindowBars,
        maxReclaimBars = s.maxReclaimBars,
        historicalSignals = s.historicalSignals,
        liveWindowBars = s.liveWindowBars,
    )
}
