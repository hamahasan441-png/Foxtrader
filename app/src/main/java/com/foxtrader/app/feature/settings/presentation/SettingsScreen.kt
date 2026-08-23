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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.R
import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.LitXGrade
import com.foxtrader.app.domain.model.PositionSizingMethod
import com.foxtrader.app.domain.model.SignalProfile
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
                title = { Text("Settings", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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

            SectionHeader("Account")
            SettingsCard {
                if (state.isLoggedIn) {
                    Text(stringResource(R.string.settings_signed_in), style = MaterialTheme.typography.bodyMedium, color = FoxSuccess)
                    state.syncMessage?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = FoxNeutral60)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::syncNow,
                        enabled = !state.isSyncing,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
                    ) {
                        Text(if (state.isSyncing) "Syncing…" else "Sync Now", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = viewModel::logout,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FoxNeutral10),
                    ) { Text("Sign Out", color = MaterialTheme.colorScheme.onSurface) }
                } else {
                    Text(stringResource(R.string.settings_sign_in_description), style = MaterialTheme.typography.bodySmall, color = FoxNeutral60)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onNavigateToLogin,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
                    ) { Text("Sign In / Register", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary) }
                }
            }

            SectionHeader("Security")
            SettingsCard {
                if (state.biometricAvailable) {
                    SwitchSetting("Require biometric unlock", state.appLockEnabled, viewModel::setAppLockEnabled)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.settings_biometric_description), style = MaterialTheme.typography.bodySmall, color = FoxNeutral60)
                } else {
                    Text(stringResource(R.string.settings_biometric_unavailable), style = MaterialTheme.typography.bodySmall, color = FoxNeutral60)
                }
            }

            SectionHeader("Privacy")
            SettingsCard {
                SwitchSetting(
                    stringResource(R.string.settings_crash_reporting_label),
                    state.crashReportingEnabled,
                    viewModel::setCrashReportingEnabled,
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.settings_crash_reporting_description), style = MaterialTheme.typography.bodySmall, color = FoxNeutral60)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.settings_educational_disclaimer), style = MaterialTheme.typography.bodySmall, color = FoxNeutral60)
            }

            SectionHeader("Risk Management")
            SettingsCard {
                SliderSetting("Risk per Trade", state.riskConfig.riskPercentPerTrade.toFloat(), 0.1f..5f, "%") { viewModel.setRiskPercent(it.toDouble()) }
                Spacer(Modifier.height(12.dp))
                SliderSetting("Max Daily Loss", state.riskConfig.maxDailyLossPercent.toFloat(), 1f..10f, "%") { viewModel.setMaxDailyLoss(it.toDouble()) }
                Spacer(Modifier.height(12.dp))
                SliderSetting("Max Drawdown", state.riskConfig.maxDrawdownPercent.toFloat(), 5f..30f, "%") { viewModel.setMaxDrawdown(it.toDouble()) }
                Spacer(Modifier.height(12.dp))
                SliderSetting("Max Consecutive Losses", state.riskConfig.maxConsecutiveLosses.toFloat(), 2f..10f, "") { viewModel.setMaxConsecutiveLosses(it.toInt()) }
                Spacer(Modifier.height(12.dp))
                SliderSetting("ATR Stop Multiplier", state.riskConfig.atrStopMultiplier.toFloat(), 0.5f..4f, "x") { viewModel.setAtrMultiplier(it.toDouble()) }
                Spacer(Modifier.height(12.dp))
                DropdownSetting(
                    label = "Position Sizing",
                    selected = state.riskConfig.sizingMethod.name,
                    options = PositionSizingMethod.entries.map { it.name },
                    onSelect = { viewModel.setSizingMethod(PositionSizingMethod.valueOf(it)) },
                )
            }

            SectionHeader("Alerts")
            SettingsCard {
                SwitchSetting("Sound", state.alertConfig.soundEnabled, viewModel::setSoundEnabled)
                Spacer(Modifier.height(12.dp))
                DropdownSetting(
                    "Min Priority",
                    state.alertConfig.minPriority.name,
                    AlertPriority.entries.map { it.name },
                ) { viewModel.setMinAlertPriority(AlertPriority.valueOf(it)) }
                Spacer(Modifier.height(12.dp))
                SliderSetting("Max Alerts / Hour", state.alertConfig.maxAlertsPerHour.toFloat(), 5f..60f, "") { viewModel.setMaxAlertsPerHour(it.toInt()) }
            }

            SectionHeader("AI Decision Engine")
            SettingsCard {
                SliderSetting("Min Confluences", state.aiConfig.minConfluences.toFloat(), 1f..9f, "") { viewModel.setMinConfluences(it.toInt()) }
                Spacer(Modifier.height(12.dp))
                SliderSetting("Min Confidence", state.aiConfig.minConfidence.toFloat(), 10f..100f, "%") { viewModel.setMinConfidence(it.toInt()) }
                Spacer(Modifier.height(12.dp))
                SliderSetting("Signal Cooldown", state.aiConfig.alertCooldownMinutes.toFloat(), 1f..60f, " min") { viewModel.setAlertCooldownMinutes(it.toInt()) }
                Spacer(Modifier.height(8.dp))
                Text(
                    "AI settings apply to decision quality and alert cooldown only. Periodic scanner alerts are not part of this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )
            }

            SectionHeader("Data Provider")
            SettingsCard {
                DropdownSetting(
                    label = "Market Data Source",
                    selected = state.dataProvider.displayName,
                    options = DataProvider.implemented().map { it.displayName },
                    onSelect = { name -> DataProvider.implemented().firstOrNull { it.displayName == name }?.let(viewModel::setDataProvider) },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.dataProvider.supportsLive) "Supports live streaming" + if (state.dataProvider.requiresApiKey) " (API key required)" else "" else "Historical / offline only",
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )
                val planned = DataProvider.entries.filterNot { it.implemented }
                if (planned.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.settings_coming_soon, planned.joinToString { it.displayName }), style = MaterialTheme.typography.bodySmall, color = FoxNeutral60)
                }
                if (state.dataProvider.implemented && state.dataProvider.supportsLive && state.dataProvider != DataProvider.SAMPLE) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.settings_live_routing_active, state.dataProvider.displayName), style = MaterialTheme.typography.bodySmall, color = FoxNeutral60)
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
                    Text(stringResource(R.string.settings_api_key_saved_note), style = MaterialTheme.typography.bodySmall, color = FoxNeutral60)
                }
                TestConnectionRow(state.providerTest, viewModel::testProviderConnection)
            }

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
                Text(stringResource(R.string.settings_backend_url_note), style = MaterialTheme.typography.bodySmall, color = FoxNeutral60)
                TestConnectionRow(state.backendTest, viewModel::testBackendConnection)
            }

            SectionHeader("Performance")
            SettingsCard {
                DropdownSetting(
                    "Chart quality",
                    state.performanceMode.displayName,
                    PerformanceMode.entries.map { it.displayName },
                ) { name -> PerformanceMode.entries.firstOrNull { it.displayName == name }?.let(viewModel::setPerformanceMode) }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_perf_mode_note), style = MaterialTheme.typography.bodySmall, color = FoxNeutral60)
                Spacer(Modifier.height(16.dp))
                DropdownSetting(
                    "Cached candles per market",
                    formatBars(state.maxCachedBars),
                    CACHE_SIZE_OPTIONS.map { formatBars(it) },
                ) { label -> CACHE_SIZE_OPTIONS.firstOrNull { formatBars(it) == label }?.let(viewModel::setMaxCachedBars) }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_perf_cache_note), style = MaterialTheme.typography.bodySmall, color = FoxNeutral60)
            }

            SectionHeader("TRADEPRO")
            SettingsCard {
                SliderSetting("Stop (points)", state.tradeProConfig.stopPoints.toFloat(), 1f..10f, " pt") { viewModel.setTradeProStopPoints(it.toDouble()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("T1 target (points)", state.tradeProConfig.target1Points.toFloat(), 1f..20f, " pt") { viewModel.setTradeProTarget1(it.toDouble()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("T2 target (points)", state.tradeProConfig.target2Points.toFloat(), 2f..40f, " pt") { viewModel.setTradeProTarget2(it.toDouble()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Contracts", state.tradeProConfig.contracts.toFloat(), 1f..20f, "") { viewModel.setTradeProContracts(it.toInt()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Max daily loss (points)", state.tradeProConfig.maxDailyLossPoints.toFloat(), 5f..100f, "") { viewModel.setTradeProMaxDailyLoss(it.toDouble()) }
                Spacer(Modifier.height(12.dp))
                SwitchSetting("Trend filter (avoid chop)", state.tradeProConfig.useTrendFilter, viewModel::setTradeProTrendFilter)
            }

            SectionHeader(stringResource(R.string.settings_litx_title))
            SettingsCard {
                SwitchSetting(stringResource(R.string.settings_litx_enable), state.litXConfig.enabled, viewModel::setLitXEnabled)
                Spacer(Modifier.height(12.dp))
                DropdownSetting("Signal profile", state.litXConfig.profile.name, SignalProfile.entries.map { it.name }) { selected ->
                    SignalProfile.entries.firstOrNull { it.name == selected }?.let(viewModel::setLitXProfile)
                }
                Spacer(Modifier.height(12.dp))
                DropdownSetting("Minimum grade", state.litXConfig.minGrade.name, listOf(LitXGrade.A_PLUS.name, LitXGrade.A.name, LitXGrade.B.name)) { n ->
                    LitXGrade.entries.firstOrNull { it.name == n }?.let(viewModel::setLitXMinGrade)
                }
                Spacer(Modifier.height(12.dp))
                SwitchSetting(stringResource(R.string.settings_litx_require_htf), state.litXConfig.requireHtfAlignment, viewModel::setLitXRequireHtf)
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Require displacement-confirmed MSS", state.litXConfig.requireStrongMss, viewModel::setLitXRequireStrongMss)
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Require premium/discount alignment", state.litXConfig.requireDirectionalZone, viewModel::setLitXRequireDirectionalZone)
                Spacer(Modifier.height(8.dp))
                SliderSetting("Minimum confidence", state.litXConfig.minConfidenceScore.toFloat(), 50f..95f, "%") { viewModel.setLitXMinConfidence(it.toInt()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting(stringResource(R.string.settings_litx_min_rr), state.litXConfig.minRiskReward.toFloat(), 1f..5f, "R") { viewModel.setLitXMinRiskReward(it.toDouble()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Displacement ATR", state.litXConfig.displacementAtrMultiple.toFloat(), 0.8f..3f, "x") { viewModel.setLitXDisplacement(it.toDouble()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Max sweep → shift bars", state.litXConfig.maxSweepToShiftBars.toFloat(), 3f..30f, "") { viewModel.setLitXSweepToShift(it.toInt()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Max shift → retest bars", state.litXConfig.maxShiftToRetestBars.toFloat(), 3f..40f, "") { viewModel.setLitXShiftToRetest(it.toInt()) }
            }

            SectionHeader("LIT — Liquidity / Inducement")
            SettingsCard {
                DropdownSetting("Preset", state.litConfig.profile.name, SignalProfile.entries.map { it.name }) { n -> SignalProfile.entries.firstOrNull { it.name == n }?.let(viewModel::setLitProfile) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Minimum confidence", state.litConfig.minConfidence.toFloat(), 50f..95f, "%") { viewModel.setLitMinConfidence(it.toInt()) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Require premium/discount alignment", state.litConfig.requireDirectionalZone, viewModel::setLitDirectionalZone)
                Spacer(Modifier.height(8.dp))
                SliderSetting("Setup lookback", state.litConfig.setupLookback.toFloat(), 20f..120f, " bars") { viewModel.setLitSetupLookback(it.toInt()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Max sweep → shift", state.litConfig.maxSweepToShiftBars.toFloat(), 3f..30f, " bars") { viewModel.setLitSweepToShift(it.toInt()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Max shift → retest", state.litConfig.maxShiftToRetestBars.toFloat(), 3f..40f, " bars") { viewModel.setLitShiftToRetest(it.toInt()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Minimum R:R", state.litConfig.minRiskReward.toFloat(), 1f..5f, "R") { viewModel.setLitMinRr(it.toDouble()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Displacement ATR", state.litConfig.displacementAtrMultiple.toFloat(), 0.8f..3f, "x") { viewModel.setLitDisplacement(it.toDouble()) }
            }

            SectionHeader("SMT — Divergence")
            SettingsCard {
                DropdownSetting("Preset", state.smtConfig.profile.name, SignalProfile.entries.map { it.name }) { n -> SignalProfile.entries.firstOrNull { it.name == n }?.let(viewModel::setSmtProfile) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Comparison period", state.smtConfig.period.toFloat(), 40f..600f, " bars") { viewModel.setSmtPeriod(it.toInt()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Swing confirmation", state.smtConfig.swingLookback.toFloat(), 1f..10f, " bars") { viewModel.setSmtSwingLookback(it.toInt()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Minimum correlation", (state.smtConfig.minCorrelation * 100).toFloat(), 10f..95f, "%") { viewModel.setSmtMinCorrelation(it / 100.0) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Max timestamp skew", (state.smtConfig.maxTimestampSkewFraction * 100).toFloat(), 0f..50f, "% bar") { viewModel.setSmtMaxSkewFraction(it / 100.0) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Swing sync tolerance", state.smtConfig.maxSwingSyncBars.toFloat(), 1f..12f, " bars") { viewModel.setSmtSyncBars(it.toInt()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Max signal age", state.smtConfig.maxSignalAgeBars.toFloat(), 1f..80f, " bars") { viewModel.setSmtMaxAge(it.toInt()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Minimum divergence strength", (state.smtConfig.minDivergenceStrength * 100).toFloat(), 1f..100f, "%") { viewModel.setSmtMinStrength(it / 100.0) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Minimum confidence", state.smtConfig.minConfidence.toFloat(), 50f..95f, "%") { viewModel.setSmtMinConfidence(it.toInt()) }
            }

            SectionHeader("SMS — Smart Money Structure")
            SettingsCard {
                DropdownSetting("Preset", state.smsConfig.profile.name, SignalProfile.entries.map { it.name }) { n -> SignalProfile.entries.firstOrNull { it.name == n }?.let(viewModel::setSmsProfile) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Swing confirmation", state.smsConfig.swingBars.toFloat(), 2f..12f, " bars") { viewModel.setSmsSwingBars(it.toInt()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Displacement ATR", state.smsConfig.displacementAtrMultiple.toFloat(), 0.8f..3f, "x") { viewModel.setSmsDisplacement(it.toDouble()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Max displacement gap", state.smsConfig.maxDisplacementGapBars.toFloat(), 1f..20f, " bars") { viewModel.setSmsGap(it.toInt()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Max sweep → shift", state.smsConfig.maxSweepToShiftBars.toFloat(), 2f..30f, " bars") { viewModel.setSmsSweepToShift(it.toInt()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Max signal age", state.smsConfig.maxSignalAgeBars.toFloat(), 1f..20f, " bars") { viewModel.setSmsMaxAge(it.toInt()) }
                Spacer(Modifier.height(8.dp))
                SliderSetting("Minimum confidence", state.smsConfig.minConfidence.toFloat(), 50f..95f, "%") { viewModel.setSmsMinConfidence(it.toInt()) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Require liquidity sweep", state.smsConfig.requireLiquiditySweep, viewModel::setSmsRequireSweep)
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Require displacement for CHOCH/MSS", state.smsConfig.requireDisplacementForChoch, viewModel::setSmsRequireDisplacement)
            }

            SectionHeader("General")
            SettingsCard {
                DropdownSetting("Default Timeframe", state.defaultTimeframe.label, Timeframe.entries.map { it.label }) { label ->
                    viewModel.setDefaultTimeframe(Timeframe.fromLabel(label))
                }
                Spacer(Modifier.height(12.dp))
                SwitchSetting("Dark Mode", state.darkMode, viewModel::setDarkMode)
            }

            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
            ) {
                Text(
                    if (state.saved) "Saved" else "Save Settings",
                    fontWeight = FontWeight.Bold,
                    color = if (state.saved) FoxSuccess else MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private val CACHE_SIZE_OPTIONS = listOf(2_000, 5_000, 10_000, 20_000)
private fun formatBars(bars: Int): String = "%,d".format(bars)

@Composable
private fun TestConnectionRow(status: ConnectionTest, onTest: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onTest,
        enabled = status != ConnectionTest.Testing,
        colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
    ) {
        Text(if (status == ConnectionTest.Testing) stringResource(R.string.settings_testing) else stringResource(R.string.settings_test_connection))
    }
    when (status) {
        is ConnectionTest.Success -> {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.settings_test_connected, status.candleCount), style = MaterialTheme.typography.bodySmall, color = FoxSuccess)
        }
        is ConnectionTest.Failure -> {
            Spacer(Modifier.height(6.dp))
            Text(status.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        else -> Unit
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = FoxAmber50, modifier = Modifier.padding(start = 4.dp))
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = FoxNeutral10)) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(formatSliderValue(value, suffix), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = FoxAmber50)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = FoxAmber50, activeTrackColor = FoxAmber50),
        )
    }
}

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
                modifier = Modifier.fillMaxWidth().clickable { expanded = true },
                textStyle = MaterialTheme.typography.bodySmall,
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
