package com.foxtrader.app.domain.model.tradepro

import com.foxtrader.app.domain.model.Direction

/**
 * State of a managed trade as it moves through the T1/T2/runner lifecycle.
 */
enum class ManagedTradeState {
    ACTIVE,
    T1_HIT,
    T2_HIT,
    RUNNER,
    CLOSED,
}

/**
 * Actions that can occur during trade management. Each represents a discrete
 * lifecycle event that the engine emits when processing a new bar.
 */
sealed class TradeManagementAction {
    data object HitT1 : TradeManagementAction()
    data object HitT2 : TradeManagementAction()
    data object HitRunner : TradeManagementAction()
    data object Stopped : TradeManagementAction()
    data object MoveToBreakeven : TradeManagementAction()
    data class TrailStop(val newStop: Double) : TradeManagementAction()
    data object CloseManually : TradeManagementAction()
}

/**
 * A trade being managed through the 3-contract TRADEPRO plan.
 * Immutable value object updated bar-by-bar by [com.foxtrader.app.domain.usecase.tradepro.TradeManagementEngine].
 */
data class ManagedTrade(
    val id: String,
    val symbol: String,
    val direction: Direction,
    val entryPrice: Double,
    val entryTimestamp: Long,
    val contracts: Int,
    val stopPrice: Double,
    val t1Price: Double,
    val t2Price: Double,
    val runnerTarget: Double,
    val currentPrice: Double,
    val state: ManagedTradeState,
    val t1FilledAt: Long? = null,
    val t2FilledAt: Long? = null,
    val closedAt: Long? = null,
    val realizedPoints: Double = 0.0,
    val unrealizedPoints: Double = 0.0,
    val exitReason: String? = null,
)
