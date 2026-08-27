package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.usecase.compass.CompassConfig
import com.foxtrader.app.domain.usecase.compass.CompassPreset

/**
 * Maps the trader-facing Compass settings onto the engine config.
 *
 * The threshold grid is deliberately not exposed. Its size is what the
 * multiple-testing correction is computed from, so letting it be edited would
 * let a trader widen the search until something passed — which is precisely the
 * failure the correction exists to prevent.
 */
fun CompassStudySettings.toEngineConfig(): CompassConfig {
    val s = sanitized()
    val base = CompassConfig.forPreset(
        when (s.preset) {
            CompassPresetOption.SCALPING -> CompassPreset.SCALPING
            CompassPresetOption.INTRADAY -> CompassPreset.INTRADAY
            CompassPresetOption.SWING -> CompassPreset.SWING
        },
    )
    return base.copy(
        horizonBars = s.horizonBars,
        barrierAtrMultiple = s.barrierAtrMultiple,
        minAccuracy = s.minAccuracy,
        minLiftOverBaseRate = s.minLiftOverBaseRate,
        minCalibrationSample = s.minCalibrationSample,
        learningWindow = s.learningWindow,
        historicalSignals = s.historicalSignals,
        liveWindowBars = s.liveWindowBars,
    )
}
