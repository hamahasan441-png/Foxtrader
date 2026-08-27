package com.foxtrader.app.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.data.auth.BiometricAuthManager
import com.foxtrader.app.data.sync.SyncManager
import com.foxtrader.app.domain.model.AlertConfig
import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.DecisionConfig
import com.foxtrader.app.domain.model.LitBreakMode
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.asLitMayMadnessSignalConfig
import com.foxtrader.app.domain.model.LitXConfig
import com.foxtrader.app.domain.model.LitXGrade
import com.foxtrader.app.domain.model.PositionSizingMethod
import com.foxtrader.app.domain.model.SignalProfile
import com.foxtrader.app.domain.model.SmsConfig
import com.foxtrader.app.domain.model.SmtConfig
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.AuthRepository
import com.foxtrader.app.domain.repository.MarketRepository
import com.foxtrader.app.domain.usecase.ai.AiAlertService
import com.foxtrader.app.domain.usecase.ai.MasterDecisionEngine
import com.foxtrader.app.domain.usecase.alerts.AlertEngine
import com.foxtrader.app.domain.usecase.performance.PerformanceMode
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.risk.RiskEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private val marketRepository: MarketRepository,
) : ViewModel() {

    private var providerSwitchJob: Job? = null

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            riskConfig = appPreferences.riskConfig.value,
            alertConfig = appPreferences.alertConfig.value,
            aiConfig = AiConfig(
                minConfluences = appPreferences.aiMinConfluences.value,
                minConfidence = appPreferences.aiMinConfidence.value,
                alertCooldownMinutes = appPreferences.aiAlertCooldownMinutes.value,
            ),
            defaultTimeframe = appPreferences.defaultTimeframe.value,
            dataProvider = appPreferences.dataProvider.value,
            providerApiKeys = appPreferences.apiKeys.value.toPersistentMap(),
            darkMode = appPreferences.darkMode.value,
            backendBaseUrl = appPreferences.backendBaseUrl.value,
            maxCachedBars = appPreferences.maxCachedBars.value,
            performanceMode = appPreferences.performanceMode.value,
            authState = authRepository.authState.value,
            appLockEnabled = appPreferences.appLockEnabled.value,
            biometricAvailable = biometricAuthManager.canAuthenticate(),
            crashReportingEnabled = appPreferences.crashReportingEnabled.value,
            tradeProConfig = appPreferences.tradeProConfig.value,
            litXConfig = appPreferences.litXConfig.value,
            litConfig = appPreferences.litConfig.value,
            smtConfig = appPreferences.smtConfig.value,
            smsConfig = appPreferences.smsConfig.value,
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        authRepository.authState.onEach { state -> _uiState.update { it.copy(authState = state) } }.launchIn(viewModelScope)
        appPreferences.apiKeys.onEach { keys -> _uiState.update { it.copy(providerApiKeys = keys.toPersistentMap()) } }.launchIn(viewModelScope)
        appPreferences.riskConfig.onEach { config -> _uiState.update { it.copy(riskConfig = config) } }.launchIn(viewModelScope)
        appPreferences.alertConfig.onEach { config -> _uiState.update { it.copy(alertConfig = config) } }.launchIn(viewModelScope)
        appPreferences.defaultTimeframe.onEach { tf -> _uiState.update { it.copy(defaultTimeframe = tf) } }.launchIn(viewModelScope)
        appPreferences.crashReportingEnabled.onEach { enabled -> _uiState.update { it.copy(crashReportingEnabled = enabled) } }.launchIn(viewModelScope)
        appPreferences.backendBaseUrl.onEach { url -> _uiState.update { it.copy(backendBaseUrl = url) } }.launchIn(viewModelScope)
        appPreferences.maxCachedBars.onEach { bars -> _uiState.update { it.copy(maxCachedBars = bars) } }.launchIn(viewModelScope)
        appPreferences.performanceMode.onEach { mode -> _uiState.update { it.copy(performanceMode = mode) } }.launchIn(viewModelScope)
        appPreferences.litXConfig.onEach { cfg -> _uiState.update { it.copy(litXConfig = cfg) } }.launchIn(viewModelScope)
        appPreferences.litConfig.onEach { cfg -> _uiState.update { it.copy(litConfig = cfg) } }.launchIn(viewModelScope)
        appPreferences.smtConfig.onEach { cfg -> _uiState.update { it.copy(smtConfig = cfg) } }.launchIn(viewModelScope)
        appPreferences.smsConfig.onEach { cfg -> _uiState.update { it.copy(smsConfig = cfg) } }.launchIn(viewModelScope)
        combine(
            appPreferences.aiMinConfluences,
            appPreferences.aiMinConfidence,
            appPreferences.aiAlertCooldownMinutes,
        ) { confluences, confidence, cooldown ->
            AiConfig(
                minConfluences = confluences,
                minConfidence = confidence,
                alertCooldownMinutes = cooldown,
            )
        }.onEach { aiConfig ->
            _uiState.update { it.copy(aiConfig = aiConfig) }
        }.launchIn(viewModelScope)
    }

    fun logout() { viewModelScope.launch { authRepository.logout() } }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncMessage = null) }
            val result = syncManager.syncNow()
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    syncMessage = if (result.success) "Synced ${result.mergedEntries} item(s)." else result.error ?: "Sync failed.",
                )
            }
        }
    }

    fun setDataProvider(provider: DataProvider) {
        val previous = _uiState.value.dataProvider
        if (previous == provider) return
        _uiState.update { it.copy(dataProvider = provider, saved = false, providerTest = ConnectionTest.Idle) }
        providerSwitchJob?.cancel()
        providerSwitchJob = viewModelScope.launch {
            marketRepository.clearMarketDataCache()
            appPreferences.setDataProvider(provider)
            marketRepository.clearMarketDataCache()
        }
    }

    fun setBackendBaseUrl(value: String) { _uiState.update { it.copy(backendBaseUrl = value, saved = false) } }

    fun setMaxCachedBars(value: Int) {
        appPreferences.setMaxCachedBars(value)
        _uiState.update { it.copy(maxCachedBars = value, saved = false) }
    }

    fun setPerformanceMode(mode: PerformanceMode) {
        appPreferences.setPerformanceMode(mode)
        _uiState.update { it.copy(performanceMode = mode, saved = false) }
    }

    fun testProviderConnection() {
        val state = _uiState.value
        appPreferences.setApiKey(state.dataProvider, state.currentProviderApiKey)
        _uiState.update { it.copy(providerTest = ConnectionTest.Testing) }
        viewModelScope.launch {
            providerSwitchJob?.join()
            val result = marketRepository.testProviderConnection()
            _uiState.update {
                it.copy(
                    providerTest = result.fold(
                        onSuccess = { count -> ConnectionTest.Success(count) },
                        onFailure = { e -> ConnectionTest.Failure(e.message ?: "Connection failed.") },
                    ),
                )
            }
        }
    }

    fun testBackendConnection() {
        appPreferences.setBackendBaseUrl(_uiState.value.backendBaseUrl)
        _uiState.update { it.copy(backendTest = ConnectionTest.Testing) }
        viewModelScope.launch {
            val result = marketRepository.testBackendConnection()
            _uiState.update {
                it.copy(
                    backendTest = result.fold(
                        onSuccess = { count -> ConnectionTest.Success(count) },
                        onFailure = { e -> ConnectionTest.Failure(e.message ?: "Backend unreachable.") },
                    ),
                )
            }
        }
    }

    fun setProviderApiKey(value: String) {
        _uiState.update { state ->
            state.copy(
                providerApiKeys = state.providerApiKeys.toMap().toMutableMap().apply { this[state.dataProvider] = value }.toPersistentMap(),
                saved = false,
            )
        }
    }

    fun setRiskPercent(value: Double) { _uiState.update { it.copy(riskConfig = it.riskConfig.copy(riskPercentPerTrade = value), saved = false) } }
    fun setSizingMethod(method: PositionSizingMethod) { _uiState.update { it.copy(riskConfig = it.riskConfig.copy(sizingMethod = method), saved = false) } }
    fun setMaxDailyLoss(value: Double) { _uiState.update { it.copy(riskConfig = it.riskConfig.copy(maxDailyLossPercent = value), saved = false) } }
    fun setMaxDrawdown(value: Double) { _uiState.update { it.copy(riskConfig = it.riskConfig.copy(maxDrawdownPercent = value), saved = false) } }
    fun setMaxConsecutiveLosses(value: Int) { _uiState.update { it.copy(riskConfig = it.riskConfig.copy(maxConsecutiveLosses = value), saved = false) } }
    fun setAtrMultiplier(value: Double) { _uiState.update { it.copy(riskConfig = it.riskConfig.copy(atrStopMultiplier = value), saved = false) } }

    fun setSoundEnabled(enabled: Boolean) { _uiState.update { it.copy(alertConfig = it.alertConfig.copy(soundEnabled = enabled), saved = false) } }
    fun setMinAlertPriority(priority: AlertPriority) { _uiState.update { it.copy(alertConfig = it.alertConfig.copy(minPriority = priority), saved = false) } }
    fun setMaxAlertsPerHour(value: Int) { _uiState.update { it.copy(alertConfig = it.alertConfig.copy(maxAlertsPerHour = value), saved = false) } }

    fun setDefaultTimeframe(tf: Timeframe) { _uiState.update { it.copy(defaultTimeframe = tf, saved = false) } }

    fun setDarkMode(dark: Boolean) {
        appPreferences.setDarkMode(dark)
        _uiState.update { it.copy(darkMode = dark, saved = false) }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        if (enabled && !biometricAuthManager.canAuthenticate()) return
        appPreferences.setAppLockEnabled(enabled)
        _uiState.update { it.copy(appLockEnabled = enabled) }
    }

    fun setCrashReportingEnabled(enabled: Boolean) {
        appPreferences.setCrashReportingEnabled(enabled)
        _uiState.update { it.copy(crashReportingEnabled = enabled) }
    }

    private fun updateLit(transform: (LitConfig) -> LitConfig) {
        val cfg = transform(_uiState.value.litConfig).asLitMayMadnessSignalConfig()
        appPreferences.setLitConfig(cfg)
        _uiState.update { it.copy(litConfig = cfg, saved = false) }
    }

    fun setLitProfile(profile: SignalProfile) {
        val cfg = LitConfig.preset(profile).asLitMayMadnessSignalConfig()
        appPreferences.setLitConfig(cfg)
        _uiState.update { it.copy(litConfig = cfg, saved = false) }
    }
    fun setLitMinConfidence(v: Int) = updateLit { it.copy(minConfidence = v) }
    fun setLitDirectionalZone(v: Boolean) = updateLit { it.copy(requireDirectionalZone = v) }
    fun setLitSetupLookback(v: Int) = updateLit { it.copy(setupLookback = v) }
    fun setLitSweepToShift(v: Int) = updateLit { it.copy(maxSweepToShiftBars = v) }
    fun setLitShiftToRetest(v: Int) = updateLit { it.copy(maxShiftToRetestBars = v) }
    fun setLitMinRr(v: Double) = updateLit { it.copy(minRiskReward = v) }
    fun setLitDisplacement(v: Double) = updateLit { it.copy(displacementAtrMultiple = v) }
    fun setLitSwingLeft(v: Int) = updateLit { it.copy(swingLeftBars = v) }
    fun setLitSwingRight(v: Int) = updateLit { it.copy(swingRightBars = v) }
    fun setLitBreakMode(v: LitBreakMode) = updateLit { it.copy(breakMode = v) }
    fun setLitMaxIdmToBos(v: Int) = updateLit { it.copy(maxIdmToBosBars = v) }
    fun setLitMaxBosToChoch(v: Int) = updateLit { it.copy(maxBosToChochBars = v) }
    fun setLitMaxPoiAge(v: Int) = updateLit { it.copy(maxPoiAgeBars = v) }
    fun setLitAllowInsideBarMother(v: Boolean) = updateLit { it.copy(allowInsideBarMother = v) }
    fun setLitFollowDeeperPoi(v: Boolean) = updateLit { it.copy(followDeeperPoiCandle = v) }
    fun setLitRequireScob(v: Boolean) = updateLit { it.copy(requireScob = v) }
    fun setLitHiddenShadowMaxAtr(v: Double) = updateLit { it.copy(hiddenShadowMaxAtrFraction = v) }
    fun setLitStopAtrBuffer(v: Double) = updateLit { it.copy(stopAtrBuffer = v) }

    private fun updateSmt(transform: (SmtConfig) -> SmtConfig) {
        val cfg = transform(_uiState.value.smtConfig).sanitized()
        appPreferences.setSmtConfig(cfg)
        _uiState.update { it.copy(smtConfig = cfg, saved = false) }
    }
    fun setSmtProfile(profile: SignalProfile) {
        val cfg = SmtConfig.preset(profile)
        appPreferences.setSmtConfig(cfg)
        _uiState.update { it.copy(smtConfig = cfg, saved = false) }
    }
    fun setSmtPeriod(v: Int) = updateSmt { it.copy(period = v) }
    fun setSmtSwingLookback(v: Int) = updateSmt { it.copy(swingLookback = v) }
    fun setSmtMinCorrelation(v: Double) = updateSmt { it.copy(minCorrelation = v) }
    fun setSmtMaxSkewFraction(v: Double) = updateSmt { it.copy(maxTimestampSkewFraction = v) }
    fun setSmtSyncBars(v: Int) = updateSmt { it.copy(maxSwingSyncBars = v) }
    fun setSmtMaxAge(v: Int) = updateSmt { it.copy(maxSignalAgeBars = v) }
    fun setSmtMinStrength(v: Double) = updateSmt { it.copy(minDivergenceStrength = v) }
    fun setSmtMinConfidence(v: Int) = updateSmt { it.copy(minConfidence = v) }

    private fun updateSms(transform: (SmsConfig) -> SmsConfig) {
        val cfg = transform(_uiState.value.smsConfig).sanitized()
        appPreferences.setSmsConfig(cfg)
        _uiState.update { it.copy(smsConfig = cfg, saved = false) }
    }
    fun setSmsProfile(profile: SignalProfile) {
        val cfg = SmsConfig.preset(profile)
        appPreferences.setSmsConfig(cfg)
        _uiState.update { it.copy(smsConfig = cfg, saved = false) }
    }
    fun setSmsSwingBars(v: Int) = updateSms { it.copy(swingBars = v) }
    fun setSmsDisplacement(v: Double) = updateSms { it.copy(displacementAtrMultiple = v) }
    fun setSmsGap(v: Int) = updateSms { it.copy(maxDisplacementGapBars = v) }
    fun setSmsSweepToShift(v: Int) = updateSms { it.copy(maxSweepToShiftBars = v) }
    fun setSmsMaxAge(v: Int) = updateSms { it.copy(maxSignalAgeBars = v) }
    fun setSmsMinConfidence(v: Int) = updateSms { it.copy(minConfidence = v) }
    fun setSmsRequireSweep(v: Boolean) = updateSms { it.copy(requireLiquiditySweep = v) }
    fun setSmsRequireDisplacement(v: Boolean) = updateSms { it.copy(requireDisplacementForChoch = v) }

    fun setLitXEnabled(enabled: Boolean) {
        val cfg = _uiState.value.litXConfig.copy(enabled = enabled)
        appPreferences.setLitXConfig(cfg)
        _uiState.update { it.copy(litXConfig = cfg) }
    }

    fun setLitXRequireHtf(enabled: Boolean) {
        val cfg = _uiState.value.litXConfig.copy(requireHtfAlignment = enabled)
        appPreferences.setLitXConfig(cfg)
        _uiState.update { it.copy(litXConfig = cfg) }
    }

    fun setLitXMinRiskReward(value: Double) {
        val cfg = _uiState.value.litXConfig.copy(minRiskReward = value.coerceIn(1.0, 5.0))
        appPreferences.setLitXConfig(cfg)
        _uiState.update { it.copy(litXConfig = cfg) }
    }

    fun setLitXProfile(profile: SignalProfile) {
        val cfg = LitXConfig.preset(profile, enabled = _uiState.value.litXConfig.enabled)
        appPreferences.setLitXConfig(cfg)
        _uiState.update { it.copy(litXConfig = cfg, saved = false) }
    }

    fun setLitXMinGrade(grade: LitXGrade) {
        val cfg = _uiState.value.litXConfig.copy(minGrade = grade)
        appPreferences.setLitXConfig(cfg)
        _uiState.update { it.copy(litXConfig = cfg, saved = false) }
    }

    fun setLitXRequireStrongMss(enabled: Boolean) {
        val cfg = _uiState.value.litXConfig.copy(requireStrongMss = enabled)
        appPreferences.setLitXConfig(cfg)
        _uiState.update { it.copy(litXConfig = cfg) }
    }

    fun setLitXRequireDirectionalZone(enabled: Boolean) {
        val cfg = _uiState.value.litXConfig.copy(requireDirectionalZone = enabled)
        appPreferences.setLitXConfig(cfg)
        _uiState.update { it.copy(litXConfig = cfg) }
    }

    fun setLitXMinConfidence(value: Int) {
        val cfg = _uiState.value.litXConfig.copy(minConfidenceScore = value.coerceIn(50, 95))
        appPreferences.setLitXConfig(cfg)
        _uiState.update { it.copy(litXConfig = cfg) }
    }

    fun setLitXDisplacement(value: Double) {
        val cfg = _uiState.value.litXConfig.copy(displacementAtrMultiple = value.coerceIn(0.8, 3.0))
        appPreferences.setLitXConfig(cfg)
        _uiState.update { it.copy(litXConfig = cfg, saved = false) }
    }

    fun setLitXSweepToShift(value: Int) {
        val cfg = _uiState.value.litXConfig.copy(maxSweepToShiftBars = value.coerceIn(3, 30))
        appPreferences.setLitXConfig(cfg)
        _uiState.update { it.copy(litXConfig = cfg, saved = false) }
    }

    fun setLitXShiftToRetest(value: Int) {
        val cfg = _uiState.value.litXConfig.copy(maxShiftToRetestBars = value.coerceIn(3, 40))
        appPreferences.setLitXConfig(cfg)
        _uiState.update { it.copy(litXConfig = cfg, saved = false) }
    }

    private fun updateTradePro(transform: (com.foxtrader.app.domain.model.tradepro.TradeProConfig) -> com.foxtrader.app.domain.model.tradepro.TradeProConfig) {
        _uiState.update { state -> state.copy(tradeProConfig = transform(state.tradeProConfig), saved = false) }
    }

    fun setTradeProPointSize(value: Double) = updateTradePro { it.copy(pointSize = value.coerceIn(0.000001, 1_000.0)) }
    fun setTradeProStopPoints(value: Double) = updateTradePro { it.copy(stopPoints = value.coerceIn(0.1, 100.0)) }
    fun setTradeProTarget1(value: Double) = updateTradePro { it.copy(target1Points = value.coerceIn(0.1, 200.0)) }
    fun setTradeProTarget2(value: Double) = updateTradePro { it.copy(target2Points = value.coerceIn(0.1, 400.0)) }
    fun setTradeProRunner(value: Double) = updateTradePro { it.copy(runnerPoints = value.coerceIn(0.1, 1_000.0)) }
    fun setTradeProContracts(value: Int) = updateTradePro { it.copy(contracts = value.coerceIn(1, 100)) }
    fun setTradeProMaxRisk(value: Double) = updateTradePro { it.copy(maxRiskPoints = value.coerceIn(0.1, 1_000.0)) }
    fun setTradeProImbalanceRatio(value: Double) = updateTradePro { it.copy(imbalanceRatio = value.coerceIn(1.0, 20.0)) }
    fun setTradeProAcceptanceBars(value: Int) = updateTradePro { it.copy(acceptanceMinBars = value.coerceIn(1, 20)) }
    fun setTradeProSwingLookback(value: Int) = updateTradePro { it.copy(swingLookback = value.coerceIn(1, 50)) }
    fun setTradeProTrendFilter(enabled: Boolean) = updateTradePro { it.copy(useTrendFilter = enabled) }
    fun setTradeProEfficiencyPeriod(value: Int) = updateTradePro { it.copy(efficiencyRatioPeriod = value.coerceIn(2, 500)) }
    fun setTradeProMinEfficiency(value: Double) = updateTradePro { it.copy(minEfficiencyRatio = value.coerceIn(0.0, 1.0)) }
    fun setTradeProTrendEmaPeriod(value: Int) = updateTradePro { it.copy(trendEmaPeriod = value.coerceIn(2, 500)) }
    fun setTradeProTrendSlopeLookback(value: Int) = updateTradePro { it.copy(trendSlopeLookback = value.coerceIn(1, 200)) }
    fun setTradeProMaxConsecutiveLosses(value: Int) = updateTradePro { it.copy(maxConsecutiveLosses = value.coerceIn(1, 20)) }
    fun setTradeProMaxDailyLoss(value: Double) = updateTradePro { it.copy(maxDailyLossPoints = value.coerceIn(0.1, 10_000.0)) }
    fun setTradeProMinCompliance(value: Double) = updateTradePro { it.copy(minCompliancePercent = value.coerceIn(0.0, 100.0)) }

    fun setMinConfluences(value: Int) { _uiState.update { it.copy(aiConfig = it.aiConfig.copy(minConfluences = value.coerceIn(1, 9)), saved = false) } }
    fun setMinConfidence(value: Int) { _uiState.update { it.copy(aiConfig = it.aiConfig.copy(minConfidence = value.coerceIn(10, 100)), saved = false) } }
    fun setAlertCooldownMinutes(value: Int) { _uiState.update { it.copy(aiConfig = it.aiConfig.copy(alertCooldownMinutes = value.coerceIn(1, 60)), saved = false) } }

    fun save() {
        val state = _uiState.value
        riskEngine.updateConfig(state.riskConfig)
        alertEngine.updateConfig(state.alertConfig)
        appPreferences.setRiskConfig(state.riskConfig)
        appPreferences.setAlertConfig(state.alertConfig)
        appPreferences.setDefaultTimeframe(state.defaultTimeframe)
        decisionEngine.updateConfig(
            DecisionConfig(
                minRequiredConfluences = state.aiConfig.minConfluences,
                minConfidence = state.aiConfig.minConfidence.toDouble(),
            )
        )
        aiAlertService.cooldownMs = state.aiConfig.alertCooldownMinutes * 60_000L
        appPreferences.setAiDecisionConfig(
            minConfluences = state.aiConfig.minConfluences,
            minConfidence = state.aiConfig.minConfidence,
            alertCooldownMinutes = state.aiConfig.alertCooldownMinutes,
        )
        appPreferences.setTradeProConfig(state.tradeProConfig)
        state.providerApiKeys.forEach { (provider, key) -> appPreferences.setApiKey(provider, key) }
        appPreferences.setBackendBaseUrl(state.backendBaseUrl)
        _uiState.update { it.copy(saved = true) }
    }
}
