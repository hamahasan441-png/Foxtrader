package com.foxtrader.app.feature.home.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.FoxAlert
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.model.ScreenerResult
import com.foxtrader.app.domain.usecase.home.ClassifiedInsight
import com.foxtrader.app.domain.usecase.home.InsightKind
import com.foxtrader.app.ui.components.FoxBadge
import com.foxtrader.app.ui.components.FoxBanner
import com.foxtrader.app.ui.components.FoxBannerTone
import com.foxtrader.app.ui.components.FoxDirectionBadge
import com.foxtrader.app.ui.components.FoxEmptyState
import com.foxtrader.app.ui.components.FoxErrorState
import com.foxtrader.app.ui.components.FoxIconButton
import com.foxtrader.app.ui.components.FoxLoadingState
import com.foxtrader.app.ui.components.FoxMetricCard
import com.foxtrader.app.ui.components.FoxPanel
import com.foxtrader.app.ui.components.FoxPercentText
import com.foxtrader.app.ui.components.FoxPriceText
import com.foxtrader.app.ui.components.FoxScreenTopBar
import com.foxtrader.app.ui.components.FoxSectionHeader
import com.foxtrader.app.ui.components.formatMoney
import com.foxtrader.app.ui.theme.FoxTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenChart: () -> Unit = {},
    onOpenMarkets: () -> Unit = {},
    onOpenAlerts: () -> Unit = {},
    onOpenJournal: () -> Unit = {},
    onOpenPortfolio: () -> Unit = {},
    onOpenLab: () -> Unit = {},
    onOpenAi: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = FoxTheme.colors
    val spacing = FoxTheme.spacing

    Scaffold(
        containerColor = colors.background,
        topBar = {
            FoxScreenTopBar(
                title = "FoxTrader",
                actions = {
                    FoxIconButton(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "Refresh market snapshot",
                        onClick = viewModel::refresh,
                    )
                    FoxIconButton(
                        icon = Icons.Outlined.Notifications,
                        contentDescription = if (state.unreadAlerts > 0) {
                            "Alerts inbox, ${state.unreadAlerts} unread"
                        } else {
                            "Alerts inbox"
                        },
                        onClick = onOpenAlerts,
                        tintActive = state.unreadAlerts > 0,
                    )
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading && state.movers.isEmpty() && state.recentTrades.isEmpty() -> {
                FoxLoadingState(
                    modifier = Modifier.padding(padding),
                    label = "Building snapshot",
                )
            }
            state.error != null && state.movers.isEmpty() -> {
                FoxErrorState(
                    title = "Snapshot unavailable",
                    subtitle = state.error ?: "The scan could not finish.",
                    modifier = Modifier.padding(padding),
                    onRetry = viewModel::refresh,
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(colors.background),
                    contentPadding = PaddingValues(
                        horizontal = spacing.screenHorizontal,
                        vertical = spacing.md,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    item {
                        GreetingHeader(state)
                    }
                    if (state.isSyntheticData) {
                        item {
                            FoxBanner(
                                title = "SIMULATED DATA",
                                text = "This snapshot is built from generated bars, not a live feed.",
                                icon = Icons.Outlined.Warning,
                            )
                        }
                    }
                    item {
                        SnapshotStrip(state, onOpenPortfolio)
                    }
                    item {
                        FoxSectionHeader("Market breadth", actionLabel = "Open scan", onAction = onOpenMarkets)
                    }
                    item {
                        BreadthCard(state)
                    }
                    item {
                        FoxSectionHeader("Top movers", actionLabel = "Open chart", onAction = onOpenChart)
                    }
                    if (state.movers.isEmpty()) {
                        item {
                            FoxEmptyState(
                                title = "No movers yet",
                                subtitle = "Run a scan from Markets once candle history is available.",
                                modifier = Modifier.height(FoxTheme.spacing.huge * 4),
                            )
                        }
                    } else {
                        items(state.movers, key = { it.symbol }) { mover ->
                            MoverRow(mover)
                        }
                    }
                    item {
                        FoxSectionHeader("Active signals", actionLabel = "Open lab", onAction = onOpenLab)
                    }
                    if (state.signals.isEmpty()) {
                        item {
                            Text(
                                "No ranked setups in this snapshot.",
                                style = FoxTheme.type.body,
                                color = colors.textMuted,
                            )
                        }
                    } else {
                        items(state.signals, key = { "sig-${it.symbol}-${it.score}" }) { signal ->
                            SignalRow(signal)
                        }
                    }
                    item {
                        FoxSectionHeader("AI market summary", actionLabel = "Workspace", onAction = onOpenAi)
                    }
                    item {
                        InsightList(state.insights)
                    }
                    item {
                        FoxSectionHeader("Watchlist", actionLabel = "Edit list", onAction = onOpenMarkets)
                    }
                    item {
                        WatchlistStrip(state)
                    }
                    item {
                        FoxSectionHeader("Recent trades", actionLabel = "Journal", onAction = onOpenJournal)
                    }
                    if (state.recentTrades.isEmpty()) {
                        item {
                            Text(
                                "No journal entries yet. Log a trade or run a backtest.",
                                style = FoxTheme.type.body,
                                color = colors.textMuted,
                            )
                        }
                    } else {
                        items(state.recentTrades, key = { it.id }) { trade ->
                            TradeRow(trade)
                        }
                    }
                    item {
                        FoxSectionHeader("Alerts")
                    }
                    if (state.recentAlerts.isEmpty()) {
                        item {
                            Text(
                                "Quiet inbox — approved signals will land here.",
                                style = FoxTheme.type.body,
                                color = colors.textMuted,
                            )
                        }
                    } else {
                        items(state.recentAlerts, key = { it.id }) { alert ->
                            AlertRow(alert)
                        }
                    }
                    item { Spacer(Modifier.height(spacing.lg)) }
                }
            }
        }
    }
}

@Composable
private fun GreetingHeader(state: HomeUiState) {
    val colors = FoxTheme.colors
    Column {
        Text(
            text = "Desk",
            style = FoxTheme.type.caption,
            color = colors.textMuted,
        )
        Text(
            text = "${state.profile.preferredTimeframe.label} · ${state.profile.greetingFocus}",
            style = FoxTheme.type.h2,
            color = colors.textPrimary,
        )
        Text(
            text = "Read the tape first. Nothing here is a trade instruction.",
            style = FoxTheme.type.caption,
            color = colors.textMuted,
        )
    }
}

@Composable
private fun SnapshotStrip(state: HomeUiState, onOpenPortfolio: () -> Unit) {
    val colors = FoxTheme.colors
    val exposure = state.portfolio?.totalExposurePercent
    val pnl = state.portfolio?.unrealizedPnl
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FoxTheme.spacing.sm),
    ) {
        FoxMetricCard(
            label = "Equity",
            value = formatMoney(state.accountEquity).removePrefix("+"),
            modifier = Modifier.weight(1f),
            caption = state.subscription.label(),
        )
        FoxMetricCard(
            label = "Open risk",
            value = if (exposure == null) "—" else "${"%.1f".format(exposure)}%",
            modifier = Modifier.weight(1f).semantics { contentDescription = "Open portfolio exposure" },
            caption = if (state.openTrades == 0) "No open trades" else "${state.openTrades} open",
        )
        FoxMetricCard(
            label = "Unrealised",
            value = if (pnl == null) "—" else formatMoney(pnl),
            modifier = Modifier.weight(1f),
            valueColor = if (pnl == null) colors.textMuted else colors.pnl(pnl),
            caption = "Journal marks",
        )
    }
    Text(
        text = "View portfolio",
        color = colors.accent,
        style = FoxTheme.type.label,
        modifier = Modifier
            .padding(top = FoxTheme.spacing.xs)
            .semantics { contentDescription = "Open portfolio exposure" }
            .clickableText(onOpenPortfolio),
    )
}

private fun Modifier.clickableText(onClick: () -> Unit): Modifier =
    androidx.compose.foundation.clickable(onClick = onClick).let { this.then(it) }

@Composable
private fun BreadthCard(state: HomeUiState) {
    FoxPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Market sentiment", style = FoxTheme.type.caption, color = FoxTheme.colors.textMuted)
                Text(state.sentimentLabel, style = FoxTheme.type.h3, color = FoxTheme.colors.textPrimary)
            }
            FoxBadge("ASSUMPTION", color = FoxTheme.colors.ai)
        }
        Spacer(Modifier.height(FoxTheme.spacing.xs))
        Text(
            "Breadth is inferred from the scanned universe, not from a global index.",
            style = FoxTheme.type.caption,
            color = FoxTheme.colors.textMuted,
        )
    }
}

@Composable
private fun MoverRow(result: ScreenerResult) {
    FoxPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(result.symbol, style = FoxTheme.type.h3, color = FoxTheme.colors.textPrimary)
                Text(result.assetClass.name, style = FoxTheme.type.caption, color = FoxTheme.colors.textMuted)
            }
            Column(horizontalAlignment = Alignment.End) {
                FoxPriceText(result.lastPrice)
                FoxPercentText(result.changePercent)
            }
        }
    }
}

@Composable
private fun SignalRow(result: ScreenerResult) {
    FoxPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(FoxTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text(result.symbol, style = FoxTheme.type.h3, fontWeight = FontWeight.Bold, color = FoxTheme.colors.textPrimary)
                    FoxDirectionBadge(result.direction, longLabel = "BUY", shortLabel = "SELL")
                }
                Text(result.strategy.label, style = FoxTheme.type.caption, color = FoxTheme.colors.textMuted)
            }
            Text("${result.score}", style = FoxTheme.type.price, color = FoxTheme.colors.accent)
        }
    }
}

@Composable
private fun InsightList(insights: List<ClassifiedInsight>) {
    FoxPanel {
        if (insights.isEmpty()) {
            Text("Summary appears after the first scan.", style = FoxTheme.type.body, color = FoxTheme.colors.textMuted)
        } else {
            insights.forEachIndexed { index, insight ->
                if (index > 0) Spacer(Modifier.height(FoxTheme.spacing.sm))
                FoxBadge(insight.kind.name, color = kindColor(insight.kind))
                Spacer(Modifier.height(FoxTheme.spacing.xxs))
                Text(insight.text, style = FoxTheme.type.body, color = FoxTheme.colors.textPrimary)
            }
        }
    }
}

@Composable
private fun kindColor(kind: InsightKind) = when (kind) {
    InsightKind.FACT -> FoxTheme.colors.information
    InsightKind.CALCULATION -> FoxTheme.colors.accent
    InsightKind.ASSUMPTION -> FoxTheme.colors.ai
    InsightKind.PROBABILITY -> FoxTheme.colors.warning
    InsightKind.OPINION -> FoxTheme.colors.textMuted
}

@Composable
private fun WatchlistStrip(state: HomeUiState) {
    val symbols = state.watchlist?.symbolNames.orEmpty()
    if (symbols.isEmpty()) {
        Text("Watchlist is empty.", style = FoxTheme.type.body, color = FoxTheme.colors.textMuted)
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(FoxTheme.spacing.xs)) {
        items(symbols.take(12), key = { it }) { symbol ->
            FoxBadge(symbol, color = FoxTheme.colors.textSecondary)
        }
    }
}

@Composable
private fun TradeRow(entry: JournalEntry) {
    FoxPanel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(entry.symbol, style = FoxTheme.type.h3, color = FoxTheme.colors.textPrimary)
                Text(entry.setupType, style = FoxTheme.type.caption, color = FoxTheme.colors.textMuted)
            }
            if (entry.pnl != null) {
                Text(formatMoney(entry.pnl), style = FoxTheme.type.price, color = FoxTheme.colors.pnl(entry.pnl))
            } else {
                FoxBadge("OPEN")
            }
        }
    }
}

@Composable
private fun AlertRow(alert: FoxAlert) {
    FoxPanel {
        Text(alert.title, style = FoxTheme.type.h3, color = FoxTheme.colors.textPrimary)
        Spacer(Modifier.height(FoxTheme.spacing.xxs))
        Text(alert.body, style = FoxTheme.type.caption, color = FoxTheme.colors.textMuted, maxLines = 2)
    }
}
