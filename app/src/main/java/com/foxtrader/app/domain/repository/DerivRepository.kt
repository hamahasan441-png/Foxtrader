package com.foxtrader.app.domain.repository

import com.foxtrader.app.domain.model.deriv.DerivAccount
import com.foxtrader.app.domain.model.deriv.DerivActiveSymbol
import com.foxtrader.app.domain.model.deriv.DerivBalance
import com.foxtrader.app.domain.model.deriv.DerivBuyResult
import com.foxtrader.app.domain.model.deriv.DerivConnectionState
import com.foxtrader.app.domain.model.deriv.DerivTransaction
import com.foxtrader.app.domain.model.deriv.DerivOpenContract
import com.foxtrader.app.domain.model.deriv.DerivContractUpdate
import com.foxtrader.app.domain.model.deriv.DerivContractUpdateHistoryEntry
import com.foxtrader.app.domain.model.deriv.DerivContractSpec
import com.foxtrader.app.domain.model.deriv.DerivCandle
import com.foxtrader.app.domain.model.deriv.DerivExecutionAuthorization
import com.foxtrader.app.domain.model.deriv.DerivPosition
import com.foxtrader.app.domain.model.deriv.DerivProposal
import com.foxtrader.app.domain.model.deriv.DerivProposalRequest
import com.foxtrader.app.domain.model.deriv.DerivSellResult
import com.foxtrader.app.domain.model.deriv.DerivTick
import com.foxtrader.app.domain.model.deriv.DerivContractCategory
import com.foxtrader.app.domain.model.deriv.DerivProfitRecord
import com.foxtrader.app.domain.model.deriv.DerivStatementRecord
import com.foxtrader.app.domain.model.deriv.DerivWallet
import com.foxtrader.app.domain.model.deriv.DerivWalletTransactionsPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface DerivRepository {
    val connectionState: StateFlow<DerivConnectionState>

    fun getSavedAppId(): String?
    fun getSavedToken(): String?
    fun getSavedAccountId(): String?
    fun saveCredentials(appId: String, token: String)
    fun clearCredentials()

    suspend fun health(): Result<Boolean>
    suspend fun getAccounts(appId: String, token: String): Result<List<DerivAccount>>
    suspend fun createDemoAccount(appId: String, token: String, currency: String = "USD", group: String = "row"): Result<DerivAccount>
    suspend fun resetDemoBalance(appId: String, token: String, account: DerivAccount): Result<Unit>
    suspend fun connectAccount(appId: String, token: String, account: DerivAccount): Result<Unit>
    suspend fun connectPublic(): Result<Unit>
    fun disconnect()

    suspend fun activeSymbols(): Result<List<DerivActiveSymbol>>
    suspend fun contractsFor(symbol: String): Result<List<DerivContractSpec>>
    suspend fun contractsList(): Result<List<DerivContractCategory>>
    suspend fun ticksHistory(symbol: String, granularitySeconds: Int, count: Int = 500): Result<List<DerivCandle>>
    fun streamTicks(symbol: String): Flow<DerivTick>
    suspend fun serverTime(): Result<Long>
    suspend fun balance(): Result<DerivBalance>
    suspend fun portfolio(): Result<List<DerivPosition>>
    suspend fun profitTable(limit: Int = 50, offset: Int = 0): Result<List<DerivProfitRecord>>
    suspend fun statement(limit: Int = 100, offset: Int = 0, actionType: String? = null): Result<List<DerivStatementRecord>>
    suspend fun wallets(appId: String, token: String, conversionCurrency: String? = "USD"): Result<List<DerivWallet>>
    suspend fun walletTransactions(appId: String, token: String, walletType: String, perPage: Int = 100): Result<DerivWalletTransactionsPage>
    suspend fun walletTransactionsPage(appId: String, token: String, pageUrl: String): Result<DerivWalletTransactionsPage>
    suspend fun proposal(request: DerivProposalRequest): Result<DerivProposal>
    suspend fun openContract(contractId: Long): Result<DerivOpenContract>
    suspend fun updateContract(contractId: Long, stopLoss: Double?, takeProfit: Double?, authorization: DerivExecutionAuthorization): Result<DerivContractUpdate>
    suspend fun contractUpdateHistory(contractId: Long, limit: Int = 500): Result<List<DerivContractUpdateHistoryEntry>>
    suspend fun cancelContract(contractId: Long, authorization: DerivExecutionAuthorization): Result<Unit>
    fun transactions(): Flow<DerivTransaction>

    suspend fun buy(
        proposalId: String,
        maxPrice: Double,
        authorization: DerivExecutionAuthorization,
    ): Result<DerivBuyResult>

    suspend fun sell(
        contractId: Long,
        minimumPrice: Double,
        authorization: DerivExecutionAuthorization,
    ): Result<DerivSellResult>
}
