package com.foxtrader.app.feature.deriv.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.foxtrader.app.domain.model.deriv.DerivAccountType
import com.foxtrader.app.ui.components.FoxPanel
import com.foxtrader.app.ui.components.FoxScreenTopBar
import com.foxtrader.app.ui.components.FoxSectionHeader
import com.foxtrader.app.ui.theme.FoxTheme

@Composable
fun Phase9DerivScreen(
    onNavigateBack: () -> Unit,
    viewModel: DerivViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = FoxTheme.colors
    val spacing = FoxTheme.spacing

    Scaffold(
        containerColor = colors.background,
        topBar = { FoxScreenTopBar(title = "Native Deriv · API & Account", onNavigateBack = onNavigateBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = spacing.screenHorizontal, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                FoxPanel {
                    Icon(Icons.Outlined.Security, contentDescription = null, tint = colors.accent)
                    Text("Deriv New API", style = FoxTheme.type.h3, color = colors.textPrimary)
                    Text(
                        "Native REST + OTP-authenticated WebSocket integration. PAT tokens are encrypted on-device. Real-money orders always require a fresh manual confirmation; there is no unattended live auto-fire path.",
                        style = FoxTheme.type.body,
                        color = colors.textMuted,
                    )
                    Text("Connection: ${state.connectionState}", style = FoxTheme.type.caption, color = colors.textMuted)
                    Text(
                        "API health: ${state.apiHealthy?.let { if (it) "OK" else "FAILED" } ?: "not checked"}",
                        style = FoxTheme.type.caption,
                        color = if (state.apiHealthy == false) colors.danger else colors.textMuted,
                    )
                    OutlinedButton(onClick = viewModel::checkHealth, modifier = Modifier.fillMaxWidth()) { Text("Check Deriv API health") }
                }
            }

            item { FoxSectionHeader("Deriv API & account switcher") }
            item {
                FoxPanel {
                    OutlinedTextField(
                        value = state.appId,
                        onValueChange = viewModel::onAppIdChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !state.loading,
                        label = { Text("Deriv App ID") },
                    )
                    OutlinedTextField(
                        value = state.token,
                        onValueChange = viewModel::onTokenChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !state.loading,
                        label = { Text("PAT / OAuth access token") },
                        visualTransformation = if (state.tokenVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = viewModel::toggleTokenVisibility) {
                                Text(if (state.tokenVisible) "Hide" else "Show")
                            }
                        },
                    )
                    if (state.credentialsDirty) {
                        Text(
                            "API configuration changed. The previous authenticated session was disconnected for safety.",
                            style = FoxTheme.type.caption,
                            color = colors.warning,
                        )
                    }
                    Button(
                        onClick = if (state.credentialsDirty) viewModel::applyCredentials else viewModel::loadAccounts,
                        enabled = state.credentialsReady && !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.credentialsDirty) "Apply API & verify accounts" else "Refresh accounts")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = viewModel::revertCredentials, enabled = !state.loading, modifier = Modifier.weight(1f)) {
                            Text("Revert API")
                        }
                        OutlinedButton(onClick = viewModel::connectPublic, enabled = !state.loading && !state.credentialsDirty, modifier = Modifier.weight(1f)) {
                            Text("Public data")
                        }
                    }
                    OutlinedButton(onClick = viewModel::createDemoAccount, enabled = state.credentialsReady && !state.credentialsDirty && !state.loading, modifier = Modifier.fillMaxWidth()) {
                        Text("Create / restore demo Options account")
                    }
                    OutlinedButton(onClick = viewModel::loadWallets, enabled = state.credentialsReady && !state.credentialsDirty && !state.loading, modifier = Modifier.fillMaxWidth()) {
                        Text("Load wallets (payment scope · read only)")
                    }
                    OutlinedButton(onClick = viewModel::clearCredentials, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Clear saved credentials") }
                }
            }

            if (state.wallets.isNotEmpty()) {
                item { FoxSectionHeader("Wallets · read only") }
                items(state.wallets, key = { it.walletId }) { wallet ->
                    FoxPanel {
                        Text("${wallet.type} wallet", style = FoxTheme.type.h3, color = colors.textPrimary)
                        wallet.balances.forEach { balance ->
                            Text(
                                "${balance.currency}: ${balance.balance} · in ${balance.input} · out ${balance.output}",
                                style = FoxTheme.type.caption,
                                color = colors.textMuted,
                            )
                        }
                        wallet.approximateTotalBalance?.let { total ->
                            Text("Approx. total: $total ${wallet.convertedTo ?: ""}", style = FoxTheme.type.body, color = colors.textPrimary)
                        }
                        OutlinedButton(
                            onClick = { viewModel.loadWalletTransactions(wallet.type) },
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Load ${wallet.type} transactions") }
                        if (state.selectedWalletType == wallet.type && state.walletTransactions.isNotEmpty()) {
                            state.walletTransactions.take(8).forEach { tx ->
                                Text(
                                    "#${tx.transactionId} · ${tx.category} · ${tx.netAmount} ${tx.currency} · ${tx.status}",
                                    style = FoxTheme.type.caption,
                                    color = colors.textMuted,
                                )
                            }
                            if (state.walletNextPageUrl != null) {
                                OutlinedButton(
                                    onClick = viewModel::loadNextWalletTransactions,
                                    enabled = !state.loading,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Load more transactions") }
                            }
                        }
                    }
                }
            }

            if (state.accounts.isNotEmpty()) {
                item { FoxSectionHeader("Options accounts") }
                items(state.accounts, key = { it.accountId }) { account ->
                    FoxPanel {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(account.accountId, style = FoxTheme.type.h3, color = colors.textPrimary)
                                Text("${account.accountType} · ${account.currency} · ${account.status ?: "status unknown"}", style = FoxTheme.type.caption, color = colors.textMuted)
                                account.balance?.let { Text("REST balance: $it ${account.currency}", style = FoxTheme.type.caption, color = colors.textMuted) }
                            }
                        }
                        val isConnectedAccount = state.authenticated && state.selectedAccount?.accountId == account.accountId
                        OutlinedButton(
                            onClick = { viewModel.connectAccount(account) },
                            enabled = !state.loading && !state.credentialsDirty && !isConnectedAccount,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    isConnectedAccount -> "Connected"
                                    state.authenticated -> if (account.accountType == DerivAccountType.REAL) "Switch to REAL account" else "Switch to demo account"
                                    account.accountType == DerivAccountType.REAL -> "Connect REAL account"
                                    else -> "Connect demo account"
                                }
                            )
                        }
                        if (account.accountType == DerivAccountType.DEMO) {
                            OutlinedButton(onClick = { viewModel.resetDemoBalance(account) }, enabled = !state.loading && !state.credentialsDirty, modifier = Modifier.fillMaxWidth()) {
                                Text("Reset demo balance")
                            }
                        }
                    }
                }
            }

            if (state.authenticated) {
                item { FoxSectionHeader("Account cockpit") }
                item {
                    FoxPanel {
                        Icon(Icons.Outlined.AccountBalance, contentDescription = null, tint = colors.accent)
                        Text("${state.selectedAccount?.accountId} · ${state.selectedAccount?.accountType}", style = FoxTheme.type.h3, color = colors.textPrimary)
                        Text(
                            state.balance?.let { "Balance ${it.amount} ${it.currency}" } ?: "Balance not loaded",
                            style = FoxTheme.type.body,
                            color = colors.textPrimary,
                        )
                        Text("Open contracts: ${state.positions.size}", style = FoxTheme.type.caption, color = colors.textMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = viewModel::refreshAccount, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null)
                                Text("Refresh")
                            }
                            OutlinedButton(onClick = viewModel::disconnect, modifier = Modifier.weight(1f)) { Text("Disconnect") }
                        }
                    }
                }
            }

            if (state.authenticated) {
                item { FoxSectionHeader("Account history") }
                item {
                    FoxPanel {
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = viewModel::loadAccountHistory, enabled = !state.loading, modifier = Modifier.weight(1f)) {
                                Text("Refresh history")
                            }
                        }
                        Text("Closed P/L records: ${state.profitRecords.size}", style = FoxTheme.type.caption, color = colors.textMuted)
                        state.profitRecords.take(5).forEach { record ->
                            Text(
                                "#${record.transactionId} · ${record.contractType ?: "contract"} · ${record.symbol ?: "—"} · buy ${record.buyPrice} · sell ${record.sellPrice} · payout ${record.payout}",
                                style = FoxTheme.type.caption,
                                color = colors.textMuted,
                            )
                        }
                        Text("Statement records: ${state.statementRecords.size}", style = FoxTheme.type.caption, color = colors.textMuted)
                        state.statementRecords.take(5).forEach { record ->
                            Text(
                                "${record.actionType} · ${record.amount} · balance ${record.balanceAfter}",
                                style = FoxTheme.type.caption,
                                color = colors.textMuted,
                            )
                        }
                    }
                }
            }

            if (state.authenticated && state.positions.isNotEmpty()) {
                item { FoxSectionHeader("Open Options contracts") }
                items(state.positions, key = { it.contractId }) { position ->
                    FoxPanel {
                        Text("#${position.contractId} · ${position.contractType}", style = FoxTheme.type.h3, color = colors.textPrimary)
                        Text(
                            "${position.symbol ?: "unknown symbol"} · P/L ${position.profit ?: "—"} ${position.currency} · Bid ${position.bidPrice ?: "—"}",
                            style = FoxTheme.type.caption,
                            color = colors.textMuted,
                        )
                        OutlinedButton(onClick = { viewModel.manageContract(position) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.manageContractId == position.contractId) "Managing contract" else "Manage contract")
                        }

                        if (state.manageContractId == position.contractId) {
                            Text(
                                if (state.selectedAccount?.accountType == DerivAccountType.REAL) "REAL MONEY changes require a fresh confirm click." else "Demo changes still use review + confirm.",
                                style = FoxTheme.type.caption,
                                color = if (state.selectedAccount?.accountType == DerivAccountType.REAL) colors.warning else colors.textMuted,
                            )
                            OutlinedTextField(
                                value = state.sellMinimumPrice,
                                onValueChange = viewModel::onSellMinimumPriceChange,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Sell minimum price (0 = market)") },
                            )
                            if (state.pendingSellContractId != position.contractId) {
                                OutlinedButton(onClick = { viewModel.reviewSell(position) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Review early sell") }
                            } else {
                                Button(onClick = { viewModel.confirmSell(position) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (state.selectedAccount?.accountType == DerivAccountType.REAL) "Confirm REAL sell" else "Confirm demo sell")
                                }
                            }

                            OutlinedTextField(
                                value = state.stopLossAmount,
                                onValueChange = viewModel::onStopLossAmountChange,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Stop-loss order amount (optional)") },
                            )
                            OutlinedTextField(
                                value = state.takeProfitAmount,
                                onValueChange = viewModel::onTakeProfitAmountChange,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Take-profit order amount (optional)") },
                            )
                            if (!state.pendingUpdateConfirmation) {
                                OutlinedButton(onClick = { viewModel.reviewUpdate(position) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Review SL / TP update") }
                            } else {
                                Button(onClick = { viewModel.confirmUpdate(position) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (state.selectedAccount?.accountType == DerivAccountType.REAL) "Confirm REAL SL / TP" else "Confirm demo SL / TP")
                                }
                            }

                            if (state.pendingCancelContractId != position.contractId) {
                                OutlinedButton(onClick = { viewModel.reviewCancel(position) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Review cancel (if supported)") }
                            } else {
                                Button(onClick = { viewModel.confirmCancel(position) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (state.selectedAccount?.accountType == DerivAccountType.REAL) "Confirm REAL cancel" else "Confirm demo cancel")
                                }
                            }
                        }
                    }
                }
            }

            item { FoxSectionHeader("Market data") }
            item {
                FoxPanel {
                    Icon(Icons.Outlined.Cloud, contentDescription = null, tint = colors.accent)
                    OutlinedTextField(
                        value = state.selectedSymbol,
                        onValueChange = viewModel::onSymbolChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Underlying symbol") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = viewModel::loadSymbols, modifier = Modifier.weight(1f)) { Text("Active symbols") }
                        OutlinedButton(onClick = viewModel::startTicks, modifier = Modifier.weight(1f)) { Text("Stream ticks") }
                    }
                    OutlinedButton(onClick = viewModel::loadContractCategories, modifier = Modifier.fillMaxWidth()) { Text("Load contract categories") }
                    Text("Symbols loaded: ${state.symbols.size} · contract categories: ${state.contractCategories.size}", style = FoxTheme.type.caption, color = colors.textMuted)
                    state.tick?.let { Text("${it.symbol}: ${it.quote} · ${it.epochSeconds}", style = FoxTheme.type.h3, color = colors.textPrimary) }
                }
            }

            if (state.authenticated) {
                item { FoxSectionHeader("Manual contract order") }
                item {
                    FoxPanel {
                        Text(
                            if (state.selectedAccount?.accountType == DerivAccountType.REAL) "REAL MONEY — review proposal, then confirm the exact order." else "Demo account — proposal and explicit confirmation are still required.",
                            style = FoxTheme.type.body,
                            color = if (state.selectedAccount?.accountType == DerivAccountType.REAL) colors.warning else colors.textMuted,
                        )
                        OutlinedTextField(value = state.contractType, onValueChange = viewModel::onContractTypeChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Contract type (e.g. CALL)") })
                        OutlinedTextField(value = state.amount, onValueChange = viewModel::onAmountChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Stake amount") })
                        OutlinedTextField(value = state.duration, onValueChange = viewModel::onDurationChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Duration (${state.durationUnit})") })
                        Button(onClick = viewModel::requestProposal, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Get proposal") }
                        state.proposal?.let { proposal ->
                            Text("Proposal ${proposal.id.take(12)}…", style = FoxTheme.type.caption, color = colors.textMuted)
                            Text("Ask ${proposal.askPrice ?: "—"} · Payout ${proposal.payout ?: "—"}", style = FoxTheme.type.h3, color = colors.textPrimary)
                            if (!state.pendingBuyConfirmation) {
                                OutlinedButton(onClick = viewModel::reviewBuy, modifier = Modifier.fillMaxWidth()) { Text("Review purchase") }
                            } else {
                                Button(onClick = viewModel::confirmBuy, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (state.selectedAccount?.accountType == DerivAccountType.REAL) "Confirm REAL purchase" else "Confirm demo purchase")
                                }
                            }
                        }
                    }
                }
            }

            state.notice?.let { notice -> item { Text(notice, style = FoxTheme.type.body, color = colors.textMuted) } }
            state.error?.let { error -> item { Text(error, style = FoxTheme.type.body, color = colors.danger) } }
        }
    }
}
