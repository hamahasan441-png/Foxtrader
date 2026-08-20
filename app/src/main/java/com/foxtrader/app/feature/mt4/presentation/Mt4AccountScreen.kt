package com.foxtrader.app.feature.mt4.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Mt4AccountInfo
import com.foxtrader.app.domain.model.Mt4Position
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearish
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxBullish
import com.foxtrader.app.ui.theme.FoxNeutral60
import com.foxtrader.app.ui.theme.FoxSuccess

/**
 * MT4 Account screen showing account info (balance, equity, margin), a live
 * trading panel (symbol, direction, lots, SL/TP) with a two-step confirmation
 * flow, the emergency kill switch, live-mode switch, and open positions with
 * close actions.
 *
 * @param onDisconnected Callback when the user disconnects (navigate back).
 * @param onOpenLiveChart Callback to jump to the chart for live MT4 quotes.
 */
@Composable
fun Mt4AccountScreen(
    onDisconnected: () -> Unit,
    onOpenLiveChart: () -> Unit = {},
    viewModel: Mt4ViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when (val current = state) {
        is Mt4UiState.Connected -> {
            ConnectedContent(
                accountInfo = current.accountInfo,
                positions = current.positions,
                isRefreshing = current.isRefreshing,
                liveModeEnabled = current.liveModeEnabled,
                killSwitchEngaged = current.killSwitchEngaged,
                quotePrice = current.lastPrice,
                tradeSymbol = current.tradeSymbol,
                tradeDirection = current.tradeDirection,
                lotsInput = current.lotsInput,
                slInput = current.slInput,
                tpInput = current.tpInput,
                isPlacing = current.isPlacing,
                pendingOrder = current.pendingOrder,
                notice = current.notice,
                onToggleLiveMode = viewModel::toggleLiveMode,
                onEngageKillSwitch = viewModel::engageKillSwitch,
                onDisengageKillSwitch = viewModel::disengageKillSwitch,
                onTradeSymbolChange = viewModel::onTradeSymbolChange,
                onDirectionChange = viewModel::onDirectionChange,
                onLotsChange = viewModel::onLotsChange,
                onSlChange = viewModel::onSlChange,
                onTpChange = viewModel::onTpChange,
                onRequestConfirmation = viewModel::requestOrderConfirmation,
                onConfirmOrder = viewModel::confirmOrder,
                onCancelOrder = viewModel::cancelOrder,
                onClosePosition = viewModel::closePosition,
                onDismissNotice = viewModel::dismissNotice,
                onRefresh = viewModel::refreshPositions,
                onOpenLiveChart = onOpenLiveChart,
                onDisconnect = {
                    viewModel.disconnect()
                    onDisconnected()
                },
            )
        }
        is Mt4UiState.Disconnected -> {
            onDisconnected()
        }
        is Mt4UiState.Connecting -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = FoxAmber50)
                Spacer(Modifier.height(16.dp))
                Text("Connecting...", color = FoxNeutral60)
            }
        }
        is Mt4UiState.Error -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = current.message,
                    color = FoxBearishText,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onDisconnected,
                    colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
                ) {
                    Text("Back", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun ConnectedContent(
    accountInfo: Mt4AccountInfo,
    positions: List<Mt4Position>,
    isRefreshing: Boolean,
    liveModeEnabled: Boolean,
    killSwitchEngaged: Boolean,
    quotePrice: Double?,
    tradeSymbol: String,
    tradeDirection: Direction,
    lotsInput: String,
    slInput: String,
    tpInput: String,
    isPlacing: Boolean,
    pendingOrder: Mt4UiState.PendingTrade?,
    notice: String?,
    onToggleLiveMode: () -> Unit,
    onEngageKillSwitch: () -> Unit,
    onDisengageKillSwitch: () -> Unit,
    onTradeSymbolChange: (String) -> Unit,
    onDirectionChange: (Direction) -> Unit,
    onLotsChange: (String) -> Unit,
    onSlChange: (String) -> Unit,
    onTpChange: (String) -> Unit,
    onRequestConfirmation: () -> Unit,
    onConfirmOrder: () -> Unit,
    onCancelOrder: () -> Unit,
    onClosePosition: (Long) -> Unit,
    onDismissNotice: () -> Unit,
    onRefresh: () -> Unit,
    onOpenLiveChart: () -> Unit,
    onDisconnect: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "MT4 Account",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = FoxAmber50,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${accountInfo.name} (${accountInfo.server})",
                style = MaterialTheme.typography.bodyMedium,
                color = FoxNeutral60,
            )
        }

        item {
            AccountInfoCard(accountInfo)
        }

        item {
            LiveTradingControls(
                liveModeEnabled = liveModeEnabled,
                killSwitchEngaged = killSwitchEngaged,
                onToggleLiveMode = onToggleLiveMode,
                onEngageKillSwitch = onEngageKillSwitch,
                onDisengageKillSwitch = onDisengageKillSwitch,
            )
        }

        item {
            TradeEntryCard(
                symbol = tradeSymbol,
                direction = tradeDirection,
                lots = lotsInput,
                sl = slInput,
                tp = tpInput,
                quotePrice = quotePrice,
                isPlacing = isPlacing,
                liveModeEnabled = liveModeEnabled,
                killSwitchEngaged = killSwitchEngaged,
                onSymbolChange = onTradeSymbolChange,
                onDirectionChange = onDirectionChange,
                onLotsChange = onLotsChange,
                onSlChange = onSlChange,
                onTpChange = onTpChange,
                onRequestConfirmation = onRequestConfirmation,
            )
        }

        notice?.let { message ->
            item {
                NoticeBanner(message = message, onDismiss = onDismissNotice)
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Open Positions (${positions.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp),
                        color = FoxAmber50,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }

        if (positions.isEmpty()) {
            item {
                Text(
                    text = "No open positions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FoxNeutral60,
                )
            }
        } else {
            items(positions, key = { it.ticket }) { position ->
                PositionCard(position = position, isPlacing = isPlacing, onClose = onClosePosition)
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOpenLiveChart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
            ) {
                Text("View live chart", color = MaterialTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                ) { Text("Refresh") }
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = FoxBearishText),
                ) { Text("Disconnect", color = MaterialTheme.colorScheme.onPrimary) }
            }
        }
    }

    // Two-step confirmation dialog.
    pendingOrder?.let { order ->
        AlertDialog(
            onDismissRequest = onCancelOrder,
            title = { Text("Confirm order") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailRow("Symbol", order.symbol)
                    DetailRow("Direction", order.direction.name.uppercase())
                    DetailRow("Volume", order.lots.toString())
                    DetailRow("Entry", order.entryPrice.toString())
                    DetailRow("Stop loss", order.stopLoss?.toString() ?: "None")
                    DetailRow("Take profit", order.takeProfit?.toString() ?: "None")
                    order.volumeBoundsNote?.let { note ->
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (order.isVolumeEstimated) MaterialTheme.colorScheme.error else FoxNeutral60,
                        )
                        if (order.isVolumeEstimated) {
                            Text(
                                text = "⚠ Using estimated limits — broker validation isn't authoritative for this order.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Text(
                        "Live orders place money at risk. Confirm to send this order to your MT4 broker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FoxNeutral60,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmOrder,
                    enabled = !isPlacing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (order.direction == Direction.BULLISH) FoxBullish else FoxBearish
                    ),
                ) {
                    if (isPlacing) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Place order", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelOrder, enabled = !isPlacing) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LiveTradingControls(
    liveModeEnabled: Boolean,
    killSwitchEngaged: Boolean,
    onToggleLiveMode: () -> Unit,
    onEngageKillSwitch: () -> Unit,
    onDisengageKillSwitch: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Live trading", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Live mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Must be ON to place real orders",
                        style = MaterialTheme.typography.bodySmall,
                        color = FoxNeutral60,
                    )
                }
                Switch(checked = liveModeEnabled, onCheckedChange = { onToggleLiveMode() })
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Emergency kill switch", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (killSwitchEngaged) "Blocks all live orders" else "Instantly block all live orders",
                        style = MaterialTheme.typography.bodySmall,
                        color = FoxNeutral60,
                    )
                }
                if (killSwitchEngaged) {
                    Button(
                        onClick = onDisengageKillSwitch,
                        colors = ButtonDefaults.buttonColors(containerColor = FoxSuccess),
                    ) { Text("Disarm", color = MaterialTheme.colorScheme.onPrimary) }
                } else {
                    OutlinedButton(
                        onClick = onEngageKillSwitch,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = FoxBearish),
                    ) { Text("Engage") }
                }
            }
        }
    }
}

@Composable
private fun TradeEntryCard(
    symbol: String,
    direction: Direction,
    lots: String,
    sl: String,
    tp: String,
    quotePrice: Double?,
    isPlacing: Boolean,
    liveModeEnabled: Boolean,
    killSwitchEngaged: Boolean,
    onSymbolChange: (String) -> Unit,
    onDirectionChange: (Direction) -> Unit,
    onLotsChange: (String) -> Unit,
    onSlChange: (String) -> Unit,
    onTpChange: (String) -> Unit,
    onRequestConfirmation: () -> Unit,
) {
    val canTrade = liveModeEnabled && !killSwitchEngaged
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("New order", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = quotePrice?.let { "Price %.5f".format(it) } ?: "No price",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FoxAmber50,
                    fontWeight = FontWeight.Medium,
                )
            }

            OutlinedTextField(
                value = symbol,
                onValueChange = onSymbolChange,
                label = { Text("Symbol") },
                singleLine = true,
                enabled = !isPlacing,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onDirectionChange(Direction.BULLISH) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (direction == Direction.BULLISH) FoxBullish else MaterialTheme.colorScheme.surface
                    ),
                ) { Text("BUY", color = MaterialTheme.colorScheme.onPrimary) }
                Button(
                    onClick = { onDirectionChange(Direction.BEARISH) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (direction == Direction.BEARISH) FoxBearish else MaterialTheme.colorScheme.surface
                    ),
                ) { Text("SELL", color = MaterialTheme.colorScheme.onPrimary) }
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = lots,
                onValueChange = onLotsChange,
                label = { Text("Volume (lots)") },
                singleLine = true,
                enabled = !isPlacing,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sl,
                onValueChange = onSlChange,
                label = { Text("Stop loss (optional)") },
                singleLine = true,
                enabled = !isPlacing,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = tp,
                onValueChange = onTpChange,
                label = { Text("Take profit (optional)") },
                singleLine = true,
                enabled = !isPlacing,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onRequestConfirmation,
                modifier = Modifier.fillMaxWidth(),
                enabled = canTrade && !isPlacing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (canTrade) FoxAmber50 else MaterialTheme.colorScheme.surfaceVariant
                ),
            ) {
                if (isPlacing) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = when {
                            !liveModeEnabled && killSwitchEngaged -> "Enable live mode & disarm kill switch"
                            !liveModeEnabled -> "Enable live mode to trade"
                            killSwitchEngaged -> "Disarm kill switch to trade"
                            else -> "Review & Place"
                        },
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FoxAmber50.copy(alpha = 0.12f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun AccountInfoCard(info: Mt4AccountInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            AccountRow("Login", info.login.toString())
            AccountRow("Balance", "${info.balance} ${info.currency}")
            AccountRow("Equity", "${info.equity} ${info.currency}")
            AccountRow("Margin", "${info.margin} ${info.currency}")
            AccountRow("Free Margin", "${info.freeMargin} ${info.currency}")
            AccountRow("Leverage", "1:${info.leverage}")
        }
    }
}

@Composable
private fun PositionCard(position: Mt4Position, isPlacing: Boolean, onClose: (Long) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = position.symbol,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = position.type.name,
                    fontWeight = FontWeight.Medium,
                    color = if (position.type.name.contains("BUY")) FoxAmber50 else FoxBearishText,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            AccountRow("Lots", position.lots.toString())
            AccountRow("Open Price", position.openPrice.toString())
            AccountRow("Profit", position.profit.toString())
            AccountRow("SL", if (position.sl > 0.0) position.sl.toString() else "None")
            AccountRow("TP", if (position.tp > 0.0) position.tp.toString() else "None")
            AccountRow("Ticket", "#${position.ticket}")
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onClose(position.ticket) },
                enabled = !isPlacing,
                colors = ButtonDefaults.buttonColors(containerColor = FoxBearish),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Close position", color = MaterialTheme.colorScheme.onPrimary) }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = FoxNeutral60)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AccountRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = FoxNeutral60,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}
