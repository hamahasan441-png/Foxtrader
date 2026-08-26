package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.usecase.rsireversal.BreakMode
import com.foxtrader.app.domain.usecase.rsireversal.EntryMode
import com.foxtrader.app.domain.usecase.rsireversal.RsiReversalConfig

/**
 * Maps the trader-facing RSI Orderflow Reversal settings onto the engine config.
 *
 * Only the exposed choices are forwarded. Everything else — epsilons, expiry
 * windows, the two documented rule ambiguities, the noise filters that §26
 * requires to stay disabled until they are researched — keeps the engine's own
 * defaults, so what ships is the specification's default preset rather than a
 * variant of it that happens to have been tuned through the UI.
 *
 * The warmup exclusion is never overridden here: it is the repaint protection
 * described in RSI_REVERSAL_RULES.md, not a tuning parameter.
 */
fun RsiReversalStudySettings.toEngineConfig(): RsiReversalConfig {
    val s = sanitized()
    return RsiReversalConfig(
        rsiLength = s.rsiLength,
        pricePivotLeft = s.pricePivotStrength,
        pricePivotRight = s.pricePivotStrength,
        rsiPivotLeft = s.rsiPivotStrength,
        rsiPivotRight = s.rsiPivotStrength,
        rsiBreakMode = if (s.requireRsiCloseBreak) BreakMode.CLOSE_BREAK else BreakMode.WICK_BREAK,
        entryMode = when (s.entryMode) {
            RsiReversalEntryPreset.AGGRESSIVE -> EntryMode.AGGRESSIVE
            RsiReversalEntryPreset.BALANCED -> EntryMode.BALANCED
            RsiReversalEntryPreset.STRICT -> EntryMode.STRICT
        },
        riskReward = s.riskReward,
        ltfConfirmationWindowBars = s.ltfConfirmationWindowBars,
    )
}
