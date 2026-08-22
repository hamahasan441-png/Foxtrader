package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Mt4AccountInfo
import com.foxtrader.app.domain.model.Mt4Credentials
import com.foxtrader.app.domain.model.Mt4ClosedPositionDetails
import com.foxtrader.app.domain.model.Mt4OrderType
import com.foxtrader.app.domain.model.Mt4PendingExpirationType
import com.foxtrader.app.domain.model.Mt4PendingOrder
import com.foxtrader.app.domain.model.Mt4PendingOrderRequest
import com.foxtrader.app.domain.model.Mt4PositionProtection
import com.foxtrader.app.domain.model.Mt4Position
import com.foxtrader.app.domain.model.Mt4Quote
import com.foxtrader.app.domain.model.Timeframe
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import retrofit2.HttpException
import javax.inject.Inject

/** Definitive broker-side trade rejection. Safe to surface as REJECTED (not UNKNOWN). */
class MetaApiTradeRejectedException(message: String) : IllegalStateException(message)

/** Broker may have received the request; callers must reconcile before retrying. */
class MetaApiTradeOutcomeUnknownException(message: String) : IllegalStateException(message)

/**
 * MetaApi REST adapter with explicit host separation and defensive mapping.
 * Provisioning requests go to the provisioning host configured in Retrofit;
 * terminal and historical market-data requests are routed to the provisioned
 * account's public region through [MetaApiEndpointResolver].
 */
class MetaApiDataSource @Inject constructor(
    private val api: MetaApiService,
) {

    private val regionByAccountId = ConcurrentHashMap<String, String>()

    suspend fun deployAccount(token: String, credentials: Mt4Credentials): String {
        require(credentials.login > 0) { "MT4/MT5 login must be positive" }
        require(credentials.password.isNotBlank()) { "MT4/MT5 password is required" }
        require(credentials.server.isNotBlank()) { "MT4/MT5 server is required" }
        require(credentials.platform.lowercase() in setOf("mt4", "mt5")) { "Platform must be mt4 or mt5" }

        val request = MetaApiDeployRequest(
            login = credentials.login.toString(),
            password = credentials.password,
            name = "FoxTrader-${credentials.login}",
            server = credentials.server.trim(),
            platform = credentials.platform.lowercase(),
        )
        // MetaApi requires a stable 32-character transaction id for a create
        // attempt. UUID without hyphens is exactly 32 hexadecimal characters.
        val transactionId = UUID.randomUUID().toString().replace("-", "")
        val response = api.deployAccount(
            authToken = token,
            transactionId = transactionId,
            request = request,
        )
        check(response.id.isNotBlank()) {
            "MetaApi: account provisioning did not return an account id (it may still be processing; retry the connection instead of submitting trades)."
        }

        // Resolve/validate region immediately. This prevents persisting an
        // account id that cannot be routed safely by the terminal API layer.
        ensureProvisionedAccountReady(token, response.id, forceRefresh = true)
        return response.id
    }

    /**
     * Validates that a cached provisioning id still exists and starts its
     * terminal if it was explicitly undeployed. The deploy endpoint is
     * idempotent, therefore this never creates a second paid cloud account.
     */
    suspend fun ensureProvisionedAccountReady(
        token: String,
        accountId: String,
        forceRefresh: Boolean = false,
    ): MetaApiProvisionedAccountResponse {
        val id = normalizeAccountId(accountId)
        var provisioned = api.getProvisionedAccount(authToken = token, accountId = id)
        check(provisioned.id.isBlank() || provisioned.id == id) { "MetaApi provisioning account id mismatch" }

        if (provisioned.state.equals("UNDEPLOYED", ignoreCase = true) ||
            provisioned.state.equals("CREATED", ignoreCase = true)
        ) {
            api.deployProvisionedAccount(authToken = token, accountId = id)
            // Deployment is asynchronous. Poll provisioning metadata for a
            // bounded period; terminal REST will perform its own final check.
            for (attempt in 0 until 5) {
                delay(500L * (attempt + 1))
                provisioned = api.getProvisionedAccount(authToken = token, accountId = id)
                if (provisioned.state.equals("DEPLOYED", ignoreCase = true)) break
            }
        }

        check(!provisioned.state.equals("DEPLOY_FAILED", ignoreCase = true)) {
            "MetaApi terminal deployment failed for this account"
        }
        check(!provisioned.state.equals("DELETE_FAILED", ignoreCase = true) &&
            !provisioned.state.equals("DELETING", ignoreCase = true)) {
            "MetaApi account is being deleted or failed deletion"
        }
        if (provisioned.region.isNotBlank()) {
            regionByAccountId[id] = MetaApiEndpointResolver.normalizeRegion(provisioned.region)
        } else if (forceRefresh) {
            throw IllegalStateException("MetaApi account has no deployment region")
        }
        return provisioned
    }


    /**
     * Connect-time terminal readiness check. Provisioning can report a valid
     * account before the regional terminal REST endpoint is ready. Retry only
     * transient startup HTTP statuses; auth/permission errors fail immediately.
     */
    suspend fun getAccountInfoWhenReady(token: String, accountId: String): Mt4AccountInfo {
        ensureProvisionedAccountReady(token, accountId)
        var last: HttpException? = null
        for (attempt in 0 until 6) {
            try {
                return getAccountInfo(token, accountId)
            } catch (e: HttpException) {
                if (e.code() !in setOf(404, 409, 503)) throw e
                last = e
                if (attempt < 5) delay(500L * (attempt + 1))
            }
        }
        throw IllegalStateException(
            "MetaApi terminal is not ready yet. Retry connection after the broker session finishes synchronizing.",
            last,
        )
    }

    suspend fun getAccountInfo(token: String, accountId: String): Mt4AccountInfo {
        val region = resolveRegion(token, accountId)
        val response = api.getAccountInformation(
            url = MetaApiEndpointResolver.clientUrl(region, accountId, "account-information"),
            authToken = token,
        )
        return response.toDomain()
    }

    suspend fun getPositions(token: String, accountId: String): List<Mt4Position> {
        val region = resolveRegion(token, accountId)
        val response = api.getPositions(
            url = MetaApiEndpointResolver.clientUrl(region, accountId, "positions"),
            authToken = token,
        )
        return response.mapNotNull { it.toDomainOrNull() }
    }

    /** Poll-safe current price endpoint used instead of an ad-hoc raw WebSocket protocol. */
    suspend fun getCurrentPrice(token: String, accountId: String, symbol: String): Mt4Quote {
        val normalized = normalizeSymbol(symbol)
        val region = resolveRegion(token, accountId)
        val response = api.getCurrentPrice(
            url = MetaApiEndpointResolver.clientUrl(region, accountId, "symbols", normalized, "current-price"),
            authToken = token,
            keepSubscription = true,
        )
        check(response.symbol.equals(normalized, ignoreCase = true)) {
            "MetaApi returned a price for an unexpected symbol"
        }
        check(response.bid.isFinite() && response.ask.isFinite() && response.bid > 0.0 && response.ask >= response.bid) {
            "MetaApi returned an invalid quote for $normalized"
        }
        val timestamp = parseIsoEpochMillis(response.time)
            ?: throw IllegalStateException("MetaApi returned a quote with an invalid timestamp")
        return Mt4Quote(
            symbol = normalized,
            bid = response.bid,
            ask = response.ask,
            timestamp = timestamp,
        )
    }

    suspend fun getHistoricalCandles(
        token: String,
        accountId: String,
        symbol: String,
        timeframe: Timeframe,
        limit: Int = 300,
    ): List<Candle> {
        val normalized = normalizeSymbol(symbol)
        val safeLimit = limit.coerceIn(1, 1000)
        val region = resolveRegion(token, accountId)
        val response = api.getHistoricalCandles(
            url = MetaApiEndpointResolver.marketDataCandlesUrl(
                region = region,
                accountId = accountId,
                symbol = normalized,
                timeframe = timeframe.toMetaApiTimeframe(),
            ),
            authToken = token,
            limit = safeLimit,
        )
        return response.mapNotNull { it.toDomain() }.sortedBy { it.timestamp }
    }

    suspend fun getHistoricalCandlesBefore(
        token: String,
        accountId: String,
        symbol: String,
        timeframe: Timeframe,
        beforeTimestampMs: Long,
        limit: Int = 300,
    ): List<Candle> {
        require(beforeTimestampMs > 0L) { "History boundary must be positive" }
        val normalized = normalizeSymbol(symbol)
        val safeLimit = limit.coerceIn(1, 1000)
        val region = resolveRegion(token, accountId)
        // MetaApi pages backwards and treats startTime as the latest boundary.
        // Move one millisecond before the oldest visible candle so the returned
        // page cannot repeat that candle and trap the chart's paging loop.
        val startTime = Instant.ofEpochMilli((beforeTimestampMs - 1L).coerceAtLeast(0L)).toString()
        val response = api.getHistoricalCandles(
            url = MetaApiEndpointResolver.marketDataCandlesUrl(
                region = region,
                accountId = accountId,
                symbol = normalized,
                timeframe = timeframe.toMetaApiTimeframe(),
            ),
            authToken = token,
            startTime = startTime,
            limit = safeLimit,
        )
        return response
            .mapNotNull { it.toDomain() }
            .asSequence()
            .filter { it.timestamp < beforeTimestampMs }
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }
            .takeLast(safeLimit)
            .toList()
    }

    suspend fun executeTrade(
        token: String,
        accountId: String,
        symbol: String,
        type: Mt4OrderType,
        lots: Double,
        sl: Double?,
        tp: Double?,
    ): Long {
        val normalized = normalizeSymbol(symbol)
        require(lots.isFinite() && lots > 0.0) { "Trade volume must be finite and positive" }
        require(type == Mt4OrderType.BUY || type == Mt4OrderType.SELL) {
            "Pending orders require an explicit broker open price and are not supported by this market-order API path"
        }
        val region = resolveRegion(token, accountId)
        val request = MetaApiTradeRequest(
            actionType = type.toMetaApiAction(),
            symbol = normalized,
            volume = lots,
            stopLoss = sl,
            takeProfit = tp,
        )
        val response = api.executeTrade(
            url = MetaApiEndpointResolver.clientUrl(region, accountId, "trade"),
            authToken = token,
            request = request,
        )
        when (response.tradeOutcome()) {
            MetaApiTradeOutcome.SUCCESS -> Unit
            MetaApiTradeOutcome.UNKNOWN -> throw MetaApiTradeOutcomeUnknownException(
                "MetaApi trade outcome is unknown: ${response.stringCode} - ${response.message}"
            )
            MetaApiTradeOutcome.REJECTED -> throw MetaApiTradeRejectedException(
                "MetaApi trade failed: ${response.stringCode} - ${response.message}"
            )
        }
        return response.orderId.toLongOrNull()?.takeIf { it > 0L }
            ?: response.positionId.toLongOrNull()?.takeIf { it > 0L }
            ?: throw MetaApiTradeOutcomeUnknownException(
                "MetaApi reported trade success but returned no numeric order/position id; reconcile before retrying"
            )
    }

    suspend fun getPendingOrders(token: String, accountId: String): List<Mt4PendingOrder> {
        val region = resolveRegion(token, accountId)
        return api.getOrders(
            url = MetaApiEndpointResolver.clientUrl(region, accountId, "orders"),
            authToken = token,
        ).mapNotNull { it.toPendingOrderOrNull() }
    }

    suspend fun placePendingOrder(
        token: String,
        accountId: String,
        request: Mt4PendingOrderRequest,
    ): Long {
        require(request.type in Mt4PendingOrderRequest.PENDING_TYPES) { "Pending order type is required" }
        require(request.lots.isFinite() && request.lots > 0.0) { "Pending volume must be positive" }
        require(request.openPrice.isFinite() && request.openPrice > 0.0) { "Pending open price must be positive" }
        val expiration = request.expirationType.toMetaApiExpiration(request.expirationTime)
        val region = resolveRegion(token, accountId)
        val response = api.executeTrade(
            url = MetaApiEndpointResolver.clientUrl(region, accountId, "trade"),
            authToken = token,
            request = MetaApiTradeRequest(
                actionType = request.type.toMetaApiAction(),
                symbol = normalizeSymbol(request.symbol),
                volume = request.lots,
                openPrice = request.openPrice,
                stopLoss = request.stopLoss,
                takeProfit = request.takeProfit,
                expiration = expiration,
            ),
        )
        requireTradeSuccess(response, "pending order")
        return response.orderId.toLongOrNull()?.takeIf { it > 0L }
            ?: throw MetaApiTradeOutcomeUnknownException(
                "MetaApi accepted the pending order but returned no numeric order id; reconcile before retrying"
            )
    }

    suspend fun modifyPendingOrder(
        token: String,
        accountId: String,
        ticket: Long,
        openPrice: Double,
        stopLoss: Double?,
        takeProfit: Double?,
    ) {
        require(ticket > 0L) { "Pending-order ticket must be positive" }
        require(openPrice.isFinite() && openPrice > 0.0) { "Pending open price must be positive" }
        val region = resolveRegion(token, accountId)
        val response = api.executeTrade(
            url = MetaApiEndpointResolver.clientUrl(region, accountId, "trade"),
            authToken = token,
            request = MetaApiTradeRequest(
                actionType = "ORDER_MODIFY",
                orderId = ticket.toString(),
                openPrice = openPrice,
                stopLoss = stopLoss,
                takeProfit = takeProfit,
            ),
        )
        requireTradeSuccess(response, "pending-order modification")
    }

    suspend fun cancelPendingOrder(token: String, accountId: String, ticket: Long) {
        require(ticket > 0L) { "Pending-order ticket must be positive" }
        val region = resolveRegion(token, accountId)
        val response = api.executeTrade(
            url = MetaApiEndpointResolver.clientUrl(region, accountId, "trade"),
            authToken = token,
            request = MetaApiTradeRequest(actionType = "ORDER_CANCEL", orderId = ticket.toString()),
        )
        requireTradeSuccess(response, "pending-order cancellation")
    }

    suspend fun modifyPositionProtection(
        token: String,
        accountId: String,
        ticket: Long,
        protection: Mt4PositionProtection,
    ) {
        require(ticket > 0L) { "Position ticket must be positive" }
        protection.stopLoss?.let { require(it.isFinite() && it >= 0.0) { "Stop loss must be finite" } }
        protection.takeProfit?.let { require(it.isFinite() && it >= 0.0) { "Take profit must be finite" } }
        protection.trailingDistancePoints?.let {
            require(it.isFinite() && it > 0.0) { "Trailing-stop distance must be positive" }
        }
        val region = resolveRegion(token, accountId)
        val response = api.executeTrade(
            url = MetaApiEndpointResolver.clientUrl(region, accountId, "trade"),
            authToken = token,
            request = MetaApiTradeRequest(
                actionType = "POSITION_MODIFY",
                positionId = ticket.toString(),
                stopLoss = protection.stopLoss,
                takeProfit = protection.takeProfit,
                trailingStopLoss = protection.trailingDistancePoints?.let {
                    MetaApiTrailingStopLoss(MetaApiTrailingDistance(distance = it))
                },
            ),
        )
        requireTradeSuccess(response, "position modification")
    }

    suspend fun partialClosePosition(
        token: String,
        accountId: String,
        ticket: Long,
        volume: Double,
    ) {
        require(ticket > 0L) { "Position ticket must be positive" }
        require(volume.isFinite() && volume > 0.0) { "Partial-close volume must be positive" }
        val region = resolveRegion(token, accountId)
        val response = api.executeTrade(
            url = MetaApiEndpointResolver.clientUrl(region, accountId, "trade"),
            authToken = token,
            request = MetaApiTradeRequest(
                actionType = "POSITION_PARTIAL",
                positionId = ticket.toString(),
                volume = volume,
            ),
        )
        requireTradeSuccess(response, "partial close")
    }

    private fun requireTradeSuccess(response: MetaApiTradeResponse, operation: String) {
        when (response.tradeOutcome()) {
            MetaApiTradeOutcome.SUCCESS -> Unit
            MetaApiTradeOutcome.UNKNOWN -> throw MetaApiTradeOutcomeUnknownException(
                "MetaApi $operation outcome is unknown: ${response.stringCode} - ${response.message}"
            )
            MetaApiTradeOutcome.REJECTED -> throw MetaApiTradeRejectedException(
                "MetaApi $operation failed: ${response.stringCode} - ${response.message}"
            )
        }
    }

    suspend fun closePosition(token: String, accountId: String, ticket: Long) {
        require(ticket > 0L) { "Position ticket must be positive" }
        val region = resolveRegion(token, accountId)
        val request = MetaApiTradeRequest(
            actionType = "POSITION_CLOSE_ID",
            positionId = ticket.toString(),
        )
        val response = api.executeTrade(
            url = MetaApiEndpointResolver.clientUrl(region, accountId, "trade"),
            authToken = token,
            request = request,
        )
        when (response.tradeOutcome()) {
            MetaApiTradeOutcome.SUCCESS -> Unit
            MetaApiTradeOutcome.UNKNOWN -> throw MetaApiTradeOutcomeUnknownException(
                "MetaApi close outcome is unknown: ${response.stringCode} - ${response.message}"
            )
            MetaApiTradeOutcome.REJECTED -> throw MetaApiTradeRejectedException(
                "MetaApi close failed: ${response.stringCode} - ${response.message}"
            )
        }
    }

    /**
     * Returns authoritative realized P/L for a fully/partially closed position
     * once MetaApi history contains at least one exit deal. Profit, commission
     * and swap are summed across every deal returned for the position because
     * commissions can be charged on both entry and exit. Null means history is
     * not synchronized enough yet to make a safe claim.
     */
    suspend fun getPositionRealizedProfit(
        token: String,
        accountId: String,
        positionId: Long,
    ): Double? = getClosedPositionDetails(token, accountId, positionId)?.realizedProfit

    /**
     * Reconstructs a closed position from authoritative history deals. The
     * weighted exit price/time are derived only from EXIT deals; realized P/L
     * includes profit + commission + swap across all deals for the position.
     * Null means history has not synchronized an exit yet.
     */
    suspend fun getClosedPositionDetails(
        token: String,
        accountId: String,
        positionId: Long,
    ): Mt4ClosedPositionDetails? {
        require(positionId > 0L) { "Position id must be positive" }
        val region = resolveRegion(token, accountId)
        val deals = api.getDealsByPosition(
            url = MetaApiEndpointResolver.clientUrl(
                region, accountId, "history-deals", "position", positionId.toString()
            ),
            authToken = token,
        )
        if (deals.isEmpty()) return null
        val exits = deals.filter { deal ->
            deal.entryType.uppercase() in setOf("DEAL_ENTRY_OUT", "DEAL_ENTRY_INOUT", "DEAL_ENTRY_OUT_BY") &&
                deal.price.isFinite() && deal.price > 0.0 && deal.volume.isFinite() && kotlin.math.abs(deal.volume) > 0.0
        }
        if (exits.isEmpty()) return null

        var realized = 0.0
        deals.forEach { deal ->
            if (!deal.profit.isFinite() || !deal.commission.isFinite() || !deal.swap.isFinite()) {
                throw IllegalStateException("MetaApi returned non-finite realized P/L data")
            }
            realized += deal.profit + deal.commission + deal.swap
            if (!realized.isFinite()) throw IllegalStateException("MetaApi realized P/L overflow")
        }
        val totalExitVolume = exits.sumOf { kotlin.math.abs(it.volume) }
        if (!totalExitVolume.isFinite() || totalExitVolume <= 0.0) return null
        val exitPrice = exits.sumOf { it.price * kotlin.math.abs(it.volume) } / totalExitVolume
        if (!exitPrice.isFinite() || exitPrice <= 0.0) return null
        val exitTime = exits.mapNotNull { parseIsoEpochMillis(it.time) }.maxOrNull() ?: return null
        return Mt4ClosedPositionDetails(
            positionId = positionId,
            exitPrice = exitPrice,
            exitTime = exitTime,
            realizedProfit = realized,
        )
    }

    suspend fun getSymbolSpecification(
        token: String,
        accountId: String,
        symbol: String,
    ): MetaApiSymbolSpecResponse? {
        val normalized = normalizeSymbol(symbol)
        val region = resolveRegion(token, accountId)
        val response = api.getSymbolSpecification(
            url = MetaApiEndpointResolver.clientUrl(region, accountId, "symbols", normalized, "specification"),
            authToken = token,
        )
        if (!response.symbol.equals(normalized, ignoreCase = true)) return null
        if (!response.minVolume.isFinite() || !response.maxVolume.isFinite() || !response.volumeStep.isFinite()) return null
        if (response.minVolume <= 0.0 || response.maxVolume < response.minVolume || response.volumeStep <= 0.0) return null
        if (!response.contractSize.isFinite() || response.contractSize <= 0.0) return null
        if (!response.tickSize.isFinite() || response.tickSize <= 0.0) return null
        if (response.point != 0.0 && (!response.point.isFinite() || response.point <= 0.0)) return null
        return response
    }

    /** Clears a cached routing decision, e.g. after a credential/account switch. */
    fun invalidateAccountRouting(accountId: String? = null) {
        if (accountId.isNullOrBlank()) regionByAccountId.clear() else regionByAccountId.remove(accountId)
    }

    /** Returns the validated regional client-api label for the provisioned account. */
    suspend fun getAccountRegion(token: String, accountId: String): String =
        resolveRegion(token, accountId)

    private suspend fun resolveRegion(token: String, accountId: String, forceRefresh: Boolean = false): String {
        val id = normalizeAccountId(accountId)
        if (!forceRefresh) regionByAccountId[id]?.let { return it }
        val provisioned = ensureProvisionedAccountReady(token, id, forceRefresh = true)
        check(provisioned.region.isNotBlank()) { "MetaApi account has no deployment region" }
        val normalized = MetaApiEndpointResolver.normalizeRegion(provisioned.region)
        regionByAccountId[id] = normalized
        return normalized
    }

    private fun normalizeAccountId(accountId: String): String {
        val id = accountId.trim()
        require(id.isNotEmpty() && id.length <= 128 && id.none { it.isISOControl() }) { "Invalid MetaApi account id" }
        return id
    }

    private fun MetaApiAccountInfoResponse.toDomain(): Mt4AccountInfo {
        check(login > 0) { "MetaApi account information has an invalid login" }
        check(balance.isFinite() && equity.isFinite() && margin.isFinite() && freeMargin.isFinite()) {
            "MetaApi account information contains non-finite financial values"
        }
        return Mt4AccountInfo(
            login = login,
            balance = balance,
            equity = equity,
            margin = margin,
            freeMargin = freeMargin,
            leverage = leverage,
            currency = currency.ifBlank { "USD" },
            name = name,
            server = server,
        )
    }

    private fun MetaApiCandleResponse.toDomain(): Candle? {
        val ts = parseIsoEpochMillis(time) ?: return null
        val o = open
        val h = high
        val l = low
        val c = close
        if (!o.isFinite() || !h.isFinite() || !l.isFinite() || !c.isFinite()) return null
        if (o <= 0.0 || h <= 0.0 || l <= 0.0 || c <= 0.0) return null
        if (h < maxOf(o, c) || l > minOf(o, c) || h < l) return null
        val v = if (volume > 0.0) volume else tickVolume
        return Candle(
            timestamp = ts,
            open = o,
            high = h,
            low = l,
            close = c,
            volume = if (v.isFinite() && v >= 0.0) v else 0.0,
        )
    }

    private fun MetaApiPositionResponse.toDomainOrNull(): Mt4Position? {
        val ticket = id.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val normalizedSymbol = symbol.trim().uppercase().takeIf { it.isNotBlank() } ?: return null
        val orderType = type.toOrderTypeOrNull() ?: return null
        if (!volume.isFinite() || volume <= 0.0 || !openPrice.isFinite() || openPrice <= 0.0) return null
        val openEpoch = parseIsoEpochMillis(time) ?: 0L
        return Mt4Position(
            ticket = ticket,
            symbol = normalizedSymbol,
            type = orderType,
            lots = volume,
            openPrice = openPrice,
            openTime = openEpoch,
            sl = stopLoss.takeIf { it.isFinite() } ?: 0.0,
            tp = takeProfit.takeIf { it.isFinite() } ?: 0.0,
            profit = profit.takeIf { it.isFinite() } ?: 0.0,
            swap = swap.takeIf { it.isFinite() } ?: 0.0,
            commission = commission.takeIf { it.isFinite() } ?: 0.0,
        )
    }

    private fun MetaApiOrderResponse.toPendingOrderOrNull(): Mt4PendingOrder? {
        val ticket = id.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val orderType = type.toOrderTypeOrNull()?.takeIf { it in Mt4PendingOrderRequest.PENDING_TYPES } ?: return null
        val normalizedSymbol = symbol.trim().uppercase().takeIf { it.isNotEmpty() } ?: return null
        if (!volume.isFinite() || volume <= 0.0 || !openPrice.isFinite() || openPrice <= 0.0) return null
        val remaining = currentVolume.takeIf { it.isFinite() && it >= 0.0 } ?: volume
        return Mt4PendingOrder(
            ticket = ticket,
            symbol = normalizedSymbol,
            type = orderType,
            lots = volume,
            remainingLots = remaining,
            openPrice = openPrice,
            currentPrice = currentPrice.takeIf { it.isFinite() && it > 0.0 },
            openTime = parseIsoEpochMillis(time) ?: 0L,
            sl = stopLoss.takeIf { it.isFinite() } ?: 0.0,
            tp = takeProfit.takeIf { it.isFinite() } ?: 0.0,
            state = state.ifBlank { "UNKNOWN" },
            expirationType = expirationType.toPendingExpirationType(),
            expirationTime = expirationTime?.let(::parseIsoEpochMillis),
        )
    }

    private fun Mt4PendingExpirationType.toMetaApiExpiration(expirationTime: Long?): MetaApiOrderExpiration {
        val type = when (this) {
            Mt4PendingExpirationType.GTC -> "ORDER_TIME_GTC"
            Mt4PendingExpirationType.DAY -> "ORDER_TIME_DAY"
            Mt4PendingExpirationType.SPECIFIED -> "ORDER_TIME_SPECIFIED"
            Mt4PendingExpirationType.SPECIFIED_DAY -> "ORDER_TIME_SPECIFIED_DAY"
        }
        val iso = when (this) {
            Mt4PendingExpirationType.SPECIFIED, Mt4PendingExpirationType.SPECIFIED_DAY -> {
                require(expirationTime != null && expirationTime > System.currentTimeMillis()) {
                    "Specified pending-order expiration must be in the future"
                }
                Instant.ofEpochMilli(expirationTime).toString()
            }
            else -> null
        }
        return MetaApiOrderExpiration(type = type, time = iso)
    }

    private fun String.toPendingExpirationType(): Mt4PendingExpirationType = when (uppercase()) {
        "ORDER_TIME_DAY" -> Mt4PendingExpirationType.DAY
        "ORDER_TIME_SPECIFIED" -> Mt4PendingExpirationType.SPECIFIED
        "ORDER_TIME_SPECIFIED_DAY" -> Mt4PendingExpirationType.SPECIFIED_DAY
        else -> Mt4PendingExpirationType.GTC
    }

    /**
     * Accept only a response whose required numeric and string codes both say
     * success. Contradictory responses are UNKNOWN, never retryable rejection.
     */
    private fun MetaApiTradeResponse.tradeOutcome(): MetaApiTradeOutcome {
        val numericSuccess = numericCode in SUCCESS_NUMERIC_CODES
        val normalizedString = stringCode.uppercase()
        val stringSuccess = normalizedString in SUCCESS_STRING_CODES
        val ambiguous = numericCode in UNKNOWN_NUMERIC_CODES || normalizedString in UNKNOWN_STRING_CODES
        return when {
            numericSuccess && stringSuccess -> MetaApiTradeOutcome.SUCCESS
            ambiguous || numericSuccess != stringSuccess -> MetaApiTradeOutcome.UNKNOWN
            else -> MetaApiTradeOutcome.REJECTED
        }
    }

    private enum class MetaApiTradeOutcome { SUCCESS, REJECTED, UNKNOWN }

    private companion object {
        val SUCCESS_NUMERIC_CODES = setOf(0, 10008, 10009, 10010, 10025)
        val SUCCESS_STRING_CODES = setOf(
            "ERR_NO_ERROR",
            "TRADE_RETCODE_PLACED",
            "TRADE_RETCODE_DONE",
            "TRADE_RETCODE_DONE_PARTIAL",
            "TRADE_RETCODE_NO_CHANGES",
        )
        // Codes whose documentation says, or strongly implies, that outcome is
        // unknown after timeout/disconnection. Keep UNKNOWN to block blind retry.
        val UNKNOWN_NUMERIC_CODES = setOf(-11, -7, -1, 1, 128)
        val UNKNOWN_STRING_CODES = setOf(
            "TRADE_RETCODE_DISCONNECTED_DURING_TRADE",
            "ERR_TRADE_TIMED_OUT",
            "TRADE_RETCODE_UNKNOWN",
            "ERR_NO_RESULT",
            "ERR_TRADE_TIMEOUT",
        )
    }

    private fun Mt4OrderType.toMetaApiAction(): String = when (this) {
        Mt4OrderType.BUY -> "ORDER_TYPE_BUY"
        Mt4OrderType.SELL -> "ORDER_TYPE_SELL"
        Mt4OrderType.BUY_LIMIT -> "ORDER_TYPE_BUY_LIMIT"
        Mt4OrderType.SELL_LIMIT -> "ORDER_TYPE_SELL_LIMIT"
        Mt4OrderType.BUY_STOP -> "ORDER_TYPE_BUY_STOP"
        Mt4OrderType.SELL_STOP -> "ORDER_TYPE_SELL_STOP"
    }

    private fun String.toOrderTypeOrNull(): Mt4OrderType? = when (uppercase()) {
        "POSITION_TYPE_BUY", "ORDER_TYPE_BUY", "BUY" -> Mt4OrderType.BUY
        "POSITION_TYPE_SELL", "ORDER_TYPE_SELL", "SELL" -> Mt4OrderType.SELL
        "ORDER_TYPE_BUY_LIMIT", "BUY_LIMIT" -> Mt4OrderType.BUY_LIMIT
        "ORDER_TYPE_SELL_LIMIT", "SELL_LIMIT" -> Mt4OrderType.SELL_LIMIT
        "ORDER_TYPE_BUY_STOP", "BUY_STOP" -> Mt4OrderType.BUY_STOP
        "ORDER_TYPE_SELL_STOP", "SELL_STOP" -> Mt4OrderType.SELL_STOP
        else -> null
    }

    private fun Timeframe.toMetaApiTimeframe(): String = when (this) {
        Timeframe.M1 -> "1m"
        Timeframe.M5 -> "5m"
        Timeframe.M15 -> "15m"
        Timeframe.M30 -> "30m"
        Timeframe.H1 -> "1h"
        Timeframe.H4 -> "4h"
        Timeframe.D1 -> "1d"
        Timeframe.W1 -> "1w"
        Timeframe.MN -> "1mn"
    }

    private fun normalizeSymbol(symbol: String): String {
        val normalized = symbol.trim().uppercase()
        // Broker-specific symbols may contain suffixes, '#', '/' or spaces.
        // URL encoding is handled by MetaApiEndpointResolver; reject only
        // blank, unreasonably long, or control-character values here.
        require(normalized.isNotEmpty() && normalized.length <= 64 && normalized.none { it.isISOControl() }) {
            "Invalid trading symbol"
        }
        return normalized
    }

    private fun parseIsoEpochMillis(value: String): Long? = runCatching {
        Instant.parse(value).toEpochMilli()
    }.getOrNull()?.takeIf { it > 0L }
}
