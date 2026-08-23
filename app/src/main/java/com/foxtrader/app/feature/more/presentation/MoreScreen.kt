package com.foxtrader.app.feature.more.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.foxtrader.app.domain.model.SubscriptionState
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.ui.components.FoxBadge
import com.foxtrader.app.ui.components.FoxPanel
import com.foxtrader.app.ui.components.FoxScreenTopBar
import com.foxtrader.app.ui.components.FoxSectionHeader
import com.foxtrader.app.ui.theme.FoxTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class MoreDestination(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val routeAction: MoreAction,
    val pro: Boolean = false,
)

enum class MoreAction {
    PORTFOLIO,
    ALERTS,
    STRATEGIES,
    STRATEGY_BUILDER,
    PRO_STUDIO,
    AI,
    WATCHLIST,
    PAPER,
    TRADE_MANAGEMENT,
    DAILY_PLAN,
    CORRELATION,
    SETTINGS,
    SUBSCRIPTION,
    LIVE_TRADING,
    AUTOMATION,
    RELEASE_READINESS,
    DERIV_NATIVE,
    JOURNAL,
}

@HiltViewModel
class MoreViewModel @Inject constructor(
    appPreferences: AppPreferences,
) : ViewModel() {
    val subscription: StateFlow<SubscriptionState> = appPreferences.subscription.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SubscriptionState(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onOpen: (MoreAction) -> Unit,
    viewModel: MoreViewModel = hiltViewModel(),
) {
    val subscription by viewModel.subscription.collectAsStateWithLifecycle()
    val colors = FoxTheme.colors
    val spacing = FoxTheme.spacing

    val research = listOf(
        MoreDestination("Strategies", "Ranked setups across the watchlist", Icons.Outlined.TrendingUp, MoreAction.STRATEGIES),
        MoreDestination("Pro Studio", "Phase 5 strategy, indicator & signal workspace", Icons.Outlined.Tune, MoreAction.PRO_STUDIO, pro = true),
        MoreDestination("Strategy builder", "IF / AND / OR research templates", Icons.Outlined.Tune, MoreAction.STRATEGY_BUILDER),
        MoreDestination("AI workspace", "Explainable research, not predictions", Icons.Outlined.AutoAwesome, MoreAction.AI),
        MoreDestination("Watchlist", "Lists, symbols, order", Icons.Outlined.Star, MoreAction.WATCHLIST),
    )
    val desk = listOf(
        MoreDestination("Portfolio", "Exposure, concentration, P&L", Icons.Outlined.AccountBalanceWallet, MoreAction.PORTFOLIO),
        MoreDestination("Professional journal", "Broker-synced entries, analytics & CSV export", Icons.Outlined.Insights, MoreAction.JOURNAL),
        MoreDestination("Alerts", "Inbox, priority, history", Icons.Outlined.Notifications, MoreAction.ALERTS),
        MoreDestination("Daily plan", "Pre-market briefing", Icons.Outlined.Insights, MoreAction.DAILY_PLAN),
        MoreDestination("Paper trading", "Simulated execution", Icons.Outlined.ShowChart, MoreAction.PAPER),
        MoreDestination("Trade management", "Open TRADEPRO setups", Icons.Outlined.Calculate, MoreAction.TRADE_MANAGEMENT),
        MoreDestination("Correlation", "Concentration clusters", Icons.Outlined.Hub, MoreAction.CORRELATION, pro = true),
    )
    val liveTrading = listOf(
        MoreDestination("Live trading", "Phase 6 · Paper & Deriv", Icons.Outlined.ShowChart, MoreAction.LIVE_TRADING),
        MoreDestination("Automation cockpit", "Phase 7 · Signal review and execution routing", Icons.Outlined.Hub, MoreAction.AUTOMATION, pro = true),
        MoreDestination("Release readiness", "Phase 8 · Production gates, security & QA", Icons.Outlined.Settings, MoreAction.RELEASE_READINESS, pro = true),
        MoreDestination("Native Deriv", "Phase 9 · Direct REST + OTP WebSocket", Icons.Outlined.AccountBalanceWallet, MoreAction.DERIV_NATIVE, pro = true),
    )
    val account = listOf(
        MoreDestination("Settings", "Risk, data, privacy, providers", Icons.Outlined.Settings, MoreAction.SETTINGS),
        MoreDestination("FoxTrader Pro", "Plan, trial, feature map", Icons.Outlined.WorkspacePremium, MoreAction.SUBSCRIPTION),
    )

    Scaffold(
        containerColor = colors.background,
        topBar = { FoxScreenTopBar(title = "More") },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = spacing.screenHorizontal, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                FoxPanel {
                    Text("Workspace", style = FoxTheme.type.caption, color = colors.textMuted)
                    Text(
                        "Plan · ${subscription.label()}",
                        style = FoxTheme.type.h3,
                        color = colors.textPrimary,
                    )
                    Text(
                        "Research tools stay available. Pro marks advanced surfaces — never ads in the tape.",
                        style = FoxTheme.type.caption,
                        color = colors.textMuted,
                    )
                }
            }
            item { FoxSectionHeader("Research") }
            items(research, key = { it.title }) { dest -> MoreRow(dest, onOpen) }
            item { FoxSectionHeader("Desk") }
            items(desk, key = { it.title }) { dest -> MoreRow(dest, onOpen) }
            item { FoxSectionHeader("Live trading") }
            items(liveTrading, key = { it.title }) { dest -> MoreRow(dest, onOpen) }
            item { FoxSectionHeader("Account") }
            items(account, key = { it.title }) { dest -> MoreRow(dest, onOpen) }
        }
    }
}

@Composable
private fun MoreRow(destination: MoreDestination, onOpen: (MoreAction) -> Unit) {
    val colors = FoxTheme.colors
    FoxPanel(modifier = Modifier.fillMaxWidth(), content = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableRow { onOpen(destination.routeAction) },
            horizontalArrangement = Arrangement.spacedBy(FoxTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(destination.icon, contentDescription = null, tint = colors.accent)
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(FoxTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text(destination.title, style = FoxTheme.type.h3, color = colors.textPrimary)
                    if (destination.pro) FoxBadge("PRO", color = colors.ai)
                }
                Text(destination.subtitle, style = FoxTheme.type.caption, color = colors.textMuted)
            }
        }
    })
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
