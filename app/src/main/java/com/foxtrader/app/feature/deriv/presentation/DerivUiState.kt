package com.foxtrader.app.feature.deriv.presentation

import com.foxtrader.app.domain.model.deriv.DerivAccount
import com.foxtrader.app.domain.model.deriv.DerivActiveSymbol
import com.foxtrader.app.domain.model.deriv.DerivBalance
import com.foxtrader.app.domain.model.deriv.DerivConnectionState
import com.foxtrader.app.domain.model.deriv.DerivPosition
import com.foxtrader.app.domain.model.deriv.DerivProposal
import com.foxtrader.app.domain.model.deriv.DerivTick
import com.foxtrader.app.domain.model.deriv.DerivContractCategory
import com.foxtrader.app.domain.model.deriv.DerivProfitRecord
import com.foxtrader.app.domain.model.deriv.DerivStatementRecord
import com.foxtrader.app.domain.model.deriv.DerivWallet
import com.foxtrader.app.domain.model.deriv.DerivWalletTransaction

data class DerivUiState(
    val appId: String = "",
    val token: String = "",
    val tokenVisible: Boolean = false,
    val credentialsDirty: Boolean = false,
    val loading: Boolean = false,
    val apiHealthy: Boolean? = null,
    val connectionState: DerivConnectionState = DerivConnectionState.DISCONNECTED,
    val accounts: List<DerivAccount> = emptyList(),
    val selectedAccount: DerivAccount? = null,
    val symbols: List<DerivActiveSymbol> = emptyList(),
    val selectedSymbol: String = "1HZ100V",
    val tick: DerivTick? = null,
    val balance: DerivBalance? = null,
    val positions: List<DerivPosition> = emptyList(),
    val contractCategories: List<DerivContractCategory> = emptyList(),
    val profitRecords: List<DerivProfitRecord> = emptyList(),
    val statementRecords: List<DerivStatementRecord> = emptyList(),
    val wallets: List<DerivWallet> = emptyList(),
    val selectedWalletType: String? = null,
    val walletTransactions: List<DerivWalletTransaction> = emptyList(),
    val walletNextPageUrl: String? = null,
    val amount: String = "10",
    val contractType: String = "CALL",
    val duration: String = "5",
    val durationUnit: String = "m",
    val proposal: DerivProposal? = null,
    val pendingBuyConfirmation: Boolean = false,
    val manageContractId: Long? = null,
    val sellMinimumPrice: String = "0",
    val pendingSellContractId: Long? = null,
    val stopLossAmount: String = "",
    val takeProfitAmount: String = "",
    val pendingUpdateConfirmation: Boolean = false,
    val pendingCancelContractId: Long? = null,
    val notice: String? = null,
    val error: String? = null,
) {
    val credentialsReady: Boolean get() = appId.isNotBlank() && token.isNotBlank()
    val authenticated: Boolean get() = !credentialsDirty && selectedAccount != null && connectionState == DerivConnectionState.CONNECTED
}
