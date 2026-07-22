package com.foxtrader.app.feature.settings.presentation

import com.foxtrader.app.domain.model.AlertConfig
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.PositionSizingMethod
import com.foxtrader.app.domain.model.RiskConfig
import com.foxtrader.app.domain.model.Timeframe

/**
 * Immutable UI state for the Settings screen.
 */
data class SettingsUiState(
    val riskConfig: RiskConfig = RiskConfig(),
    val alertConfig: AlertConfig = AlertConfig(),
    val defaultTimeframe: Timeframe = Timeframe.M15,
    val dataProvider: DataProvider = DataProvider.SAMPLE,
    val darkMode: Boolean = true,
    val saved: Boolean = false,
)
