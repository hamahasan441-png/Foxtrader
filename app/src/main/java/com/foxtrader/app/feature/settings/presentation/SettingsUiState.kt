package com.foxtrader.app.feature.settings.presentation

import androidx.compose.runtime.Immutable
import com.foxtrader.app.domain.model.AlertConfig
import com.foxtrader.app.domain.usecase.performance.PerformanceMode
import com.foxtrader.app.domain.model.AuthState
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.LitXConfig
import com.foxtrader.app.domain.model.LitConfig
import com.foxtrader.app.domain.model.SmtConfig
import com.foxtrader.app.domain.model.SmsConfig
import com.foxtrader.app.domain.model.RiskConfig
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf

/** AI Decision Engine configuration exposed in Settings. */
@Immutable
data class AiConfig(
    val minConfluences: Int = 5,
    val minConfidence: Int = 55,
    val alertCooldownMinutes: Int = 5,
)

sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Testing : ConnectionTest
    data class Success(val candleCount: Int) : ConnectionTest
    data class Failure(val message: String) : ConnectionTest
}

@Immutable
data class SettingsUiState(
    val riskConfig: RiskConfig = RiskConfig(),
    val alertConfig: AlertConfig = AlertConfig(),
    val aiConfig: AiConfig = AiConfig(),
    val defaultTimeframe: Timeframe = Timeframe.M15,
    val dataProvider: DataProvider = DataProvider.SAMPLE,
    val providerApiKeys: ImmutableMap<DataProvider, String> = persistentMapOf(),
    val darkMode: Boolean = true,
    val backendBaseUrl: String = "",
    val maxCachedBars: Int = 5_000,
    val performanceMode: PerformanceMode = PerformanceMode.SMOOTH,
    val providerTest: ConnectionTest = ConnectionTest.Idle,
    val backendTest: ConnectionTest = ConnectionTest.Idle,
    val authState: AuthState = AuthState.UNAUTHENTICATED,
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
    val appLockEnabled: Boolean = false,
    val biometricAvailable: Boolean = false,
    val crashReportingEnabled: Boolean = false,
    val tradeProConfig: TradeProConfig = TradeProConfig(),
    val litXConfig: LitXConfig = LitXConfig(),
    val litConfig: LitConfig = LitConfig(),
    val smtConfig: SmtConfig = SmtConfig(),
    val smsConfig: SmsConfig = SmsConfig(),
    val mt4LiveModeEnabled: Boolean = false,
    val mt4KillSwitchEngaged: Boolean = false,
    val mt4StaleQuoteTimeoutMs: Long = 5_000L,
    val mt4ConfirmationTimeoutMs: Long = 60_000L,
    val mt4MinFreeMargin: Double = 0.0,
    val mt4MaxDailyLoss: Double = 0.0,
    val saved: Boolean = false,
) {
    val isLoggedIn: Boolean get() = authState == AuthState.AUTHENTICATED
    val currentProviderApiKey: String get() = providerApiKeys[dataProvider].orEmpty()
    val currentProviderApiKeyLabel: String get() = dataProvider.apiKeyLabel ?: "API Key"
}
