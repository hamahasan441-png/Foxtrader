package com.foxtrader.app.data.repository

import com.foxtrader.app.data.remote.api.MetaApiDataSource
import com.foxtrader.app.data.remote.api.MetaApiTradeTransport
import com.foxtrader.app.data.remote.websocket.Mt4QuoteStream
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Mt4AccountInfo
import com.foxtrader.app.domain.model.Mt4Broker
import com.foxtrader.app.domain.model.Mt4Credentials
import com.foxtrader.app.domain.model.Mt4OrderType
import com.foxtrader.app.domain.model.Mt4Position
import com.foxtrader.app.domain.model.Mt4Quote
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.Mt4Repository
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import com.foxtrader.app.domain.usecase.execution.ExecutionContext
import com.foxtrader.app.domain.usecase.execution.ExecutionCoordinator
import com.foxtrader.app.domain.usecase.execution.ExecutionPolicy
import com.foxtrader.app.domain.usecase.execution.ExecutionReceipt
import com.foxtrader.app.domain.usecase.execution.TradeIntent
import com.foxtrader.app.domain.usecase.mt4.Mt4BrokerDirectory
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.risk.InstrumentSpec
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [Mt4Repository].
 *
 * Delegates REST operations to [MetaApiDataSource] and live-quote streaming to
 * [Mt4QuoteStream]. Manages the MetaApi account ID lifecycle internally.
 *
 * Live order placement is routed through the execution safety stack
 * ([ExecutionCoordinator], [ExecutionPolicy], [ExecutionContext],
 * [MetaApiTradeTransport]): every order is gated by the persisted live-mode
 * switch, the emergency kill switch, fresh confirmation, a stale-quote gate,
 * broker volume bounds and SL/TP direction validation — and recorded to the
 * append-only audit log for reconciliation.
 */
@Singleton
class Mt4RepositoryImpl @Inject constructor(
    private val dataSource: MetaApiDataSource,
    private val quoteStream: Mt4QuoteStream,
    private val appPreferences: AppPreferences,
    private val brokerDirectory: Mt4BrokerDirectory,
    private val executionCoordinator: ExecutionCoordinator,
    private val tradeTransport: MetaApiTradeTransport,
    private val auditLog: RoomExecutionAuditLog,
    private val instrumentTypeResolver: InstrumentTypeResolver,
) : Mt4Repository {

    private var accountId: String = appPreferences.getMetaApiAccountId().orEmpty()

    override suspend fun searchBrokers(query: String): List<Mt4Broker> = brokerDirectory.search(query)

    override suspend fun getHistoricalCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int,
    ): Result<List<Candle>> = runCatching {
        val token = requireToken()
        check(accountId.isNotBlank()) { "Not connected. Call connect() first." }
        dataSource.getHistoricalCandles(token, accountId, symbol, timeframe, limit)
    }

    override fun isConnected(): Boolean = accountId.isNotBlank()

    override fun isLiveModeEnabled(): Boolean = appPreferences.isMt4LiveModeEnabled()

    override fun setLiveModeEnabled(enabled: Boolean) = appPreferences.setMt4LiveModeEnabled(enabled)

    override fun isKillSwitchEngaged(): Boolean = appPreferences.isMt4KillSwitchEngaged()

    override fun setKillSwitch(engaged: Boolean) = appPreferences.setMt4KillSwitch(engaged)

    override fun getLastConnection(): Mt4Credentials? {
        val login = appPreferences.getMetaApiLastLogin() ?: return null
        val server = appPreferences.getMetaApiLastServer() ?: return null
        return Mt4Credentials(login = login, password = "", server = server)
    }

    override suspend fun connect(credentials: Mt4Credentials): Result<Mt4AccountInfo> =
        runCatching {
            val token = requireToken()
            val cachedId = appPreferences.getMetaApiAccountId()
            val deployedId = if (!cachedId.isNullOrBlank()) {
                // Reuse previously provisioned account, skipping a redundant deploy call.
                cachedId
            } else {
                val newId = dataSource.deployAccount(token, credentials)
                appPreferences.setMetaApiAccountId(newId)
                newId
            }
            accountId = deployedId
            appPreferences.setMetaApiLastLogin(credentials.login)
            appPreferences.setMetaApiLastServer(credentials.server)
            quoteStream.connect(token, deployedId)
            dataSource.getAccountInfo(token, deployedId).also { info ->
                appPreferences.setMetaApiAccountName(info.name)
            }
        }

    override suspend fun disconnect() {
        quoteStream.disconnect()
        accountId = ""
        appPreferences.setMetaApiAccountId(null)
        appPreferences.setMetaApiAccountName(null)
    }

    override suspend fun getAccountInfo(): Result<Mt4AccountInfo> =
        runCatching {
            val token = requireToken()
            check(accountId.isNotBlank()) { "Not connected. Call connect() first." }
            dataSource.getAccountInfo(token, accountId)
        }

    override suspend fun getPositions(): Result<List<Mt4Position>> =
        runCatching {
            val token = requireToken()
            check(accountId.isNotBlank()) { "Not connected. Call connect() first." }
            dataSource.getPositions(token, accountId)
        }

    override fun streamQuotes(symbols: List<String>): Flow<Mt4Quote> {
        symbols.forEach { quoteStream.subscribe(it) }
        // Ensure the stream is actually connected. On a fresh login this is a
        // no-op (already connected via connect()), but after an app-restart
        // restore the stream has never been started, so without this the account
        // screen would subscribe to a disconnected stream and show no live price.
        ensureQuoteStreamConnected()
        return quoteStream.quotes
    }

    /**
     * Connects the quote stream for the persisted (restored) session. [connect]
     * is idempotent, so calling it when already connected/connecting is a no-op.
     */
    private fun ensureQuoteStreamConnected() {
        val token = appPreferences.getMetaApiToken()
            ?: appPreferences.getApiKey(DataProvider.MT4)
        val id = appPreferences.getMetaApiAccountId()
        if (!token.isNullOrBlank() && !id.isNullOrBlank()) {
            quoteStream.connect(token, id)
        }
    }

    // ========================================================================
    // LIVE EXECUTION (safety-gated)
    // ========================================================================

    override suspend fun placeTrade(
        symbol: String,
        type: Mt4OrderType,
        lots: Double,
        sl: Double?,
        tp: Double?,
        confirmationTimestamp: Long,
    ): Result<Long> {
        val policy = buildExecutionPolicy()
        val context = buildExecutionContext(symbol)
        // A market order enters at the current mid price captured at confirm time.
        val quote = context.quote
        if (quote == null) {
            return Result.failure(
                IllegalStateException("No live MT4 price available. Connect your MT4 feed before trading.")
            )
        }
        val entryPrice = (quote.bid + quote.ask) / 2.0

        return runCatching {
            val token = requireToken()
            check(accountId.isNotBlank()) { "Not connected. Call connect() first." }

            val intent = TradeIntent(
                symbol = symbol,
                direction = type.toDirection(),
                volume = lots,
                entryPrice = entryPrice,
                stopLoss = sl,
                takeProfit = tp,
                confirmationTimestamp = confirmationTimestamp,
            )

            val receipt = executionCoordinator.execute(intent, policy, context) { ti ->
                tradeTransport.submitMarketOrder(token, accountId, ti)
            }

            when (receipt) {
                is ExecutionReceipt.Accepted -> receipt.orderId.toLongOrNull()
                    ?: throw IllegalStateException("MetaApi accepted the order but returned no numeric id")
                is ExecutionReceipt.Rejected ->
                    throw IllegalStateException("Order rejected: ${receipt.reasons.joinToString("; ")}")
                is ExecutionReceipt.Unknown ->
                    throw IllegalStateException(
                        "Order outcome unknown. It may or may not have reached the broker. " +
                            "Check your MT4 positions before trying again — it will NOT be retried automatically."
                    )
            }
        }
    }

    override suspend fun closeTrade(
        ticket: Long,
        confirmationTimestamp: Long,
    ): Result<Unit> {
        val policy = buildExecutionPolicy()
        return runCatching {
            val token = requireToken()
            check(accountId.isNotBlank()) { "Not connected. Call connect() first." }

            // Closing is gated the same way as opening: live mode + kill switch +
            // fresh confirmation. There is no separate "close intent" object, so
            // we run the two order-level gates directly (fail closed) before
            // touching the broker.
            closeSafetyCheck(policy, confirmationTimestamp)

            dataSource.closePosition(token, accountId, ticket)

            // Record an audit entry for the close. We locate the position (if
            // still present) to build a meaningful receipt; a close is inherently
            // idempotent at the broker, so the coordinator is not needed here.
            val position = dataSource.getPositions(token, accountId)
                .firstOrNull { it.ticket == ticket }
            if (position != null) {
                auditLog.record(
                    ExecutionReceipt.Accepted(
                        intent = TradeIntent(
                            symbol = position.symbol,
                            direction = position.type.toDirection(),
                            volume = position.lots,
                            entryPrice = position.openPrice,
                            confirmationTimestamp = confirmationTimestamp,
                        ),
                        orderId = ticket.toString(),
                    )
                )
            }
        }
    }

    override suspend fun reconcileUnknownOrders(): Int {
        val unknowns = auditLog.unknown()
        if (unknowns.isEmpty()) return 0

        return runCatching {
            val token = requireToken()
            check(accountId.isNotBlank()) { "Not connected. Call connect() first." }
            val positions = dataSource.getPositions(token, accountId)
            var unresolved = 0
            for (receipt in unknowns) {
                val intent = receipt.intent
                val matched = positions.any { pos ->
                    pos.symbol.equals(intent.symbol, ignoreCase = true) &&
                        pos.type.toDirection() == intent.direction &&
                        kotlin.math.abs(pos.lots - intent.volume) < 1e-9 &&
                        kotlin.math.abs(pos.openPrice - intent.entryPrice) < 0.01
                }
                if (matched) {
                    // Confirmed filled — promote to ACCEPTED.
                    auditLog.record(
                        ExecutionReceipt.Accepted(
                            intent = intent,
                            orderId = positions.firstOrNull {
                                it.symbol.equals(intent.symbol, ignoreCase = true) &&
                                    it.type.toDirection() == intent.direction &&
                                    kotlin.math.abs(it.lots - intent.volume) < 1e-9 &&
                                    kotlin.math.abs(it.openPrice - intent.entryPrice) < 0.01
                            }?.ticket?.toString().orEmpty(),
                        )
                    )
                } else {
                    // Still ambiguous — never auto-retry.
                    unresolved++
                }
            }
            unresolved
        }.getOrDefault(unknowns.size)
    }

    // ========================================================================
    // EXECUTION POLICY / CONTEXT BUILDERS
    // ========================================================================

    private fun buildExecutionPolicy(): ExecutionPolicy = ExecutionPolicy(
        liveModeEnabled = appPreferences.isMt4LiveModeEnabled(),
        emergencyKillSwitch = appPreferences.isMt4KillSwitchEngaged(),
        requireFreshConfirmation = true,
        confirmationMaxAgeMs = appPreferences.getMt4ConfirmationTimeoutMs(),
        staleQuoteMaxAgeMs = appPreferences.getMt4StaleQuoteTimeoutMs(),
        maxDailyLossInAccountCurrency = appPreferences.getMt4MaxDailyLoss(),
        minFreeMarginInAccountCurrency = appPreferences.getMt4MinFreeMargin(),
    )

    private suspend fun buildExecutionContext(symbol: String): ExecutionContext {
        val quote = quoteStream.latestQuote(symbol)
        val accountInfo = try {
            val token = requireToken()
            if (accountId.isNotBlank()) dataSource.getAccountInfo(token, accountId) else null
        } catch (_: Exception) {
            null
        }
        return ExecutionContext(
            quote = quote,
            freeMargin = accountInfo?.freeMargin,
            // No authoritative intraday realized P&L is wired yet, so the
            // max-daily-loss gate stays permissive (null = gate skipped) until a
            // realized P&L source feeds this. Free margin is populated above.
            dailyLossInAccountCurrency = null,
            accountCurrency = accountInfo?.currency ?: "USD",
            spec = buildInstrumentSpec(symbol, accountInfo?.currency ?: "USD"),
            quoteToAccountRate = null, // quote==account currency assumed; FX conversion not wired yet
        )
    }

    private fun buildInstrumentSpec(symbol: String, accountCurrency: String): InstrumentSpec {
        val instrumentType = instrumentTypeResolver.resolve(symbol)
        return InstrumentSpec(
            symbol = symbol,
            contractSize = instrumentType.contractSize,
            tickSize = instrumentType.pipSize,
            point = instrumentType.pipSize,
            minVolume = DEFAULT_MIN_VOLUME,
            maxVolume = DEFAULT_MAX_VOLUME,
            volumeStep = DEFAULT_VOLUME_STEP,
            quoteCurrency = accountCurrency,
        )
    }

    /** Fail-closed check used for close-trade (order-level gates only). */
    private fun closeSafetyCheck(policy: ExecutionPolicy, confirmationTimestamp: Long) {
        val now = System.currentTimeMillis()
        if (!policy.liveModeEnabled) {
            throw IllegalStateException("Live MT4 execution is not enabled. Enable it from the MT4 account screen.")
        }
        if (policy.emergencyKillSwitch) {
            throw IllegalStateException("Emergency kill switch is engaged.")
        }
        if (policy.requireFreshConfirmation && now - confirmationTimestamp > policy.confirmationMaxAgeMs) {
            throw IllegalStateException("Close confirmation is stale; confirm again.")
        }
    }

    private fun requireToken(): String {
        val token = appPreferences.getMetaApiToken()
            ?: appPreferences.getApiKey(DataProvider.MT4)
        check(!token.isNullOrBlank()) { "MetaApi token not configured. Set it in Settings." }
        return token
    }

    private fun Mt4OrderType.toDirection(): Direction = when (this) {
        Mt4OrderType.BUY, Mt4OrderType.BUY_LIMIT, Mt4OrderType.BUY_STOP -> Direction.BULLISH
        Mt4OrderType.SELL, Mt4OrderType.SELL_LIMIT, Mt4OrderType.SELL_STOP -> Direction.BEARISH
    }

    private companion object {
        const val DEFAULT_MIN_VOLUME = 0.01
        const val DEFAULT_MAX_VOLUME = 100.0
        const val DEFAULT_VOLUME_STEP = 0.01
    }
}
