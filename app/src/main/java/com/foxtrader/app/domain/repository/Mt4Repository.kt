package com.foxtrader.app.domain.repository

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Mt4AccountInfo
import com.foxtrader.app.domain.model.Mt4AccountProfile
import com.foxtrader.app.domain.model.Mt4Broker
import com.foxtrader.app.domain.model.Mt4Credentials
import com.foxtrader.app.domain.model.Mt4OrderType
import com.foxtrader.app.domain.model.Mt4Position
import com.foxtrader.app.domain.model.Mt4PendingOrder
import com.foxtrader.app.domain.model.Mt4PendingOrderRequest
import com.foxtrader.app.domain.model.Mt4PositionProtection
import com.foxtrader.app.domain.model.Mt4Quote
import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.flow.Flow
import com.foxtrader.app.domain.model.Mt4PendingOrderSnapshot
import com.foxtrader.app.domain.model.Mt4PositionSnapshot

/**
 * Repository interface for MT4 account operations via MetaApi.
 *
 * Encapsulates all broker communication: account provisioning, information
 * retrieval, position management, live quote streaming, and trade execution.
 *
 * Implementations handle token management internally. Methods returning
 * [Result] expose recoverable broker failures there; non-[Result] maintenance
 * operations may throw when their integrity cannot be verified (for example,
 * reconciliation when the durable audit store is unavailable).
 */
interface Mt4Repository {

    /**
     * Connect to an MT4 account by deploying it on MetaApi.
     *
     * @param credentials MT4 login/password/server.
     * @return Account info on success or error details on failure.
     */
    suspend fun connect(credentials: Mt4Credentials): Result<Mt4AccountInfo>

    /**
     * Disconnect the currently connected MT4 account and clean up resources.
     */
    suspend fun disconnect()

    /**
     * Retrieve the current account information (balance, equity, margin, etc.).
     */
    suspend fun getAccountInfo(): Result<Mt4AccountInfo>

    /**
     * Retrieve all open positions on the connected account.
     */
    suspend fun getPositions(): Result<List<Mt4Position>>

    /** Retrieve broker-authoritative pending orders for the connected account. */
    suspend fun getPendingOrders(): Result<List<Mt4PendingOrder>>

    /**
     * Stream real-time quotes for the given symbols.
     *
     * @param symbols List of trading instruments to subscribe to.
     * @return A [Flow] emitting [Mt4Quote] updates as they arrive.
     */
    fun streamQuotes(symbols: List<String>): Flow<Mt4Quote>

    /**
     * Search the curated MT4 broker directory by name or server.
     * A blank query returns the full directory.
     */
    suspend fun searchBrokers(query: String): List<Mt4Broker>

    /**
     * Fetch historical candles for a symbol/timeframe from the connected MT4
     * account (seeds the chart history when MT4 is the active provider).
     */
    suspend fun getHistoricalCandles(
        symbol: String,
        timeframe: Timeframe,
        limit: Int = 300,
    ): Result<List<Candle>>

    /**
     * Page broker-authoritative candles strictly older than [beforeTimestampMs].
     * MetaApi loads candles backwards from its `startTime` boundary.
     */
    suspend fun getHistoricalCandlesBefore(
        symbol: String,
        timeframe: Timeframe,
        beforeTimestampMs: Long,
        limit: Int = 300,
    ): Result<List<Candle>>

    /** True when an MT4 account is currently connected (has an account ID). */
    fun isConnected(): Boolean

    /** Whether the persisted live-execution switch is enabled. */
    fun isLiveModeEnabled(): Boolean

    /** Persist the live-execution switch (must be user-confirmed). */
    fun setLiveModeEnabled(enabled: Boolean)

    /** Whether the persisted emergency kill switch is engaged. */
    fun isKillSwitchEngaged(): Boolean

    /** Engage the emergency kill switch (blocks all live MT4 orders). */
    fun setKillSwitch(engaged: Boolean)

    /** The login/server from the most recent connection, for prefilling the form. */
    fun getLastConnection(): Mt4Credentials?

    /** Password-free recent broker accounts available in the Phase 6 selector. */
    fun getSavedAccounts(): List<Mt4AccountProfile>

    /** Remove one saved account descriptor. Does not touch broker-side accounts. */
    fun removeSavedAccount(profile: Mt4AccountProfile)

    /**
     * Place a trade on the connected account.
     *
     * @param symbol Trading instrument.
     * @param type Market order type (BUY or SELL). Pending orders use [placePendingOrder].
     * @param lots Trade volume.
     * @param sl Stop loss price (null for no SL).
     * @param tp Take profit price (null for no TP).
     * @return The order ticket number on success.
     */
    suspend fun placeTrade(
        symbol: String,
        type: Mt4OrderType,
        lots: Double,
        sl: Double?,
        tp: Double?,
        /** Executable ask/bid shown in the review dialog. */
        reviewedEntryPrice: Double? = null,
        /** Maximum adverse/favorable drift from reviewed price, in broker points. */
        maxSlippagePoints: Double? = null,
        confirmationTimestamp: Long = System.currentTimeMillis(),
    ): Result<Long>

    /**
     * Close an open trade by ticket number.
     *
     * @param ticket The order ticket to close.
     * @param confirmationTimestamp Wall-clock time the user confirmed the close.
     */
    suspend fun closeTrade(
        ticket: Long,
        confirmationTimestamp: Long = System.currentTimeMillis(),
    ): Result<Unit>

    /** Create a broker pending order after an explicit review/confirmation. */
    suspend fun placePendingOrder(
        request: Mt4PendingOrderRequest,
        confirmationTimestamp: Long = System.currentTimeMillis(),
    ): Result<Long>

    /** Modify open price/SL/TP of an existing broker pending order. */
    suspend fun modifyPendingOrder(
        ticket: Long,
        openPrice: Double,
        stopLoss: Double?,
        takeProfit: Double?,
        confirmationTimestamp: Long = System.currentTimeMillis(),
        expectedState: Mt4PendingOrderSnapshot? = null,
    ): Result<Unit>

    /** Cancel a broker pending order. */
    suspend fun cancelPendingOrder(
        ticket: Long,
        confirmationTimestamp: Long = System.currentTimeMillis(),
    ): Result<Unit>

    /** Modify broker-side SL/TP and/or trailing-stop protection. */
    suspend fun modifyPositionProtection(
        ticket: Long,
        protection: Mt4PositionProtection,
        confirmationTimestamp: Long = System.currentTimeMillis(),
        expectedState: Mt4PositionSnapshot? = null,
    ): Result<Unit>

    /** Move the position stop to its broker open price when market geometry allows. */
    suspend fun movePositionToBreakEven(
        ticket: Long,
        confirmationTimestamp: Long = System.currentTimeMillis(),
    ): Result<Unit>

    /** Close only [lots] of an existing position; never silently escalates to full close. */
    suspend fun partialCloseTrade(
        ticket: Long,
        lots: Double,
        confirmationTimestamp: Long = System.currentTimeMillis(),
        expectedState: Mt4PositionSnapshot? = null,
    ): Result<Unit>

    /**
     * Reconcile UNKNOWN execution/management receipts against broker-authoritative
     * positions and pending orders after reconnect/app restart. UNKNOWN actions are
     * never retried automatically; reconciliation promotes them only when the target
     * broker state can be proven, otherwise they remain UNKNOWN for operator review.
     * Returns the number of receipts still unresolved.
     */
    suspend fun reconcileUnknownOrders(): Int

    /**
     * Synchronize broker-authoritative open/closed position state into the local
     * professional journal. Missing history is left unresolved, never guessed.
     * Returns the number of journal rows still awaiting authoritative close data.
     */
    suspend fun synchronizeBrokerJournal(): Result<Int>

    /**
     * Fetch broker-authoritative instrument spec for [symbol], cached per account
     * + symbol for a short TTL. Falls back to estimated defaults when broker fetch fails.
     * The returned [com.foxtrader.app.domain.usecase.risk.InstrumentSpec.isEstimated]
     * flag indicates fallback.
     */
    suspend fun getInstrumentSpec(symbol: String): com.foxtrader.app.domain.usecase.risk.InstrumentSpec
}
