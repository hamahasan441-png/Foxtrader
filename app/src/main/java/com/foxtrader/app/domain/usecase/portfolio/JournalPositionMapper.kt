package com.foxtrader.app.domain.usecase.portfolio

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.JournalEntry
import com.foxtrader.app.domain.sdk.broker.Position
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the trade journal into [PortfolioEngine].
 *
 * `PortfolioEngine` consumes `List<Position>`, which the SDK defines as coming
 * from a [com.foxtrader.app.domain.sdk.broker.BrokerAdapter]. No broker adapter
 * is implemented yet (and per the masterplan none will be until the risk engine
 * is battle-tested), so today the only real record of what a trader is holding
 * is the journal's **open** entries.
 *
 * This mapper makes that the source of truth without pretending it is a broker
 * feed: it converts open journal entries into [Position] snapshots, marking
 * unrealised P&L against a live price where one is available and falling back
 * to the entry price (P&L 0) where it is not.
 *
 * `NOTE` Closed entries are excluded — they carry realised P&L and contribute no
 * open exposure. Including them would double-count risk that no longer exists.
 */
@Singleton
class JournalPositionMapper @Inject constructor() {

    /**
     * @param entries all journal entries; closed ones are filtered out.
     * @param livePrices symbol (uppercase) → latest known price. Missing symbols
     *   fall back to the entry price, which yields an honest 0.0 unrealised P&L
     *   rather than an invented number.
     */
    fun toPositions(
        entries: List<JournalEntry>,
        livePrices: Map<String, Double> = emptyMap(),
    ): List<Position> = entries
        .filter { it.isOpen }
        .map { entry ->
            val symbol = entry.symbol.uppercase()
            val current = livePrices[symbol]?.takeIf { it > 0.0 } ?: entry.entryPrice
            Position(
                symbol = symbol,
                direction = entry.direction,
                volume = entry.volume,
                entryPrice = entry.entryPrice,
                currentPrice = current,
                unrealizedPnl = unrealizedPnl(entry, current),
            )
        }

    /**
     * Mark-to-market P&L in quote-currency terms, consistent with the contract
     * sizing `PortfolioEngine` applies to notional.
     */
    private fun unrealizedPnl(entry: JournalEntry, currentPrice: Double): Double {
        val delta = when (entry.direction) {
            Direction.BULLISH -> currentPrice - entry.entryPrice
            Direction.BEARISH -> entry.entryPrice - currentPrice
        }
        return delta * entry.volume * CONTRACT_SIZE
    }

    private companion object {
        /** Matches PortfolioEngine.DEFAULT_CONTRACT_SIZE so the two agree. */
        const val CONTRACT_SIZE = 100_000
    }
}
