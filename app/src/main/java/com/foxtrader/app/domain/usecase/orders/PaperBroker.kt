package com.foxtrader.app.domain.usecase.orders

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.sdk.broker.BrokerAdapter
import com.foxtrader.app.domain.sdk.broker.OrderRequest
import com.foxtrader.app.domain.sdk.broker.OrderResult
import com.foxtrader.app.domain.sdk.broker.Position
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Paper-trading [BrokerAdapter] — the first concrete broker implementation.
 *
 * It routes orders into an in-process [PaperTradingEngine] account instead of a
 * live venue, so the whole execution stack (including
 * [RiskGatedBrokerExecutor], which had no adapter to talk to) can run with zero
 * real-capital risk. Because it never touches live funds it is the safe adapter
 * to pair with the risk gate.
 *
 * Market orders fill at the last price fed via [onPrice]/[onCandle] (a paper
 * broker has no external quote source), with the engine's slippage/commission
 * model applied. State mutations are serialised behind a [Mutex] since the
 * [BrokerAdapter] methods are suspending and may be called concurrently.
 */
@Singleton
class PaperBroker @Inject constructor(
    private val engine: PaperTradingEngine,
) : BrokerAdapter {

    override val id: String = "paper"
    override val displayName: String = "Paper Trading"

    /** Empty = no symbol restriction; the risk gate treats this as "allow all". */
    override val supportedAssets: List<String> = emptyList()

    private val mutex = Mutex()

    @Volatile
    private var account: PaperAccount = PaperAccount.initial(DEFAULT_STARTING_BALANCE)
    private var lastPrices: Map<String, Double> = emptyMap()
    private var fillConfig: PaperFillConfig = PaperFillConfig()
    private var orderCounter: Long = 0L

    /** Latest account snapshot (immutable) for UI/portfolio reads. */
    fun snapshot(): PaperAccount = account

    /** Reset the account (e.g. when the user restarts a paper session). */
    suspend fun reset(
        startingBalance: Double = DEFAULT_STARTING_BALANCE,
        config: PaperFillConfig = PaperFillConfig(),
    ) = mutex.withLock {
        account = PaperAccount.initial(startingBalance)
        fillConfig = config
        lastPrices = emptyMap()
        orderCounter = 0L
    }

    /** Feed the latest price for [symbol] and mark open positions to it. */
    suspend fun onPrice(symbol: String, price: Double) = mutex.withLock {
        lastPrices = lastPrices + (symbol to price)
        account = engine.mark(account, mapOf(symbol to price))
    }

    /** Feed a candle: marks to the close and auto-closes SL/TP hits intrabar. */
    suspend fun onCandle(symbol: String, candle: Candle) = mutex.withLock {
        lastPrices = lastPrices + (symbol to candle.close)
        account = engine.onCandle(account, symbol, candle, fillConfig)
    }

    override suspend fun connect(): Boolean = true

    override suspend fun placeOrder(order: OrderRequest): OrderResult = mutex.withLock {
        val price = lastPrices[order.symbol]
            ?: error("No market price known for ${order.symbol}; feed a price before ordering")
        orderCounter += 1
        val orderId = "paper-${order.symbol}-$orderCounter"
        val timestamp = System.currentTimeMillis()
        account = engine.open(
            account = account,
            id = orderId,
            symbol = order.symbol,
            direction = order.direction,
            volume = order.volume,
            requestedPrice = price,
            config = fillConfig,
            stopLoss = order.stopLoss,
            takeProfit = order.takeProfit,
            timestamp = timestamp,
        )
        val filled = account.positions.first { it.id == orderId }
        OrderResult(
            orderId = orderId,
            symbol = order.symbol,
            filledPrice = filled.entryPrice,
            volume = order.volume,
            timestamp = timestamp,
        )
    }

    /**
     * "Cancel" for a paper broker closes the position at its last known price —
     * there is no resting order book, so an open paper position is the only
     * cancellable unit. Returns false when the id is unknown.
     */
    override suspend fun cancelOrder(orderId: String): Boolean = mutex.withLock {
        val position = account.positions.firstOrNull { it.id == orderId } ?: return@withLock false
        val price = lastPrices[position.symbol] ?: position.currentPrice
        account = engine.close(account, orderId, price, fillConfig, System.currentTimeMillis())
        true
    }

    override suspend fun getPositions(): List<Position> = mutex.withLock {
        account.positions.map { it.toBrokerPosition() }
    }

    private companion object {
        const val DEFAULT_STARTING_BALANCE = 10_000.0
    }
}
