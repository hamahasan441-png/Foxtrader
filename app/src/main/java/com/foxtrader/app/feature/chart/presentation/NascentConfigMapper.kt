package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.usecase.nascent.NascentConfig
import com.foxtrader.app.domain.usecase.nascent.model.SignalConfidence

/**
 * Maps the trader-facing Nascent settings onto the engine's configuration.
 *
 * The engine keeps its full threshold surface; this deliberately exposes only
 * the choices a trader should be making, so the shipped defaults stay a
 * faithful implementation of the methodology rather than a tuned variant of it.
 */
fun NascentStudySettings.toEngineConfig(): NascentConfig {
    val s = sanitized()
    return NascentConfig(
        mode = s.mode,
        minConfidence = when (s.quality) {
            NascentQuality.A_PLUS_ONLY -> SignalConfidence.A_PLUS
            NascentQuality.A_AND_ABOVE -> SignalConfidence.A
            NascentQuality.ALL_VALID -> SignalConfidence.WATCH
        },
        // With historical reconstruction off the engine still walks the series
        // — that is the only way to stay non-repainting — but only the live
        // window is reported, so old arrows are not drawn.
        historyDepthBars = if (s.historicalCalculation) s.historyDepthBars else s.liveWindowBars,
        liveWindowBars = s.liveWindowBars,
        collectDiagnostics = s.debug != NascentDebugLevel.OFF,
    )
}
