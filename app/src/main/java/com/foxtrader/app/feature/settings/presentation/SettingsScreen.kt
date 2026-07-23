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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.AlertPriority
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.PositionSizingMethod
import com.foxtrader.app.domain.model.Timeframe
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

            // === ACCOUNT ===
            SectionHeader("Account")

            SettingsCard {
                if (state.isLoggedIn) {
                    Text(
                        text = "Signed in — cloud sync enabled",
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
                        text = "Sign in to back up and sync your journal, drawings, and settings across devices.",
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
                        text = "Lock the app with your fingerprint, face, or device PIN on launch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FoxNeutral60,
                    )
                } else {
                    Text(
                        text = "Biometric unlock unavailable — no biometrics or screen lock set up on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FoxNeutral60,
                    )
                }
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

            // === DATA ===
            SectionHeader("Data Provider")

            SettingsCard {
                DropdownSetting(
                    label = "Market Data Source",
                    selected = state.dataProvider.displayName,
                    options = DataProvider.entries.map { it.displayName },
                    onSelect = { name ->
                        DataProvider.entries.firstOrNull { it.displayName == name }
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
                text = if (suffix == "") "${value.toInt()}" else "%.1f".format(value) + suffix,
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
