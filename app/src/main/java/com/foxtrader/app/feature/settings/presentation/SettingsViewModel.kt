package com.foxtrader.app.feature.settings.presentation

import androidx.lifecycle.ViewModel
import com.foxtrader.app.domain.model.AlertConfig
import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.model.PositionSizingMethod
import com.foxtrader.app.domain.model.RiskConfig
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.alerts.AlertEngine
import com.foxtrader.app.domain.usecase.risk.RiskEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val riskEngine: RiskEngine,
    private val alertEngine: AlertEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            riskConfig = riskEngine.getConfig(),
            alertConfig = alertEngine.getConfig(),
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // --- Risk Config ---

    fun setRiskPercent(value: Double) {
        _uiState.update { it.copy(riskConfig = it.riskConfig.copy(riskPercentPerTrade = value), saved = false) }
    }

    fun setSizingMethod(method: PositionSizingMethod) {
        _uiState.update { it.copy(riskConfig = it.riskConfig.copy(sizingMethod = method), saved = false) }
    }

    fun setMaxDailyLoss(value: Double) {
        _uiState.update { it.copy(riskConfig = it.riskConfig.copy(maxDailyLossPercent = value), saved = false) }
    }

    fun setMaxDrawdown(value: Double) {
        _uiState.update { it.copy(riskConfig = it.riskConfig.copy(maxDrawdownPercent = value), saved = false) }
    }

    fun setMaxConsecutiveLosses(value: Int) {
        _uiState.update { it.copy(riskConfig = it.riskConfig.copy(maxConsecutiveLosses = value), saved = false) }
    }

    fun setAtrMultiplier(value: Double) {
        _uiState.update { it.copy(riskConfig = it.riskConfig.copy(atrStopMultiplier = value), saved = false) }
    }

    // --- Alert Config ---

    fun setSoundEnabled(enabled: Boolean) {
        _uiState.update { it.copy(alertConfig = it.alertConfig.copy(soundEnabled = enabled), saved = false) }
    }

    fun setMinAlertPriority(priority: AlertPriority) {
        _uiState.update { it.copy(alertConfig = it.alertConfig.copy(minPriority = priority), saved = false) }
    }

    fun setMaxAlertsPerHour(value: Int) {
        _uiState.update { it.copy(alertConfig = it.alertConfig.copy(maxAlertsPerHour = value), saved = false) }
    }

    // --- General ---

    fun setDefaultTimeframe(tf: Timeframe) {
        _uiState.update { it.copy(defaultTimeframe = tf, saved = false) }
    }

    fun setDarkMode(dark: Boolean) {
        _uiState.update { it.copy(darkMode = dark, saved = false) }
    }

    // --- Save ---

    fun save() {
        val state = _uiState.value
        riskEngine.updateConfig(state.riskConfig)
        alertEngine.updateConfig(state.alertConfig)
        _uiState.update { it.copy(saved = true) }
    }
}
