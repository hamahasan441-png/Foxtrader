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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.foxtrader.app.domain.model.Mt4PendingOrder
import com.foxtrader.app.domain.model.Mt4PendingExpirationType
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
                platform = current.platform,
                executionMode = current.executionMode,
                positions = current.positions,
                pendingOrders = current.pendingOrders,
                isRefreshing = current.isRefreshing,
                liveModeEnabled = current.liveModeEnabled,
                killSwitchEngaged = current.killSwitchEngaged,
                quotePrice = current.lastPrice,
                tradeSymbol = current.tradeSymbol,
                tradeDirection = current.tradeDirection,
                orderEntryKind = current.orderEntryKind,
                pendingPriceInput = current.pendingPriceInput,
                pendingExpirationType = current.pendingExpirationType,
                pendingExpirationInput = current.pendingExpirationInput,
                lotsInput = current.lotsInput,
                slInput = current.slInput,
                tpInput = current.tpInput,
                isPlacing = current.isPlacing,
                pendingOrder = current.pendingOrder,
                pendingClose = current.pendingClose,
                positionManager = current.positionManager,
                pendingOrderManager = current.pendingOrderManager,
                notice = current.notice,
                onToggleLiveMode = viewModel::toggleLiveMode,
                onEngageKillSwitch = viewModel::engageKillSwitch,
                onDisengageKillSwitch = viewModel::disengageKillSwitch,
                onTradeSymbolChange = viewModel::onTradeSymbolChange,
                onDirectionChange = viewModel::onDirectionChange,
                onOrderEntryKindChange = viewModel::onOrderEntryKindChange,
                onPendingPriceChange = viewModel::onPendingPriceChange,
                onPendingExpirationTypeChange = viewModel::onPendingExpirationTypeChange,
                onPendingExpirationInputChange = viewModel::onPendingExpirationInputChange,
                onLotsChange = viewModel::onLotsChange,
                onSlChange = viewModel::onSlChange,
                onTpChange = viewModel::onTpChange,
                onRequestConfirmation = viewModel::requestOrderConfirmation,
                onConfirmOrder = viewModel::confirmOrder,
                onCancelOrder = viewModel::cancelOrder,
                onRequestClosePosition = viewModel::requestClosePosition,
                onConfirmClosePosition = viewModel::confirmClosePosition,
                onCancelClosePosition = viewModel::cancelClosePosition,
                onRequestManagePosition = viewModel::requestManagePosition,
                onDismissPositionManager = viewModel::dismissPositionManager,
                onPositionSlChange = viewModel::updatePositionManagerSl,
                onPositionTpChange = viewModel::updatePositionManagerTp,
                onPositionTrailingChange = viewModel::updatePositionManagerTrailing,
                onPositionPartialChange = viewModel::updatePositionManagerPartial,
                onApplyPositionProtection = viewModel::applyPositionProtection,
                onMoveBreakEven = viewModel::moveManagedPositionToBreakEven,
                onPartialClose = viewModel::partialCloseManagedPosition,
                onRequestManagePendingOrder = viewModel::requestManagePendingOrder,
                onDismissPendingOrderManager = viewModel::dismissPendingOrderManager,
                onPendingManagerPriceChange = viewModel::updatePendingManagerPrice,
                onPendingManagerSlChange = viewModel::updatePendingManagerSl,
                onPendingManagerTpChange = viewModel::updatePendingManagerTp,
                onApplyPendingModification = viewModel::applyPendingOrderModification,
                onCancelManagedPendingOrder = viewModel::cancelManagedPendingOrder,
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
    platform: String,
    executionMode: BrokerExecutionMode,
    positions: List<Mt4Position>,
    pendingOrders: List<Mt4PendingOrder>,
    isRefreshing: Boolean,
    liveModeEnabled: Boolean,
    killSwitchEngaged: Boolean,
    quotePrice: Double?,
    tradeSymbol: String,
    tradeDirection: Direction,
    orderEntryKind: BrokerOrderEntryKind,
    pendingPriceInput: String,
    pendingExpirationType: Mt4PendingExpirationType,
    pendingExpirationInput: String,
    lotsInput: String,
    slInput: String,
    tpInput: String,
    isPlacing: Boolean,
    pendingOrder: Mt4UiState.PendingTrade?,
    pendingClose: Mt4UiState.PendingClose?,
    positionManager: Mt4UiState.PositionManagerDraft?,
    pendingOrderManager: Mt4UiState.PendingOrderManagerDraft?,
    notice: String?,
    onToggleLiveMode: () -> Unit,
    onEngageKillSwitch: () -> Unit,
    onDisengageKillSwitch: () -> Unit,
    onTradeSymbolChange: (String) -> Unit,
    onDirectionChange: (Direction) -> Unit,
    onOrderEntryKindChange: (BrokerOrderEntryKind) -> Unit,
    onPendingPriceChange: (String) -> Unit,
    onPendingExpirationTypeChange: (Mt4PendingExpirationType) -> Unit,
    onPendingExpirationInputChange: (String) -> Unit,
    onLotsChange: (String) -> Unit,
    onSlChange: (String) -> Unit,
    onTpChange: (String) -> Unit,
    onRequestConfirmation: () -> Unit,
    onConfirmOrder: () -> Unit,
    onCancelOrder: () -> Unit,
    onRequestClosePosition: (Long) -> Unit,
    onConfirmClosePosition: () -> Unit,
    onCancelClosePosition: () -> Unit,
    onRequestManagePosition: (Long) -> Unit,
    onDismissPositionManager: () -> Unit,
    onPositionSlChange: (String) -> Unit,
    onPositionTpChange: (String) -> Unit,
    onPositionTrailingChange: (String) -> Unit,
    onPositionPartialChange: (String) -> Unit,
    onApplyPositionProtection: () -> Unit,
    onMoveBreakEven: () -> Unit,
    onPartialClose: () -> Unit,
    onRequestManagePendingOrder: (Long) -> Unit,
    onDismissPendingOrderManager: () -> Unit,
    onPendingManagerPriceChange: (String) -> Unit,
    onPendingManagerSlChange: (String) -> Unit,
    onPendingManagerTpChange: (String) -> Unit,
    onApplyPendingModification: () -> Unit,
    onCancelManagedPendingOrder: () -> Unit,
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
                text = "Trading Account",
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
            Text(
                text = if (executionMode == BrokerExecutionMode.UNKNOWN) {
                    "${platform.uppercase()} · UNKNOWN account type — treat as LIVE"
                } else {
                    "${platform.uppercase()} · ${executionMode.name} account"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (executionMode == BrokerExecutionMode.DEMO) FoxSuccess else FoxAmber50,
                fontWeight = FontWeight.SemiBold,
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
                orderEntryKind = orderEntryKind,
                pendingPrice = pendingPriceInput,
                pendingExpirationType = pendingExpirationType,
                pendingExpirationInput = pendingExpirationInput,
                lots = lotsInput,
                sl = slInput,
                tp = tpInput,
                quotePrice = quotePrice,
                isPlacing = isPlacing,
                liveModeEnabled = liveModeEnabled,
                killSwitchEngaged = killSwitchEngaged,
                onSymbolChange = onTradeSymbolChange,
                onDirectionChange = onDirectionChange,
                onOrderEntryKindChange = onOrderEntryKindChange,
                onPendingPriceChange = onPendingPriceChange,
                onPendingExpirationTypeChange = onPendingExpirationTypeChange,
                onPendingExpirationInputChange = onPendingExpirationInputChange,
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
                PositionCard(
                    position = position,
                    isPlacing = isPlacing,
                    onManage = onRequestManagePosition,
                    onClose = onRequestClosePosition,
                )
            }
        }

        item {
            Text(
                text = "Pending Orders (${pendingOrders.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (pendingOrders.isEmpty()) {
            item { Text("No pending orders", style = MaterialTheme.typography.bodyMedium, color = FoxNeutral60) }
        } else {
            items(pendingOrders, key = { "pending-${it.ticket}" }) { order ->
                PendingOrderCard(order = order, isPlacing = isPlacing, onManage = onRequestManagePendingOrder)
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
                    DetailRow("Order type", order.orderType.name)
                    DetailRow("Direction", order.direction.name.uppercase())
                    DetailRow("Volume", order.lots.toString())
                    DetailRow("Entry", order.entryPrice.toString())
                    DetailRow("Stop loss", order.stopLoss?.toString() ?: "None")
                    DetailRow("Take profit", order.takeProfit?.toString() ?: "None")
                    if (order.orderType !in setOf(com.foxtrader.app.domain.model.Mt4OrderType.BUY, com.foxtrader.app.domain.model.Mt4OrderType.SELL)) {
                        DetailRow("Expiration", order.expirationType.name + (order.expirationTime?.let { " · ${java.time.Instant.ofEpochMilli(it)}" } ?: ""))
                    }
                    order.maxSlippagePoints?.let { points ->
                        DetailRow("Review drift cap", "%.1f broker points".format(java.util.Locale.US, points))
                    }
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
                        when (executionMode) {
                            BrokerExecutionMode.DEMO -> "Demo execution: confirm to send this simulated broker order."
                            BrokerExecutionMode.LIVE -> "Live orders place money at risk. Confirm to send this order to your broker."
                            BrokerExecutionMode.UNKNOWN -> "Broker account type could not be verified. Treat this as LIVE: confirm only if you intend to place a real-money order."
                        },
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
                        Text(
                            if (order.orderType == com.foxtrader.app.domain.model.Mt4OrderType.BUY ||
                                order.orderType == com.foxtrader.app.domain.model.Mt4OrderType.SELL
                            ) "Place market order" else "Place pending order",
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelOrder, enabled = !isPlacing) { Text("Cancel") }
            },
        )
    }

    pendingClose?.let { close ->
        AlertDialog(
            onDismissRequest = onCancelClosePosition,
            title = { Text("Confirm position close") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailRow("Ticket", close.ticket.toString())
                    DetailRow("Symbol", close.symbol)
                    DetailRow("Volume", close.lots.toString())
                    DetailRow("Current P/L", close.profit.toString())
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Text(
                        when (executionMode) {
                            BrokerExecutionMode.DEMO -> "Confirm to close this demo position."
                            BrokerExecutionMode.LIVE -> "Live close is irreversible and affects real funds. Confirm only after reviewing this exact position."
                            BrokerExecutionMode.UNKNOWN -> "Broker account type could not be verified. Treat this close as LIVE and confirm only after reviewing this exact position."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = FoxNeutral60,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmClosePosition,
                    enabled = !isPlacing,
                    colors = ButtonDefaults.buttonColors(containerColor = FoxBearishText),
                ) { Text("Close position", color = MaterialTheme.colorScheme.onPrimary) }
            },
            dismissButton = {
                TextButton(onClick = onCancelClosePosition, enabled = !isPlacing) { Text("Cancel") }
            },
        )
    }

    positionManager?.let { manager ->
        AlertDialog(
            onDismissRequest = onDismissPositionManager,
            title = { Text("Position manager · #${manager.ticket}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DetailRow("Symbol", manager.symbol)
                    DetailRow("Side", manager.side.name)
                    DetailRow("Open", manager.openPrice.toString())
                    DetailRow("Volume", manager.lots.toString())
                    OutlinedTextField(
                        value = manager.stopLossInput,
                        onValueChange = onPositionSlChange,
                        label = { Text("Stop loss · 0 removes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = manager.takeProfitInput,
                        onValueChange = onPositionTpChange,
                        label = { Text("Take profit · 0 removes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = manager.trailingPointsInput,
                        onValueChange = onPositionTrailingChange,
                        label = { Text("Trailing distance (broker points, optional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    HorizontalDivider()
                    OutlinedTextField(
                        value = manager.partialLotsInput,
                        onValueChange = onPositionPartialChange,
                        label = { Text("Partial close volume") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    Text(
                        "Apply/BE/Partial are explicit broker actions. The repository rechecks the position, account, quote and broker constraints before submission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FoxNeutral60,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onMoveBreakEven, enabled = !isPlacing, modifier = Modifier.weight(1f)) {
                            Text("Break-even", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = onPartialClose, enabled = !isPlacing, modifier = Modifier.weight(1f)) {
                            Text("Partial close", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onApplyPositionProtection, enabled = !isPlacing) { Text("Apply protection") }
            },
            dismissButton = {
                TextButton(onClick = onDismissPositionManager, enabled = !isPlacing) { Text("Cancel") }
            },
        )
    }

    pendingOrderManager?.let { manager ->
        AlertDialog(
            onDismissRequest = onDismissPendingOrderManager,
            title = { Text("Pending order · #${manager.ticket}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DetailRow("Symbol", manager.symbol)
                    DetailRow("Type", manager.type.name)
                    DetailRow("Volume", manager.lots.toString())
                    OutlinedTextField(
                        value = manager.openPriceInput,
                        onValueChange = onPendingManagerPriceChange,
                        label = { Text("Open price") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = manager.stopLossInput,
                        onValueChange = onPendingManagerSlChange,
                        label = { Text("Stop loss · 0 removes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = manager.takeProfitInput,
                        onValueChange = onPendingManagerTpChange,
                        label = { Text("Take profit · 0 removes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    Text(
                        "Modification is checked against current price, stop level and freeze level before broker submission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FoxNeutral60,
                    )
                }
            },
            confirmButton = {
                Button(onClick = onApplyPendingModification, enabled = !isPlacing) { Text("Apply") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onCancelManagedPendingOrder, enabled = !isPlacing) {
                        Text("Cancel order", color = FoxBearishText)
                    }
                    TextButton(onClick = onDismissPendingOrderManager, enabled = !isPlacing) { Text("Close") }
                }
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
                        "Must be ON before broker execution",
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
    orderEntryKind: BrokerOrderEntryKind,
    pendingPrice: String,
    pendingExpirationType: Mt4PendingExpirationType,
    pendingExpirationInput: String,
    lots: String,
    sl: String,
    tp: String,
    quotePrice: Double?,
    isPlacing: Boolean,
    liveModeEnabled: Boolean,
    killSwitchEngaged: Boolean,
    onSymbolChange: (String) -> Unit,
    onDirectionChange: (Direction) -> Unit,
    onOrderEntryKindChange: (BrokerOrderEntryKind) -> Unit,
    onPendingPriceChange: (String) -> Unit,
    onPendingExpirationTypeChange: (Mt4PendingExpirationType) -> Unit,
    onPendingExpirationInputChange: (String) -> Unit,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BrokerOrderEntryKind.entries.forEach { kind ->
                    OutlinedButton(
                        onClick = { onOrderEntryKindChange(kind) },
                        enabled = !isPlacing,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (orderEntryKind == kind) FoxAmber50 else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) { Text(kind.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp) }
                }
            }
            if (orderEntryKind != BrokerOrderEntryKind.MARKET) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pendingPrice,
                    onValueChange = onPendingPriceChange,
                    label = { Text("Pending open price") },
                    singleLine = true,
                    enabled = !isPlacing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (orderEntryKind == BrokerOrderEntryKind.LIMIT) {
                        if (direction == Direction.BULLISH) "BUY LIMIT must be below ask" else "SELL LIMIT must be above bid"
                    } else {
                        if (direction == Direction.BULLISH) "BUY STOP must be above ask" else "SELL STOP must be below bid"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )
                Spacer(Modifier.height(8.dp))
                Text("Expiration", style = MaterialTheme.typography.labelMedium, color = FoxNeutral60)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Mt4PendingExpirationType.entries.forEach { expiration ->
                        OutlinedButton(
                            onClick = { onPendingExpirationTypeChange(expiration) },
                            enabled = !isPlacing,
                            modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (pendingExpirationType == expiration) FoxAmber50 else MaterialTheme.colorScheme.onSurface,
                            ),
                        ) { Text(expiration.name.replace("SPECIFIED_DAY", "SPEC DAY").replace("SPECIFIED", "SPEC"), fontSize = 10.sp) }
                    }
                }
                if (pendingExpirationType == Mt4PendingExpirationType.SPECIFIED ||
                    pendingExpirationType == Mt4PendingExpirationType.SPECIFIED_DAY
                ) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = pendingExpirationInput,
                        onValueChange = onPendingExpirationInputChange,
                        label = { Text("Expiration local time") },
                        supportingText = { Text("yyyy-MM-dd HH:mm") },
                        singleLine = true,
                        enabled = !isPlacing,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
private fun PositionCard(
    position: Mt4Position,
    isPlacing: Boolean,
    onManage: (Long) -> Unit,
    onClose: (Long) -> Unit,
) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onManage(position.ticket) },
                    enabled = !isPlacing,
                    modifier = Modifier.weight(1f),
                ) { Text("Manage") }
                Button(
                    onClick = { onClose(position.ticket) },
                    enabled = !isPlacing,
                    colors = ButtonDefaults.buttonColors(containerColor = FoxBearish),
                    modifier = Modifier.weight(1f),
                ) { Text("Close", color = MaterialTheme.colorScheme.onPrimary) }
            }
        }
    }
}

@Composable
private fun PendingOrderCard(
    order: Mt4PendingOrder,
    isPlacing: Boolean,
    onManage: (Long) -> Unit,
) {
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
                Text(order.symbol, fontWeight = FontWeight.Bold)
                Text(order.type.name, color = FoxAmber50, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            AccountRow("Volume", order.remainingLots.toString())
            AccountRow("Open Price", order.openPrice.toString())
            AccountRow("Current", order.currentPrice?.toString() ?: "—")
            AccountRow("SL", if (order.sl > 0.0) order.sl.toString() else "None")
            AccountRow("TP", if (order.tp > 0.0) order.tp.toString() else "None")
            AccountRow("State", order.state)
            AccountRow("Ticket", "#${order.ticket}")
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onManage(order.ticket) },
                enabled = !isPlacing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Modify / Cancel") }
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
