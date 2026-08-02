package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.ManagedTrade
import com.foxtrader.app.domain.model.tradepro.ManagedTradeState
import com.foxtrader.app.domain.model.tradepro.TradeManagementAction
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import com.foxtrader.app.domain.model.tradepro.TradeProSetup
import java.util.UUID
import javax.inject.Inject

/**
 * Manages a live trade through the 3-contract T1/T2/runner lifecycle, bar-by-bar.
 *
 * Rules (per the framework):
 * - Stop is NOT touched until T1 fills.
 * - Stop moves to break-even ONLY after T2 fills.
 * - The runner trails behind structure (simple N-point trail, configurable via [trailPoints]).
 */
class TradeManagementEngine @Inject constructor() {

    /** Default trailing distance for the runner in points. */
    private val trailPoints: Double = 4.0

    /**
     * Opens a new managed trade from a confirmed [setup] using the given [config].
     * The trade starts in [ManagedTradeState.ACTIVE] with full contract allocation.
     */
    fun openTrade(setup: TradeProSetup, config: TradeProConfig): ManagedTrade {
        val plan = setup.managementPlan
        return ManagedTrade(
            id = UUID.randomUUID().toString(),
            symbol = setup.symbol,
            direction = setup.direction,
            entryPrice = setup.entry,
            entryTimestamp = System.currentTimeMillis(),
            contracts = plan.contracts,
            stopPrice = setup.stopLoss,
            t1Price = setup.target1,
            t2Price = setup.target2,
            runnerTarget = setup.runnerTarget,
            currentPrice = setup.entry,
            state = ManagedTradeState.ACTIVE,
        )
    }

    /**
     * Processes one bar (candle) against the managed trade. Checks stop hit, T1 hit, T2 hit,
     * and runner target or trail stop in order.
     *
     * @return The updated [ManagedTrade] and an optional [TradeManagementAction] describing
     *         what happened on this bar (null if nothing changed).
     */
    fun tick(trade: ManagedTrade, candle: Candle): Pair<ManagedTrade, TradeManagementAction?> {
        if (trade.state == ManagedTradeState.CLOSED) {
            return trade to null
        }

        val updated = trade.copy(currentPrice = candle.close)

        // Check stop hit first (highest priority).
        if (isStopHit(updated, candle)) {
            val unrealized = computeUnrealized(updated)
            val closed = updated.copy(
                state = ManagedTradeState.CLOSED,
                closedAt = candle.timestamp,
                unrealizedPoints = 0.0,
                realizedPoints = updated.realizedPoints + unrealized,
                exitReason = "Stop hit",
            )
            return closed to TradeManagementAction.Stopped
        }

        // Process based on current state.
        return when (updated.state) {
            ManagedTradeState.ACTIVE -> processActive(updated, candle)
            ManagedTradeState.T1_HIT -> processT1Hit(updated, candle)
            ManagedTradeState.T2_HIT -> processT2Hit(updated, candle)
            ManagedTradeState.RUNNER -> processRunner(updated, candle)
            ManagedTradeState.CLOSED -> updated to null
        }
    }

    /**
     * Closes a trade manually at the given [price].
     */
    fun closeManually(trade: ManagedTrade, price: Double): ManagedTrade {
        if (trade.state == ManagedTradeState.CLOSED) return trade
        val unrealized = computeUnrealizedAt(trade, price)
        return trade.copy(
            state = ManagedTradeState.CLOSED,
            currentPrice = price,
            closedAt = System.currentTimeMillis(),
            unrealizedPoints = 0.0,
            realizedPoints = trade.realizedPoints + unrealized,
            exitReason = "Manual close",
        )
    }

    // --- Private helpers ---

    private fun processActive(trade: ManagedTrade, candle: Candle): Pair<ManagedTrade, TradeManagementAction?> {
        if (isT1Hit(trade, candle)) {
            val t1Points = pointsFromEntry(trade, trade.t1Price)
            val t1Contracts = trade.contracts / 3
            val realized = t1Points * t1Contracts
            val result = trade.copy(
                state = ManagedTradeState.T1_HIT,
                t1FilledAt = candle.timestamp,
                realizedPoints = trade.realizedPoints + realized,
                unrealizedPoints = computeUnrealized(trade),
            )
            return result to TradeManagementAction.HitT1
        }
        // Nothing happened; update unrealized.
        val result = trade.copy(unrealizedPoints = computeUnrealized(trade))
        return result to null
    }

    private fun processT1Hit(trade: ManagedTrade, candle: Candle): Pair<ManagedTrade, TradeManagementAction?> {
        if (isT2Hit(trade, candle)) {
            val t2Points = pointsFromEntry(trade, trade.t2Price)
            val t2Contracts = trade.contracts / 3
            val realized = t2Points * t2Contracts
            // Move stop to break-even after T2.
            val result = trade.copy(
                state = ManagedTradeState.T2_HIT,
                t2FilledAt = candle.timestamp,
                stopPrice = trade.entryPrice,
                realizedPoints = trade.realizedPoints + realized,
                unrealizedPoints = computeUnrealized(trade),
            )
            return result to TradeManagementAction.HitT2
        }
        val result = trade.copy(unrealizedPoints = computeUnrealized(trade))
        return result to null
    }

    private fun processT2Hit(trade: ManagedTrade, candle: Candle): Pair<ManagedTrade, TradeManagementAction?> {
        // Transition to RUNNER state and start trailing.
        if (isRunnerTargetHit(trade, candle)) {
            val runnerPoints = pointsFromEntry(trade, trade.runnerTarget)
            val runnerContracts = trade.contracts - (trade.contracts / 3) * 2
            val realized = runnerPoints * runnerContracts
            val result = trade.copy(
                state = ManagedTradeState.CLOSED,
                closedAt = candle.timestamp,
                realizedPoints = trade.realizedPoints + realized,
                unrealizedPoints = 0.0,
                exitReason = "Runner target hit",
            )
            return result to TradeManagementAction.HitRunner
        }
        // Trail the stop for the runner.
        val trailedStop = computeTrailingStop(trade, candle)
        if (trailedStop != null && trailedStop != trade.stopPrice) {
            val result = trade.copy(
                state = ManagedTradeState.RUNNER,
                stopPrice = trailedStop,
                unrealizedPoints = computeUnrealized(trade),
            )
            return result to TradeManagementAction.TrailStop(trailedStop)
        }
        val result = trade.copy(
            state = ManagedTradeState.RUNNER,
            unrealizedPoints = computeUnrealized(trade),
        )
        return result to null
    }

    private fun processRunner(trade: ManagedTrade, candle: Candle): Pair<ManagedTrade, TradeManagementAction?> {
        if (isRunnerTargetHit(trade, candle)) {
            val runnerPoints = pointsFromEntry(trade, trade.runnerTarget)
            val runnerContracts = trade.contracts - (trade.contracts / 3) * 2
            val realized = runnerPoints * runnerContracts
            val result = trade.copy(
                state = ManagedTradeState.CLOSED,
                closedAt = candle.timestamp,
                realizedPoints = trade.realizedPoints + realized,
                unrealizedPoints = 0.0,
                exitReason = "Runner target hit",
            )
            return result to TradeManagementAction.HitRunner
        }
        // Continue trailing.
        val trailedStop = computeTrailingStop(trade, candle)
        if (trailedStop != null && trailedStop != trade.stopPrice) {
            val result = trade.copy(
                stopPrice = trailedStop,
                unrealizedPoints = computeUnrealized(trade),
            )
            return result to TradeManagementAction.TrailStop(trailedStop)
        }
        val result = trade.copy(unrealizedPoints = computeUnrealized(trade))
        return result to null
    }

    private fun isStopHit(trade: ManagedTrade, candle: Candle): Boolean =
        when (trade.direction) {
            Direction.BULLISH -> candle.low <= trade.stopPrice
            Direction.BEARISH -> candle.high >= trade.stopPrice
        }

    private fun isT1Hit(trade: ManagedTrade, candle: Candle): Boolean =
        when (trade.direction) {
            Direction.BULLISH -> candle.high >= trade.t1Price
            Direction.BEARISH -> candle.low <= trade.t1Price
        }

    private fun isT2Hit(trade: ManagedTrade, candle: Candle): Boolean =
        when (trade.direction) {
            Direction.BULLISH -> candle.high >= trade.t2Price
            Direction.BEARISH -> candle.low <= trade.t2Price
        }

    private fun isRunnerTargetHit(trade: ManagedTrade, candle: Candle): Boolean =
        when (trade.direction) {
            Direction.BULLISH -> candle.high >= trade.runnerTarget
            Direction.BEARISH -> candle.low <= trade.runnerTarget
        }

    /**
     * Computes a simple N-point trailing stop. The stop only tightens (never widens).
     * For a BULLISH trade: trail = close - trailPoints (only move up).
     * For a BEARISH trade: trail = close + trailPoints (only move down).
     */
    private fun computeTrailingStop(trade: ManagedTrade, candle: Candle): Double? {
        val candidate = when (trade.direction) {
            Direction.BULLISH -> candle.close - trailPoints
            Direction.BEARISH -> candle.close + trailPoints
        }
        // Only tighten (never widen the stop).
        return when (trade.direction) {
            Direction.BULLISH -> if (candidate > trade.stopPrice) candidate else null
            Direction.BEARISH -> if (candidate < trade.stopPrice) candidate else null
        }
    }

    private fun pointsFromEntry(trade: ManagedTrade, price: Double): Double =
        when (trade.direction) {
            Direction.BULLISH -> price - trade.entryPrice
            Direction.BEARISH -> trade.entryPrice - price
        }

    private fun computeUnrealized(trade: ManagedTrade): Double {
        val remainingContracts = remainingContracts(trade)
        val ptsPerContract = pointsFromEntry(trade, trade.currentPrice)
        return ptsPerContract * remainingContracts
    }

    private fun computeUnrealizedAt(trade: ManagedTrade, price: Double): Double {
        val remainingContracts = remainingContracts(trade)
        val ptsPerContract = when (trade.direction) {
            Direction.BULLISH -> price - trade.entryPrice
            Direction.BEARISH -> trade.entryPrice - price
        }
        return ptsPerContract * remainingContracts
    }

    private fun remainingContracts(trade: ManagedTrade): Int {
        val third = trade.contracts / 3
        return when (trade.state) {
            ManagedTradeState.ACTIVE -> trade.contracts
            ManagedTradeState.T1_HIT -> trade.contracts - third
            ManagedTradeState.T2_HIT, ManagedTradeState.RUNNER -> trade.contracts - third * 2
            ManagedTradeState.CLOSED -> 0
        }
    }
}
