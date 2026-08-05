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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.domain.model.Mt4AccountInfo
import com.foxtrader.app.domain.model.Mt4Position
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * MT4 Account screen showing account info (balance, equity, margin) and
 * a scrollable list of open positions.
 *
 * @param onDisconnected Callback when the user disconnects (navigate back).
 */
@Composable
fun Mt4AccountScreen(
    onDisconnected: () -> Unit,
    viewModel: Mt4ViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when (val current = state) {
        is Mt4UiState.Connected -> {
            ConnectedContent(
                accountInfo = current.accountInfo,
                positions = current.positions,
                isRefreshing = current.isRefreshing,
                onRefresh = viewModel::refreshPositions,
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
    onRefresh: () -> Unit,
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
                PositionCard(position)
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Refresh")
                }
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = FoxBearishText),
                ) {
                    Text("Disconnect", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun AccountInfoCard(info: Mt4AccountInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
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

@Composable
private fun PositionCard(position: Mt4Position) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
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
        }
    }
}
