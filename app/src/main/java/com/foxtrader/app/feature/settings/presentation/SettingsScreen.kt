package com.foxtrader.app.feature.settings.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.R
import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.PositionSizingMethod
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.performance.PerformanceMode
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60
import com.foxtrader.app.ui.theme.FoxSuccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToLogin: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Settings", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SectionHeader("Workspace")

            SettingsCard {
                Text(
                    "Desk setup and FoxTrader Pro live under More. Risk, data and privacy stay here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )
            }

            // === ACCOUNT ===
            SectionHeader("Account")

            SettingsCard {
                if (state.isLoggedIn) {
                    Text(
                        text = stringResource(R.string.settings_signed_in),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FoxSuccess,
                    )
                    if (state.syncMessage != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = state.syncMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = FoxNeutral60,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::syncNow,
                        enabled = !state.isSyncing,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
                    ) {
                        Text(
                            text = if (state.isSyncing) "Syncing…" else "Sync Now",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = viewModel::logout,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FoxNeutral10),
                    ) {
                        Text("Sign Out", color = MaterialTheme.colorScheme.onSurface)
                    }
                } else {
                    Text(
                        text = stringResource(R.string.settings_sign_in_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = FoxNeutral60,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onNavigateToLogin,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
                    ) {
                        Text(
                            "Sign In / Register",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }

            // === SECURITY ===
            SectionHeader("Security")

            SettingsCard {
                if (state.biometricAvailable) {
                    SwitchSetting(
                        label = "Require biometric unlock",
                        checked = state.appLockEnabled,
                        onCheckedChange = viewModel::setAppLockEnabled,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_biometric_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = FoxNeutral60,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_biometric_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = FoxNeutral60,
                    )
                }
            }

            // === PRIVACY ===
            SectionHeader("Privacy")

            SettingsCard {
                SwitchSetting(
                    label = stringResource(R.string.settings_crash_reporting_label),
                    checked = state.crashReportingEnabled,
                    onCheckedChange = viewModel::setCrashReportingEnabled,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_crash_reporting_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_educational_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )
            }

            // === RISK MANAGEMENT ===
            SectionHeader("Risk Management")

            SettingsCard {
                // Risk per trade
                SliderSetting(
                    label = "Risk per Trade",
                    value = state.riskConfig.riskPercentPerTrade.toFloat(),
                    range = 0.1f..5f,
                    suffix = "%",
                    onValueChange = { viewModel.setRiskPercent(it.toDouble()) },
                )

                Spacer(Modifier.height(12.dp))

                // Max daily loss
                SliderSetting(
                    label = "Max Daily Loss",
                    value = state.riskConfig.maxDailyLossPercent.toFloat(),
                    range = 1f..10f,
                    suffix = "%",
                    onValueChange = { viewModel.setMaxDailyLoss(it.toDouble()) },
                )

                Spacer(Modifier.height(12.dp))

                // Max drawdown
                SliderSetting(
                    label = "Max Drawdown",
                    value = state.riskConfig.maxDrawdownPercent.toFloat(),
                    range = 5f..30f,
                    suffix = "%",
                    onValueChange = { viewModel.setMaxDrawdown(it.toDouble()) },
                )

                Spacer(Modifier.height(12.dp))

                // Max consecutive losses
                SliderSetting(
                    label = "Max Consecutive Losses",
                    value = state.riskConfig.maxConsecutiveLosses.toFloat(),
                    range = 2f..10f,
                    suffix = "",
                    onValueChange = { viewModel.setMaxConsecutiveLosses(it.toInt()) },
                )

                Spacer(Modifier.height(12.dp))

                // ATR multiplier
                SliderSetting(
                    label = "ATR Stop Multiplier",
                    value = state.riskConfig.atrStopMultiplier.toFloat(),
                    range = 0.5f..4f,
                    suffix = "x",
                    onValueChange = { viewModel.setAtrMultiplier(it.toDouble()) },
                )

                Spacer(Modifier.height(12.dp))

                // Sizing method dropdown
                DropdownSetting(
                    label = "Position Sizing",
                    selected = state.riskConfig.sizingMethod.name,
                    options = PositionSizingMethod.entries.map { it.name },
                    onSelect = { viewModel.setSizingMethod(PositionSizingMethod.valueOf(it)) },
                )
            }

            // === LIVE TRADING (MT4) ===
            SectionHeader("Live Trading (MT4)")

            SettingsCard {
                SwitchSetting(
                    label = "Live mode",
                    checked = state.mt4LiveModeEnabled,
                    onCheckedChange = viewModel::setMt4LiveModeEnabled,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Must be ON to place real MT4 orders. Off by default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )
                if (state.mt4LiveModeEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "⚠ Live mode is enabled — real orders can be sent to your MT4 broker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(12.dp))

                SwitchSetting(
                    label = "Emergency kill switch",
                    checked = state.mt4KillSwitchEngaged,
                    onCheckedChange = viewModel::setMt4KillSwitch,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (state.mt4KillSwitchEngaged) {
                        "Engaged — all live MT4 orders are blocked."
                    } else {
                        "Block all live MT4 orders instantly. Keep this in mind while live mode is on."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.mt4KillSwitchEngaged) MaterialTheme.colorScheme.error else FoxNeutral60,
                )

                Spacer(Modifier.height(12.dp))

                SliderSetting(
                    label = "Stale quote timeout",
                    value = (state.mt4StaleQuoteTimeoutMs / 1000f).coerceIn(0.5f, 30f),
                    range = 0.5f..30f,
                    suffix = "s",
                    onValueChange = viewModel::setMt4StaleQuoteTimeoutSec,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Reject an order if the latest MT4 quote is older than this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )

                Spacer(Modifier.height(12.dp))

                SliderSetting(
                    label = "Confirmation timeout",
                    value = (state.mt4ConfirmationTimeoutMs / 1000f).coerceIn(5f, 300f),
                    range = 5f..300f,
                    suffix = "s",
                    onValueChange = viewModel::setMt4ConfirmationTimeoutSec,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "A confirmed order older than this is rejected; confirm again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )

                Spacer(Modifier.height(12.dp))

                // Min free margin
                Text("Minimum free margin (account currency; 0 = off)", fontSize = 13.sp, color = FoxNeutral60)
                Spacer(Modifier.height(4.dp))
                DecimalAmountField(
                    value = state.mt4MinFreeMargin,
                    onValueChange = viewModel::setMt4MinFreeMargin,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                // Max daily loss
                Text("Max daily loss (account currency; 0 = off)", fontSize = 13.sp, color = FoxNeutral60)
                Spacer(Modifier.height(4.dp))
                DecimalAmountField(
                    value = state.mt4MaxDailyLoss,
                    onValueChange = viewModel::setMt4MaxDailyLoss,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Note: the max-daily-loss gate needs an intraday realized-P&L source; it is currently advisory.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )
            }

            // === ALERTS ===
            SectionHeader("Alerts")

            SettingsCard {
                SwitchSetting(
                    label = "Sound",
                    checked = state.alertConfig.soundEnabled,
                    onCheckedChange = viewModel::setSoundEnabled,
                )

                Spacer(Modifier.height(12.dp))

                DropdownSetting(
                    label = "Min Priority",
                    selected = state.alertConfig.minPriority.name,
                    options = AlertPriority.entries.map { it.name },
                    onSelect = { viewModel.setMinAlertPriority(AlertPriority.valueOf(it)) },
                )

                Spacer(Modifier.height(12.dp))

                SliderSetting(
                    label = "Max Alerts / Hour",
                    value = state.alertConfig.maxAlertsPerHour.toFloat(),
                    range = 5f..60f,
                    suffix = "",
                    onValueChange = { viewModel.setMaxAlertsPerHour(it.toInt()) },
                )
            }

            // === AI DECISION ENGINE ===
            SectionHeader("AI Decision Engine")

            SettingsCard {
                SliderSetting(
                    label = "Min Confluences",
                    value = state.aiConfig.minConfluences.toFloat(),
                    range = 1f..9f,
                    suffix = "",
                    onValueChange = { viewModel.setMinConfluences(it.toInt()) },
                )

                Spacer(Modifier.height(12.dp))

                SliderSetting(
                    label = "Min Confidence",
                    value = state.aiConfig.minConfidence.toFloat(),
                    range = 10f..100f,
                    suffix = "%",
                    onValueChange = { viewModel.setMinConfidence(it.toInt()) },
                )

                Spacer(Modifier.height(12.dp))

                SliderSetting(
                    label = "Signal Cooldown",
                    value = state.aiConfig.alertCooldownMinutes.toFloat(),
                    range = 1f..60f,
                    suffix = " min",
                    onValueChange = { viewModel.setAlertCooldownMinutes(it.toInt()) },
                )

                Spacer(Modifier.height(12.dp))

                SwitchSetting(
                    label = "Background Scan Alerts",
                    checked = state.aiConfig.backgroundScanEnabled,
                    onCheckedChange = viewModel::setBackgroundScanEnabled,
                )

                if (state.aiConfig.backgroundScanEnabled) {
                    Spacer(Modifier.height(12.dp))
                    SliderSetting(
                        label = "Scan Interval",
                        value = state.aiConfig.backgroundScanIntervalMinutes.toFloat(),
                        range = 15f..240f,
                        suffix = " min",
                        onValueChange = { viewModel.setBackgroundScanIntervalMinutes(it.toInt()) },
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_background_scan_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )
            }

            // === DATA ===
            SectionHeader("Data Provider")

            SettingsCard {
                // Only providers with a real fetch path are selectable. Listing
                // the rest (and taking their API keys) made the app silently
                // fall back to synthetic data while the user believed they were
                // seeing their broker's prices.
                DropdownSetting(
                    label = "Market Data Source",
                    selected = state.dataProvider.displayName,
                    options = DataProvider.implemented().map { it.displayName },
                    onSelect = { name ->
                        DataProvider.implemented().firstOrNull { it.displayName == name }
                            ?.let(viewModel::setDataProvider)
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (state.dataProvider.supportsLive) {
                        "Supports live streaming" +
                            if (state.dataProvider.requiresApiKey) " (API key required)" else ""
                    } else {
                        "Historical / offline only"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )

                val planned = DataProvider.entries.filterNot { it.implemented }
                if (planned.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_coming_soon, planned.joinToString { it.displayName }),
                        style = MaterialTheme.typography.bodySmall,
                        color = FoxNeutral60,
                    )
                }
                if (state.dataProvider == DataProvider.BINANCE || state.dataProvider == DataProvider.BYBIT) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_live_routing_active, state.dataProvider.displayName),
                        style = MaterialTheme.typography.bodySmall,
                        color = FoxNeutral60,
                    )
                }
                if (state.dataProvider.requiresApiKey) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.currentProviderApiKey,
                        onValueChange = viewModel::setProviderApiKey,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(state.currentProviderApiKeyLabel) },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_api_key_saved_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = FoxNeutral60,
                    )
                }
                TestConnectionRow(
                    status = state.providerTest,
                    onTest = viewModel::testProviderConnection,
                )
            }

            // === Backend ===
            SectionHeader("Backend")

            SettingsCard {
                OutlinedTextField(
                    value = state.backendBaseUrl,
                    onValueChange = viewModel::setBackendBaseUrl,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_backend_url_label)) },
                    placeholder = { Text(stringResource(R.string.settings_backend_url_placeholder)) },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_backend_url_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )
                TestConnectionRow(
                    status = state.backendTest,
                    onTest = viewModel::testBackendConnection,
                )
            }

            // === Performance ===
            SectionHeader("Performance")

            SettingsCard {
                DropdownSetting(
                    label = "Chart quality",
                    selected = state.performanceMode.displayName,
                    options = PerformanceMode.entries.map { it.displayName },
                    onSelect = { name ->
                        PerformanceMode.entries.firstOrNull { it.displayName == name }
                            ?.let(viewModel::setPerformanceMode)
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_perf_mode_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )
                Spacer(Modifier.height(16.dp))
                DropdownSetting(
                    label = "Cached candles per market",
                    selected = formatBars(state.maxCachedBars),
                    options = CACHE_SIZE_OPTIONS.map { formatBars(it) },
                    onSelect = { label ->
                        CACHE_SIZE_OPTIONS.firstOrNull { formatBars(it) == label }
                            ?.let(viewModel::setMaxCachedBars)
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_perf_cache_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )
            }

            // === TRADEPRO ===
            SectionHeader("TRADEPRO")

            SettingsCard {
                SliderSetting(
                    label = "Stop (points)",
                    value = state.tradeProConfig.stopPoints.toFloat(),
                    range = 1f..10f,
                    suffix = " pt",
                    onValueChange = { viewModel.setTradeProStopPoints(it.toDouble()) },
                )
                Spacer(Modifier.height(8.dp))
                SliderSetting(
                    label = "T1 target (points)",
                    value = state.tradeProConfig.target1Points.toFloat(),
                    range = 1f..20f,
                    suffix = " pt",
                    onValueChange = { viewModel.setTradeProTarget1(it.toDouble()) },
                )
                Spacer(Modifier.height(8.dp))
                SliderSetting(
                    label = "T2 target (points)",
                    value = state.tradeProConfig.target2Points.toFloat(),
                    range = 2f..40f,
                    suffix = " pt",
                    onValueChange = { viewModel.setTradeProTarget2(it.toDouble()) },
                )
                Spacer(Modifier.height(8.dp))
                SliderSetting(
                    label = "Contracts",
                    value = state.tradeProConfig.contracts.toFloat(),
                    range = 1f..20f,
                    suffix = "",
                    onValueChange = { viewModel.setTradeProContracts(it.toInt()) },
                )
                Spacer(Modifier.height(8.dp))
                SliderSetting(
                    label = "Max daily loss (points)",
                    value = state.tradeProConfig.maxDailyLossPoints.toFloat(),
                    range = 5f..100f,
                    suffix = "",
                    onValueChange = { viewModel.setTradeProMaxDailyLoss(it.toDouble()) },
                )
                Spacer(Modifier.height(12.dp))
                SwitchSetting(
                    label = "Trend filter (avoid chop)",
                    checked = state.tradeProConfig.useTrendFilter,
                    onCheckedChange = viewModel::setTradeProTrendFilter,
                )
            }

            // === LIT X ===
            SectionHeader(stringResource(R.string.settings_litx_title))

            SettingsCard {
                SwitchSetting(
                    label = stringResource(R.string.settings_litx_enable),
                    checked = state.litXConfig.enabled,
                    onCheckedChange = viewModel::setLitXEnabled,
                )
                Spacer(Modifier.height(12.dp))
                SwitchSetting(
                    label = stringResource(R.string.settings_litx_require_htf),
                    checked = state.litXConfig.requireHtfAlignment,
                    onCheckedChange = viewModel::setLitXRequireHtf,
                )
                Spacer(Modifier.height(8.dp))
                SliderSetting(
                    label = stringResource(R.string.settings_litx_min_rr),
                    value = state.litXConfig.minRiskReward.toFloat(),
                    range = 1f..5f,
                    suffix = "R",
                    onValueChange = { viewModel.setLitXMinRiskReward(it.toDouble()) },
                )
            }

            // === GENERAL ===
            SectionHeader("General")

            SettingsCard {
                DropdownSetting(
                    label = "Default Timeframe",
                    selected = state.defaultTimeframe.label,
                    options = Timeframe.entries.map { it.label },
                    onSelect = { label -> viewModel.setDefaultTimeframe(Timeframe.fromLabel(label)) },
                )

                Spacer(Modifier.height(12.dp))

                SwitchSetting(
                    label = "Dark Mode",
                    checked = state.darkMode,
                    onCheckedChange = viewModel::setDarkMode,
                )
            }

            // === SAVE BUTTON ===
            Button(
                onClick = viewModel::save,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FoxAmber50,
                ),
            ) {
                Text(
                    text = if (state.saved) "Saved" else "Save Settings",
                    fontWeight = FontWeight.Bold,
                    color = if (state.saved) FoxSuccess else MaterialTheme.colorScheme.onPrimary,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ============================================================================
// REUSABLE SETTING COMPONENTS
// ============================================================================

private val CACHE_SIZE_OPTIONS = listOf(2_000, 5_000, 10_000, 20_000)

private fun formatBars(bars: Int): String = "%,d".format(bars)

/** A "Test connection" button plus its inline success/failure status. */
@Composable
private fun TestConnectionRow(status: ConnectionTest, onTest: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onTest,
        enabled = status != ConnectionTest.Testing,
        colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
    ) {
        Text(
            text = if (status == ConnectionTest.Testing) {
                stringResource(R.string.settings_testing)
            } else {
                stringResource(R.string.settings_test_connection)
            },
        )
    }
    when (status) {
        is ConnectionTest.Success -> {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_test_connected, status.candleCount),
                style = MaterialTheme.typography.bodySmall,
                color = FoxSuccess,
            )
        }
        is ConnectionTest.Failure -> {
            Spacer(Modifier.height(6.dp))
            Text(
                text = status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        else -> Unit
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = FoxAmber50,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FoxNeutral10),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                // NOTE: never embed `suffix` inside the format string — a bare
                // "%" (e.g. "%.1f%") throws UnknownFormatConversionException and
                // crashed the whole Settings screen. Build the suffix separately.
                text = formatSliderValue(value, suffix),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = FoxAmber50,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = FoxAmber50,
                activeTrackColor = FoxAmber50,
            ),
        )
    }
}

/**
 * Decimal text field backed by a raw string so the user can actually type a
 * decimal (e.g. "1.5") without the field reformatting after each keystroke and
 * eating the separator. The parsed value is pushed up only when valid; blank is
 * treated as 0. External [value] resets the field only when it no longer matches
 * what was typed (e.g. after leaving/re-entering the screen).
 */
@Composable
private fun DecimalAmountField(
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf(formatDecimal(value)) }
    LaunchedEffect(value) {
        val current = text.toDoubleOrNull()
        if (current != value) text = formatDecimal(value)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            onValueChange(newText.toDoubleOrNull() ?: 0.0)
        },
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/** Renders a Double setting as a compact decimal string (no trailing zeros). */
private fun formatDecimal(value: Double): String {
    if (value <= 0.0) return "0"
    if (value == value.toLong().toDouble()) return value.toLong().toString()
    return "%.2f".format(value).trimEnd('0').trimEnd('.')
}

private fun formatSliderValue(value: Float, suffix: String): String {
    if (suffix.isEmpty()) return value.toInt().toString()
    val whole = value.toInt().toFloat() == value
    val formatted = if (whole) value.toInt().toString() else "%.1f".format(value)
    return formatted + suffix
}

@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = FoxAmber50),
        )
    }
}

@Composable
private fun DropdownSetting(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(label, fontSize = 13.sp, color = FoxNeutral60)
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
                textStyle = MaterialTheme.typography.bodySmall,
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, fontSize = 13.sp) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
