package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.usecase.keystone.KeystoneConfig
import com.foxtrader.app.domain.usecase.keystone.KeystoneEntryMode
import com.foxtrader.app.domain.usecase.keystone.KeystonePreset

/**
 * Maps the trader-facing Keystone settings onto the engine config.
 *
 * The validation parameters are deliberately not carried across from the UI,
 * because they are not exposed there: the fold count, the bootstrap size and
 * the exit grid the overfitting probability is measured over all determine how
 * strong a claim the report is entitled to make. An editable version would let
 * the test be weakened until it passed, which is the one thing the report exists
 * to prevent.
 */
fun KeystoneStudySettings.toEngineConfig(): KeystoneConfig {
    val s = sanitized()
    val base = KeystoneConfig.forPreset(
        when (s.preset) {
            KeystonePresetOption.SCALPING -> KeystonePreset.SCALPING
            KeystonePresetOption.INTRADAY -> KeystonePreset.INTRADAY
            KeystonePresetOption.SWING -> KeystonePreset.SWING
        },
    )
    return base.copy(
        requireSmt = s.requireSmt,
        avoidTradingAgainstSession = s.avoidTradingAgainstSession,
        entryMode = when (s.entryMode) {
            KeystoneEntryOption.FVG_THEN_EQUILIBRIUM -> KeystoneEntryMode.FVG_THEN_EQUILIBRIUM
            KeystoneEntryOption.FVG_ONLY -> KeystoneEntryMode.FVG_ONLY
            KeystoneEntryOption.EQUILIBRIUM_ONLY -> KeystoneEntryMode.EQUILIBRIUM_ONLY
        },
        minRewardMultiple = s.minRewardMultiple,
        // The engine refuses a default target below its own floor, so a trader
        // who raises the floor past the default gets the floor rather than a
        // configuration that can never produce a signal.
        defaultRewardMultiple = maxOf(s.defaultRewardMultiple, s.minRewardMultiple),
        stopAtrBuffer = s.stopAtrBuffer,
        displacementAtrMultiple = s.displacementAtrMultiple,
        riskPercent = s.riskPercent,
        maxDailyLosses = s.maxDailyLosses,
        maxDailySignals = s.maxDailySignals,
        assumedSpreadFraction = s.assumedSpreadFraction,
        newsBlackoutMinutes = s.newsBlackoutMinutes,
        enforceAcceptance = s.enforceAcceptance,
        historicalSignals = s.historicalSignals,
        liveWindowBars = s.liveWindowBars,
    )
}
