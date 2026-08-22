package com.foxtrader.app.data.remote.api

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.Mt4OrderType
import com.foxtrader.app.domain.usecase.execution.ExecutionReceipt
import com.foxtrader.app.domain.usecase.execution.TradeIntent
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Broker transport seam that submits a [TradeIntent] to MetaApi and produces an
 * [ExecutionReceipt].
 *
 * This is the only code that touches the MetaApi trade endpoint for live orders.
 * It deliberately never logs the raw broker payload or any credentials. It maps
 * the receipt outcome conservatively:
 *  - a broker confirmation with a real order id => [ExecutionReceipt.Accepted]
 *  - a explicit definitive broker rejection code => [ExecutionReceipt.Rejected]
 *  - anything ambiguous (exception mid-flight) => [ExecutionReceipt.Unknown]
 *
 * An [ExecutionReceipt.Unknown] must be reconciled against the broker's order
 * history, never automatically retried (see the execution safety stack).
 */
@Singleton
class MetaApiTradeTransport @Inject constructor(
    private val dataSource: MetaApiDataSource,
) {

    /**
     * Submit a live market order for [intent]. [token] and [accountId] are the
     * MetaApi credentials for the connected MT4 account.
     */
    suspend fun submitMarketOrder(
        token: String,
        accountId: String,
        intent: TradeIntent,
    ): ExecutionReceipt {
        val type = when (intent.direction) {
            Direction.BULLISH -> Mt4OrderType.BUY
            Direction.BEARISH -> Mt4OrderType.SELL
        }
        return try {
            val orderId = dataSource.executeTrade(
                token = token,
                accountId = accountId,
                symbol = intent.symbol,
                type = type,
                lots = intent.volume,
                sl = intent.stopLoss,
                tp = intent.takeProfit,
            )
            if (orderId > 0L) {
                ExecutionReceipt.Accepted(
                    intent = intent,
                    orderId = orderId.toString(),
                    fillPrice = null,
                )
            } else {
                ExecutionReceipt.Rejected(
                    intent = intent,
                    reasons = listOf("MetaApi accepted the request but returned no order id"),
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: MetaApiTradeRejectedException) {
            // Only an explicit broker return-code rejection is definitive. Any
            // other post-request parsing/state failure is ambiguous and must
            // remain UNKNOWN to prevent a duplicate retry.
            ExecutionReceipt.Rejected(
                intent = intent,
                reasons = listOf(e.message ?: "Broker rejected the order"),
            )
        } catch (e: Exception) {
            // Ambiguous — the request may or may not have reached the broker
            // (timeout, dropped connection, auth hiccup). Classify as UNKNOWN so
            // it is reconciled against broker history rather than blindly retried.
            ExecutionReceipt.Unknown(intent = intent)
        }
    }
}
