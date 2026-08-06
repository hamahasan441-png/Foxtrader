package com.foxtrader.app.domain.repository

import com.foxtrader.app.domain.model.Mt4AccountInfo
import com.foxtrader.app.domain.model.Mt4Credentials
import com.foxtrader.app.domain.model.Mt4OrderType
import com.foxtrader.app.domain.model.Mt4Position
import com.foxtrader.app.domain.model.Mt4Quote
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
    ): Result<Long>

    /**
     * Close an open trade by ticket number.
     *
     * @param ticket The order ticket to close.
     */
    suspend fun closeTrade(ticket: Long): Result<Unit>
}
