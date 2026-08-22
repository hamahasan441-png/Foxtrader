package com.foxtrader.app.data.repository

import com.foxtrader.app.data.remote.deriv.DerivApiException
import com.foxtrader.app.data.remote.deriv.DerivCredentialStore
import com.foxtrader.app.data.remote.deriv.DerivRestClient
import com.foxtrader.app.data.remote.deriv.DerivWebSocketClient
import com.foxtrader.app.domain.model.deriv.DerivAccount
import com.foxtrader.app.domain.model.deriv.DerivActiveSymbol
import com.foxtrader.app.domain.model.deriv.DerivBalance
import com.foxtrader.app.domain.model.deriv.DerivBuyResult
import com.foxtrader.app.domain.model.deriv.DerivConnectionState
import com.foxtrader.app.domain.model.deriv.DerivCandle
import com.foxtrader.app.domain.model.deriv.DerivContractSpec
import com.foxtrader.app.domain.model.deriv.DerivContractCategory
import com.foxtrader.app.domain.model.deriv.DerivProfitRecord
import com.foxtrader.app.domain.model.deriv.DerivStatementRecord
import com.foxtrader.app.domain.model.deriv.DerivWallet
import com.foxtrader.app.domain.model.deriv.DerivWalletTransactionsPage
import com.foxtrader.app.domain.model.deriv.DerivContractUpdate
import com.foxtrader.app.domain.model.deriv.DerivContractUpdateHistoryEntry
import com.foxtrader.app.domain.model.deriv.DerivOpenContract
import com.foxtrader.app.domain.model.deriv.DerivTransaction
import com.foxtrader.app.domain.model.deriv.DerivExecutionAuthorization
import com.foxtrader.app.domain.model.deriv.DerivPosition
import com.foxtrader.app.domain.model.deriv.DerivProposal
import com.foxtrader.app.domain.model.deriv.DerivProposalRequest
import com.foxtrader.app.domain.model.deriv.DerivSellResult
import com.foxtrader.app.domain.model.deriv.DerivTick
import com.foxtrader.app.domain.repository.DerivRepository
import com.foxtrader.app.domain.usecase.deriv.DerivRequestBuilder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DerivRepositoryImpl @Inject constructor(
    private val rest: DerivRestClient,
    private val ws: DerivWebSocketClient,
    private val credentials: DerivCredentialStore,
) : DerivRepository {

    override val connectionState = ws.state

    @Volatile private var connectedAccount: DerivAccount? = null
    @Volatile private var authenticatedSessionStartedAtMs: Long = 0L
    private val connectionOperation = AtomicLong(0L)

    override fun getSavedAppId(): String? = credentials.appId()
    override fun getSavedToken(): String? = credentials.token()
    override fun getSavedAccountId(): String? = credentials.accountId()

    override fun saveCredentials(appId: String, token: String) {
        val normalizedAppId = appId.trim()
        val normalizedToken = token.trim()
        require(normalizedAppId.isNotBlank()) { "Deriv App ID is required" }
        require(normalizedToken.isNotBlank()) { "Deriv token is required" }
        val changed = credentials.appId() != normalizedAppId || credentials.token() != normalizedToken
        if (changed) {
            // Credential replacement is an identity boundary. Tear down the
            // authenticated WebSocket before persisting the new identity.
            disconnect()
        }
        credentials.save(normalizedAppId, normalizedToken)
    }

    override fun clearCredentials() {
        disconnect()
        credentials.clear()
    }

    override suspend fun health(): Result<Boolean> = cancellationAwareResult { rest.health() }

    override suspend fun createDemoAccount(appId: String, token: String, currency: String, group: String): Result<DerivAccount> = cancellationAwareResult {
        require(appId.isNotBlank()) { "Deriv App ID is required" }
        require(token.isNotBlank()) { "Deriv authorization token is required" }
        rest.createDemoAccount(appId, token, currency.trim().uppercase(), group.trim())
    }

    override suspend fun resetDemoBalance(appId: String, token: String, account: DerivAccount): Result<Unit> = cancellationAwareResult {
        require(appId.isNotBlank()) { "Deriv App ID is required" }
        require(token.isNotBlank()) { "Deriv authorization token is required" }
        require(account.accountType == com.foxtrader.app.domain.model.deriv.DerivAccountType.DEMO) { "Only demo accounts can be reset" }
        rest.resetDemoBalance(appId, token, account)
    }

    override suspend fun getAccounts(appId: String, token: String): Result<List<DerivAccount>> = cancellationAwareResult {
        require(appId.isNotBlank()) { "Deriv App ID is required" }
        require(token.isNotBlank()) { "Deriv authorization token is required" }
        rest.getAccounts(appId, token)
    }

    override suspend fun connectAccount(appId: String, token: String, account: DerivAccount): Result<Unit> = cancellationAwareResult {
        require(appId.isNotBlank()) { "Deriv App ID is required" }
        require(token.isNotBlank()) { "Deriv authorization token is required" }
        require(account.accountId.isNotBlank()) { "Deriv account ID is required" }
        require(account.accountType != com.foxtrader.app.domain.model.deriv.DerivAccountType.UNKNOWN) { "Unsupported Deriv account type" }

        // Every connect/switch gets a monotonically increasing operation id. A
        // slower OTP/connect response from an older request is never allowed to
        // become the active account after a newer switch has started.
        val operation = connectionOperation.incrementAndGet()
        connectedAccount = null
        authenticatedSessionStartedAtMs = 0L
        val session = rest.requestOtp(appId, token, account)
        check(connectionOperation.get() == operation) { "Deriv account switch was superseded" }
        ws.connectAuthenticated(session.webSocketUrl).getOrThrow()
        check(connectionOperation.get() == operation) { "Deriv account switch was superseded" }
        // Commit persistent identity before exposing the account as connected.
        // If encrypted storage fails, tear down this half-open authenticated
        // session rather than returning a failed Result with a live socket.
        try {
            credentials.save(appId.trim(), token.trim())
            credentials.saveAccountId(account.accountId)
        } catch (e: Exception) {
            if (connectionOperation.get() == operation) {
                ws.disconnect()
            }
            throw e
        }
        check(connectionOperation.get() == operation) { "Deriv account switch was superseded" }
        authenticatedSessionStartedAtMs = System.currentTimeMillis()
        connectedAccount = account
    }

    override suspend fun connectPublic(): Result<Unit> = cancellationAwareResult {
        val operation = connectionOperation.incrementAndGet()
        connectedAccount = null
        authenticatedSessionStartedAtMs = 0L
        ws.connectPublic().getOrThrow()
        check(connectionOperation.get() == operation) { "Deriv public connection was superseded" }
    }

    override fun disconnect() {
        connectionOperation.incrementAndGet()
        connectedAccount = null
        authenticatedSessionStartedAtMs = 0L
        ws.disconnect()
    }

    override suspend fun activeSymbols(): Result<List<DerivActiveSymbol>> = cancellationAwareResult {
        ensureConnected()
        val reqId = ws.nextReqId()
        val root = ws.request(DerivRequestBuilder.activeSymbols(reqId), reqId)
        val items = root["active_symbols"]?.jsonArray ?: JsonArray(emptyList())
        items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val symbol = obj.string("underlying_symbol") ?: return@mapNotNull null
            DerivActiveSymbol(
                symbol = symbol,
                displayName = obj.string("underlying_symbol_name") ?: symbol,
                market = obj.string("market"),
                subgroup = obj.string("subgroup"),
                submarket = obj.string("submarket"),
                symbolType = obj.string("underlying_symbol_type"),
                pipSize = obj.number("pip_size"),
                exchangeOpen = obj.boolLike("exchange_is_open") ?: true,
                tradingSuspended = obj.boolLike("is_trading_suspended") ?: false,
            )
        }
    }

    override suspend fun contractsFor(symbol: String): Result<List<DerivContractSpec>> = cancellationAwareResult {
        ensureConnected()
        require(symbol.isNotBlank()) { "Symbol is required" }
        val reqId = ws.nextReqId()
        val root = ws.request(DerivRequestBuilder.contractsFor(symbol, reqId), reqId)
        val available = root["contracts_for"]?.jsonObject?.get("available") as? JsonArray ?: JsonArray(emptyList())
        available.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val type = obj.string("contract_type") ?: return@mapNotNull null
            DerivContractSpec(
                contractType = type,
                category = obj.string("contract_category"),
                expiryType = obj.string("expiry_type"),
                market = obj.string("market"),
                submarket = obj.string("submarket"),
                sentiment = obj.string("sentiment"),
                barriers = obj.intValue("barriers"),
            )
        }
    }

    override suspend fun contractsList(): Result<List<DerivContractCategory>> = cancellationAwareResult {
        ensureConnected()
        val reqId = ws.nextReqId()
        val root = ws.request(DerivRequestBuilder.contractsList(reqId), reqId)
        val items = root["contracts_list"] as? JsonArray ?: JsonArray(emptyList())
        items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val contractType = obj.string("contract_type")
                ?: obj.string("value")
                ?: obj.string("contract_category")
                ?: return@mapNotNull null
            DerivContractCategory(
                contractType = contractType,
                category = obj.string("contract_category") ?: obj.string("category"),
                displayName = obj.string("display_name") ?: obj.string("name") ?: obj.string("text"),
            )
        }.distinctBy { it.contractType }
    }

    override suspend fun ticksHistory(symbol: String, granularitySeconds: Int, count: Int): Result<List<DerivCandle>> = cancellationAwareResult {
        ensureConnected()
        require(symbol.isNotBlank()) { "Symbol is required" }
        require(granularitySeconds > 0) { "Granularity must be positive" }
        val reqId = ws.nextReqId()
        val root = ws.request(DerivRequestBuilder.ticksHistory(symbol, granularitySeconds, count, reqId), reqId)
        val candles = root["candles"] as? JsonArray ?: JsonArray(emptyList())
        candles.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val epoch = obj.longValue("epoch") ?: return@mapNotNull null
            val open = obj.number("open") ?: return@mapNotNull null
            val high = obj.number("high") ?: return@mapNotNull null
            val low = obj.number("low") ?: return@mapNotNull null
            val close = obj.number("close") ?: return@mapNotNull null
            DerivCandle(epoch, open, high, low, close)
        }
    }

    override suspend fun serverTime(): Result<Long> = cancellationAwareResult {
        ensureConnected()
        val reqId = ws.nextReqId()
        val root = ws.request(DerivRequestBuilder.serverTime(reqId), reqId)
        root.longValue("time") ?: throw DerivApiException("Missing Deriv server time")
    }

    override fun streamTicks(symbol: String): Flow<DerivTick> = flow {
        require(symbol.isNotBlank()) { "Symbol is required" }
        ensureConnected()
        val reqId = ws.nextReqId()
        val initial = ws.request(DerivRequestBuilder.ticks(symbol, reqId, subscribe = true), reqId)
        val subscriptionId = initial["subscription"]?.jsonObject?.string("id")
        val sessionGeneration = ws.sessionGeneration()
        initial.parseTick()?.let { emit(it) }
        try {
            ws.messagesForGeneration(sessionGeneration).collect { root ->
                if (!ws.isCurrentGeneration(sessionGeneration)) {
                    throw DerivApiException("Deriv session changed; resubscribe to ticks")
                }
                val messageSubscription = root["subscription"]?.jsonObject?.string("id")
                if (!subscriptionId.isNullOrBlank() && messageSubscription != subscriptionId) {
                    return@collect
                }
                val tick = root.parseTick() ?: return@collect
                if (tick.symbol.equals(symbol, ignoreCase = true)) emit(tick)
            }
        } finally {
            if (!subscriptionId.isNullOrBlank() && ws.state.value == DerivConnectionState.CONNECTED) {
                withContext(NonCancellable) {
                    runCatching {
                        val forgetId = ws.nextReqId()
                        ws.request(DerivRequestBuilder.forget(subscriptionId, forgetId), forgetId)
                    }
                }
            }
        }
    }

    override suspend fun balance(): Result<DerivBalance> = cancellationAwareResult {
        ensureAuthenticated()
        val reqId = ws.nextReqId()
        val root = ws.request(DerivRequestBuilder.balance(reqId), reqId)
        val obj = root["balance"]?.jsonObject ?: throw DerivApiException("Missing balance response")
        DerivBalance(
            amount = obj.number("balance") ?: throw DerivApiException("Missing balance amount"),
            currency = obj.string("currency") ?: connectedAccount?.currency ?: "USD",
        )
    }

    override suspend fun portfolio(): Result<List<DerivPosition>> = cancellationAwareResult {
        ensureAuthenticated()
        val reqId = ws.nextReqId()
        val root = ws.request(DerivRequestBuilder.portfolio(reqId), reqId)
        val contracts = root["portfolio"]?.jsonObject?.get("contracts") as? JsonArray ?: JsonArray(emptyList())
        contracts.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val contractId = obj.longValue("contract_id") ?: return@mapNotNull null
            DerivPosition(
                contractId = contractId,
                contractType = obj.string("contract_type") ?: "UNKNOWN",
                symbol = obj.string("underlying_symbol"),
                currency = obj.string("currency") ?: connectedAccount?.currency ?: "USD",
                buyPrice = obj.number("buy_price"),
                bidPrice = obj.number("bid_price"),
                payout = obj.number("payout"),
                profit = obj.number("profit"),
                isSold = obj.boolLike("is_sold") ?: false,
            )
        }
    }

    override suspend fun profitTable(limit: Int, offset: Int): Result<List<DerivProfitRecord>> = cancellationAwareResult {
        ensureAuthenticated()
        val reqId = ws.nextReqId()
        val root = ws.request(DerivRequestBuilder.profitTable(limit, offset, reqId), reqId)
        val table = root["profit_table"] as? JsonObject ?: throw DerivApiException("Missing profit table response")
        val items = table["transactions"] as? JsonArray ?: JsonArray(emptyList())
        items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            DerivProfitRecord(
                transactionId = obj.longValue("transaction_id") ?: return@mapNotNull null,
                contractId = obj.longValue("contract_id"),
                contractType = obj.string("contract_type"),
                symbol = obj.string("underlying_symbol"),
                buyPrice = obj.number("buy_price") ?: return@mapNotNull null,
                sellPrice = obj.number("sell_price") ?: return@mapNotNull null,
                payout = obj.number("payout") ?: return@mapNotNull null,
                purchaseTimeEpochSeconds = obj.longValue("purchase_time") ?: return@mapNotNull null,
                sellTimeEpochSeconds = obj.longValue("sell_time"),
            )
        }
    }

    override suspend fun statement(limit: Int, offset: Int, actionType: String?): Result<List<DerivStatementRecord>> = cancellationAwareResult {
        ensureAuthenticated()
        val reqId = ws.nextReqId()
        val root = ws.request(DerivRequestBuilder.statement(limit, offset, actionType, reqId), reqId)
        val statement = root["statement"] as? JsonObject ?: throw DerivApiException("Missing statement response")
        val items = statement["transactions"] as? JsonArray ?: JsonArray(emptyList())
        items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            DerivStatementRecord(
                transactionId = obj.longValue("transaction_id") ?: return@mapNotNull null,
                actionType = obj.string("action_type") ?: return@mapNotNull null,
                amount = obj.number("amount") ?: return@mapNotNull null,
                balanceAfter = obj.number("balance_after") ?: return@mapNotNull null,
                transactionTimeEpochSeconds = obj.longValue("transaction_time") ?: return@mapNotNull null,
                contractId = obj.longValue("contract_id"),
                symbol = obj.string("underlying_symbol"),
            )
        }
    }

    override suspend fun wallets(appId: String, token: String, conversionCurrency: String?): Result<List<DerivWallet>> = cancellationAwareResult {
        require(appId.isNotBlank()) { "Deriv App ID is required" }
        require(token.isNotBlank()) { "Deriv authorization token is required" }
        rest.wallets(appId, token, conversionCurrency)
    }

    override suspend fun walletTransactions(
        appId: String,
        token: String,
        walletType: String,
        perPage: Int,
    ): Result<DerivWalletTransactionsPage> = cancellationAwareResult {
        require(appId.isNotBlank()) { "Deriv App ID is required" }
        require(token.isNotBlank()) { "Deriv authorization token is required" }
        rest.walletTransactions(appId, token, walletType, perPage)
    }

    override suspend fun walletTransactionsPage(
        appId: String,
        token: String,
        pageUrl: String,
    ): Result<DerivWalletTransactionsPage> = cancellationAwareResult {
        require(appId.isNotBlank()) { "Deriv App ID is required" }
        require(token.isNotBlank()) { "Deriv authorization token is required" }
        require(pageUrl.isNotBlank()) { "Wallet pagination URL is required" }
        rest.walletTransactionsPage(appId, token, pageUrl)
    }

    override suspend fun proposal(request: DerivProposalRequest): Result<DerivProposal> = cancellationAwareResult {
        ensureConnected()
        val reqId = ws.nextReqId()
        val root = ws.request(DerivRequestBuilder.proposal(request, reqId), reqId)
        val obj = root["proposal"]?.jsonObject ?: throw DerivApiException("Missing proposal response")
        DerivProposal(
            id = obj.string("id") ?: throw DerivApiException("Proposal ID missing"),
            askPrice = obj.number("ask_price"),
            payout = obj.number("payout"),
            spot = obj.number("spot"),
            longcode = obj.string("longcode"),
        )
    }

    override suspend fun openContract(contractId: Long): Result<DerivOpenContract> = cancellationAwareResult {
        ensureAuthenticated()
        require(contractId > 0) { "Invalid contract ID" }
        val reqId = ws.nextReqId()
        val root = ws.request(DerivRequestBuilder.proposalOpenContract(contractId, reqId), reqId)
        val obj = root["proposal_open_contract"]?.jsonObject ?: throw DerivApiException("Missing open-contract response")
        DerivOpenContract(
            contractId = obj.longValue("contract_id") ?: contractId,
            contractType = obj.string("contract_type") ?: "UNKNOWN",
            currency = obj.string("currency") ?: connectedAccount?.currency ?: "USD",
            symbol = obj.string("underlying_symbol"),
            buyPrice = obj.number("buy_price"),
            bidPrice = obj.number("bid_price"),
            payout = obj.number("payout"),
            profit = obj.number("profit"),
            currentSpot = obj.number("current_spot"),
            exitSpot = obj.number("exit_spot"),
            isSold = obj.boolLike("is_sold") ?: false,
        )
    }

    override suspend fun updateContract(
        contractId: Long,
        stopLoss: Double?,
        takeProfit: Double?,
        authorization: DerivExecutionAuthorization,
    ): Result<DerivContractUpdate> = cancellationAwareResult {
        ensureAuthenticated()
        require(contractId > 0) { "Invalid contract ID" }
        require(stopLoss != null || takeProfit != null) { "At least one limit order is required" }
        require(stopLoss == null || (stopLoss >= 0.0 && stopLoss.isFinite())) { "Invalid stop loss" }
        require(takeProfit == null || (takeProfit >= 0.0 && takeProfit.isFinite())) { "Invalid take profit" }
        requireExecutionAuthorization(authorization, "contract update")
        val reqId = ws.nextReqId()
        val root = ws.request(DerivRequestBuilder.contractUpdate(contractId, stopLoss, takeProfit, reqId), reqId)
        val obj = root["contract_update"]?.jsonObject ?: throw DerivApiException("Missing contract-update response")
        DerivContractUpdate(
            stopLoss = obj["stop_loss"]?.jsonObject?.number("order_amount"),
            takeProfit = obj["take_profit"]?.jsonObject?.number("order_amount"),
        )
    }

    override suspend fun contractUpdateHistory(contractId: Long, limit: Int): Result<List<DerivContractUpdateHistoryEntry>> = cancellationAwareResult {
        ensureAuthenticated()
        require(contractId > 0) { "Invalid contract ID" }
        val reqId = ws.nextReqId()
        val root = ws.request(DerivRequestBuilder.contractUpdateHistory(contractId, limit, reqId), reqId)
        val items = root["contract_update_history"] as? JsonArray ?: JsonArray(emptyList())
        items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val displayName = obj.string("display_name") ?: return@mapNotNull null
            val orderDate = obj.longValue("order_date") ?: return@mapNotNull null
            val orderType = obj.string("order_type") ?: return@mapNotNull null
            DerivContractUpdateHistoryEntry(
                displayName = displayName,
                orderAmount = obj.number("order_amount"),
                orderDateEpochSeconds = orderDate,
                orderType = orderType,
                value = obj.number("value"),
            )
        }
    }

    override suspend fun cancelContract(
        contractId: Long,
        authorization: DerivExecutionAuthorization,
    ): Result<Unit> = cancellationAwareResult {
        ensureAuthenticated()
        require(contractId > 0) { "Invalid contract ID" }
        requireExecutionAuthorization(authorization, "cancellation")
        val reqId = ws.nextReqId()
        ws.request(DerivRequestBuilder.cancel(contractId, reqId), reqId)
        Unit
    }

    override fun transactions(): Flow<DerivTransaction> = flow {
        ensureAuthenticated()
        val reqId = ws.nextReqId()
        val initial = ws.request(DerivRequestBuilder.transaction(reqId), reqId)
        val subscriptionId = initial["subscription"]?.jsonObject?.string("id")
        val sessionGeneration = ws.sessionGeneration()
        initial.parseTransaction()?.let { emit(it) }
        try {
            ws.messagesForGeneration(sessionGeneration).collect { root ->
                if (!ws.isCurrentGeneration(sessionGeneration)) {
                    throw DerivApiException("Deriv session changed; resubscribe to transactions")
                }
                val messageSubscription = root["subscription"]?.jsonObject?.string("id")
                if (!subscriptionId.isNullOrBlank() && messageSubscription != subscriptionId) {
                    return@collect
                }
                root.parseTransaction()?.let { emit(it) }
            }
        } finally {
            if (!subscriptionId.isNullOrBlank() && ws.state.value == DerivConnectionState.CONNECTED) {
                withContext(NonCancellable) {
                    runCatching {
                        val forgetId = ws.nextReqId()
                        ws.request(DerivRequestBuilder.forget(subscriptionId, forgetId), forgetId)
                    }
                }
            }
        }
    }

    override suspend fun buy(
        proposalId: String,
        maxPrice: Double,
        authorization: DerivExecutionAuthorization,
    ): Result<DerivBuyResult> = cancellationAwareResult {
        ensureAuthenticated()
        require(proposalId.isNotBlank()) { "Proposal ID is required" }
        require(maxPrice >= 0.0 && maxPrice.isFinite()) { "Invalid maximum price" }
        requireExecutionAuthorization(authorization, "purchase")
        val reqId = ws.nextReqId()
        val root = ws.request(DerivRequestBuilder.buy(proposalId, maxPrice, reqId), reqId)
        val obj = root["buy"]?.jsonObject ?: throw DerivApiException("Missing buy response")
        DerivBuyResult(
            contractId = obj.longValue("contract_id") ?: throw DerivApiException("Contract ID missing"),
            transactionId = obj.longValue("transaction_id"),
            buyPrice = obj.number("buy_price"),
            balanceAfter = obj.number("balance_after"),
            payout = obj.number("payout"),
            longcode = obj.string("longcode"),
        )
    }

    override suspend fun sell(
        contractId: Long,
        minimumPrice: Double,
        authorization: DerivExecutionAuthorization,
    ): Result<DerivSellResult> = cancellationAwareResult {
        ensureAuthenticated()
        require(contractId > 0) { "Invalid contract ID" }
        require(minimumPrice >= 0.0 && minimumPrice.isFinite()) { "Invalid sell price" }
        requireExecutionAuthorization(authorization, "sell")
        val reqId = ws.nextReqId()
        val root = ws.request(DerivRequestBuilder.sell(contractId, minimumPrice, reqId), reqId)
        val obj = root["sell"]?.jsonObject ?: throw DerivApiException("Missing sell response")
        DerivSellResult(
            contractId = obj.longValue("contract_id") ?: contractId,
            transactionId = obj.longValue("transaction_id"),
            soldFor = obj.number("sold_for"),
            balanceAfter = obj.number("balance_after"),
        )
    }

    private fun requireExecutionAuthorization(
        authorization: DerivExecutionAuthorization,
        action: String,
    ): DerivAccount {
        val account = connectedAccount ?: throw IllegalStateException("An authenticated Deriv account is required")
        val now = System.currentTimeMillis()
        check(authorization.canSubmitFor(account, now)) {
            "Account changed or fresh manual confirmation is required for Deriv $action"
        }
        val sessionStartedAt = authenticatedSessionStartedAtMs
        check(sessionStartedAt > 0L && authorization.confirmationEpochMs >= sessionStartedAt) {
            "Deriv session changed; review the $action again before submitting"
        }
        return account
    }

    private fun ensureConnected() {
        check(ws.state.value == DerivConnectionState.CONNECTED) { "Connect to Deriv first" }
    }

    private fun ensureAuthenticated() {
        ensureConnected()
        check(connectedAccount != null) { "An authenticated Deriv account is required" }
    }

    private fun JsonObject.parseTick(): DerivTick? {
        val obj = this["tick"] as? JsonObject ?: return null
        val symbol = obj.string("symbol") ?: return null
        val quote = obj.number("quote") ?: return null
        val epoch = obj.longValue("epoch") ?: return null
        return DerivTick(symbol, quote, epoch, obj.intValue("pip_size"))
    }


    private fun JsonObject.parseTransaction(): DerivTransaction? {
        val obj = this["transaction"] as? JsonObject ?: return null
        return DerivTransaction(
            transactionId = obj.longValue("transaction_id"),
            action = obj.string("action"),
            amount = obj.number("amount"),
            symbol = obj.string("underlying_symbol"),
            epochSeconds = obj.longValue("transaction_time"),
        )
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.number(key: String): Double? = this[key]?.jsonPrimitive?.let { p ->
        p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
    }
    private fun JsonObject.longValue(key: String): Long? = this[key]?.jsonPrimitive?.let { p ->
        p.longOrNull ?: p.contentOrNull?.toLongOrNull()
    }
    private fun JsonObject.intValue(key: String): Int? = this[key]?.jsonPrimitive?.let { p ->
        p.intOrNull ?: p.contentOrNull?.toIntOrNull()
    }
    private fun JsonObject.boolLike(key: String): Boolean? = this[key]?.jsonPrimitive?.let { p ->
        p.booleanOrNull ?: when (p.intOrNull ?: p.contentOrNull?.toIntOrNull()) {
            1 -> true
            0 -> false
            else -> null
        }
    }
}

/** Never convert structured coroutine cancellation into a normal Result failure. */
private inline fun <T> cancellationAwareResult(block: () -> T): Result<T> =
    runCatching(block).rethrowCancellation()

