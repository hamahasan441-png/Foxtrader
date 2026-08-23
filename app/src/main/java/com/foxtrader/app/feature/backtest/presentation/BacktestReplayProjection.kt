package com.foxtrader.app.feature.backtest.presentation

import com.foxtrader.app.domain.model.BacktestResult
import com.foxtrader.app.domain.model.BinaryBacktestResult
import com.foxtrader.app.domain.model.BinaryOutcome
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction

/** A chart-safe trade projection at one historical replay cursor. */
data class ReplayTradeMarker(
    val entryIndex: Int,
    val entryPrice: Double,
    val direction: Direction,
    val exitIndex: Int? = null,
    val exitPrice: Double? = null,
    val outcome: String? = null,
    val pnl: Double? = null,
) {
    val isOpen: Boolean get() = exitIndex == null
}

data class BacktestReplayProjection(
    val cursor: Int,
    val revealedBars: Int,
    val totalBars: Int,
    val completedTrades: Int,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    val netPnL: Double,
    val markers: List<ReplayTradeMarker>,
) {
    val decidedTrades: Int get() = wins + losses
    val winRate: Double get() = if (decidedTrades == 0) 0.0 else wins * 100.0 / decidedTrades
}

/**
 * Projects a completed causal backtest onto a historical cursor without leaking future outcomes.
 * Entries are visible once their entry bar is revealed. Exit price/outcome/P&L remain null until
 * the actual exit/expiry bar is at or behind [cursor].
 */
fun projectBacktestReplay(
    candles: List<Candle>,
    result: BacktestResult?,
    binaryResult: BinaryBacktestResult?,
    cursor: Int,
): BacktestReplayProjection {
    if (candles.isEmpty()) return BacktestReplayProjection(0, 0, 0, 0, 0, 0, 0, 0.0, emptyList())
    val safeCursor = cursor.coerceIn(0, candles.lastIndex)

    if (binaryResult != null) {
        val markers = binaryResult.trades
  .asSequence()
  .filter { it.entryIndex <= safeCursor }
  .map { trade ->
      val settled = trade.expiryIndex <= safeCursor
      ReplayTradeMarker(
          entryIndex = trade.entryIndex,
          entryPrice = trade.entryPrice,
          direction = trade.direction,
          exitIndex = trade.expiryIndex.takeIf { settled },
          exitPrice = trade.expiryPrice.takeIf { settled },
          outcome = trade.outcome.name.takeIf { settled },
          pnl = trade.pnl.takeIf { settled },
      )
  }
  .toList()
        val settled = binaryResult.trades.filter { it.expiryIndex <= safeCursor }
        val wins = settled.count { it.outcome == BinaryOutcome.WIN }
        val losses = settled.count { it.outcome == BinaryOutcome.LOSS }
        val ties = settled.count { it.outcome == BinaryOutcome.TIE }
        return BacktestReplayProjection(
  cursor = safeCursor,
  revealedBars = safeCursor + 1,
  totalBars = candles.size,
  completedTrades = settled.size,
  wins = wins,
  losses = losses,
  ties = ties,
  netPnL = settled.sumOf { it.pnl },
  markers = markers,
        )
    }

    val standard = result
    val markers = standard?.trades.orEmpty()
        .asSequence()
        .filter { it.entryIndex <= safeCursor }
        .map { trade ->
  val closed = trade.exitIndex <= safeCursor
  ReplayTradeMarker(
      entryIndex = trade.entryIndex,
      entryPrice = trade.entryPrice,
      direction = trade.direction,
      exitIndex = trade.exitIndex.takeIf { closed },
      exitPrice = trade.exitPrice.takeIf { closed },
      outcome = if (!closed) null else when {
          trade.netPnL > PNL_EPSILON -> "WIN"
          trade.netPnL < -PNL_EPSILON -> "LOSS"
          else -> "BREAKEVEN"
      },
      pnl = trade.netPnL.takeIf { closed },
  )
        }
        .toList()
    val closed = standard?.trades.orEmpty().filter { it.exitIndex <= safeCursor }
    val wins = closed.count { it.netPnL > PNL_EPSILON }
    val losses = closed.count { it.netPnL < -PNL_EPSILON }
    val ties = closed.size - wins - losses
    return BacktestReplayProjection(
        cursor = safeCursor,
        revealedBars = safeCursor + 1,
        totalBars = candles.size,
        completedTrades = closed.size,
        wins = wins,
        losses = losses,
        ties = ties,
        netPnL = closed.sumOf { it.netPnL },
        markers = markers,
    )
}

private const val PNL_EPSILON = 1e-9
