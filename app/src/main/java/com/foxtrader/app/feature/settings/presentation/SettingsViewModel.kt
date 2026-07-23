package com.foxtrader.app.feature.settings.presentation

import androidx.lifecycle.ViewModel
import com.foxtrader.app.data.auth.BiometricAuthManager
import com.foxtrader.app.data.sync.SyncManager
import com.foxtrader.app.domain.model.AlertConfig
import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.DecisionConfig
import com.foxtrader.app.domain.model.PositionSizingMethod
import com.foxtrader.app.domain.model.RiskConfig
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.AuthRepository
import com.foxtrader.app.domain.usecase.ai.AiAlertService
import com.foxtrader.app.domain.usecase.ai.MasterDecisionEngine
import com.foxtrader.app.domain.usecase.alerts.AlertEngine
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.risk.RiskEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val riskEngine: RiskEngine,
    private val alertEngine: AlertEngine,
    private val appPreferences: AppPreferences,
    private val decisionEngine: MasterDecisionEngine,
    private val aiAlertService: AiAlertService,
    private val authRepository: AuthRepository,
    private val syncManager: SyncManager,
    private val biometricAuthManager: BiometricAuthManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            riskConfig = riskEngine.getConfig(),
            alertConfig = alertEngine.getConfig(),
            dataProvider = appPreferences.dataProvider.value,
            alphaVantageApiKey = appPreferences.alphaVantageApiKey.value,
            darkMode = appPreferences.darkMode.value,
            authState = authRepository.authState.value,
            appLockEnabled = appPreferences.appLockEnabled.value,
            biometricAvailable = biometricAuthManager.canAuthenticate(),
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        authRepository.authState
            .onEach { state -> _uiState.update { it.copy(authState = state) } }
            .launchIn(viewModelScope)
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncMessage = null) }
            val result = syncManager.syncNow()
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    syncMessage = if (result.success) {
                        "Synced ${result.mergedEntries} item(s)."
                    } else {
                        result.error ?: "Sync failed."
                    },
                )
            }
        }
    }

    fun setDataProvider(provider: DataProvider) {
        appPreferences.setDataProvider(provider)
        _uiState.update { it.copy(dataProvider = provider, saved = false) }
    }

    fun setAlphaVantageApiKey(value: String) {
        // Persisted on Save to match the rest of editable settings fields.
        _uiState.update { it.copy(alphaVantageApiKey = value, saved = false) }
    }

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
        appPreferences.setDarkMode(dark)
        _uiState.update { it.copy(darkMode = dark, saved = false) }
    }

    // --- Security ---

    fun setAppLockEnabled(enabled: Boolean) {
        // Only allow enabling if the device can actually authenticate.
        if (enabled && !biometricAuthManager.canAuthenticate()) return
        appPreferences.setAppLockEnabled(enabled)
        _uiState.update { it.copy(appLockEnabled = enabled) }
    }

    // --- AI Config ---

    fun setMinConfluences(value: Int) {
        _uiState.update { it.copy(aiConfig = it.aiConfig.copy(minConfluences = value.coerceIn(1, 9)), saved = false) }
    }

    fun setMinConfidence(value: Int) {
        _uiState.update { it.copy(aiConfig = it.aiConfig.copy(minConfidence = value.coerceIn(10, 100)), saved = false) }
    }

    fun setAlertCooldownMinutes(value: Int) {
        _uiState.update { it.copy(aiConfig = it.aiConfig.copy(alertCooldownMinutes = value.coerceIn(1, 60)), saved = false) }
    }

    fun setBackgroundScanEnabled(enabled: Boolean) {
        _uiState.update { it.copy(aiConfig = it.aiConfig.copy(backgroundScanEnabled = enabled), saved = false) }
    }

    // --- Save ---

    fun save() {
        val state = _uiState.value
        riskEngine.updateConfig(state.riskConfig)
        alertEngine.updateConfig(state.alertConfig)

        // Propagate AI config to the decision engine and alert service.
        decisionEngine.updateConfig(
            DecisionConfig(
                minRequiredConfluences = state.aiConfig.minConfluences,
                minConfidence = state.aiConfig.minConfidence.toDouble(),
            )
        )
        aiAlertService.cooldownMs = state.aiConfig.alertCooldownMinutes * 60_000L
        appPreferences.setApiKey(DataProvider.ALPHA_VANTAGE, state.alphaVantageApiKey.trim())

        _uiState.update { it.copy(saved = true) }
    }
}
