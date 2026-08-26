package com.foxtrader.app.domain.usecase.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.foxtrader.app.di.IoDispatcher
import com.foxtrader.app.domain.model.AlertConfig
import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.PositionSizingMethod
import com.foxtrader.app.domain.model.RiskConfig
import com.foxtrader.app.domain.model.SmcVisualMode
import com.foxtrader.app.domain.model.StrategyBlueprint
import com.foxtrader.app.domain.model.SubscriptionPlan
import com.foxtrader.app.domain.model.SubscriptionState
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.WorkspaceProfile
import com.foxtrader.app.domain.model.tradepro.AlertRule
import com.foxtrader.app.domain.model.LitXConfig
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.SmtConfig
import com.foxtrader.app.domain.model.SmsConfig
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import com.foxtrader.app.domain.usecase.chart.ChartLayout
import com.foxtrader.app.feature.chart.presentation.IndicatorToggles
import com.foxtrader.app.domain.usecase.performance.PerformanceMode
import com.foxtrader.app.domain.usecase.chart.ChartPanelSeed
import com.foxtrader.app.domain.usecase.chart.ChartViewportState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fox_settings")

/**
 * App-wide preferences — persisted via Jetpack DataStore.
 * Holds cross-feature settings; exposed as StateFlows for reactive UI.
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val securePrefs: SharedPreferences by lazy { createSecurePrefs() }

    private val _dataProvider = MutableStateFlow(DataProvider.DUKASCOPY)
    val dataProvider: StateFlow<DataProvider> = _dataProvider.asStateFlow()

    private val _defaultTimeframe = MutableStateFlow(Timeframe.M15)
    val defaultTimeframe: StateFlow<Timeframe> = _defaultTimeframe.asStateFlow()

    private val _darkMode = MutableStateFlow(true)
    val darkMode: StateFlow<Boolean> = _darkMode.asStateFlow()

    private val _appLockEnabled = MutableStateFlow(false)
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    /**
     * Optional user override for the FoxTrader backend origin (scheme/host/port).
     * Blank means "use the build-time default" ([NetworkModule]'s BASE_URL). Read
     * per-request by the dynamic base-URL interceptor so it can be changed at
     * runtime without rebuilding.
     */
    private val _backendBaseUrl = MutableStateFlow("")
    val backendBaseUrl: StateFlow<String> = _backendBaseUrl.asStateFlow()

    /**
     * Hot-cache ceiling per (symbol, timeframe). Higher keeps more scrollback
     * without refetching, at the cost of memory/storage; the repository prunes
     * to this after every fetch. Clamped to [MIN_CACHED_BARS]..[MAX_CACHED_BARS].
     */
    private val _maxCachedBars = MutableStateFlow(DEFAULT_MAX_CACHED_BARS)
    val maxCachedBars: StateFlow<Int> = _maxCachedBars.asStateFlow()

    /**
     * Chart performance mode (adaptive-quality ceiling). Default [PerformanceMode.SMOOTH]
     * imposes no cap, matching prior always-adaptive behaviour.
     */
    private val _performanceMode = MutableStateFlow(PerformanceMode.SMOOTH)
    val performanceMode: StateFlow<PerformanceMode> = _performanceMode.asStateFlow()

    private val _backgroundScanEnabled = MutableStateFlow(true)
    val backgroundScanEnabled: StateFlow<Boolean> = _backgroundScanEnabled.asStateFlow()

    private val _backgroundScanIntervalMinutes = MutableStateFlow(DEFAULT_BACKGROUND_SCAN_INTERVAL_MINUTES)
    val backgroundScanIntervalMinutes: StateFlow<Int> = _backgroundScanIntervalMinutes.asStateFlow()

    private val _aiMinConfluences = MutableStateFlow(DEFAULT_AI_MIN_CONFLUENCES)
    val aiMinConfluences: StateFlow<Int> = _aiMinConfluences.asStateFlow()

    private val _aiMinConfidence = MutableStateFlow(DEFAULT_AI_MIN_CONFIDENCE)
    val aiMinConfidence: StateFlow<Int> = _aiMinConfidence.asStateFlow()

    private val _aiAlertCooldownMinutes = MutableStateFlow(DEFAULT_AI_ALERT_COOLDOWN_MINUTES)
    val aiAlertCooldownMinutes: StateFlow<Int> = _aiAlertCooldownMinutes.asStateFlow()

    private val _riskConfig = MutableStateFlow(RiskConfig())
    val riskConfig: StateFlow<RiskConfig> = _riskConfig.asStateFlow()

    private val _alertConfig = MutableStateFlow(AlertConfig())
    val alertConfig: StateFlow<AlertConfig> = _alertConfig.asStateFlow()

    private val _apiKeys = MutableStateFlow<Map<DataProvider, String>>(emptyMap())
    val apiKeys: StateFlow<Map<DataProvider, String>> = _apiKeys.asStateFlow()

    private val _multiChartPreferences = MutableStateFlow<PersistedMultiChartState?>(null)
    val multiChartPreferences: StateFlow<PersistedMultiChartState?> = _multiChartPreferences.asStateFlow()

    /** Opt-in crash reporting. Defaults OFF — the user must explicitly enable it. */
    private val _crashReportingEnabled = MutableStateFlow(false)
    val crashReportingEnabled: StateFlow<Boolean> = _crashReportingEnabled.asStateFlow()

    /**
     * First-run educational-tool disclaimer gate. Defaults OFF (not acknowledged)
     * so the disclaimer is surfaced before any analysis on first launch, and is
     * persisted once the user acknowledges it so it is never shown again.
     */
    private val _disclaimerAcknowledged = MutableStateFlow(false)
    val disclaimerAcknowledged: StateFlow<Boolean> = _disclaimerAcknowledged.asStateFlow()

    private val _workspaceProfile = MutableStateFlow(WorkspaceProfile())
    val workspaceProfile: StateFlow<WorkspaceProfile> = _workspaceProfile.asStateFlow()

    private val _subscription = MutableStateFlow(SubscriptionState())
    val subscription: StateFlow<SubscriptionState> = _subscription.asStateFlow()

    private val _smcVisualMode = MutableStateFlow(SmcVisualMode.PROFESSIONAL)
    val smcVisualMode: StateFlow<SmcVisualMode> = _smcVisualMode.asStateFlow()

    private val _strategyBlueprints = MutableStateFlow<List<StrategyBlueprint>>(emptyList())
    val strategyBlueprints: StateFlow<List<StrategyBlueprint>> = _strategyBlueprints.asStateFlow()

    /** One-shot navigation handoff from Strategy Builder to Backtesting Lab. */
    private val _requestedBacktestBlueprintId = MutableStateFlow<String?>(null)

    private val _tradeProConfig = MutableStateFlow(TradeProConfig())
    val tradeProConfig: StateFlow<TradeProConfig> = _tradeProConfig.asStateFlow()

    /** LIT X Institutional Framework config (opt-in; defaults to disabled). */
    private val _litXConfig = MutableStateFlow(LitXConfig())
    val litXConfig: StateFlow<LitXConfig> = _litXConfig.asStateFlow()

    private val _litConfig = MutableStateFlow(LitConfig())
    val litConfig: StateFlow<LitConfig> = _litConfig.asStateFlow()

    private val _smtConfig = MutableStateFlow(SmtConfig())
    val smtConfig: StateFlow<SmtConfig> = _smtConfig.asStateFlow()

    private val _smsConfig = MutableStateFlow(SmsConfig())
    val smsConfig: StateFlow<SmsConfig> = _smsConfig.asStateFlow()

    /**
     * Chart indicator selection and every tuned study parameter.
     *
     * Previously this lived only in ChartViewModel state, so closing the app
     * discarded the trader's entire workspace: which studies were on, and every
     * period, threshold and mode they had tuned. Persisting it makes the chart
     * open the way it was left, which is the baseline expectation for a charting
     * tool.
     */
    private val _indicatorToggles = MutableStateFlow(IndicatorToggles())
    val indicatorToggles: StateFlow<IndicatorToggles> = _indicatorToggles.asStateFlow()

    /**
     * Flips true once the DataStore read has populated every flow above.
     *
     * Until then each flow still reports its compile-time default, which is
     * indistinguishable from a user who genuinely saved the defaults. A consumer
     * that restores state exactly once must gate on this or it will race the
     * load and restore nothing.
     */
    private val _hydrated = MutableStateFlow(false)
    val hydrated: StateFlow<Boolean> = _hydrated.asStateFlow()

    private val _tradeProAlertRules = MutableStateFlow<List<AlertRule>>(emptyList())
    val tradeProAlertRules: StateFlow<List<AlertRule>> = _tradeProAlertRules.asStateFlow()

    init {
        // Load persisted values into StateFlows on init.
        scope.launch {
            context.dataStore.data.collect { prefs ->
                _darkMode.value = prefs[KEY_DARK_MODE] ?: true
                _appLockEnabled.value = prefs[KEY_APP_LOCK] ?: false
                _backendBaseUrl.value = prefs[KEY_BACKEND_BASE_URL].orEmpty()
                _maxCachedBars.value = prefs[KEY_MAX_CACHED_BARS] ?: DEFAULT_MAX_CACHED_BARS
                _performanceMode.value = prefs[KEY_PERFORMANCE_MODE]
                    ?.let { name -> runCatching { PerformanceMode.valueOf(name) }.getOrNull() }
                    ?: PerformanceMode.SMOOTH
                _backgroundScanEnabled.value = prefs[KEY_BACKGROUND_SCAN_ENABLED] ?: true
                _backgroundScanIntervalMinutes.value = (
                    prefs[KEY_BACKGROUND_SCAN_INTERVAL_MINUTES]
                        ?: DEFAULT_BACKGROUND_SCAN_INTERVAL_MINUTES
                    ).coerceIn(
                        MIN_BACKGROUND_SCAN_INTERVAL_MINUTES,
                        MAX_BACKGROUND_SCAN_INTERVAL_MINUTES,
                    )
                _aiMinConfluences.value = (prefs[KEY_AI_MIN_CONFLUENCES] ?: DEFAULT_AI_MIN_CONFLUENCES)
                    .coerceIn(MIN_AI_CONFLUENCES, MAX_AI_CONFLUENCES)
                _aiMinConfidence.value = (prefs[KEY_AI_MIN_CONFIDENCE] ?: DEFAULT_AI_MIN_CONFIDENCE)
                    .coerceIn(MIN_AI_CONFIDENCE, MAX_AI_CONFIDENCE)
                _aiAlertCooldownMinutes.value = (
                    prefs[KEY_AI_ALERT_COOLDOWN_MINUTES]
                        ?: DEFAULT_AI_ALERT_COOLDOWN_MINUTES
                    ).coerceIn(MIN_AI_ALERT_COOLDOWN_MINUTES, MAX_AI_ALERT_COOLDOWN_MINUTES)
                _dataProvider.value = prefs[KEY_PROVIDER]?.let { name ->
                    runCatching { DataProvider.valueOf(name) }.getOrNull()
                        ?.takeIf { it.implemented }
                        ?: DataProvider.DUKASCOPY
                } ?: DataProvider.DUKASCOPY
                _defaultTimeframe.value = prefs[KEY_DEFAULT_TIMEFRAME]?.let { label ->
                    Timeframe.fromLabel(label)
                } ?: Timeframe.M15
                _riskConfig.value = readRiskConfig(prefs)
                _alertConfig.value = readAlertConfig(prefs)
                val storedApiKeys = loadPersistedApiKeys()
                val legacyAlphaKey = prefs[KEY_ALPHA_VANTAGE_API_KEY].orEmpty()
                if (
                    legacyAlphaKey.isNotBlank() &&
                    storedApiKeys[DataProvider.ALPHA_VANTAGE].isNullOrBlank()
                ) {
                    setApiKey(DataProvider.ALPHA_VANTAGE, legacyAlphaKey)
                    context.dataStore.edit { it.remove(KEY_ALPHA_VANTAGE_API_KEY) }
                } else {
                    _apiKeys.value = storedApiKeys
                }
                _multiChartPreferences.value = prefs[KEY_MULTI_CHART_STATE]
                    ?.let(::decodeMultiChartState)
                    ?: PersistedMultiChartState()
                _crashReportingEnabled.value = prefs[KEY_CRASH_REPORTING_ENABLED] ?: false
                _disclaimerAcknowledged.value = prefs[KEY_DISCLAIMER_ACKNOWLEDGED] ?: false
                _workspaceProfile.value = prefs[KEY_WORKSPACE_PROFILE]?.let { raw ->
                    runCatching { json.decodeFromString<WorkspaceProfile>(raw) }.getOrDefault(WorkspaceProfile())
                } ?: WorkspaceProfile(
                    // Existing installs already passed the disclaimer. Don't
                    // force the new desk setup on them or on instrumentation.
                    completed = prefs[KEY_DISCLAIMER_ACKNOWLEDGED] ?: false,
                )
                _subscription.value = prefs[KEY_SUBSCRIPTION]?.let { raw ->
                    runCatching { json.decodeFromString<SubscriptionState>(raw) }.getOrDefault(SubscriptionState())
                } ?: SubscriptionState()
                _smcVisualMode.value = prefs[KEY_SMC_VISUAL_MODE]?.let { name ->
                    runCatching { SmcVisualMode.valueOf(name) }.getOrDefault(SmcVisualMode.PROFESSIONAL)
                } ?: SmcVisualMode.PROFESSIONAL
                _strategyBlueprints.value = prefs[KEY_STRATEGY_BLUEPRINTS]?.let { raw ->
                    runCatching { json.decodeFromString<List<StrategyBlueprint>>(raw) }.getOrDefault(emptyList())
                } ?: emptyList()
                _tradeProConfig.value = prefs[KEY_TRADEPRO_CONFIG]?.let { raw ->
                    runCatching { json.decodeFromString<TradeProConfig>(raw) }.getOrDefault(TradeProConfig())
                } ?: TradeProConfig()
                _litXConfig.value = prefs[KEY_LITX_CONFIG]?.let { raw ->
                    runCatching { json.decodeFromString<LitXConfig>(raw) }.getOrDefault(LitXConfig())
                }?.sanitized() ?: LitXConfig()
                _litConfig.value = prefs[KEY_LIT_CONFIG]?.let { raw ->
                    runCatching { json.decodeFromString<LitConfig>(raw) }.getOrDefault(LitConfig())
                }?.sanitized() ?: LitConfig()
                _smtConfig.value = prefs[KEY_SMT_CONFIG]?.let { raw ->
                    runCatching { json.decodeFromString<SmtConfig>(raw) }.getOrDefault(SmtConfig())
                }?.sanitized() ?: SmtConfig()
                _smsConfig.value = prefs[KEY_SMS_CONFIG]?.let { raw ->
                    runCatching { json.decodeFromString<SmsConfig>(raw) }.getOrDefault(SmsConfig())
                }?.sanitized() ?: SmsConfig()
                _indicatorToggles.value = prefs[KEY_INDICATOR_TOGGLES]?.let { raw ->
                    // A stored payload written by an older build can be missing
                    // fields or carry a renamed enum. Falling back to defaults
                    // costs the trader their layout once; throwing here would
                    // make the chart unopenable until app data is cleared.
                    runCatching { json.decodeFromString<IndicatorToggles>(raw) }.getOrNull()
                }?.let { it.copy(settings = it.settings.sanitized()) } ?: IndicatorToggles()
                _tradeProAlertRules.value = prefs[KEY_TRADEPRO_ALERT_RULES]?.let { raw ->
                    runCatching { json.decodeFromString<List<AlertRule>>(raw) }.getOrDefault(emptyList())
                } ?: emptyList()
                // Published last: every StateFlow above now holds its persisted
                // value. Consumers that must restore exactly once (rather than
                // react to their own later writes) wait on this instead of
                // sampling a flow that may still be reporting its default.
                _hydrated.value = true
            }
        }
    }

    fun setCrashReportingEnabled(enabled: Boolean) {
        _crashReportingEnabled.value = enabled
        scope.launch { context.dataStore.edit { it[KEY_CRASH_REPORTING_ENABLED] = enabled } }
    }

    /** Persist that the user has acknowledged the educational-tool disclaimer. */
    fun setDisclaimerAcknowledged(acknowledged: Boolean) {
        _disclaimerAcknowledged.value = acknowledged
        scope.launch { context.dataStore.edit { it[KEY_DISCLAIMER_ACKNOWLEDGED] = acknowledged } }
    }

    fun setWorkspaceProfile(profile: WorkspaceProfile) {
        _workspaceProfile.value = profile
        scope.launch { context.dataStore.edit { it[KEY_WORKSPACE_PROFILE] = json.encodeToString(profile) } }
    }

    fun setSubscription(state: SubscriptionState) {
        _subscription.value = state
        scope.launch { context.dataStore.edit { it[KEY_SUBSCRIPTION] = json.encodeToString(state) } }
    }

    fun startProTrial(durationMs: Long = DEFAULT_TRIAL_DURATION_MS) {
        setSubscription(
            SubscriptionState(
                plan = SubscriptionPlan.TRIAL,
                trialEndsAtEpochMs = System.currentTimeMillis() + durationMs,
            ),
        )
    }

    fun setSmcVisualMode(mode: SmcVisualMode) {
        _smcVisualMode.value = mode
        scope.launch { context.dataStore.edit { it[KEY_SMC_VISUAL_MODE] = mode.name } }
    }

    fun setStrategyBlueprints(blueprints: List<StrategyBlueprint>) {
        _strategyBlueprints.value = blueprints
        scope.launch { context.dataStore.edit { it[KEY_STRATEGY_BLUEPRINTS] = json.encodeToString(blueprints) } }
    }

    fun upsertStrategyBlueprint(blueprint: StrategyBlueprint) {
        val next = _strategyBlueprints.value
            .filterNot { it.id == blueprint.id } + blueprint
        setStrategyBlueprints(next)
    }

    fun deleteStrategyBlueprint(id: String) {
        setStrategyBlueprints(_strategyBlueprints.value.filterNot { it.id == id })
    }

    fun requestBacktestForBlueprint(id: String) {
        _requestedBacktestBlueprintId.value = id
    }

    fun consumeRequestedBacktestBlueprintId(): String? =
        _requestedBacktestBlueprintId.value.also { _requestedBacktestBlueprintId.value = null }

    fun setTradeProConfig(config: TradeProConfig) {
        _tradeProConfig.value = config
        scope.launch { context.dataStore.edit { it[KEY_TRADEPRO_CONFIG] = json.encodeToString(config) } }
    }

    fun setIndicatorToggles(toggles: IndicatorToggles) {
        val safe = toggles.copy(settings = toggles.settings.sanitized())
        _indicatorToggles.value = safe
        scope.launch { context.dataStore.edit { it[KEY_INDICATOR_TOGGLES] = json.encodeToString(safe) } }
    }

    fun setLitXConfig(config: LitXConfig) {
        val safe = config.sanitized()
        _litXConfig.value = safe
        scope.launch { context.dataStore.edit { it[KEY_LITX_CONFIG] = json.encodeToString(safe) } }
    }

    fun setLitConfig(config: LitConfig) {
        val safe = config.sanitized()
        _litConfig.value = safe
        scope.launch { context.dataStore.edit { it[KEY_LIT_CONFIG] = json.encodeToString(safe) } }
    }

    fun setSmtConfig(config: SmtConfig) {
        val safe = config.sanitized()
        _smtConfig.value = safe
        scope.launch { context.dataStore.edit { it[KEY_SMT_CONFIG] = json.encodeToString(safe) } }
    }

    fun setSmsConfig(config: SmsConfig) {
        val safe = config.sanitized()
        _smsConfig.value = safe
        scope.launch { context.dataStore.edit { it[KEY_SMS_CONFIG] = json.encodeToString(safe) } }
    }

    fun setTradeProAlertRules(rules: List<AlertRule>) {
        _tradeProAlertRules.value = rules
        scope.launch { context.dataStore.edit { it[KEY_TRADEPRO_ALERT_RULES] = json.encodeToString(rules) } }
    }

    fun setDataProvider(provider: DataProvider) {
        _dataProvider.value = provider
        scope.launch { context.dataStore.edit { it[KEY_PROVIDER] = provider.name } }
    }

    fun setDefaultTimeframe(timeframe: Timeframe) {
        _defaultTimeframe.value = timeframe
        scope.launch { context.dataStore.edit { it[KEY_DEFAULT_TIMEFRAME] = timeframe.label } }
    }

    fun setDarkMode(enabled: Boolean) {
        _darkMode.value = enabled
        scope.launch { context.dataStore.edit { it[KEY_DARK_MODE] = enabled } }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        _appLockEnabled.value = enabled
        scope.launch { context.dataStore.edit { it[KEY_APP_LOCK] = enabled } }
    }

    /** Persist the backend origin override. Blank clears it (revert to default). */
    fun setBackendBaseUrl(url: String) {
        val normalized = url.trim()
        _backendBaseUrl.value = normalized
        scope.launch { context.dataStore.edit { it[KEY_BACKEND_BASE_URL] = normalized } }
    }

    /** Persist the hot-cache ceiling, clamped to the supported range. */
    fun setMaxCachedBars(value: Int) {
        val clamped = value.coerceIn(MIN_CACHED_BARS, MAX_CACHED_BARS)
        _maxCachedBars.value = clamped
        scope.launch { context.dataStore.edit { it[KEY_MAX_CACHED_BARS] = clamped } }
    }

    /** Persist the chart performance mode. */
    fun setPerformanceMode(mode: PerformanceMode) {
        _performanceMode.value = mode
        scope.launch { context.dataStore.edit { it[KEY_PERFORMANCE_MODE] = mode.name } }
    }

    fun setBackgroundScanEnabled(enabled: Boolean) {
        _backgroundScanEnabled.value = enabled
        scope.launch { context.dataStore.edit { it[KEY_BACKGROUND_SCAN_ENABLED] = enabled } }
    }

    fun setBackgroundScanIntervalMinutes(minutes: Int) {
        val coerced = minutes.coerceIn(
            MIN_BACKGROUND_SCAN_INTERVAL_MINUTES,
            MAX_BACKGROUND_SCAN_INTERVAL_MINUTES,
        )
        _backgroundScanIntervalMinutes.value = coerced
        scope.launch { context.dataStore.edit { it[KEY_BACKGROUND_SCAN_INTERVAL_MINUTES] = coerced } }
    }

    fun setBackgroundScanConfig(enabled: Boolean, intervalMinutes: Int) {
        val coerced = intervalMinutes.coerceIn(
            MIN_BACKGROUND_SCAN_INTERVAL_MINUTES,
            MAX_BACKGROUND_SCAN_INTERVAL_MINUTES,
        )
        _backgroundScanEnabled.value = enabled
        _backgroundScanIntervalMinutes.value = coerced
        scope.launch {
            context.dataStore.edit {
                it[KEY_BACKGROUND_SCAN_ENABLED] = enabled
                it[KEY_BACKGROUND_SCAN_INTERVAL_MINUTES] = coerced
            }
        }
    }

    fun setAiDecisionConfig(
        minConfluences: Int,
        minConfidence: Int,
        alertCooldownMinutes: Int,
    ) {
        val confluences = minConfluences.coerceIn(MIN_AI_CONFLUENCES, MAX_AI_CONFLUENCES)
        val confidence = minConfidence.coerceIn(MIN_AI_CONFIDENCE, MAX_AI_CONFIDENCE)
        val cooldown = alertCooldownMinutes.coerceIn(
            MIN_AI_ALERT_COOLDOWN_MINUTES,
            MAX_AI_ALERT_COOLDOWN_MINUTES,
        )
        _aiMinConfluences.value = confluences
        _aiMinConfidence.value = confidence
        _aiAlertCooldownMinutes.value = cooldown
        scope.launch {
            context.dataStore.edit {
                it[KEY_AI_MIN_CONFLUENCES] = confluences
                it[KEY_AI_MIN_CONFIDENCE] = confidence
                it[KEY_AI_ALERT_COOLDOWN_MINUTES] = cooldown
            }
        }
    }

    fun setRiskConfig(config: RiskConfig) {
        _riskConfig.value = config
        scope.launch {
            context.dataStore.edit {
                it[KEY_RISK_PERCENT_PER_TRADE] = config.riskPercentPerTrade
                it[KEY_RISK_SIZING_METHOD] = config.sizingMethod.name
                it[KEY_RISK_MAX_DAILY_LOSS_PERCENT] = config.maxDailyLossPercent
                it[KEY_RISK_MAX_DRAWDOWN_PERCENT] = config.maxDrawdownPercent
                it[KEY_RISK_MAX_CONSECUTIVE_LOSSES] = config.maxConsecutiveLosses
                it[KEY_RISK_ATR_STOP_MULTIPLIER] = config.atrStopMultiplier
            }
        }
    }

    fun setAlertConfig(config: AlertConfig) {
        _alertConfig.value = config
        scope.launch {
            context.dataStore.edit {
                it[KEY_ALERT_SOUND_ENABLED] = config.soundEnabled
                it[KEY_ALERT_MIN_PRIORITY] = config.minPriority.name
                it[KEY_ALERT_MAX_PER_HOUR] = config.maxAlertsPerHour
                it[KEY_ALERT_COOLDOWN_MS] = config.cooldownMs
            }
        }
    }

    fun setApiKey(provider: DataProvider, key: String) {
        if (!provider.requiresApiKey) return

        val normalizedKey = key.trim()
        val updatedKeys = _apiKeys.value.toMutableMap()

        if (normalizedKey.isBlank()) {
            updatedKeys.remove(provider)
            securePrefs.edit().remove(apiKeyPreferenceName(provider)).apply()
        } else {
            updatedKeys[provider] = normalizedKey
            securePrefs.edit().putString(apiKeyPreferenceName(provider), normalizedKey).apply()
        }

        _apiKeys.value = updatedKeys.toMap()
    }

    /**
     * Retrieve a provider API key directly from the synchronous encrypted prefs
     * so it is available immediately at app start (the StateFlow is populated
     * asynchronously).
     */
    fun getApiKey(provider: DataProvider): String? =
        securePrefs.getString(apiKeyPreferenceName(provider), null)?.trim()?.takeIf { it.isNotBlank() }

    fun setMultiChartPreferences(state: PersistedMultiChartState) {
        _multiChartPreferences.value = state
        scope.launch {
            context.dataStore.edit {
                it[KEY_MULTI_CHART_STATE] = json.encodeToString(state)
            }
        }
    }

    fun canGoLive(): Boolean {
        val p = _dataProvider.value
        if (!p.supportsLive || !p.implemented) return false
        if (p.requiresApiKey && getApiKey(p).isNullOrBlank()) return false
        return true
    }

    private fun readRiskConfig(prefs: Preferences): RiskConfig {
        val defaults = RiskConfig()
        val sizingMethod = prefs[KEY_RISK_SIZING_METHOD]?.let { name ->
            runCatching { PositionSizingMethod.valueOf(name) }.getOrDefault(defaults.sizingMethod)
        } ?: defaults.sizingMethod
        return defaults.copy(
            sizingMethod = sizingMethod,
            riskPercentPerTrade = prefs[KEY_RISK_PERCENT_PER_TRADE] ?: defaults.riskPercentPerTrade,
            maxDailyLossPercent = prefs[KEY_RISK_MAX_DAILY_LOSS_PERCENT] ?: defaults.maxDailyLossPercent,
            maxDrawdownPercent = prefs[KEY_RISK_MAX_DRAWDOWN_PERCENT] ?: defaults.maxDrawdownPercent,
            maxConsecutiveLosses = prefs[KEY_RISK_MAX_CONSECUTIVE_LOSSES] ?: defaults.maxConsecutiveLosses,
            atrStopMultiplier = prefs[KEY_RISK_ATR_STOP_MULTIPLIER] ?: defaults.atrStopMultiplier,
        )
    }

    private fun readAlertConfig(prefs: Preferences): AlertConfig {
        val defaults = AlertConfig()
        val minPriority = prefs[KEY_ALERT_MIN_PRIORITY]?.let { name ->
            runCatching { AlertPriority.valueOf(name) }.getOrDefault(defaults.minPriority)
        } ?: defaults.minPriority
        return defaults.copy(
            soundEnabled = prefs[KEY_ALERT_SOUND_ENABLED] ?: defaults.soundEnabled,
            minPriority = minPriority,
            maxAlertsPerHour = prefs[KEY_ALERT_MAX_PER_HOUR] ?: defaults.maxAlertsPerHour,
            cooldownMs = prefs[KEY_ALERT_COOLDOWN_MS] ?: defaults.cooldownMs,
        )
    }

    private fun loadPersistedApiKeys(): Map<DataProvider, String> =
        DataProvider.entries
            .filter { it.requiresApiKey }
            .mapNotNull { provider ->
                securePrefs.getString(apiKeyPreferenceName(provider), null)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { provider to it }
            }
            .toMap()

    private fun createSecurePrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            android.util.Log.w(
                TAG,
                "EncryptedSharedPreferences $SECURE_PREFS_FILE_NAME corrupted or unreadable after restore — wiping and recreating empty store",
                e,
            )
            try {
                context.deleteSharedPreferences(SECURE_PREFS_FILE_NAME)
            } catch (_: Exception) {
                // Best-effort delete.
            }
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    private fun apiKeyPreferenceName(provider: DataProvider): String =
        "provider_api_key_${provider.name.lowercase(Locale.ROOT)}"

    private fun decodeMultiChartState(raw: String): PersistedMultiChartState =
        runCatching { json.decodeFromString<PersistedMultiChartState>(raw) }
            .getOrDefault(PersistedMultiChartState())

    private companion object {
        const val TAG = "AppPreferences"
        const val SECURE_PREFS_FILE_NAME = "fox_provider_keys"
        const val MIN_BACKGROUND_SCAN_INTERVAL_MINUTES = 15
        const val MAX_BACKGROUND_SCAN_INTERVAL_MINUTES = 240
        const val DEFAULT_BACKGROUND_SCAN_INTERVAL_MINUTES = 15
        const val MIN_AI_CONFLUENCES = 1
        const val MAX_AI_CONFLUENCES = 9
        const val DEFAULT_AI_MIN_CONFLUENCES = 5
        const val MIN_AI_CONFIDENCE = 10
        const val MAX_AI_CONFIDENCE = 100
        const val DEFAULT_AI_MIN_CONFIDENCE = 55
        const val MIN_AI_ALERT_COOLDOWN_MINUTES = 1
        const val MAX_AI_ALERT_COOLDOWN_MINUTES = 60
        const val DEFAULT_AI_ALERT_COOLDOWN_MINUTES = 5

        val json = Json { ignoreUnknownKeys = true }

        val KEY_DARK_MODE = booleanPreferencesKey("dark_mode")
        val KEY_APP_LOCK = booleanPreferencesKey("app_lock_enabled")
        val KEY_BACKEND_BASE_URL = stringPreferencesKey("backend_base_url")
        val KEY_MAX_CACHED_BARS = intPreferencesKey("max_cached_bars")
        val KEY_PERFORMANCE_MODE = stringPreferencesKey("performance_mode")

        /** Hot-cache ceiling bounds (bars per symbol/timeframe). */
        const val DEFAULT_MAX_CACHED_BARS = 5_000
        const val MIN_CACHED_BARS = 1_000
        const val MAX_CACHED_BARS = 20_000
        val KEY_BACKGROUND_SCAN_ENABLED = booleanPreferencesKey("background_scan_enabled")
        val KEY_BACKGROUND_SCAN_INTERVAL_MINUTES = intPreferencesKey("background_scan_interval_minutes")
        val KEY_AI_MIN_CONFLUENCES = intPreferencesKey("ai_min_confluences")
        val KEY_AI_MIN_CONFIDENCE = intPreferencesKey("ai_min_confidence")
        val KEY_AI_ALERT_COOLDOWN_MINUTES = intPreferencesKey("ai_alert_cooldown_minutes")
        val KEY_RISK_PERCENT_PER_TRADE = doublePreferencesKey("risk_percent_per_trade")
        val KEY_RISK_SIZING_METHOD = stringPreferencesKey("risk_sizing_method")
        val KEY_RISK_MAX_DAILY_LOSS_PERCENT = doublePreferencesKey("risk_max_daily_loss_percent")
        val KEY_RISK_MAX_DRAWDOWN_PERCENT = doublePreferencesKey("risk_max_drawdown_percent")
        val KEY_RISK_MAX_CONSECUTIVE_LOSSES = intPreferencesKey("risk_max_consecutive_losses")
        val KEY_RISK_ATR_STOP_MULTIPLIER = doublePreferencesKey("risk_atr_stop_multiplier")
        val KEY_ALERT_SOUND_ENABLED = booleanPreferencesKey("alert_sound_enabled")
        val KEY_ALERT_MIN_PRIORITY = stringPreferencesKey("alert_min_priority")
        val KEY_ALERT_MAX_PER_HOUR = intPreferencesKey("alert_max_per_hour")
        val KEY_ALERT_COOLDOWN_MS = longPreferencesKey("alert_cooldown_ms")
        val KEY_PROVIDER = stringPreferencesKey("data_provider")
        val KEY_DEFAULT_TIMEFRAME = stringPreferencesKey("default_timeframe")
        val KEY_ALPHA_VANTAGE_API_KEY = stringPreferencesKey("alpha_vantage_api_key")
        val KEY_MULTI_CHART_STATE = stringPreferencesKey("multi_chart_state")
        val KEY_CRASH_REPORTING_ENABLED = booleanPreferencesKey("crash_reporting_enabled")
        val KEY_DISCLAIMER_ACKNOWLEDGED = booleanPreferencesKey("disclaimer_acknowledged")
        val KEY_WORKSPACE_PROFILE = stringPreferencesKey("workspace_profile")
        val KEY_SUBSCRIPTION = stringPreferencesKey("subscription_state")
        val KEY_SMC_VISUAL_MODE = stringPreferencesKey("smc_visual_mode")
        val KEY_STRATEGY_BLUEPRINTS = stringPreferencesKey("strategy_blueprints")
        const val DEFAULT_TRIAL_DURATION_MS = 14L * 24L * 60L * 60L * 1000L
        val KEY_TRADEPRO_CONFIG = stringPreferencesKey("tradepro_config")
        val KEY_LITX_CONFIG = stringPreferencesKey("litx_config")
        val KEY_LIT_CONFIG = stringPreferencesKey("lit_phase13_config")
        val KEY_SMT_CONFIG = stringPreferencesKey("smt_phase13_config")
        val KEY_SMS_CONFIG = stringPreferencesKey("sms_phase13_config")
        val KEY_INDICATOR_TOGGLES = stringPreferencesKey("chart_indicator_toggles")
        val KEY_TRADEPRO_ALERT_RULES = stringPreferencesKey("tradepro_alert_rules")

    }
}

@Serializable
data class PersistedMultiChartPanel(
    val symbol: String = "EURUSD",
    val timeframe: Timeframe = Timeframe.M15,
    val viewport: ChartViewportState? = null,
)

@Serializable
enum class PersistedCrosshairSource { NONE, PRIMARY, PANEL }

@Serializable
data class PersistedMultiChartState(
    val layout: ChartLayout = ChartLayout.SINGLE,
    val linkedToPrimary: Boolean = true,
    val symbolLinkEnabled: Boolean = true,
    val timeframeLinkEnabled: Boolean = true,
    val crosshairSyncEnabled: Boolean = true,
    val activePanelIndex: Int = 0,
    val primaryViewport: ChartViewportState? = null,
    val syncedCrosshairTimestamp: Long? = null,
    val syncedCrosshairSource: PersistedCrosshairSource = PersistedCrosshairSource.NONE,
    val syncedCrosshairPanelIndex: Int? = null,
    val panels: List<PersistedMultiChartPanel> = listOf(PersistedMultiChartPanel()),
) {
    fun panelSeeds(): List<ChartPanelSeed> = panels.map { ChartPanelSeed(it.symbol, it.timeframe) }
}
