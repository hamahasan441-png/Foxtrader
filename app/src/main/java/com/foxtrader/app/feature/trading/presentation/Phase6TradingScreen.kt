package com.foxtrader.app.feature.trading.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.foxtrader.app.ui.components.FoxPanel
import com.foxtrader.app.ui.components.FoxScreenTopBar
import com.foxtrader.app.ui.components.FoxSectionHeader
import com.foxtrader.app.ui.theme.FoxTheme

/**
 * Phase 6 execution entry point. It keeps paper, broker-demo and live-money
 * workflows visibly separated before the user reaches an execution surface.
 */
@Composable
fun Phase6TradingScreen(
    onNavigateBack: () -> Unit,
    onOpenPaper: () -> Unit,
    onOpenBroker: () -> Unit,
    onOpenDeriv: () -> Unit,
) {
    val colors = FoxTheme.colors
    val spacing = FoxTheme.spacing

    Scaffold(
        containerColor = colors.background,
        topBar = { FoxScreenTopBar(title = "Phase 6 · Trading", onNavigateBack = onNavigateBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = spacing.screenHorizontal, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                FoxPanel {
                    Icon(Icons.Outlined.Security, contentDescription = null, tint = colors.accent)
                    Text("Execution safety first", style = FoxTheme.type.h3, color = colors.textPrimary)
                    Text(
                        "Paper, demo and live execution stay separate. Broker orders use fresh confirmation, stale-quote checks, volume bounds, SL/TP direction validation, daily-loss controls, idempotency and an emergency kill switch.",
                        style = FoxTheme.type.body,
                        color = colors.textMuted,
                    )
                }
            }

            item { FoxSectionHeader("Choose environment") }

            item {
                FoxPanel {
                    Icon(Icons.Outlined.Science, contentDescription = null, tint = colors.accent)
                    Text("Paper", style = FoxTheme.type.h3, color = colors.textPrimary)
                    Text("Local simulated execution. No broker credentials and no real orders.", style = FoxTheme.type.caption, color = colors.textMuted)
                    Button(
                        onClick = onOpenPaper,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    ) { Text("Open paper trading") }
                }
            }

            item {
                FoxPanel {
                    Icon(Icons.Outlined.ShowChart, contentDescription = null, tint = colors.accent)
                    Text("Broker demo", style = FoxTheme.type.h3, color = colors.textPrimary)
                    Text("Connect an MT4/MT5 demo server. Deriv MT5 profiles are supported through the MetaApi adapter.", style = FoxTheme.type.caption, color = colors.textMuted)
                    OutlinedButton(onClick = onOpenBroker, modifier = Modifier.fillMaxWidth()) {
                        Text("Connect demo account")
                    }
                }
            }

            item {
                FoxPanel {
                    Icon(Icons.Outlined.AccountBalance, contentDescription = null, tint = colors.warning)
                    Text("Live", style = FoxTheme.type.h3, color = colors.textPrimary)
                    Text("Real-money broker execution. Live mode is OFF by default and every order remains safety-gated.", style = FoxTheme.type.caption, color = colors.textMuted)
                    OutlinedButton(onClick = onOpenBroker, modifier = Modifier.fillMaxWidth()) {
                        Text("Connect MT4/MT5 live account")
                    }
                }
            }

            item {
                FoxPanel {
                    Icon(Icons.Outlined.Security, contentDescription = null, tint = colors.accent)
                    Text("Native Deriv API", style = FoxTheme.type.h3, color = colors.textPrimary)
                    Text("Phase 9 · Direct Deriv Options REST + OTP WebSocket. Separate from the MetaApi MT5 adapter.", style = FoxTheme.type.caption, color = colors.textMuted)
                    OutlinedButton(onClick = onOpenDeriv, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Native Deriv")
                    }
                }
            }
        }
    }
}
