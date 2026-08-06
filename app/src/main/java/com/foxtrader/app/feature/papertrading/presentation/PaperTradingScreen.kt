package com.foxtrader.app.feature.papertrading.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.orders.PaperPosition
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullishText
import com.foxtrader.app.ui.theme.FoxNeutral10
import com.foxtrader.app.ui.theme.FoxNeutral60
import kotlin.math.abs
import kotlin.math.round

/**
 * Paper Trading screen — a simulated, zero-risk trading account.
 *
 * Shows account equity/balance and realised/unrealised P&L, lets the trader
 * open one-tap market orders at the price fed from the chart (via the shared
 * [com.foxtrader.app.domain.usecase.orders.PaperTradingSession]), and manages
 * open positions. Backed by the [com.foxtrader.app.domain.usecase.orders.PaperBroker].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperTradingScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: PaperTradingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paper Trading", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::reset) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Reset paper account",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = FoxAmber50)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { AccountCard(state) }
                    item { OrderTicketCard(state, viewModel) }
                    item { SectionLabel("Open positions") }
                    if (!state.hasPositions) {
                        item {
                            Text(
                                text = "No open positions. Open a chart to load a price, then Buy or Sell.",
                                color = FoxNeutral60,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    } else {
                        items(state.positions) { position ->
                            PositionRow(position, onClose = { viewModel.close(position.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountCard(state: PaperTradingUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FoxNeutral10),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Equity", color = FoxNeutral60, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatMoney(state.equity),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Metric("Balance", formatMoney(state.balance))
                Metric("Realised", formatSignedMoney(state.realizedPnl), pnlColor(state.realizedPnl))
                Metric("Unrealised", formatSignedMoney(state.unrealizedPnl), pnlColor(state.unrealizedPnl))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${state.positions.size} open · ${state.closedTradeCount} closed",
                color = FoxNeutral60,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun OrderTicketCard(state: PaperTradingUiState, viewModel: PaperTradingViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FoxNeutral10),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            val market = state.market
            Text(
                text = if (market != null) "${market.symbol} @ ${trimZeros(market.price)}" else "No price yet",
                color = if (market != null) MaterialTheme.colorScheme.onSurface else FoxNeutral60,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(10.dp))

            // Volume stepper.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Volume", color = FoxNeutral60, fontSize = 11.sp)
                Spacer(Modifier.width(12.dp))
                IconButton(onClick = viewModel::decreaseVolume) {
                    Icon(Icons.Default.Remove, contentDescription = "Decrease volume", tint = FoxAmber50)
                }
                Text(
                    text = trimZeros(state.orderVolume),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
                IconButton(onClick = viewModel::increaseVolume) {
                    Icon(Icons.Default.Add, contentDescription = "Increase volume", tint = FoxAmber50)
                }
            }
            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = viewModel::buy,
                    enabled = state.canTrade,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = FoxBullishText),
                ) { Text("Buy", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = viewModel::sell,
                    enabled = state.canTrade,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = FoxBearishText),
                ) { Text("Sell", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun PositionRow(position: PaperPosition, onClose: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FoxNeutral10),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = position.symbol,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    DirectionTag(position.direction)
                }
                Text(
                    text = formatSignedMoney(position.unrealizedPnl),
                    color = pnlColor(position.unrealizedPnl),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(
                    text = "${trimZeros(position.volume)} @ ${trimZeros(position.entryPrice)} → ${trimZeros(position.currentPrice)}",
                    color = FoxNeutral60,
                    fontSize = 10.sp,
                )
                TextButton(onClick = onClose) {
                    Text("Close", color = FoxAmber50, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DirectionTag(direction: Direction) {
    val bullish = direction == Direction.BULLISH
    val color = if (bullish) FoxBullishText else FoxBearishText
    Text(
        text = if (bullish) "LONG" else "SHORT",
        color = color,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun Metric(label: String, value: String, valueColor: Color? = null) {
    Column {
        Text(label, color = FoxNeutral60, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun pnlColor(value: Double): Color = when {
    value > 0.0 -> FoxBullishText
    value < 0.0 -> FoxBearishText
    else -> MaterialTheme.colorScheme.onSurface
}

private fun formatMoney(value: Double): String = "$" + roundTo(value, 2)

private fun formatSignedMoney(value: Double): String {
    val sign = if (value > 0) "+" else if (value < 0) "-" else ""
    return "$sign$" + roundTo(abs(value), 2)
}

private fun trimZeros(value: Double): String {
    val rounded = roundTo(value, 5)
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

private fun roundTo(value: Double, decimals: Int): Double {
    if (value.isNaN() || value.isInfinite()) return 0.0
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    return round(value * factor) / factor
}
