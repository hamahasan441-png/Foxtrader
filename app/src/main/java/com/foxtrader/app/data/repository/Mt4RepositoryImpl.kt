package com.foxtrader.app.data.repository

import com.foxtrader.app.data.remote.api.MetaApiDataSource
import com.foxtrader.app.data.remote.websocket.Mt4QuoteStream
import com.foxtrader.app.domain.model.Mt4AccountInfo
import com.foxtrader.app.domain.model.Mt4Credentials
import com.foxtrader.app.domain.model.Mt4OrderType
import com.foxtrader.app.domain.model.Mt4Position
import com.foxtrader.app.domain.model.Mt4Quote
import com.foxtrader.app.domain.repository.Mt4Repository
import com.foxtrader.app.domain.usecase.preferences.AppPreferences
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [Mt4Repository].
 *
 * Delegates REST operations to [MetaApiDataSource] and live-quote streaming
 * to [Mt4QuoteStream]. Manages the MetaApi account ID lifecycle internally.
 *
 * The MetaApi auth token is retrieved from [AppPreferences.getApiKey] for
 * [DataProvider.MT4]. The account ID is obtained during [connect] and
 * stored for subsequent calls.
 */
@Singleton
class Mt4RepositoryImpl @Inject constructor(
    private val dataSource: MetaApiDataSource,
    private val quoteStream: Mt4QuoteStream,
    private val appPreferences: AppPreferences,
) : Mt4Repository {

    private var accountId: String = ""

    override suspend fun connect(credentials: Mt4Credentials): Result<Mt4AccountInfo> =
        runCatching {
            val token = requireToken()
            val deployedId = dataSource.deployAccount(token, credentials)
            accountId = deployedId
            quoteStream.connect(token, deployedId)
            dataSource.getAccountInfo(token, deployedId)
        }

    override suspend fun disconnect() {
        quoteStream.disconnect()
        accountId = ""
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
        return quoteStream.quotes
    }

    override suspend fun placeTrade(
        symbol: String,
        type: Mt4OrderType,
        lots: Double,
        sl: Double?,
        tp: Double?,
    ): Result<Long> = runCatching {
        val token = requireToken()
        check(accountId.isNotBlank()) { "Not connected. Call connect() first." }
        dataSource.executeTrade(token, accountId, symbol, type, lots, sl, tp)
    }

    override suspend fun closeTrade(ticket: Long): Result<Unit> = runCatching {
        val token = requireToken()
        check(accountId.isNotBlank()) { "Not connected. Call connect() first." }
        dataSource.closePosition(token, accountId, ticket)
    }

    private fun requireToken(): String {
        val token = appPreferences.getMetaApiToken()
        check(!token.isNullOrBlank()) { "MetaApi token not configured. Set it in Settings." }
        return token
    }
}
