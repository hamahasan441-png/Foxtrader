package com.foxtrader.app.domain.usecase.orders

import com.foxtrader.app.domain.model.Direction
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
 *
 * Every order is routed through [RiskGatedBrokerExecutor] so paper trading
 * respects the same mandatory risk gate as live execution — no order can reach
 * [PaperBroker] unless [com.foxtrader.app.domain.usecase.risk.RiskEngine]
 * allows the proposed risk first. The free-typed UI volume is applied as a
 * manual override on top of the engine's computed size; if it would exceed the
 * configured risk %, the trade is rejected (with reasons) and never reaches the
 * broker.
 */
@Singleton
class PaperTradingSession @Inject constructor(
    private val broker: PaperBroker,
    private val riskGatedExecutor: RiskGatedBrokerExecutor,
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

    /** Open a long market order at the last known price, through the risk gate. */
    suspend fun buy(symbol: String, volume: Double): RiskGatedBrokerResult =
        place(symbol, Direction.BULLISH, volume)

    /** Open a short market order at the last known price, through the risk gate. */
    suspend fun sell(symbol: String, volume: Double): RiskGatedBrokerResult =
        place(symbol, Direction.BEARISH, volume)

    /**
     * Route a market order through the risk gate before [PaperBroker].
     *
     * The caller-supplied [volume] is treated as a manual override on the risk
     * engine's computed size; the gate recomputes the actual risk of that
     * volume and rejects the trade (without touching the broker) when it would
     * exceed the configured per-trade risk. The volume that actually fills is
     * `result.sizing.volume` — the risk-adjusted value — not the free-typed UI
     * volume.
     */
    private suspend fun place(symbol: String, direction: Direction, volume: Double): RiskGatedBrokerResult {
        val market = _market.value
        if (market == null || !market.symbol.equals(symbol, ignoreCase = true)) {
            return RiskGatedBrokerResult.rejected(
                listOf("No market price has been fed for $symbol yet — nothing to fill against"),
            )
        }
        if (volume <= 0.0) {
            return RiskGatedBrokerResult.rejected(listOf("Order volume must be positive"))
        }

        val entryPrice = market.price
        val stopLoss = defaultStop(entryPrice, direction)
        return riskGatedExecutor.placeMarketOrder(
            adapter = broker,
            symbol = symbol,
            direction = direction,
            entryPrice = entryPrice,
            stopLoss = stopLoss,
            volumeOverride = volume,
            executionAuthorized = true,
        ).also { result ->
            if (result.accepted) _account.value = broker.snapshot()
        }
    }

    /**
     * Derive a paper-trading default stop-loss from the entry price (0.5%, the
     * same default the risk engine's fixed stop uses). The risk gate needs a
     * stop to price the proposed risk; paper positions carry it too, so the
     * engine's SL/TP auto-close path applies as it does for a live trade.
     */
    private fun defaultStop(entryPrice: Double, direction: Direction): Double {
        val distance = entryPrice * DEFAULT_STOP_PERCENT
        return if (direction == Direction.BULLISH) entryPrice - distance else entryPrice + distance
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

    private companion object {
        const val DEFAULT_STOP_PERCENT = 0.005
    }
}
