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
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import com.foxtrader.app.domain.usecase.chart.ChartLayout
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

    private val _dataProvider = MutableStateFlow(DataProvider.SAMPLE)
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

    private val _tradeProConfig = MutableStateFlow(TradeProConfig())
    val tradeProConfig: StateFlow<TradeProConfig> = _tradeProConfig.asStateFlow()

    /** LIT X Institutional Framework config (opt-in; defaults to disabled). */
    private val _litXConfig = MutableStateFlow(LitXConfig())
    val litXConfig: StateFlow<LitXConfig> = _litXConfig.asStateFlow()

    private val _tradeProAlertRules = MutableStateFlow<List<AlertRule>>(emptyList())
    val tradeProAlertRules: StateFlow<List<AlertRule>> = _tradeProAlertRules.asStateFlow()

    private val _metaApiToken = MutableStateFlow<String?>(null)
    val metaApiToken: StateFlow<String?> = _metaApiToken.asStateFlow()

    private val _metaApiAccountId = MutableStateFlow<String?>(null)
    val metaApiAccountId: StateFlow<String?> = _metaApiAccountId.asStateFlow()

    private val _metaApiLastLogin = MutableStateFlow<Int?>(null)
    private val _metaApiLastServer = MutableStateFlow<String?>(null)
    private val _metaApiAccountName = MutableStateFlow<String?>(null)

    /**
     * Whether live MT4 order execution is enabled. Defaults OFF — the user must
     * explicitly (and deliberately) enable it from the MT4 account screen after
     * the safety pipeline is in place. A persisted, user-confirmed setting.
     */
    private val _mt4LiveModeEnabled = MutableStateFlow(false)
    val mt4LiveModeEnabled: StateFlow<Boolean> = _mt4LiveModeEnabled.asStateFlow()

    /**
     * Emergency kill switch. When true, no live MT4 order may be placed. Set by
     * the UI's kill switch and cleared only by explicit user action.
     */
    private val _mt4KillSwitch = MutableStateFlow(false)
    val mt4KillSwitch: StateFlow<Boolean> = _mt4KillSwitch.asStateFlow()

    /** Max age (ms) of the reference quote before a live order is rejected. */
    private val _mt4StaleQuoteTimeoutMs = MutableStateFlow(DEFAULT_MT4_STALE_QUOTE_MS)
    val mt4StaleQuoteTimeoutMs: StateFlow<Long> = _mt4StaleQuoteTimeoutMs.asStateFlow()

    /** Max age (ms) of the user's order confirmation before it is considered stale. */
    private val _mt4ConfirmationTimeoutMs = MutableStateFlow(DEFAULT_MT4_CONFIRMATION_MS)
    val mt4ConfirmationTimeoutMs: StateFlow<Long> = _mt4ConfirmationTimeoutMs.asStateFlow()

    /** Minimum free margin (account currency) required to place a live order; 0 = off. */
    private val _mt4MinFreeMargin = MutableStateFlow(0.0)
    val mt4MinFreeMargin: StateFlow<Double> = _mt4MinFreeMargin.asStateFlow()

    /** Ceiling on the day's realized loss (account currency) before live orders block; 0 = off. */
    private val _mt4MaxDailyLoss = MutableStateFlow(0.0)
    val mt4MaxDailyLoss: StateFlow<Double> = _mt4MaxDailyLoss.asStateFlow()

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
                    runCatching { DataProvider.valueOf(name) }.getOrDefault(DataProvider.SAMPLE)
                } ?: DataProvider.SAMPLE
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
                } ?: LitXConfig()
                _tradeProAlertRules.value = prefs[KEY_TRADEPRO_ALERT_RULES]?.let { raw ->
                    runCatching { json.decodeFromString<List<AlertRule>>(raw) }.getOrDefault(emptyList())
                } ?: emptyList()
                _metaApiToken.value = securePrefs.getString(SECURE_KEY_META_API_TOKEN, null)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                _metaApiAccountId.value = securePrefs.getString(SECURE_KEY_META_API_ACCOUNT_ID, null)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                _metaApiLastLogin.value = securePrefs.getInt(SECURE_KEY_META_API_LAST_LOGIN, -1).takeIf { it > 0 }
                _metaApiLastServer.value = securePrefs.getString(SECURE_KEY_META_API_LAST_SERVER, null)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                _metaApiAccountName.value = securePrefs.getString(SECURE_KEY_META_API_ACCOUNT_NAME, null)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                _mt4LiveModeEnabled.value = prefs[KEY_MT4_LIVE_MODE] ?: false
                _mt4KillSwitch.value = prefs[KEY_MT4_KILL_SWITCH] ?: false
                _mt4StaleQuoteTimeoutMs.value =
                    (prefs[KEY_MT4_STALE_QUOTE_TIMEOUT] ?: DEFAULT_MT4_STALE_QUOTE_MS)
                        .coerceIn(MIN_MT4_STALE_QUOTE_MS, MAX_MT4_STALE_QUOTE_MS)
                _mt4ConfirmationTimeoutMs.value =
                    (prefs[KEY_MT4_CONFIRMATION_TIMEOUT] ?: DEFAULT_MT4_CONFIRMATION_MS)
                        .coerceIn(MIN_MT4_CONFIRMATION_MS, MAX_MT4_CONFIRMATION_MS)
                _mt4MinFreeMargin.value = prefs[KEY_MT4_MIN_FREE_MARGIN] ?: 0.0
                _mt4MaxDailyLoss.value = prefs[KEY_MT4_MAX_DAILY_LOSS] ?: 0.0
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

    fun setTradeProConfig(config: TradeProConfig) {
        _tradeProConfig.value = config
        scope.launch { context.dataStore.edit { it[KEY_TRADEPRO_CONFIG] = json.encodeToString(config) } }
    }

    fun setLitXConfig(config: LitXConfig) {
        _litXConfig.value = config
        scope.launch { context.dataStore.edit { it[KEY_LITX_CONFIG] = json.encodeToString(config) } }
    }

    fun setTradeProAlertRules(rules: List<AlertRule>) {
        _tradeProAlertRules.value = rules
        scope.launch { context.dataStore.edit { it[KEY_TRADEPRO_ALERT_RULES] = json.encodeToString(rules) } }
    }

    /**
     * Persist the MetaApi auth token to encrypted shared preferences.
     * This token is used to authenticate REST and WebSocket calls to MetaApi.
     */
    fun setMetaApiToken(token: String) {
        val normalized = token.trim()
        if (normalized.isBlank()) {
            _metaApiToken.value = null
            securePrefs.edit().remove(SECURE_KEY_META_API_TOKEN).apply()
        } else {
            _metaApiToken.value = normalized
            securePrefs.edit().putString(SECURE_KEY_META_API_TOKEN, normalized).apply()
        }
    }

    /**
     * Retrieve the currently stored MetaApi token (null if not set).
     *
     * Read directly from the synchronous encrypted prefs rather than the
     * asynchronously-populated StateFlow so callers (e.g. the MT4 repository at
     * app start) see the persisted value even before the background load runs.
     */
    fun getMetaApiToken(): String? =
        securePrefs.getString(SECURE_KEY_META_API_TOKEN, null)?.trim()?.takeIf { it.isNotBlank() }

    /**
     * Persist the MetaApi account ID to encrypted shared preferences.
     * This allows reconnects to skip the deploy step when the account
     * has already been provisioned for the current login/server.
     */
    fun setMetaApiAccountId(accountId: String?) {
        val normalized = accountId?.trim()?.takeIf { it.isNotBlank() }
        _metaApiAccountId.value = normalized
        if (normalized == null) {
            securePrefs.edit().remove(SECURE_KEY_META_API_ACCOUNT_ID).apply()
        } else {
            securePrefs.edit().putString(SECURE_KEY_META_API_ACCOUNT_ID, normalized).apply()
        }
    }

    /** Retrieve the currently cached MetaApi account ID (null if none). */
    fun getMetaApiAccountId(): String? =
        securePrefs.getString(SECURE_KEY_META_API_ACCOUNT_ID, null)?.trim()?.takeIf { it.isNotBlank() }

    /** Persist the last connected MT4 login for prefilling the login form. */
    fun setMetaApiLastLogin(login: Int?) {
        val normalized = login?.takeIf { it > 0 }
        _metaApiLastLogin.value = normalized
        if (normalized == null) {
            securePrefs.edit().remove(SECURE_KEY_META_API_LAST_LOGIN).apply()
        } else {
            securePrefs.edit().putInt(SECURE_KEY_META_API_LAST_LOGIN, normalized).apply()
        }
    }

    /** The MT4 login used for the most recent connection, or null. */
    fun getMetaApiLastLogin(): Int? =
        securePrefs.getInt(SECURE_KEY_META_API_LAST_LOGIN, -1).takeIf { it > 0 }

    /** Persist the last connected MT4 server for prefilling the login form. */
    fun setMetaApiLastServer(server: String?) {
        val normalized = server?.trim()?.takeIf { it.isNotBlank() }
        _metaApiLastServer.value = normalized
        if (normalized == null) {
            securePrefs.edit().remove(SECURE_KEY_META_API_LAST_SERVER).apply()
        } else {
            securePrefs.edit().putString(SECURE_KEY_META_API_LAST_SERVER, normalized).apply()
        }
    }

    /** The MT4 server used for the most recent connection, or null. */
    fun getMetaApiLastServer(): String? =
        securePrefs.getString(SECURE_KEY_META_API_LAST_SERVER, null)?.trim()?.takeIf { it.isNotBlank() }

    /** Persist the connected account display name for showing in the account screen. */
    fun setMetaApiAccountName(name: String?) {
        val normalized = name?.trim()?.takeIf { it.isNotBlank() }
        _metaApiAccountName.value = normalized
        if (normalized == null) {
            securePrefs.edit().remove(SECURE_KEY_META_API_ACCOUNT_NAME).apply()
        } else {
            securePrefs.edit().putString(SECURE_KEY_META_API_ACCOUNT_NAME, normalized).apply()
        }
    }

    /** The connected account display name, or null. */
    fun getMetaApiAccountName(): String? =
        securePrefs.getString(SECURE_KEY_META_API_ACCOUNT_NAME, null)?.trim()?.takeIf { it.isNotBlank() }

    /**
     * Persisted live-MT4-execution switch. Callers must read this to build the
     * [com.foxtrader.app.domain.usecase.execution.ExecutionPolicy]; it is the
     * single persisted "go live" flag.
     */
    fun setMt4LiveModeEnabled(enabled: Boolean) {
        _mt4LiveModeEnabled.value = enabled
        scope.launch { context.dataStore.edit { it[KEY_MT4_LIVE_MODE] = enabled } }
    }

    fun isMt4LiveModeEnabled(): Boolean = _mt4LiveModeEnabled.value

    /** Persisted emergency kill switch; true = block all live MT4 orders. */
    fun setMt4KillSwitch(engaged: Boolean) {
        _mt4KillSwitch.value = engaged
        scope.launch { context.dataStore.edit { it[KEY_MT4_KILL_SWITCH] = engaged } }
    }

    fun isMt4KillSwitchEngaged(): Boolean = _mt4KillSwitch.value

    /** Set the max age (ms) of the reference quote before a live order is rejected. */
    fun setMt4StaleQuoteTimeoutMs(value: Long) {
        val clamped = value.coerceIn(MIN_MT4_STALE_QUOTE_MS, MAX_MT4_STALE_QUOTE_MS)
        _mt4StaleQuoteTimeoutMs.value = clamped
        scope.launch { context.dataStore.edit { it[KEY_MT4_STALE_QUOTE_TIMEOUT] = clamped } }
    }

    fun getMt4StaleQuoteTimeoutMs(): Long = _mt4StaleQuoteTimeoutMs.value

    /** Set the max age (ms) of the order confirmation before it is considered stale. */
    fun setMt4ConfirmationTimeoutMs(value: Long) {
        val clamped = value.coerceIn(MIN_MT4_CONFIRMATION_MS, MAX_MT4_CONFIRMATION_MS)
        _mt4ConfirmationTimeoutMs.value = clamped
        scope.launch { context.dataStore.edit { it[KEY_MT4_CONFIRMATION_TIMEOUT] = clamped } }
    }

    fun getMt4ConfirmationTimeoutMs(): Long = _mt4ConfirmationTimeoutMs.value

    /** Set the minimum free margin (account currency) required to place a live order; 0 = off. */
    fun setMt4MinFreeMargin(value: Double) {
        val normalized = if (value.isFinite()) value.coerceAtLeast(0.0) else 0.0
        _mt4MinFreeMargin.value = normalized
        scope.launch { context.dataStore.edit { it[KEY_MT4_MIN_FREE_MARGIN] = normalized } }
    }

    fun getMt4MinFreeMargin(): Double = _mt4MinFreeMargin.value

    /** Set the daily realized-loss ceiling (account currency) before live orders block; 0 = off. */
    fun setMt4MaxDailyLoss(value: Double) {
        val normalized = if (value.isFinite()) value.coerceAtLeast(0.0) else 0.0
        _mt4MaxDailyLoss.value = normalized
        scope.launch { context.dataStore.edit { it[KEY_MT4_MAX_DAILY_LOSS] = normalized } }
    }

    fun getMt4MaxDailyLoss(): Double = _mt4MaxDailyLoss.value

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
        if (!p.supportsLive) return false
        if (p.requiresApiKey && _apiKeys.value[p].isNullOrBlank()) return false
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
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun apiKeyPreferenceName(provider: DataProvider): String =
        "provider_api_key_${provider.name.lowercase(Locale.ROOT)}"

    private fun decodeMultiChartState(raw: String): PersistedMultiChartState =
        runCatching { json.decodeFromString<PersistedMultiChartState>(raw) }
            .getOrDefault(PersistedMultiChartState())

    private companion object {
        const val SECURE_PREFS_FILE_NAME = "fox_provider_keys"
        const val SECURE_KEY_META_API_TOKEN = "meta_api_token"
        const val SECURE_KEY_META_API_ACCOUNT_ID = "meta_api_account_id"
        const val SECURE_KEY_META_API_LAST_LOGIN = "meta_api_last_login"
        const val SECURE_KEY_META_API_LAST_SERVER = "meta_api_last_server"
        const val SECURE_KEY_META_API_ACCOUNT_NAME = "meta_api_account_name"
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
        val KEY_TRADEPRO_ALERT_RULES = stringPreferencesKey("tradepro_alert_rules")
        val KEY_MT4_LIVE_MODE = booleanPreferencesKey("mt4_live_mode")
        val KEY_MT4_KILL_SWITCH = booleanPreferencesKey("mt4_kill_switch")
        val KEY_MT4_STALE_QUOTE_TIMEOUT = longPreferencesKey("mt4_stale_quote_timeout")
        val KEY_MT4_CONFIRMATION_TIMEOUT = longPreferencesKey("mt4_confirmation_timeout")
        val KEY_MT4_MIN_FREE_MARGIN = doublePreferencesKey("mt4_min_free_margin")
        val KEY_MT4_MAX_DAILY_LOSS = doublePreferencesKey("mt4_max_daily_loss")

        const val DEFAULT_MT4_STALE_QUOTE_MS = 5_000L
        const val MIN_MT4_STALE_QUOTE_MS = 500L
        const val MAX_MT4_STALE_QUOTE_MS = 30_000L
        const val DEFAULT_MT4_CONFIRMATION_MS = 60_000L
        const val MIN_MT4_CONFIRMATION_MS = 5_000L
        const val MAX_MT4_CONFIRMATION_MS = 300_000L
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
