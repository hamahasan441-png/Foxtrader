package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.usecase.crucible.CrucibleConfig
import com.foxtrader.app.domain.usecase.crucible.CruciblePreset
import com.foxtrader.app.domain.usecase.crucible.CrucibleTarget

/**
 * Maps the trader-facing Crucible settings onto the engine config.
 *
 * The quantile cut-points, the fold count and the embargo are deliberately left
 * at the preset's values. All three determine either the size of the search or
 * the strength of the leak protection, and both are quantities the engine's
 * corrections are computed from — exposing them would let the search be widened
 * or the protection weakened until a finding appeared.
 */
fun CrucibleStudySettings.toEngineConfig(): CrucibleConfig {
    val s = sanitized()
    val base = CrucibleConfig.forPreset(
        when (s.preset) {
            CruciblePresetOption.SCALPING -> CruciblePreset.SCALPING
            CruciblePresetOption.INTRADAY -> CruciblePreset.INTRADAY
            CruciblePresetOption.SWING -> CruciblePreset.SWING
        },
    )
    return base.copy(
        target = when (s.target) {
            CrucibleTargetOption.DIRECTION -> CrucibleTarget.DIRECTION
            CrucibleTargetOption.MOVEMENT -> CrucibleTarget.MOVEMENT
        },
        horizonBars = s.horizonBars,
        barrierAtrMultiple = s.barrierAtrMultiple,
        movementBarrierAtrMultiple = s.movementBarrierAtrMultiple,
        minAccuracy = s.minAccuracy,
        minLiftOverBaseRate = s.minLiftOverBaseRate,
        falseDiscoveryRate = s.falseDiscoveryRate,
        maxOverfittingProbability = s.maxOverfittingProbability,
        minEffectiveSample = s.minEffectiveSample,
        // The embargo must never be shorter than the horizon it protects
        // against, whatever horizon the trader chooses.
        embargoBars = maxOf(base.embargoBars, s.horizonBars),
        historicalSignals = s.historicalSignals,
        liveWindowBars = s.liveWindowBars,
    )
}
