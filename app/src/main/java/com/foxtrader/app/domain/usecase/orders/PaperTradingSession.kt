package com.foxtrader.app.domain.usecase.orders

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.sdk.broker.OrderRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reactive, app-wide coordinator over [PaperBroker].
 *
 * A single source of truth for paper-trading state: it feeds market prices into
 * the broker, executes one-tap market orders, and re-publishes the resulting
 * [PaperAccount] as a [StateFlow] any screen can observe. Modelled on
 * [com.foxtrader.app.domain.usecase.replay.ReplayEngine] — a `@Singleton`
 * holder exposing a `StateFlow`, injected into the relevant ViewModels so the
 * chart (which has live prices) and the paper-trading screen share one account.
 */
@Singleton
class PaperTradingSession @Inject constructor(
    private val broker: PaperBroker,
) {

    /** The most recent price the session was fed — powers one-tap order entry. */
    data class MarketSnapshot(val symbol: String, val price: Double)

    private val _account = MutableStateFlow(broker.snapshot())
    val account: StateFlow<PaperAccount> = _account.asStateFlow()

    private val _market = MutableStateFlow<MarketSnapshot?>(null)
    val market: StateFlow<MarketSnapshot?> = _market.asStateFlow()

    /** Feed the latest price for [symbol]; marks open positions to market. */
    suspend fun onPrice(symbol: String, price: Double) {
        if (price <= 0.0) return
        broker.onPrice(symbol, price)
        _market.value = MarketSnapshot(symbol, price)
        _account.value = broker.snapshot()
    }

    /** Open a long market order at the last known price. */
    suspend fun buy(symbol: String, volume: Double): Boolean = place(symbol, Direction.BULLISH, volume)

    /** Open a short market order at the last known price. */
    suspend fun sell(symbol: String, volume: Double): Boolean = place(symbol, Direction.BEARISH, volume)

    private suspend fun place(symbol: String, direction: Direction, volume: Double): Boolean {
        if (volume <= 0.0) return false
        return try {
            broker.placeOrder(OrderRequest(symbol, direction, volume))
            _account.value = broker.snapshot()
            true
        } catch (ignored: IllegalStateException) {
            // No market price has been fed for this symbol yet — nothing to fill against.
            false
        }
    }

    /** Close a paper position by id. Returns false when the id is unknown. */
    suspend fun close(positionId: String): Boolean {
        val closed = broker.cancelOrder(positionId)
        _account.value = broker.snapshot()
        return closed
    }

    /** Reset the account to a fresh balance and clear the fed market price. */
    suspend fun reset() {
        broker.reset()
        _account.value = broker.snapshot()
        _market.value = null
    }
}
