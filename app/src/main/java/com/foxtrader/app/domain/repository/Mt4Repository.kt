package com.foxtrader.app.domain.repository

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Mt4AccountInfo
import com.foxtrader.app.domain.model.Mt4Broker
import com.foxtrader.app.domain.model.Mt4Credentials
import com.foxtrader.app.domain.model.Mt4OrderType
import com.foxtrader.app.domain.model.Mt4Position
import com.foxtrader.app.domain.model.Mt4Quote
import com.foxtrader.app.domain.model.Timeframe
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for MT4 account operations via MetaApi.
 *
 * Encapsulates all broker communication: account provisioning, information
 * retrieval, position management, live quote streaming, and trade execution.
 *
 * Implementations must handle token management internally and propagate
 * errors as [Result] failures rather than throwing.
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

    /**
     * Place a trade on the connected account.
     *
     * @param symbol Trading instrument.
     * @param type Order type (market or pending).
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

    /**
     * Reconcile UNKNOWN order receipts against the broker's current positions
     * after an app restart. UNKNOWN orders are never retried automatically;
     * reconciliation either confirms them (ACCEPTED) or leaves them UNKNOWN for
     * an operator. Returns the number of receipts still unresolved.
     */
    suspend fun reconcileUnknownOrders(): Int

    /**
     * Fetch broker-authoritative instrument spec for [symbol], cached per symbol
     * short TTL. Falls back to estimated defaults when broker fetch fails.
     * The returned [com.foxtrader.app.domain.usecase.risk.InstrumentSpec.isEstimated]
     * flag indicates fallback.
     */
    suspend fun getInstrumentSpec(symbol: String): com.foxtrader.app.domain.usecase.risk.InstrumentSpec
}
