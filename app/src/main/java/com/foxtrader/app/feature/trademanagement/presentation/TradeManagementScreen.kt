package com.foxtrader.app.feature.trademanagement.presentation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.ManagedTrade
import com.foxtrader.app.domain.model.tradepro.ManagedTradeState
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBullish
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * Trade Management Dashboard screen. Shows a list of active managed trades with their
 * lifecycle state, and a detail panel for the selected trade.
 */
@Composable
fun TradeManagementScreen(
    modifier: Modifier = Modifier,
    onNavigateToSimulator: () -> Unit = {},
    onNavigateToRiskDashboard: () -> Unit = {},
    viewModel: TradeManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        Text(
            text = "Trade Management",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onNavigateToRiskDashboard,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = FoxAmber50,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("Risk Dashboard", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onNavigateToSimulator,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("Trade Simulator", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!state.hasActiveTrades && state.managedTrades.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.managedTrades, key = { it.id }) { trade ->
                    TradeListItem(
                        trade = trade,
                        isSelected = state.selectedTrade?.id == trade.id,
                        onClick = { viewModel.selectTrade(trade.id) },
                    )
                }
            }

            state.selectedTrade?.let { trade ->
                Spacer(modifier = Modifier.height(16.dp))
                TradeDetailPanel(
                    trade = trade,
                    onClose = { viewModel.closeTrade(trade.id, trade.currentPrice) },
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No Active Trades",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Open a trade from an executable TRADEPRO setup to begin management.",
                style = MaterialTheme.typography.bodyMedium,
                color = FoxNeutral60,
            )
        }
    }
}

@Composable
private fun TradeListItem(
    trade: ManagedTrade,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        FoxNeutral10
    }
    val pnlColor = if (trade.unrealizedPoints + trade.realizedPoints >= 0.0) FoxBullish else FoxBearish
    val directionColor = if (trade.direction == Direction.BULLISH) FoxBullish else FoxBearish
    val totalPoints = trade.realizedPoints + trade.unrealizedPoints

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Symbol
        Text(
            text = trade.symbol,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Direction badge
        Text(
            text = if (trade.direction == Direction.BULLISH) "LONG" else "SHORT",
            style = MaterialTheme.typography.labelSmall,
            color = directionColor,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(directionColor.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Entry price
        Text(
            text = "@ ${"%.2f".format(trade.entryPrice)}",
            style = MaterialTheme.typography.bodySmall,
            color = FoxNeutral60,
        )

        Spacer(modifier = Modifier.weight(1f))

        // P&L
        Text(
            text = "%+.1f pts".format(totalPoints),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = pnlColor,
        )

        Spacer(modifier = Modifier.width(8.dp))

        // State badge
        StateBadge(state = trade.state)
    }
}

@Composable
private fun StateBadge(state: ManagedTradeState) {
    val (label, color) = when (state) {
        ManagedTradeState.ACTIVE -> "ACTIVE" to FoxAmber50
        ManagedTradeState.T1_HIT -> "T1" to FoxBullish
        ManagedTradeState.T2_HIT -> "T2" to FoxBullish
        ManagedTradeState.RUNNER -> "RUNNER" to FoxBullish
        ManagedTradeState.CLOSED -> "CLOSED" to FoxNeutral60
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun TradeDetailPanel(
    trade: ManagedTrade,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(FoxNeutral10)
            .padding(16.dp),
    ) {
        Text(
            text = "${trade.symbol} Detail",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Lifecycle progress bar: ACTIVE -> T1 -> T2 -> RUNNER -> CLOSED
        LifecycleProgressBar(state = trade.state)

        Spacer(modifier = Modifier.height(12.dp))

        // Levels row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LevelColumn(label = "Entry", value = trade.entryPrice)
            LevelColumn(label = "Stop", value = trade.stopPrice)
            LevelColumn(label = "Current", value = trade.currentPrice)
            LevelColumn(label = "T1", value = trade.t1Price)
            LevelColumn(label = "T2", value = trade.t2Price)
            LevelColumn(label = "Runner", value = trade.runnerTarget)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Points summary
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(text = "Realized", style = MaterialTheme.typography.labelSmall, color = FoxNeutral60)
                Text(
                    text = "%+.1f pts".format(trade.realizedPoints),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (trade.realizedPoints >= 0.0) FoxBullish else FoxBearish,
                )
            }
            Column {
                Text(text = "Unrealized", style = MaterialTheme.typography.labelSmall, color = FoxNeutral60)
                Text(
                    text = "%+.1f pts".format(trade.unrealizedPoints),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (trade.unrealizedPoints >= 0.0) FoxBullish else FoxBearish,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Close button
        if (trade.state != ManagedTradeState.CLOSED) {
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FoxBearish,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("Close Trade")
            }
        }
    }
}

@Composable
private fun LifecycleProgressBar(state: ManagedTradeState) {
    val progress = when (state) {
        ManagedTradeState.ACTIVE -> 0.1f
        ManagedTradeState.T1_HIT -> 0.35f
        ManagedTradeState.T2_HIT -> 0.6f
        ManagedTradeState.RUNNER -> 0.85f
        ManagedTradeState.CLOSED -> 1.0f
    }
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "Active", style = MaterialTheme.typography.labelSmall, color = FoxNeutral60)
            Text(text = "T1", style = MaterialTheme.typography.labelSmall, color = FoxNeutral60)
            Text(text = "T2", style = MaterialTheme.typography.labelSmall, color = FoxNeutral60)
            Text(text = "Runner", style = MaterialTheme.typography.labelSmall, color = FoxNeutral60)
            Text(text = "Closed", style = MaterialTheme.typography.labelSmall, color = FoxNeutral60)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = FoxAmber50,
            trackColor = FoxNeutral60.copy(alpha = 0.3f),
        )
    }
}

@Composable
private fun LevelColumn(label: String, value: Double) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = FoxNeutral60)
        Text(
            text = "%.2f".format(value),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
