package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.usecase.apex.ApexConfig
import com.foxtrader.app.domain.usecase.apex.ApexPreset
import com.foxtrader.app.domain.usecase.apex.WarmupPolicy

/**
 * Maps the trader-facing Apex settings onto the engine config.
 *
 * The preset supplies the trading style — which members vote, how wide the
 * agreement window is, how long a trade is held — and the exposed dials then
 * override only the parts of the precision gate a trader should own. The member
 * engines' own configs are never touched from here: a member retuned to agree
 * more often stops being independent evidence, and the agreement between them
 * is the only reason an Apex signal is worth more than any one of them.
 */
fun ApexStudySettings.toEngineConfig(): ApexConfig {
    val s = sanitized()
    val base = ApexConfig.forPreset(
        when (s.preset) {
            ApexPresetOption.SCALPING -> ApexPreset.SCALPING
            ApexPresetOption.INTRADAY -> ApexPreset.INTRADAY
            ApexPresetOption.SWING -> ApexPreset.SWING
        },
    )
    return base.copy(
        minAgreeingMembers = s.minAgreeingMembers.coerceAtMost(base.members.size),
        minHitRate = s.minHitRate,
        minResolvedSample = s.minResolvedSample,
        precisionWindow = s.precisionWindow,
        useConfidenceBound = s.useConfidenceBound,
        warmupPolicy = if (s.publishBeforeMeasured) {
            WarmupPolicy.PUBLISH_UNMEASURED
        } else {
            WarmupPolicy.WITHHOLD
        },
        historicalSignals = s.historicalSignals,
        liveWindowBars = s.liveWindowBars,
    )
}
