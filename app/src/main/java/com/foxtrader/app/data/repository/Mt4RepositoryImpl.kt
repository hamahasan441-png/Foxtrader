package com.foxtrader.app.data.repository

import com.foxtrader.app.data.remote.api.MetaApiDataSource
import com.foxtrader.app.data.remote.api.MetaApiTradeTransport
import com.foxtrader.app.data.remote.api.MetaApiTradeRejectedException
import com.foxtrader.app.data.remote.api.MetaApiTradeOutcomeUnknownException
import com.foxtrader.app.data.remote.websocket.Mt4QuoteStream
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.DataProvider
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Mt4AccountInfo
import com.foxtrader.app.domain.model.Mt4AccountProfile
import com.foxtrader.app.domain.model.Mt4Broker
import com.foxtrader.app.domain.model.Mt4Credentials
import com.foxtrader.app.domain.model.Mt4OrderType
import com.foxtrader.app.domain.model.Mt4Position
import com.foxtrader.app.domain.model.Mt4PendingOrder
import com.foxtrader.app.domain.model.Mt4PendingOrderRequest
import com.foxtrader.app.domain.model.Mt4PositionProtection
import com.foxtrader.app.domain.model.Mt4PositionSnapshot
import com.foxtrader.app.domain.model.Mt4PendingOrderSnapshot
import com.foxtrader.app.domain.model.Mt4Quote
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.repository.Mt4Repository
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import com.foxtrader.app.domain.usecase.execution.ExecutionContext
import com.foxtrader.app.domain.usecase.execution.BrokerJournalSynchronizer
import com.foxtrader.app.domain.usecase.execution.ExecutionCoordinator
import com.foxtrader.app.domain.usecase.execution.ExecutionPolicy
import com.foxtrader.app.domain.usecase.execution.ExecutionReceipt
import com.foxtrader.app.domain.usecase.execution.ExecutionSafetyDecision
import com.foxtrader.app.domain.usecase.execution.ExecutionSafetyLayer
import com.foxtrader.app.domain.usecase.execution.TradeIntent
import com.foxtrader.app.domain.usecase.mt4.Mt4BrokerDirectory
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import com.foxtrader.app.domain.usecase.risk.InstrumentSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.HttpException

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
    private val executionSafetyLayer: ExecutionSafetyLayer,
    private val tradeTransport: MetaApiTradeTransport,
    private val auditLog: RoomExecutionAuditLog,
    private val instrumentTypeResolver: InstrumentTypeResolver,
    private val brokerJournalSynchronizer: BrokerJournalSynchronizer,
) : Mt4Repository {

    @Volatile
    private var accountId: String = appPreferences.getMetaApiAccountId().orEmpty()
    private val connectionOperation = AtomicLong(0L)
    private val managementReservations = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    override suspend fun searchBrokers(query: String): List<Mt4Broker> = brokerDirectory.search(query)

    override suspend fun getHistoricalCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int,
    ): Result<List<Candle>> = runCatching {
        val token = requireToken()
        val expectedAccountId = accountId
        check(expectedAccountId.isNotBlank()) { "Not connected. Call connect() first." }
        dataSource.getHistoricalCandles(token, expectedAccountId, symbol, timeframe, limit)
    }.rethrowCancellation()

    override suspend fun getHistoricalCandlesBefore(
        symbol: String,
        timeframe: Timeframe,
        beforeTimestampMs: Long,
        limit: Int,
    ): Result<List<Candle>> = runCatching {
        require(beforeTimestampMs > 0L) { "History boundary must be positive" }
        val token = requireToken()
        val expectedAccountId = accountId
        check(expectedAccountId.isNotBlank()) { "Not connected. Call connect() first." }
        dataSource.getHistoricalCandlesBefore(
            token = token,
            accountId = expectedAccountId,
            symbol = symbol,
            timeframe = timeframe,
            beforeTimestampMs = beforeTimestampMs,
            limit = limit,
        )
    }.rethrowCancellation()

    override fun isConnected(): Boolean = accountId.isNotBlank()

    override fun isLiveModeEnabled(): Boolean = appPreferences.isMt4LiveModeEnabled()

    override fun setLiveModeEnabled(enabled: Boolean) = appPreferences.setMt4LiveModeEnabled(enabled)

    override fun isKillSwitchEngaged(): Boolean = appPreferences.isMt4KillSwitchEngaged()

    override fun setKillSwitch(engaged: Boolean) = appPreferences.setMt4KillSwitch(engaged)

    override fun getLastConnection(): Mt4Credentials? {
        val login = appPreferences.getMetaApiLastLogin() ?: return null
        val server = appPreferences.getMetaApiLastServer() ?: return null
        return Mt4Credentials(login = login, password = "", server = server, platform = appPreferences.getMetaApiLastPlatform())
    }

    override fun getSavedAccounts(): List<Mt4AccountProfile> = appPreferences.getSavedBrokerAccounts()

    override fun removeSavedAccount(profile: Mt4AccountProfile) {
        appPreferences.removeSavedBrokerAccount(profile)
    }

    override suspend fun connect(credentials: Mt4Credentials): Result<Mt4AccountInfo> =
        runCatching {
            val operation = connectionOperation.incrementAndGet()
            val token = requireToken()
            require(credentials.login > 0) { "Login must be positive" }
            require(credentials.password.isNotBlank()) { "Password is required" }
            require(credentials.server.isNotBlank()) { "Server is required" }
            require(credentials.platform.lowercase() in setOf("mt4", "mt5")) { "Platform must be mt4 or mt5" }

            val sameAccount = appPreferences.getMetaApiLastLogin() == credentials.login &&
                appPreferences.getMetaApiLastServer().equals(credentials.server, ignoreCase = true) &&
                appPreferences.getMetaApiLastPlatform().equals(credentials.platform, ignoreCase = true)
            val activeCachedId = appPreferences.getMetaApiAccountId().takeIf { sameAccount && !it.isNullOrBlank() }
            val savedCachedId = appPreferences.getSavedBrokerAccounts().firstOrNull { profile ->
                profile.login == credentials.login &&
                    profile.server.equals(credentials.server, ignoreCase = true) &&
                    profile.platform.equals(credentials.platform, ignoreCase = true)
            }?.metaApiAccountId?.takeIf { !it.isNullOrBlank() }

            // Reuse a previously provisioned MetaApi id before creating another
            // cloud account. First validate the provisioning object itself. A
            // genuine provisioning 404 means the saved id belongs to a removed
            // account (or a previous MetaApi tenant/token), so create exactly
            // one replacement. Terminal-startup 404s are handled separately and
            // must never create duplicate paid provisioning accounts.
            val cachedCandidate = activeCachedId ?: savedCachedId
            val candidateId = if (cachedCandidate != null) {
                try {
                    val provisioned = dataSource.ensureProvisionedAccountReady(token, cachedCandidate)
                    check(provisioned.login.isBlank() || provisioned.login == credentials.login.toString()) {
                        "Cached MetaApi id belongs to a different login"
                    }
                    check(provisioned.server.isBlank() || provisioned.server.equals(credentials.server, ignoreCase = true)) {
                        "Cached MetaApi id belongs to a different broker server"
                    }
                    check(provisioned.platform.isBlank() || provisioned.platform.equals(credentials.platform, ignoreCase = true)) {
                        "Cached MetaApi id belongs to a different platform"
                    }
                    cachedCandidate
                } catch (e: HttpException) {
                    if (e.code() != 404) throw e
                    dataSource.invalidateAccountRouting(cachedCandidate)
                    dataSource.deployAccount(token, credentials)
                }
            } else {
                dataSource.deployAccount(token, credentials)
            }
            val info = dataSource.getAccountInfoWhenReady(token, candidateId)
            check(info.login == credentials.login) {
                "MetaApi account mismatch: expected login ${credentials.login}, got ${info.login}"
            }
            if (info.server.isNotBlank()) {
                check(info.server.equals(credentials.server, ignoreCase = true)) {
                    "MetaApi account server mismatch: expected ${credentials.server}, got ${info.server}"
                }
            }
            if (connectionOperation.get() != operation) {
                throw CancellationException("MT4/MT5 connection was superseded by another account operation")
            }

            val previousId = accountId
            accountId = candidateId
            if (previousId != candidateId) {
                synchronized(specCacheLock) { specCache.clear() }
                dataSource.invalidateAccountRouting(previousId.takeIf { it.isNotBlank() })
            }

            // Persist only after account identity and terminal REST access have
            // both been validated. A failed connect therefore cannot leave a
            // half-connected local session.
            appPreferences.setMetaApiAccountId(candidateId)
            appPreferences.setMetaApiLastLogin(credentials.login)
            appPreferences.setMetaApiLastServer(credentials.server)
            appPreferences.setMetaApiLastPlatform(credentials.platform)
            appPreferences.setMetaApiAccountName(info.name)
            appPreferences.upsertSavedBrokerAccount(
                Mt4AccountProfile(
                    login = credentials.login,
                    server = credentials.server,
                    platform = credentials.platform,
                    displayName = info.name,
                    metaApiAccountId = candidateId,
                )
            )
            // Live execution permission is session-scoped, not account-global.
            // A user must explicitly re-enable live mode after every connect or
            // account switch; otherwise a previous account's live toggle could
            // silently carry over to a different broker account.
            appPreferences.setMt4LiveModeEnabled(false)
            quoteStream.connect(token, candidateId)
            info
        }.rethrowCancellation()

    override suspend fun disconnect() {
        connectionOperation.incrementAndGet()
        val previousId = accountId
        quoteStream.disconnect()
        accountId = ""
        synchronized(specCacheLock) { specCache.clear() }
        dataSource.invalidateAccountRouting(previousId.takeIf { it.isNotBlank() })
        // Clear only the ACTIVE session id. Saved profiles retain their
        // non-secret MetaApi provisioning ids for safe reconnect/reuse.
        appPreferences.setMetaApiAccountId(null)
        appPreferences.setMetaApiAccountName(null)
        appPreferences.setMt4LiveModeEnabled(false)
    }

    override suspend fun getAccountInfo(): Result<Mt4AccountInfo> =
        runCatching {
            val token = requireToken()
            val expectedAccountId = accountId
            check(expectedAccountId.isNotBlank()) { "Not connected. Call connect() first." }
            dataSource.getAccountInfo(token, expectedAccountId)
        }.rethrowCancellation()

    override suspend fun getPositions(): Result<List<Mt4Position>> =
        runCatching {
            val token = requireToken()
            val expectedAccountId = accountId
            check(expectedAccountId.isNotBlank()) { "Not connected. Call connect() first." }
            dataSource.getPositions(token, expectedAccountId)
        }.rethrowCancellation()

    override suspend fun getPendingOrders(): Result<List<Mt4PendingOrder>> =
        runCatching {
            val token = requireToken()
            val expectedAccountId = accountId
            check(expectedAccountId.isNotBlank()) { "Not connected. Call connect() first." }
            dataSource.getPendingOrders(token, expectedAccountId)
        }.rethrowCancellation()

    override fun streamQuotes(symbols: List<String>): Flow<Mt4Quote> {
        quoteStream.replaceSubscriptions(symbols)
        // Ensure the REST-backed price stream is active for a restored session.
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
        reviewedEntryPrice: Double?,
        maxSlippagePoints: Double?,
        confirmationTimestamp: Long,
    ): Result<Long> {
        if (type != Mt4OrderType.BUY && type != Mt4OrderType.SELL) {
            return Result.failure(
                IllegalArgumentException(
                    "Pending orders require an explicit open price and are not supported by placeTrade(); use a dedicated pending-order flow."
                )
            )
        }
        val token = runCatching { requireToken() }.getOrElse { return Result.failure(it) }
        val expectedAccountId = accountId
        if (expectedAccountId.isBlank()) return Result.failure(IllegalStateException("Not connected. Call connect() first."))

        val policy = buildExecutionPolicy()
        val context = buildExecutionContext(symbol, expectedAccountId)
        val quote = context.quote ?: return Result.failure(
            IllegalStateException("No live MT4/MT5 price available. Wait for a fresh broker price before trading.")
        )
        context.spec?.allowedOrderTypes?.takeIf { it.isNotEmpty() }?.let { allowed ->
            val brokerType = type.toMetaApiActionName()
            if (brokerType !in allowed) {
                return Result.failure(
                    IllegalStateException("Broker does not allow ${type.name} market orders for $symbol.")
                )
            }
        }
        // A market BUY executes against ask; a market SELL executes against bid.
        // Using the midpoint can incorrectly validate a TP/SL that is already on
        // the wrong side of the executable price when the spread is non-trivial.
        val executablePrice = if (type == Mt4OrderType.BUY) quote.ask else quote.bid
        val entryPrice = reviewedEntryPrice
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: executablePrice

        return runCatching {
            check(accountId == expectedAccountId) { "Account changed while preparing the order; review and confirm again." }
            val normalizedSl = normalizeNewOrderProtection(sl, "stop loss")
            val normalizedTp = normalizeNewOrderProtection(tp, "take profit")
            val intent = TradeIntent(
                symbol = symbol,
                direction = type.toDirection(),
                volume = lots,
                entryPrice = entryPrice,
                stopLoss = normalizedSl,
                takeProfit = normalizedTp,
                maxSlippagePoints = maxSlippagePoints,
                confirmationTimestamp = confirmationTimestamp,
                executionScope = executionScopeFor(expectedAccountId),
            )

            val receipt = executionCoordinator.execute(intent, policy, context) { ti ->
                check(accountId == expectedAccountId) { "Account changed before broker submission; order cancelled." }
                tradeTransport.submitMarketOrder(token, expectedAccountId, ti)
            }

            when (receipt) {
                is ExecutionReceipt.Accepted -> receipt.orderId.toLongOrNull()
                    ?: throw IllegalStateException("MetaApi accepted the order but returned no numeric id")
                is ExecutionReceipt.Rejected ->
                    throw IllegalStateException("Order rejected: ${receipt.reasons.joinToString("; ")}")
                is ExecutionReceipt.Unknown ->
                    throw IllegalStateException(
                        "Order outcome unknown. It may or may not have reached the broker. " +
                            "Check your MT4/MT5 positions before trying again — it will NOT be retried automatically."
                    )
            }
        }.rethrowCancellation()
    }

    override suspend fun closeTrade(
        ticket: Long,
        confirmationTimestamp: Long,
    ): Result<Unit> {
        val policy = buildExecutionPolicy()
        return runCatching {
            require(ticket > 0L) { "Position ticket must be positive" }
            val token = requireToken()
            val expectedAccountId = accountId
            check(expectedAccountId.isNotBlank()) { "Not connected. Call connect() first." }
            closeSafetyCheck(policy, confirmationTimestamp)

            // Closing is a live-money action too. Require an authoritative
            // pre-close snapshot so we bind the confirmation and audit record
            // to the exact position on the exact connected account. Never send
            // a close when the position list could not be verified.
            val positionBeforeClose = dataSource.getPositions(token, expectedAccountId)
                .firstOrNull { it.ticket == ticket }
                ?: throw IllegalStateException("Position #$ticket is not open on the connected account. Refresh positions and review again.")
            check(accountId == expectedAccountId) { "Account changed while preparing the close; review and confirm again." }

            val closeIntent = TradeIntent(
                symbol = positionBeforeClose.symbol,
                direction = positionBeforeClose.type.toDirection(),
                volume = positionBeforeClose.lots,
                entryPrice = positionBeforeClose.openPrice,
                confirmationTimestamp = confirmationTimestamp,
                executionScope = executionScopeFor(expectedAccountId),
                operationTag = "CLOSE:$ticket",
            )

            // A previous ACCEPTED close is idempotently complete. UNKNOWN means
            // the broker may already have closed it; never blind-retry.
            when (auditLog.findByIdempotencyKey(closeIntent.idempotencyKey)) {
                is ExecutionReceipt.Accepted -> return@runCatching Unit
                is ExecutionReceipt.Unknown -> throw IllegalStateException(
                    "Close outcome for position #$ticket is still unknown. Refresh/reconcile before trying again."
                )
                else -> Unit
            }

            // Durable write-ahead reservation closes the crash/timeout window.
            // If Room is unavailable this write throws and the broker is never
            // contacted.
            val reservation = ExecutionReceipt.Unknown(closeIntent)
            auditLog.record(reservation)
            check(accountId == expectedAccountId) { "Account changed before close submission; close cancelled." }

            try {
                dataSource.closePosition(token, expectedAccountId, ticket)
            } catch (rejected: MetaApiTradeRejectedException) {
                // This is the one safe case where the pre-submit UNKNOWN can be
                // downgraded to REJECTED: MetaApi returned an explicit broker
                // rejection, so the broker did not accept the close request.
                // Persisting REJECTED allows a later fresh review/retry.
                try {
                    auditLog.record(
                        ExecutionReceipt.Rejected(
                            intent = closeIntent,
                            reasons = listOf(rejected.message ?: "Broker rejected the close"),
                        )
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Keep UNKNOWN if persistence itself is unavailable.
                }
                throw rejected
            }

            // MetaApi's trade response itself does not contain authoritative
            // realized P/L. Recover it from history deals for this position; if
            // history has not synchronized yet, keep null and let reconciliation
            // fill it later. Never substitute pre-close floating P/L.
            val realizedProfit = loadRealizedProfitWhenReady(
                token = token,
                accountId = expectedAccountId,
                ticket = ticket,
                attempts = 3,
            )
            val accepted = ExecutionReceipt.Accepted(
                intent = closeIntent,
                orderId = ticket.toString(),
                realizedProfit = realizedProfit,
            )
            try {
                auditLog.record(accepted)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Broker reported success but final local persistence failed.
                // UNKNOWN reservation remains durable and blocks a blind retry.
                throw IllegalStateException(
                    "Position close was submitted, but its final audit state could not be saved. Refresh/reconcile before any retry."
                )
            }
        }.rethrowCancellation()
    }

    override suspend fun placePendingOrder(
        request: Mt4PendingOrderRequest,
        confirmationTimestamp: Long,
    ): Result<Long> = runCatching {
        val token = requireToken()
        val expectedAccountId = accountId
        check(expectedAccountId.isNotBlank()) { "Not connected. Call connect() first." }
        val policy = buildExecutionPolicy()
        val normalizedRequest = request.copy(
            stopLoss = normalizeNewOrderProtection(request.stopLoss, "stop loss"),
            takeProfit = normalizeNewOrderProtection(request.takeProfit, "take profit"),
        )
        val context = buildExecutionContext(normalizedRequest.symbol, expectedAccountId)
        validatePendingOrder(normalizedRequest, policy, context, confirmationTimestamp)
        check(accountId == expectedAccountId) { "Account changed while reviewing the pending order; review again." }

        val intent = TradeIntent(
            symbol = normalizedRequest.symbol,
            direction = normalizedRequest.type.toDirection(),
            volume = normalizedRequest.lots,
            entryPrice = normalizedRequest.openPrice,
            stopLoss = normalizedRequest.stopLoss,
            takeProfit = normalizedRequest.takeProfit,
            confirmationTimestamp = confirmationTimestamp,
            executionScope = executionScopeFor(expectedAccountId),
            operationTag = "PENDING_CREATE:${normalizedRequest.type.name}:${normalizedRequest.expirationType.name}:${normalizedRequest.expirationTime ?: 0L}",
        )
        executeAuditedManagement(intent, expectedAccountId) {
            dataSource.placePendingOrder(token, expectedAccountId, normalizedRequest)
        }
    }.rethrowCancellation()

    override suspend fun modifyPendingOrder(
        ticket: Long,
        openPrice: Double,
        stopLoss: Double?,
        takeProfit: Double?,
        confirmationTimestamp: Long,
        expectedState: Mt4PendingOrderSnapshot?,
    ): Result<Unit> = runCatching {
        require(ticket > 0L) { "Pending-order ticket must be positive" }
        val token = requireToken()
        val expectedAccountId = accountId
        check(expectedAccountId.isNotBlank()) { "Not connected. Call connect() first." }
        val policy = buildExecutionPolicy()
        closeSafetyCheck(policy, confirmationTimestamp)
        val order = dataSource.getPendingOrders(token, expectedAccountId).firstOrNull { it.ticket == ticket }
            ?: throw IllegalStateException("Pending order #$ticket is no longer active. Refresh before modifying it.")
        val request = Mt4PendingOrderRequest(
            symbol = order.symbol,
            type = order.type,
            lots = order.remainingLots.takeIf { it > 0.0 } ?: order.lots,
            openPrice = openPrice,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            expirationType = order.expirationType,
            expirationTime = order.expirationTime,
        )
        val context = buildExecutionContext(order.symbol, expectedAccountId)
        validatePendingGeometry(request, context, useFreezeLevel = true)
        val spec = context.spec ?: throw IllegalStateException("Broker symbol specification unavailable.")
        val stateTolerance = maxOf(spec.point * 2.0, openPrice * 0.0000001)
        expectedState?.let { reviewed ->
            val currentLots = order.remainingLots.takeIf { it > 0.0 } ?: order.lots
            val unchanged = kotlin.math.abs(order.openPrice - reviewed.openPrice) <= stateTolerance &&
                managementPriceMatches(order.sl, reviewed.stopLoss, stateTolerance) &&
                managementPriceMatches(order.tp, reviewed.takeProfit, stateTolerance) &&
                kotlin.math.abs(currentLots - reviewed.lots) < 1e-9
            check(unchanged) { "Pending order changed at the broker since review. Refresh and review again." }
        }
        if (kotlin.math.abs(order.openPrice - openPrice) <= stateTolerance &&
            managementPriceMatches(order.sl, stopLoss, stateTolerance) &&
            managementPriceMatches(order.tp, takeProfit, stateTolerance)
        ) {
            return@runCatching Unit
        }
        check(accountId == expectedAccountId) { "Account changed while preparing the pending-order modification." }
        val intent = TradeIntent(
            symbol = order.symbol,
            direction = order.type.toDirection(),
            volume = request.lots,
            entryPrice = openPrice,
            // TradeIntent reserves 0 for invalid executable prices; broker
            // management uses 0 as the explicit "remove SL/TP" command. Keep
            // the idempotency payload positive/null and encode remove/keep
            // semantics in the operation tag below.
            stopLoss = stopLoss?.takeIf { it > 0.0 },
            takeProfit = takeProfit?.takeIf { it > 0.0 },
            confirmationTimestamp = confirmationTimestamp,
            executionScope = executionScopeFor(expectedAccountId),
            operationTag = "PENDING_MODIFY:$ticket:${order.type.name}:FROM_PRICE=${order.openPrice}:FROM_SL=${order.sl}:FROM_TP=${order.tp}:TO_PRICE=$openPrice:SL=${managementPriceTag(stopLoss)}:TP=${managementPriceTag(takeProfit)}",
        )
        executeAuditedManagement(intent, expectedAccountId, fallbackId = ticket) {
            dataSource.modifyPendingOrder(token, expectedAccountId, ticket, openPrice, stopLoss, takeProfit)
            ticket
        }
        Unit
    }.rethrowCancellation()

    override suspend fun cancelPendingOrder(
        ticket: Long,
        confirmationTimestamp: Long,
    ): Result<Unit> = runCatching {
        require(ticket > 0L) { "Pending-order ticket must be positive" }
        val token = requireToken()
        val expectedAccountId = accountId
        check(expectedAccountId.isNotBlank()) { "Not connected. Call connect() first." }
        closeSafetyCheck(buildExecutionPolicy(), confirmationTimestamp)
        val order = dataSource.getPendingOrders(token, expectedAccountId).firstOrNull { it.ticket == ticket }
            ?: return@runCatching Unit // desired state is already true; do not resurrect/retry.
        val intent = TradeIntent(
            symbol = order.symbol,
            direction = order.type.toDirection(),
            volume = order.remainingLots.takeIf { it > 0.0 } ?: order.lots,
            entryPrice = order.openPrice,
            confirmationTimestamp = confirmationTimestamp,
            executionScope = executionScopeFor(expectedAccountId),
            operationTag = "PENDING_CANCEL:$ticket",
        )
        executeAuditedManagement(intent, expectedAccountId, fallbackId = ticket) {
            dataSource.cancelPendingOrder(token, expectedAccountId, ticket)
            ticket
        }
        Unit
    }.rethrowCancellation()

    override suspend fun modifyPositionProtection(
        ticket: Long,
        protection: Mt4PositionProtection,
        confirmationTimestamp: Long,
        expectedState: Mt4PositionSnapshot?,
    ): Result<Unit> = runCatching {
        require(ticket > 0L) { "Position ticket must be positive" }
        require(protection.stopLoss != null || protection.takeProfit != null || protection.trailingDistancePoints != null) {
            "At least one protection field must be supplied"
        }
        val token = requireToken()
        val expectedAccountId = accountId
        check(expectedAccountId.isNotBlank()) { "Not connected. Call connect() first." }
        closeSafetyCheck(buildExecutionPolicy(), confirmationTimestamp)
        val position = dataSource.getPositions(token, expectedAccountId).firstOrNull { it.ticket == ticket }
            ?: throw IllegalStateException("Position #$ticket is no longer open. Refresh before modifying it.")
        val context = buildExecutionContext(position.symbol, expectedAccountId)
        validatePositionProtection(position, protection, context)
        val spec = context.spec ?: throw IllegalStateException("Broker symbol specification unavailable.")
        val stateTolerance = maxOf(spec.point * 2.0, position.openPrice * 0.0000001)
        expectedState?.let { reviewed ->
            val unchanged = kotlin.math.abs(position.lots - reviewed.lots) < 1e-9 &&
                managementPriceMatches(position.sl, reviewed.stopLoss, stateTolerance) &&
                managementPriceMatches(position.tp, reviewed.takeProfit, stateTolerance)
            check(unchanged) { "Position protection changed at the broker since review. Refresh and review again." }
        }
        if (protection.trailingDistancePoints == null &&
            managementPriceMatches(position.sl, protection.stopLoss, stateTolerance) &&
            managementPriceMatches(position.tp, protection.takeProfit, stateTolerance)
        ) {
            return@runCatching Unit
        }
        check(accountId == expectedAccountId) { "Account changed while preparing position protection." }
        val intent = TradeIntent(
            symbol = position.symbol,
            direction = position.type.toDirection(),
            volume = position.lots,
            entryPrice = position.openPrice,
            confirmationTimestamp = confirmationTimestamp,
            executionScope = executionScopeFor(expectedAccountId),
            operationTag = "POSITION_MODIFY:$ticket:FROM_SL=${position.sl}:FROM_TP=${position.tp}:SL=${managementPriceTag(protection.stopLoss)}:TP=${managementPriceTag(protection.takeProfit)}:TRAIL=${protection.trailingDistancePoints ?: "KEEP"}",
        )
        executeAuditedManagement(intent, expectedAccountId, fallbackId = ticket) {
            dataSource.modifyPositionProtection(token, expectedAccountId, ticket, protection)
            ticket
        }
        Unit
    }.rethrowCancellation()

    override suspend fun movePositionToBreakEven(
        ticket: Long,
        confirmationTimestamp: Long,
    ): Result<Unit> = runCatching {
        require(ticket > 0L) { "Position ticket must be positive" }
        val token = requireToken()
        val expectedAccountId = accountId
        check(expectedAccountId.isNotBlank()) { "Not connected. Call connect() first." }
        closeSafetyCheck(buildExecutionPolicy(), confirmationTimestamp)
        val position = dataSource.getPositions(token, expectedAccountId).firstOrNull { it.ticket == ticket }
            ?: throw IllegalStateException("Position #$ticket is no longer open.")
        val context = buildExecutionContext(position.symbol, expectedAccountId)
        val protection = Mt4PositionProtection(stopLoss = position.openPrice)
        validatePositionProtection(position, protection, context)
        val spec = context.spec ?: throw IllegalStateException("Broker symbol specification unavailable.")
        val stateTolerance = maxOf(spec.point * 2.0, position.openPrice * 0.0000001)
        if (position.sl > 0.0 && kotlin.math.abs(position.sl - position.openPrice) <= stateTolerance) {
            return@runCatching Unit
        }
        val intent = TradeIntent(
            symbol = position.symbol,
            direction = position.type.toDirection(),
            volume = position.lots,
            entryPrice = position.openPrice,
            confirmationTimestamp = confirmationTimestamp,
            executionScope = executionScopeFor(expectedAccountId),
            operationTag = "BREAK_EVEN:$ticket:FROM_SL=${position.sl}",
        )
        executeAuditedManagement(intent, expectedAccountId, fallbackId = ticket) {
            dataSource.modifyPositionProtection(token, expectedAccountId, ticket, protection)
            ticket
        }
        Unit
    }.rethrowCancellation()

    override suspend fun partialCloseTrade(
        ticket: Long,
        lots: Double,
        confirmationTimestamp: Long,
        expectedState: Mt4PositionSnapshot?,
    ): Result<Unit> = runCatching {
        require(ticket > 0L) { "Position ticket must be positive" }
        require(lots.isFinite() && lots > 0.0) { "Partial-close volume must be positive" }
        val token = requireToken()
        val expectedAccountId = accountId
        check(expectedAccountId.isNotBlank()) { "Not connected. Call connect() first." }
        closeSafetyCheck(buildExecutionPolicy(), confirmationTimestamp)
        val position = dataSource.getPositions(token, expectedAccountId).firstOrNull { it.ticket == ticket }
            ?: throw IllegalStateException("Position #$ticket is no longer open.")
        expectedState?.let { reviewed ->
            check(kotlin.math.abs(position.lots - reviewed.lots) < 1e-9 &&
                managementPriceMatches(position.sl, reviewed.stopLoss, 1e-9) &&
                managementPriceMatches(position.tp, reviewed.takeProfit, 1e-9)
            ) { "Position changed at the broker since review. Refresh and review again." }
        }
        require(lots < position.lots - 1e-9) { "Partial close must be smaller than the open volume. Use Close for a full close." }
        val spec = buildInstrumentSpec(position.symbol, dataSource.getAccountInfo(token, expectedAccountId).currency, expectedAccountId)
        check(!spec.isEstimated) { "Broker volume specification is unavailable; partial close is blocked." }
        require(spec.isValidVolume(lots)) { "Partial-close volume does not match broker min/max/step." }
        val remaining = position.lots - lots
        require(remaining + 1e-9 >= spec.minVolume && spec.isValidVolume(remaining)) {
            "Partial close would leave an invalid broker volume ($remaining)."
        }
        val intent = TradeIntent(
            symbol = position.symbol,
            direction = position.type.toDirection(),
            volume = lots,
            entryPrice = position.openPrice,
            confirmationTimestamp = confirmationTimestamp,
            executionScope = executionScopeFor(expectedAccountId),
            operationTag = "POSITION_PARTIAL:$ticket:${position.lots}",
        )
        executeAuditedManagement(intent, expectedAccountId, fallbackId = ticket) {
            dataSource.partialClosePosition(token, expectedAccountId, ticket, lots)
            ticket
        }
        Unit
    }.rethrowCancellation()

    /**
     * Durable fail-safe wrapper for broker management calls that are not new
     * market orders. UNKNOWN is written before the broker is contacted and an
     * ambiguous result is never retried automatically.
     */
    private suspend fun executeAuditedManagement(
        intent: TradeIntent,
        expectedAccountId: String,
        fallbackId: Long? = null,
        action: suspend () -> Long,
    ): Long {
        if (!managementReservations.add(intent.idempotencyKey)) {
            throw IllegalStateException("The same broker action is already in progress.")
        }
        try {
            when (val existing = auditLog.findByIdempotencyKey(intent.idempotencyKey)) {
                is ExecutionReceipt.Accepted -> return existing.orderId.toLongOrNull() ?: fallbackId
                    ?: throw IllegalStateException("Broker action is already accepted but has no numeric id")
                is ExecutionReceipt.Unknown -> throw IllegalStateException(
                    "A previous attempt has an unknown broker outcome. Reconcile/refresh before retrying."
                )
                else -> Unit
            }
            check(accountId == expectedAccountId) { "Account changed before broker submission." }
            val reservation = ExecutionReceipt.Unknown(intent)
            auditLog.record(reservation) // throws => broker is never touched
            val result = try {
                action()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (rejected: MetaApiTradeRejectedException) {
                try {
                    auditLog.record(ExecutionReceipt.Rejected(intent, listOf(rejected.message ?: "Broker rejected the action")))
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Preserve UNKNOWN if the definitive rejection could not be persisted.
                }
                throw rejected
            } catch (unknown: MetaApiTradeOutcomeUnknownException) {
                throw IllegalStateException(
                    "Broker action outcome is unknown. Refresh/reconcile before any retry.", unknown
                )
            } catch (e: Exception) {
                // This could have happened after the request left the device.
                // Keep the durable UNKNOWN rather than guessing it was rejected.
                throw IllegalStateException(
                    "Broker action could not be confirmed. Its outcome is UNKNOWN; do not retry blindly.", e
                )
            }
            check(accountId == expectedAccountId) {
                "Account changed after broker submission. Outcome belongs to the previous account; refresh/reconcile."
            }
            try {
                auditLog.record(ExecutionReceipt.Accepted(intent = intent, orderId = result.toString()))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Broker accepted the action, but final audit persistence failed. UNKNOWN remains; reconcile before retrying.", e
                )
            }
            return result
        } finally {
            managementReservations.remove(intent.idempotencyKey)
        }
    }

    private fun normalizeNewOrderProtection(value: Double?, label: String): Double? {
        if (value == null || value == 0.0) return null
        require(value.isFinite() && value > 0.0) { "$label must be a positive finite price or 0 to disable" }
        return value
    }

    private fun managementPriceTag(value: Double?): String = when {
        value == null -> "KEEP"
        value == 0.0 -> "0"
        else -> value.toString()
    }

    /**
     * Null means the operation did not provide a value (KEEP). Zero means the
     * broker-side level must be removed. Positive values are compared with a
     * symbol-point tolerance because terminals can normalize prices to digits.
     */
    private fun parseManagementPriceTag(value: String?): Double? = when (value) {
        null, "KEEP" -> null
        else -> value.toDoubleOrNull()
    }

    private fun managementPriceMatches(actual: Double, requested: Double?, tolerance: Double): Boolean = when {
        requested == null -> true
        requested == 0.0 -> actual <= 0.0
        !requested.isFinite() || requested < 0.0 -> false
        !actual.isFinite() -> false
        else -> kotlin.math.abs(actual - requested) <= tolerance
    }

    private fun validatePendingOrder(
        request: Mt4PendingOrderRequest,
        policy: ExecutionPolicy,
        context: ExecutionContext,
        confirmationTimestamp: Long,
    ) {
        val quote = context.quote ?: throw IllegalStateException("No fresh broker quote is available for pending-order review.")
        val executable = if (request.type.toDirection() == Direction.BULLISH) quote.ask else quote.bid
        val probe = TradeIntent(
            symbol = request.symbol,
            direction = request.type.toDirection(),
            volume = request.lots,
            entryPrice = executable,
            confirmationTimestamp = confirmationTimestamp,
            executionScope = "pending-validation",
        )
        when (val decision = executionSafetyLayer.evaluate(probe, policy, context)) {
            is ExecutionSafetyDecision.Rejected -> throw IllegalStateException(decision.reasons.joinToString("; "))
            ExecutionSafetyDecision.Allowed -> Unit
        }
        validatePendingGeometry(request, context, useFreezeLevel = false)
    }

    private fun validatePendingGeometry(
        request: Mt4PendingOrderRequest,
        context: ExecutionContext,
        useFreezeLevel: Boolean,
    ) {
        val quote = context.quote ?: throw IllegalStateException("Broker quote unavailable.")
        val spec = context.spec ?: throw IllegalStateException("Broker symbol specification unavailable.")
        check(!spec.isEstimated) { "Broker symbol specification is estimated; pending orders are blocked." }
        require(request.openPrice.isFinite() && request.openPrice > 0.0) { "Pending open price must be positive." }
        val actionName = request.type.toMetaApiActionName()
        if (spec.allowedOrderTypes.isNotEmpty() && actionName !in spec.allowedOrderTypes) {
            throw IllegalStateException("Broker does not allow ${request.type.name} for ${request.symbol}.")
        }
        val expirationName = request.expirationType.toMetaApiExpirationName()
        if (spec.allowedExpirationModes.isNotEmpty() && expirationName !in spec.allowedExpirationModes) {
            throw IllegalStateException("Broker does not allow ${request.expirationType.name} expiration for ${request.symbol}.")
        }
        if (request.expirationType.name.startsWith("SPECIFIED")) {
            require(request.expirationTime != null && request.expirationTime > System.currentTimeMillis()) {
                "Specified expiration must be in the future."
            }
        }

        val current = when (request.type) {
            Mt4OrderType.BUY_LIMIT, Mt4OrderType.BUY_STOP -> quote.ask
            Mt4OrderType.SELL_LIMIT, Mt4OrderType.SELL_STOP -> quote.bid
            else -> throw IllegalArgumentException("Not a pending order type")
        }
        when (request.type) {
            Mt4OrderType.BUY_LIMIT -> require(request.openPrice < current) { "BUY LIMIT price must be below current ask." }
            Mt4OrderType.SELL_LIMIT -> require(request.openPrice > current) { "SELL LIMIT price must be above current bid." }
            Mt4OrderType.BUY_STOP -> require(request.openPrice > current) { "BUY STOP price must be above current ask." }
            Mt4OrderType.SELL_STOP -> require(request.openPrice < current) { "SELL STOP price must be below current bid." }
            else -> Unit
        }
        val levelPoints = maxOf(spec.stopsLevelPoints, if (useFreezeLevel) spec.freezeLevelPoints else 0.0)
        val minDistance = levelPoints * spec.point
        if (minDistance > 0.0) {
            require(kotlin.math.abs(request.openPrice - current) + 1e-12 >= minDistance) {
                "Pending price is inside the broker stop/freeze level (${levelPoints} points)."
            }
        }
        validateStopsForReference(
            direction = request.type.toDirection(),
            reference = request.openPrice,
            stopLoss = request.stopLoss,
            takeProfit = request.takeProfit,
            minDistance = spec.stopsLevelPoints * spec.point,
        )
    }

    private fun validatePositionProtection(
        position: Mt4Position,
        protection: Mt4PositionProtection,
        context: ExecutionContext,
    ) {
        val quote = context.quote ?: throw IllegalStateException("No broker quote is available for position management.")
        val spec = context.spec ?: throw IllegalStateException("Broker symbol specification unavailable.")
        check(!spec.isEstimated) { "Broker symbol specification is estimated; protection modification is blocked." }
        val reference = if (position.type.toDirection() == Direction.BULLISH) quote.bid else quote.ask
        // Modifying protection can be prohibited inside the broker freeze level even
        // when the ordinary stop-distance requirement would otherwise pass.
        val effectiveLevelPoints = maxOf(spec.stopsLevelPoints, spec.freezeLevelPoints)
        val minDistance = effectiveLevelPoints * spec.point
        validateStopsForReference(
            direction = position.type.toDirection(),
            reference = reference,
            stopLoss = protection.stopLoss,
            takeProfit = protection.takeProfit,
            minDistance = minDistance,
        )
        protection.trailingDistancePoints?.let { points ->
            require(points.isFinite() && points > 0.0) { "Trailing-stop distance must be positive." }
            require(points + 1e-9 >= effectiveLevelPoints) {
                "Trailing stop is inside the broker minimum stop/freeze distance (${effectiveLevelPoints} points)."
            }
        }
    }

    private fun validateStopsForReference(
        direction: Direction,
        reference: Double,
        stopLoss: Double?,
        takeProfit: Double?,
        minDistance: Double,
    ) {
        require(reference.isFinite() && reference > 0.0) { "Invalid reference price." }
        stopLoss?.let { sl ->
            require(sl.isFinite() && sl >= 0.0) { "Stop loss must be finite." }
            if (sl > 0.0) {
                if (direction == Direction.BULLISH) require(sl < reference) { "Bullish stop loss must be below the reference price." }
                else require(sl > reference) { "Bearish stop loss must be above the reference price." }
                if (minDistance > 0.0) require(kotlin.math.abs(reference - sl) + 1e-12 >= minDistance) {
                    "Stop loss is inside the broker minimum stop distance."
                }
            }
        }
        takeProfit?.let { tp ->
            require(tp.isFinite() && tp >= 0.0) { "Take profit must be finite." }
            if (tp > 0.0) {
                if (direction == Direction.BULLISH) require(tp > reference) { "Bullish take profit must be above the reference price." }
                else require(tp < reference) { "Bearish take profit must be below the reference price." }
                if (minDistance > 0.0) require(kotlin.math.abs(tp - reference) + 1e-12 >= minDistance) {
                    "Take profit is inside the broker minimum stop distance."
                }
            }
        }
    }

    private fun Mt4OrderType.toMetaApiActionName(): String = when (this) {
        Mt4OrderType.BUY -> "ORDER_TYPE_BUY"
        Mt4OrderType.SELL -> "ORDER_TYPE_SELL"
        Mt4OrderType.BUY_LIMIT -> "ORDER_TYPE_BUY_LIMIT"
        Mt4OrderType.SELL_LIMIT -> "ORDER_TYPE_SELL_LIMIT"
        Mt4OrderType.BUY_STOP -> "ORDER_TYPE_BUY_STOP"
        Mt4OrderType.SELL_STOP -> "ORDER_TYPE_SELL_STOP"
    }

    private fun com.foxtrader.app.domain.model.Mt4PendingExpirationType.toMetaApiExpirationName(): String = when (this) {
        com.foxtrader.app.domain.model.Mt4PendingExpirationType.GTC -> "ORDER_TIME_GTC"
        com.foxtrader.app.domain.model.Mt4PendingExpirationType.DAY -> "ORDER_TIME_DAY"
        com.foxtrader.app.domain.model.Mt4PendingExpirationType.SPECIFIED -> "ORDER_TIME_SPECIFIED"
        com.foxtrader.app.domain.model.Mt4PendingExpirationType.SPECIFIED_DAY -> "ORDER_TIME_SPECIFIED_DAY"
    }

    override suspend fun synchronizeBrokerJournal(): Result<Int> = runCatching {
        val expectedAccountId = accountId
        check(expectedAccountId.isNotBlank()) { "Not connected. Call connect() first." }
        val token = requireToken()
        val positions = dataSource.getPositions(token, expectedAccountId)
        check(accountId == expectedAccountId) { "Account changed during journal synchronization; retry." }
        brokerJournalSynchronizer.synchronize(
            executionScope = executionScopeFor(expectedAccountId),
            positions = positions,
            loadCloseDetails = { ticket ->
                if (accountId != expectedAccountId) null
                else dataSource.getClosedPositionDetails(token, expectedAccountId, ticket)
            },
        ).unresolved
    }.rethrowCancellation()

    override suspend fun reconcileUnknownOrders(): Int {
        val expectedAccountId = accountId
        if (expectedAccountId.isBlank()) return 0
        val scope = executionScopeFor(expectedAccountId)
        val unknowns = try {
            auditLog.unknown(scope)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            throw IllegalStateException("Execution audit log is unavailable; reconciliation cannot be trusted.", e)
        }
        val acceptedClosesNeedingProfit = try {
            auditLog.acceptedClosesWithUnknownProfit(scope)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            throw IllegalStateException("Execution P/L audit log is unavailable; reconciliation cannot be trusted.", e)
        }
        if (unknowns.isEmpty() && acceptedClosesNeedingProfit.isEmpty()) return 0

        return runCatching {
            val token = requireToken()
            check(accountId == expectedAccountId) { "Account changed before reconciliation; try again." }
            val positions = if (unknowns.isNotEmpty()) {
                dataSource.getPositions(token, expectedAccountId)
            } else {
                emptyList()
            }
            val pendingOrders = if (unknowns.any { it.intent.operationTag.startsWith("PENDING_") }) {
                dataSource.getPendingOrders(token, expectedAccountId)
            } else {
                emptyList()
            }
            var unresolved = 0
            for (receipt in unknowns) {
                if (accountId != expectedAccountId) return@runCatching unknowns.size
                val intent = receipt.intent

                if (intent.operationTag.startsWith("CLOSE:")) {
                    val ticket = intent.operationTag.substringAfter("CLOSE:").toLongOrNull()
                    if (ticket == null) {
                        unresolved++
                        continue
                    }
                    // If the exact ticket is no longer open, the desired close
                    // outcome is true. It may have been closed by our timed-out
                    // request or externally; either way a second close must not
                    // be submitted. Authoritative realized P/L is recovered from
                    // position history when that history has synchronized.
                    if (positions.none { it.ticket == ticket }) {
                        val realizedProfit = loadRealizedProfitWhenReady(
                            token = token,
                            accountId = expectedAccountId,
                            ticket = ticket,
                            attempts = 1,
                        )
                        auditLog.record(
                            ExecutionReceipt.Accepted(
                                intent = intent,
                                orderId = ticket.toString(),
                                realizedProfit = realizedProfit,
                            )
                        )
                    } else {
                        unresolved++
                    }
                    continue
                }

                if (intent.operationTag.startsWith("PENDING_CREATE:")) {
                    val typeName = intent.operationTag.split(':').getOrNull(1)
                    val expectedType = typeName?.let { runCatching { Mt4OrderType.valueOf(it) }.getOrNull() }
                    if (expectedType == null) {
                        unresolved++
                        continue
                    }
                    val spec = buildInstrumentSpec(intent.symbol, "USD", expectedAccountId)
                    val priceTolerance = maxOf(spec.point * 2.0, intent.entryPrice * 0.0000001)
                    val matches = pendingOrders.filter { order ->
                        order.symbol.equals(intent.symbol, ignoreCase = true) &&
                            order.type == expectedType &&
                            kotlin.math.abs(order.lots - intent.volume) < 1e-9 &&
                            kotlin.math.abs(order.openPrice - intent.entryPrice) <= priceTolerance
                    }
                    if (matches.size == 1) {
                        auditLog.record(
                            ExecutionReceipt.Accepted(
                                intent = intent,
                                orderId = matches.single().ticket.toString(),
                            )
                        )
                    } else if (matches.isEmpty()) {
                        // A newly-created pending order can fill before the app
                        // reconciles. In that case it disappears from /orders;
                        // a unique position with the same direction/volume and a
                        // nearby open price/time is strong enough proof that the
                        // create request reached the broker.
                        val timeToleranceMs = 5 * 60_000L
                        val filled = positions.filter { pos ->
                            pos.symbol.equals(intent.symbol, ignoreCase = true) &&
                                pos.type.toDirection() == intent.direction &&
                                kotlin.math.abs(pos.lots - intent.volume) < 1e-9 &&
                                kotlin.math.abs(pos.openPrice - intent.entryPrice) <= maxOf(priceTolerance * 25.0, spec.point * 50.0) &&
                                (pos.openTime <= 0L || intent.confirmationTimestamp <= 0L ||
                                    kotlin.math.abs(pos.openTime - intent.confirmationTimestamp) <= timeToleranceMs)
                        }
                        if (filled.size == 1) {
                            auditLog.record(
                                ExecutionReceipt.Accepted(
                                    intent = intent,
                                    orderId = filled.single().ticket.toString(),
                                )
                            )
                        } else {
                            unresolved++
                        }
                    } else {
                        unresolved++
                    }
                    continue
                }

                if (intent.operationTag.startsWith("PENDING_CANCEL:")) {
                    val ticket = intent.operationTag.substringAfter("PENDING_CANCEL:").substringBefore(':').toLongOrNull()
                    if (ticket == null) {
                        unresolved++
                        continue
                    }
                    // No active pending order with this exact ticket means the
                    // requested terminal state (inactive) is already true. It
                    // may have been cancelled, filled or expired, but a second
                    // cancel must never be submitted blindly.
                    if (pendingOrders.none { it.ticket == ticket }) {
                        auditLog.record(ExecutionReceipt.Accepted(intent = intent, orderId = ticket.toString()))
                    } else {
                        unresolved++
                    }
                    continue
                }

                if (intent.operationTag.startsWith("PENDING_MODIFY:")) {
                    val parts = intent.operationTag.split(':')
                    val ticket = parts.getOrNull(1)?.toLongOrNull()
                    val slTag = parts.firstOrNull { it.startsWith("SL=") }?.substringAfter("SL=")
                    val tpTag = parts.firstOrNull { it.startsWith("TP=") }?.substringAfter("TP=")
                    val targetSl = parseManagementPriceTag(slTag)
                    val targetTp = parseManagementPriceTag(tpTag)
                    val malformedTag = slTag == null || tpTag == null ||
                        (slTag != "KEEP" && targetSl == null) || (tpTag != "KEEP" && targetTp == null)
                    val order = ticket?.let { id -> pendingOrders.firstOrNull { it.ticket == id } }
                    if (ticket == null || order == null || malformedTag) {
                        unresolved++
                        continue
                    }
                    val spec = buildInstrumentSpec(intent.symbol, "USD", expectedAccountId)
                    val tolerance = maxOf(spec.point * 2.0, intent.entryPrice * 0.0000001)
                    val priceMatches = kotlin.math.abs(order.openPrice - intent.entryPrice) <= tolerance
                    val slMatches = managementPriceMatches(order.sl, targetSl, tolerance)
                    val tpMatches = managementPriceMatches(order.tp, targetTp, tolerance)
                    if (priceMatches && slMatches && tpMatches) {
                        auditLog.record(ExecutionReceipt.Accepted(intent = intent, orderId = ticket.toString()))
                    } else {
                        unresolved++
                    }
                    continue
                }

                if (intent.operationTag.startsWith("BREAK_EVEN:")) {
                    val ticket = intent.operationTag.substringAfter("BREAK_EVEN:").substringBefore(':').toLongOrNull()
                    val position = ticket?.let { id -> positions.firstOrNull { it.ticket == id } }
                    if (ticket == null || position == null) {
                        unresolved++
                        continue
                    }
                    val spec = buildInstrumentSpec(intent.symbol, "USD", expectedAccountId)
                    val tolerance = maxOf(spec.point * 2.0, position.openPrice * 0.0000001)
                    if (position.sl > 0.0 && kotlin.math.abs(position.sl - position.openPrice) <= tolerance) {
                        auditLog.record(ExecutionReceipt.Accepted(intent = intent, orderId = ticket.toString()))
                    } else {
                        unresolved++
                    }
                    continue
                }

                if (intent.operationTag.startsWith("POSITION_MODIFY:")) {
                    val parts = intent.operationTag.split(':')
                    val ticket = parts.getOrNull(1)?.toLongOrNull()
                    val slTag = parts.firstOrNull { it.startsWith("SL=") }?.substringAfter("SL=")
                    val tpTag = parts.firstOrNull { it.startsWith("TP=") }?.substringAfter("TP=")
                    val trailTag = parts.firstOrNull { it.startsWith("TRAIL=") }?.substringAfter("TRAIL=")
                    val targetSl = parseManagementPriceTag(slTag)
                    val targetTp = parseManagementPriceTag(tpTag)
                    val malformedTag = slTag == null || tpTag == null || trailTag == null ||
                        (slTag != "KEEP" && targetSl == null) || (tpTag != "KEEP" && targetTp == null) ||
                        (trailTag != "KEEP" && (trailTag.toDoubleOrNull()?.let { it.isFinite() && it > 0.0 } != true))
                    val trailingRequested = trailTag != null && trailTag != "KEEP"
                    val position = ticket?.let { id -> positions.firstOrNull { it.ticket == id } }
                    if (ticket == null || position == null || malformedTag || trailingRequested) {
                        // Current MetaApi position DTO does not expose the active
                        // trailing-stop configuration, so never claim a trailing
                        // modification was confirmed from incomplete evidence.
                        unresolved++
                        continue
                    }
                    val spec = buildInstrumentSpec(intent.symbol, "USD", expectedAccountId)
                    val tolerance = maxOf(spec.point * 2.0, position.openPrice * 0.0000001)
                    if (managementPriceMatches(position.sl, targetSl, tolerance) &&
                        managementPriceMatches(position.tp, targetTp, tolerance)
                    ) {
                        auditLog.record(ExecutionReceipt.Accepted(intent = intent, orderId = ticket.toString()))
                    } else {
                        unresolved++
                    }
                    continue
                }

                if (intent.operationTag.startsWith("POSITION_PARTIAL:")) {
                    val parts = intent.operationTag.split(':')
                    val ticket = parts.getOrNull(1)?.toLongOrNull()
                    val originalLots = parts.getOrNull(2)?.toDoubleOrNull()
                    val position = ticket?.let { id -> positions.firstOrNull { it.ticket == id } }
                    if (ticket == null || originalLots == null || position == null) {
                        unresolved++
                        continue
                    }
                    val expectedRemaining = originalLots - intent.volume
                    if (expectedRemaining > 0.0 && kotlin.math.abs(position.lots - expectedRemaining) < 1e-9) {
                        auditLog.record(ExecutionReceipt.Accepted(intent = intent, orderId = ticket.toString()))
                    } else {
                        unresolved++
                    }
                    continue
                }

                if (intent.operationTag != "OPEN") {
                    // Unknown future management operation: keep it UNKNOWN unless
                    // an explicit broker-state proof is implemented above.
                    unresolved++
                    continue
                }

                val spec = buildInstrumentSpec(intent.symbol, "USD", expectedAccountId)
                val priceTolerance = maxOf(spec.point * 50.0, intent.entryPrice * 0.000001)
                val timeToleranceMs = 5 * 60_000L
                val matches = positions.filter { pos ->
                    pos.symbol.equals(intent.symbol, ignoreCase = true) &&
                        pos.type.toDirection() == intent.direction &&
                        kotlin.math.abs(pos.lots - intent.volume) < 1e-9 &&
                        kotlin.math.abs(pos.openPrice - intent.entryPrice) <= priceTolerance &&
                        (pos.openTime <= 0L || intent.confirmationTimestamp <= 0L ||
                            kotlin.math.abs(pos.openTime - intent.confirmationTimestamp) <= timeToleranceMs)
                }
                // Only a unique candidate is strong enough evidence to promote
                // UNKNOWN to ACCEPTED. Ambiguous matches stay UNKNOWN.
                if (matches.size == 1) {
                    auditLog.record(
                        ExecutionReceipt.Accepted(
                            intent = intent,
                            orderId = matches.single().ticket.toString(),
                        )
                    )
                } else {
                    unresolved++
                }
            }

            // A close can be definitively accepted before MetaApi history deals
            // finish synchronizing. Backfill any accepted close with null P/L so
            // the daily-loss gate can recover without guessing from floating P/L.
            val pendingProfit = if (unknowns.isEmpty()) {
                acceptedClosesNeedingProfit
            } else {
                auditLog.acceptedClosesWithUnknownProfit(scope)
            }
            for (accepted in pendingProfit) {
                if (accountId != expectedAccountId) return@runCatching unknowns.size
                val ticket = accepted.intent.operationTag
                    .takeIf { it.startsWith("CLOSE:") }
                    ?.substringAfter("CLOSE:")
                    ?.toLongOrNull()
                    ?: continue
                val realized = loadRealizedProfitWhenReady(
                    token = token,
                    accountId = expectedAccountId,
                    ticket = ticket,
                    attempts = 1,
                ) ?: continue
                auditLog.record(accepted.copy(realizedProfit = realized))
            }
            unresolved
        }.rethrowCancellation().getOrDefault(unknowns.size)
    }

    /**
     * History synchronization can lag a successful close by a short interval.
     * Retry only reads; never retry the broker close itself. Null means the
     * authoritative exit deal is not visible yet.
     */
    private suspend fun loadRealizedProfitWhenReady(
        token: String,
        accountId: String,
        ticket: Long,
        attempts: Int,
    ): Double? {
        repeat(attempts.coerceAtLeast(1)) { index ->
            try {
                dataSource.getPositionRealizedProfit(token, accountId, ticket)?.let { return it }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Treat history read failures as unknown; execution safety will
                // fail closed when a daily-loss limit needs unavailable P/L.
            }
            if (index < attempts - 1) delay(250L * (index + 1))
        }
        return null
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

    private suspend fun buildExecutionContext(symbol: String, expectedAccountId: String): ExecutionContext {
        val token = requireToken()
        val streamedQuote = quoteStream.latestQuote(symbol)
        // Execution prefers a just-in-time broker current-price read. The real-time
        // Socket.IO stream remains the display/low-latency source and a safe fallback
        // when the one-shot current-price endpoint is temporarily unavailable; the
        // stale-quote gate below still validates the broker timestamp either way.
        val quote = if (expectedAccountId.isNotBlank() && accountId == expectedAccountId) {
            try {
                dataSource.getCurrentPrice(token, expectedAccountId, symbol)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                streamedQuote
            }
        } else {
            null
        }
        val accountInfo = try {
            if (expectedAccountId.isNotBlank() && accountId == expectedAccountId) dataSource.getAccountInfo(token, expectedAccountId) else null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }

        // Compute today's realized gross loss only for the connected broker
        // account. Pre-v10 unscoped realized rows make the result unknown for
        // the remainder of that local calendar day, so an enabled live loss gate fails
        // closed instead of mixing P&L/currencies across accounts.
        val dailyLossForGate: Double? = try {
            auditLog.getTodayRealizedLoss(executionScopeFor(expectedAccountId))
                ?.takeIf { it.isFinite() && it >= 0.0 }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }

        return ExecutionContext(
            quote = quote,
            freeMargin = accountInfo?.freeMargin,
            dailyLossInAccountCurrency = dailyLossForGate,
            accountCurrency = accountInfo?.currency ?: "USD",
            spec = buildInstrumentSpec(symbol, accountInfo?.currency ?: "USD", expectedAccountId),
            quoteToAccountRate = null, // quote==account currency assumed; FX conversion not wired yet
        )
    }

    // ---- Broker spec cache (per symbol, short TTL) ----
    private data class CachedBrokerSpec(
        val spec: com.foxtrader.app.data.remote.api.MetaApiSymbolSpecResponse,
        val expiresAt: Long,
    )

    private val specCache = mutableMapOf<String, CachedBrokerSpec>()
    private val specCacheLock = Any()
    private val SPEC_CACHE_TTL_MS = 60_000L // 1 minute — short TTL per task

    override suspend fun getInstrumentSpec(symbol: String): InstrumentSpec {
        // Capture account identity before any suspension; never combine currency
        // from account A with a broker specification fetched from account B.
        val expectedAccountId = accountId
        val accountCurrency = try {
            val token = requireToken()
            if (expectedAccountId.isNotBlank() && accountId == expectedAccountId) {
                dataSource.getAccountInfo(token, expectedAccountId).currency
            } else {
                "USD"
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            "USD"
        }
        check(accountId == expectedAccountId) { "Account changed while loading broker specification; retry." }
        return buildInstrumentSpec(symbol, accountCurrency, expectedAccountId)
    }

    private suspend fun buildInstrumentSpec(symbol: String, accountCurrency: String, expectedAccountId: String): InstrumentSpec {
        val instrumentType = instrumentTypeResolver.resolve(symbol)

        // Try cache first
        val now = System.currentTimeMillis()
        val cacheKey = "${expectedAccountId}|${symbol.uppercase()}"
        val cached = synchronized(specCacheLock) {
            specCache[cacheKey]?.takeIf { it.expiresAt > now }?.spec
        }
        val brokerSpec: com.foxtrader.app.data.remote.api.MetaApiSymbolSpecResponse? = cached ?: run {
            // Fetch from broker if connected, otherwise null -> fallback
            try {
                val token = requireToken()
                if (expectedAccountId.isBlank() || accountId != expectedAccountId) null
                else dataSource.getSymbolSpecification(token, expectedAccountId, symbol)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }?.also { fetched ->
                synchronized(specCacheLock) {
                    specCache[cacheKey] = CachedBrokerSpec(fetched, now + SPEC_CACHE_TTL_MS)
                }
            }
        }

        return if (brokerSpec != null) {
            // Broker-authoritative spec
            val contractSize = if (brokerSpec.contractSize.isFinite() && brokerSpec.contractSize > 0.0) {
                brokerSpec.contractSize
            } else {
                instrumentType.contractSize
            }
            val tickSize = if (brokerSpec.tickSize.isFinite() && brokerSpec.tickSize > 0.0) {
                brokerSpec.tickSize
            } else {
                instrumentType.pipSize
            }
            val point = brokerSpec.point.takeIf { it.isFinite() && it > 0.0 } ?: tickSize
            val profitCurrency = brokerSpec.profitCurrency.trim().uppercase()
            val baseCurrency = brokerSpec.baseCurrency.trim().uppercase()
            InstrumentSpec(
                symbol = symbol,
                contractSize = contractSize,
                tickSize = tickSize,
                point = point,
                minVolume = brokerSpec.minVolume,
                maxVolume = brokerSpec.maxVolume,
                volumeStep = brokerSpec.volumeStep,
                // MetaApi exposes the profit currency in symbol specification;
                // using accountCurrency here would silently assume a 1:1 FX rate.
                quoteCurrency = profitCurrency.ifBlank { accountCurrency },
                baseCurrency = baseCurrency.ifBlank { null },
                isEstimated = false,
                stopsLevelPoints = brokerSpec.stopsLevel.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                freezeLevelPoints = brokerSpec.freezeLevel.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
                allowedOrderTypes = brokerSpec.allowedOrderTypes.map { it.uppercase() }.toSet(),
                allowedExpirationModes = brokerSpec.allowedExpirationModes.map { it.uppercase() }.toSet(),
            )
        } else {
            // Fallback to hardcoded defaults — surface as estimated
            InstrumentSpec(
                symbol = symbol,
                contractSize = instrumentType.contractSize,
                tickSize = instrumentType.pipSize,
                point = instrumentType.pipSize,
                minVolume = DEFAULT_MIN_VOLUME,
                maxVolume = DEFAULT_MAX_VOLUME,
                volumeStep = DEFAULT_VOLUME_STEP,
                quoteCurrency = accountCurrency,
                baseCurrency = null,
                isEstimated = true,
            )
        }
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
        if (policy.requireFreshConfirmation) {
            val age = now - confirmationTimestamp
            if (confirmationTimestamp <= 0L || age < 0L || age > policy.confirmationMaxAgeMs) {
                throw IllegalStateException("Close confirmation is stale or invalid; confirm again.")
            }
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

    /** Hash broker account identity before using it as a persisted execution scope. */
    private fun executionScopeFor(accountId: String): String {
        require(accountId.isNotBlank()) { "MetaApi account ID is required for execution scope" }
        val raw = "metaapi|${accountId.trim()}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val DEFAULT_MIN_VOLUME = 0.01
        const val DEFAULT_MAX_VOLUME = 100.0
        const val DEFAULT_VOLUME_STEP = 0.01
    }
}
