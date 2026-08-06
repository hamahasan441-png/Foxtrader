package com.foxtrader.app.domain.usecase.orders

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.sdk.broker.Position
import com.foxtrader.app.domain.usecase.calculator.InstrumentTypeResolver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Execution costs applied to paper fills.
 *
 * @param slippage adverse price offset applied to every fill (price units): a
 *   buy fills [slippage] higher, a sell [slippage] lower.
 * @param commissionPerLot currency charged per unit of volume, per side. A
 *   round trip (open + close) therefore costs `commissionPerLot * volume * 2`.
 */
data class PaperFillConfig(
    val slippage: Double = 0.0,
    val commissionPerLot: Double = 0.0,
)

/**
 * An open paper position. [entryPrice] is the cost-adjusted fill (slippage
 * already applied); [currentPrice] is the latest marked market price.
 * [contractSize] is the money-per-price-unit conversion for the instrument, so
 * P&L stays asset-class-correct (FX lot vs. crypto coin vs. gold).
 */
data class PaperPosition(
    val id: String,
    val symbol: String,
    val direction: Direction,
    val volume: Double,
    val entryPrice: Double,
    val currentPrice: Double,
    val contractSize: Double,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val openedAt: Long = 0L,
) {
    /** Gross (pre-commission) mark-to-market profit in account currency. */
    val unrealizedPnl: Double
        get() {
            val diff = if (direction == Direction.BULLISH) {
                currentPrice - entryPrice
            } else {
                entryPrice - currentPrice
            }
            return diff * volume * contractSize
        }

    /** Adapt to the SDK's broker [Position] type for the portfolio/UI layers. */
    fun toBrokerPosition(): Position = Position(
        symbol = symbol,
        direction = direction,
        volume = volume,
        entryPrice = entryPrice,
        currentPrice = currentPrice,
        unrealizedPnl = unrealizedPnl,
    )
}

/** A realized (closed) paper trade, retained for the trade blotter / analytics. */
data class PaperTrade(
    val id: String,
    val symbol: String,
    val direction: Direction,
    val volume: Double,
    val entryPrice: Double,
    val exitPrice: Double,
    val contractSize: Double,
    val grossPnl: Double,
    val commission: Double,
    val netPnl: Double,
    val openedAt: Long,
    val closedAt: Long,
)

/**
 * Immutable snapshot of a paper-trading account.
 *
 * [balance] is realized cash (only changes when a position closes); equity adds
 * open mark-to-market. Because all execution costs are realized on close,
 * `realizedPnl == balance - startingBalance` always holds.
 */
data class PaperAccount(
    val startingBalance: Double,
    val balance: Double,
    val positions: List<PaperPosition> = emptyList(),
    val closedTrades: List<PaperTrade> = emptyList(),
) {
    val unrealizedPnl: Double get() = positions.sumOf { it.unrealizedPnl }
    val equity: Double get() = balance + unrealizedPnl
    val realizedPnl: Double get() = balance - startingBalance

    companion object {
        fun initial(startingBalance: Double): PaperAccount =
            PaperAccount(startingBalance = startingBalance, balance = startingBalance)
    }
}

/**
 * Pure, deterministic paper-trading account engine.
 *
 * The first concrete producer of broker [Position]s in the app: it turns fills
 * into tracked positions, applies slippage + commission, marks open positions
 * to market, and realizes P&L on close. No Android, no I/O, no hidden clock —
 * every method takes its inputs explicitly and returns a new [PaperAccount], so
 * it is trivially unit-testable and safe on any dispatcher.
 */
@Singleton
class PaperTradingEngine @Inject constructor(
    private val instrumentTypeResolver: InstrumentTypeResolver,
) {

    /** Open a position at [requestedPrice], applying adverse slippage. */
    fun open(
        account: PaperAccount,
        id: String,
        symbol: String,
        direction: Direction,
        volume: Double,
        requestedPrice: Double,
        config: PaperFillConfig = PaperFillConfig(),
        stopLoss: Double? = null,
        takeProfit: Double? = null,
        timestamp: Long = 0L,
    ): PaperAccount {
        if (volume <= 0.0 || requestedPrice <= 0.0) return account
        val contractSize = instrumentTypeResolver.resolve(symbol).contractSize
        val fill = if (direction == Direction.BULLISH) {
            requestedPrice + config.slippage
        } else {
            requestedPrice - config.slippage
        }
        val position = PaperPosition(
            id = id,
            symbol = symbol,
            direction = direction,
            volume = volume,
            entryPrice = fill,
            currentPrice = requestedPrice,
            contractSize = contractSize,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            openedAt = timestamp,
        )
        return account.copy(positions = account.positions + position)
    }

    /** Mark open positions to the latest prices (unmatched symbols untouched). */
    fun mark(account: PaperAccount, priceBySymbol: Map<String, Double>): PaperAccount =
        account.copy(
            positions = account.positions.map { p ->
                priceBySymbol[p.symbol]?.let { p.copy(currentPrice = it) } ?: p
            },
        )

    /**
     * Close a single position by id at [requestedPrice] (adverse slippage +
     * round-trip commission applied), realizing its net P&L into the balance.
     */
    fun close(
        account: PaperAccount,
        positionId: String,
        requestedPrice: Double,
        config: PaperFillConfig = PaperFillConfig(),
        timestamp: Long = 0L,
    ): PaperAccount {
        val position = account.positions.firstOrNull { it.id == positionId } ?: return account
        val exit = if (position.direction == Direction.BULLISH) {
            requestedPrice - config.slippage
        } else {
            requestedPrice + config.slippage
        }
        val diff = if (position.direction == Direction.BULLISH) {
            exit - position.entryPrice
        } else {
            position.entryPrice - exit
        }
        val gross = diff * position.volume * position.contractSize
        val commission = config.commissionPerLot * position.volume * 2.0
        val net = gross - commission
        val trade = PaperTrade(
            id = position.id,
            symbol = position.symbol,
            direction = position.direction,
            volume = position.volume,
            entryPrice = position.entryPrice,
            exitPrice = exit,
            contractSize = position.contractSize,
            grossPnl = gross,
            commission = commission,
            netPnl = net,
            openedAt = position.openedAt,
            closedAt = timestamp,
        )
        return account.copy(
            balance = account.balance + net,
            positions = account.positions.filterNot { it.id == positionId },
            closedTrades = account.closedTrades + trade,
        )
    }

    /**
     * Advance one candle for [symbol]: mark positions to the close, then close
     * any whose stop-loss or take-profit was touched intrabar. Stops are checked
     * before targets (worst-case fill ordering). Positions on other symbols are
     * left untouched.
     */
    fun onCandle(
        account: PaperAccount,
        symbol: String,
        candle: Candle,
        config: PaperFillConfig = PaperFillConfig(),
    ): PaperAccount {
        var acc = mark(account, mapOf(symbol to candle.close))
        val triggers = acc.positions
            .filter { it.symbol == symbol }
            .mapNotNull { p -> triggerPrice(p, candle)?.let { p.id to it } }
        for ((id, exitPrice) in triggers) {
            acc = close(acc, id, exitPrice, config, candle.timestamp)
        }
        return acc
    }

    private fun triggerPrice(position: PaperPosition, candle: Candle): Double? {
        val stop = position.stopLoss
        val target = position.takeProfit
        return if (position.direction == Direction.BULLISH) {
            when {
                stop != null && candle.low <= stop -> stop
                target != null && candle.high >= target -> target
                else -> null
            }
        } else {
            when {
                stop != null && candle.high >= stop -> stop
                target != null && candle.low <= target -> target
                else -> null
            }
        }
    }
}
