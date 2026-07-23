package com.foxtrader.app.feature.settings.presentation

import com.foxtrader.app.domain.model.AlertConfig
import com.foxtrader.app.domain.model.AuthState
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.PositionSizingMethod
import com.foxtrader.app.domain.model.RiskConfig
import com.foxtrader.app.domain.model.Timeframe

/**
 * AI Decision Engine configuration exposed in the Settings screen.
 */
data class AiConfig(
    /** Minimum confluences (of 9) to approve a signal. */
    val minConfluences: Int = 5,
    /** Minimum aggregate confidence (0..100) to approve. */
    val minConfidence: Int = 55,
    /** Push-alert cooldown per symbol+direction, in minutes. */
    val alertCooldownMinutes: Int = 5,
    /** Enable/disable background periodic scan alerts. */
    val backgroundScanEnabled: Boolean = true,
    /** Background scan interval in minutes (WorkManager minimum 15). */
    val backgroundScanIntervalMinutes: Int = 15,
)

/**
 * Immutable UI state for the Settings screen.
 */
data class SettingsUiState(
    val riskConfig: RiskConfig = RiskConfig(),
    val alertConfig: AlertConfig = AlertConfig(),
    val aiConfig: AiConfig = AiConfig(),
    val defaultTimeframe: Timeframe = Timeframe.M15,
    val dataProvider: DataProvider = DataProvider.SAMPLE,
    val darkMode: Boolean = true,
    val authState: AuthState = AuthState.UNAUTHENTICATED,
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
    val saved: Boolean = false,
) {
    val isLoggedIn: Boolean get() = authState == AuthState.AUTHENTICATED
}
