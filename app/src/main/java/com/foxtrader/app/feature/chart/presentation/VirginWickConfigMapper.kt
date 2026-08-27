package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.KillZone
import com.foxtrader.app.domain.usecase.virginwick.EntryMode
import com.foxtrader.app.domain.usecase.virginwick.VirginWickConfig
import com.foxtrader.app.domain.usecase.virginwick.WickTestMode

/**
 * Maps the trader-facing Virgin Wick settings onto the engine config.
 *
 * Only the exposed choices are forwarded; the rest keeps the engine's defaults,
 * so what ships is the methodology rather than a variant of it tuned through
 * the UI. The warmup is never overridden — it is what stops the engine calling
 * a zone off a context timeframe that has barely started.
 */
fun VirginWickStudySettings.toEngineConfig(): VirginWickConfig {
    val s = sanitized()
    return VirginWickConfig(
        testMode = when (s.testMode) {
            VirginWickTestPreset.ANY_TOUCH -> WickTestMode.ANY_TOUCH
            VirginWickTestPreset.MIDPOINT -> WickTestMode.MIDPOINT
            VirginWickTestPreset.EXTREME -> WickTestMode.EXTREME
        },
        entryMode = when (s.entryMode) {
            VirginWickEntryPreset.POI_TOUCH -> EntryMode.POI_TOUCH
            VirginWickEntryPreset.IFVG -> EntryMode.IFVG
            VirginWickEntryPreset.IFVG_IN_POI -> EntryMode.IFVG_IN_POI
        },
        // The methodology's index-futures session focus, expressed as the two
        // kill zones those instruments actually move in.
        sessions = if (s.killZonesOnly) {
            setOf(KillZone.LONDON_OPEN, KillZone.NEW_YORK_OPEN)
        } else {
            emptySet()
        },
        closesBeyondToActivate = s.closesBeyondToActivate,
        confirmationWindowBars = s.confirmationWindowBars,
        defaultRewardMultiple = s.defaultRewardMultiple,
        minRewardMultiple = s.minRewardMultiple,
        maxDolRewardMultiple = s.maxDolRewardMultiple,
        historicalSignals = s.historicalSignals,
        liveWindowBars = s.liveWindowBars,
    )
}
