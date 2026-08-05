package com.foxtrader.app.feature.settings.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.AlertConfig
import com.foxtrader.app.domain.model.AuthState
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.PositionSizingMethod
import com.foxtrader.app.domain.model.LitXConfig
import com.foxtrader.app.domain.model.RiskConfig
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf

/**
 * AI Decision Engine configuration exposed in the Settings screen.
 */
@Immutable
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
 * Transient status of a one-shot connection test (never persisted).
 */
sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Testing : ConnectionTest
    data class Success(val candleCount: Int) : ConnectionTest
    data class Failure(val message: String) : ConnectionTest
}

/**
 * Immutable UI state for the Settings screen.
 */
@Immutable
data class SettingsUiState(
    val riskConfig: RiskConfig = RiskConfig(),
    val alertConfig: AlertConfig = AlertConfig(),
    val aiConfig: AiConfig = AiConfig(),
    val defaultTimeframe: Timeframe = Timeframe.M15,
    val dataProvider: DataProvider = DataProvider.SAMPLE,
    val providerApiKeys: ImmutableMap<DataProvider, String> = persistentMapOf(),
    val darkMode: Boolean = true,
    /** User override for the FoxTrader backend origin; blank = build default. */
    val backendBaseUrl: String = "",
    /** Hot-cache ceiling per market (bars); performance/memory tradeoff. */
    val maxCachedBars: Int = 5_000,
    val providerTest: ConnectionTest = ConnectionTest.Idle,
    val backendTest: ConnectionTest = ConnectionTest.Idle,
    val authState: AuthState = AuthState.UNAUTHENTICATED,
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
    // --- Security ---
    val appLockEnabled: Boolean = false,
    val biometricAvailable: Boolean = false,
    // --- Privacy ---
    val crashReportingEnabled: Boolean = false,
    // --- TRADEPRO ---
    val tradeProConfig: TradeProConfig = TradeProConfig(),
    val litXConfig: LitXConfig = LitXConfig(),
    val saved: Boolean = false,
) {
    val isLoggedIn: Boolean get() = authState == AuthState.AUTHENTICATED
    val currentProviderApiKey: String get() = providerApiKeys[dataProvider].orEmpty()
    val currentProviderApiKeyLabel: String get() = dataProvider.apiKeyLabel ?: "API Key"
}
